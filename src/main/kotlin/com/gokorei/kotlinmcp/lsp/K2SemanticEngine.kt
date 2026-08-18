@file:Suppress("K1_ANALYSIS", "DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
package com.gokorei.kotlinmcp.lsp

import com.gokorei.kotlinmcp.execution.SnippetCompiler
import com.gokorei.kotlinmcp.shared.SourceUtils
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import java.io.File

/** Where a resolved symbol's declaration lives. */
enum class ResolvedSource { SNIPPET, WORKSPACE, EXTERNAL, UNRESOLVED }

/**
 * A resolved declaration target: where a reference points, plus the identifier
 * it resolves to. `file` is "Snippet.kt" for snippet declarations, a
 * workspace-relative path for project declarations, and a short tag for
 * external (stdlib / dependency) symbols.
 */
data class ResolvedDeclaration(
    val symbol: String,
    val file: String,
    val line: Int,
    val fqn: String?,
    val signature: String?,
    val source: ResolvedSource
)

/** A single bound occurrence of a symbol: either the declaration or a usage resolving to it. */
data class ResolvedReference(
    val symbol: String,
    val file: String,
    val line: Int,
    val column: Int,
    val snippet: String,
    val kind: String,
    val fqn: String?
)

/**
 * Workspace-level K2 semantic resolution for the LSP-facing tools.
 *
 * Unlike the snippet-scoped [K2SnippetFrontend.analyzeSession], a [session]
 * analyzes the pasted snippet together with every `.kt` file in the workspace
 * in ONE binding pass, so references resolve across file boundaries — the same
 * semantics an IDE-backed LSP offers.
 *
 * All primitives are non-throwing: on any failure they return `null` /
 * `UNRESOLVED` / an empty collection rather than throwing across the MCP
 * boundary. Structured [com.gokorei.kotlinmcp.models.KotlinMcpResult] wrapping
 * happens in the service layer that consumes this engine.
 */
interface K2SemanticEngine {

    /**
     * Builds (or reuses) a K2 analysis session over the workspace plus the
     * snippet. The returned session's `file` is the analyzed snippet.
     */
    fun session(workspacePath: String?, snippet: String): K2AnalysisSession?

    /**
     * Resolves a reference expression to its declaration target (file, line,
     * FQN, signature). Returns `null` when the element is not resolvable.
     */
fun resolveReference(session: K2AnalysisSession, reference: KtReferenceExpression, workspaceRoot: String?): ResolvedDeclaration?

    /** FQN of a named declaration as seen by the binding context. */
    fun fqNameOfDeclaration(session: K2AnalysisSession, declaration: KtNamedDeclaration): String?

    /** Type of an expression as resolved by K2 (e.g. `kotlin.String`). */
    fun typeOfExpression(session: K2AnalysisSession, expression: KtExpression): String?

    /**
     * All occurrences — the declaration plus every reference that resolves to
     * it — across the snippet and the workspace. Same-name symbols that are
     * NOT the same declaration (shadowed locals, unrelated top-level symbols)
     * are excluded.
     */
    fun referencesForSymbol(session: K2AnalysisSession, symbol: String, workspacePath: String?): List<ResolvedReference>

    /** Compiled-class / build-libs classpath detected under the workspace. */
    fun projectClasspath(workspacePath: String?): List<String>

    /** Number of times the workspace `.kt` files were (re)parsed since construction. */
    val workspaceRebuilds: Int

    /** Releases cached PSI state; the shared environment stays owned by [K2SnippetFrontend]. */
    fun close()
}

class DefaultK2SemanticEngine : K2SemanticEngine {

    private data class WorkspaceFile(val rel: String, val file: File, val psi: KtFile)

    private data class WorkspaceSnapshot(
        val root: File,
        val files: List<WorkspaceFile>,
        val lastModified: Map<File, Long>
    )

    @Volatile
    private var snapshot: WorkspaceSnapshot? = null

    @Volatile
    private var closed = false

    @Volatile
    override var workspaceRebuilds: Int = 0
        private set

    override fun session(workspacePath: String?, snippet: String): K2AnalysisSession? {
        if (closed) return null
        return K2SnippetFrontend.analyzeSession(snippet, workspaceFiles(workspacePath).map { it.psi })
    }

    override fun resolveReference(session: K2AnalysisSession, reference: KtReferenceExpression, workspaceRoot: String?): ResolvedDeclaration? {
        if (closed) return null
        val descriptor = session.bindingContext[BindingContext.REFERENCE_TARGET, reference]
        if (descriptor == null) {
            return ResolvedDeclaration(
                symbol = reference.text,
                file = "?",
                line = 0,
                fqn = null,
                signature = null,
                source = ResolvedSource.UNRESOLVED
            )
        }
        val fqn = ((descriptor as? ClassConstructorDescriptor)?.constructedClass?.let { safeFqn(it) } ?: safeFqn(descriptor))
        val files = listOf("Snippet.kt" to session.file) + workspaceFiles(workspaceRoot).map { it.rel to it.psi }
        val declPsi = declarationPsiFor(session, files, descriptor)
        return if (declPsi != null) {
            val containing = declPsi.containingFile as KtFile
            val isSnippet = containing == session.file || containing.name.startsWith("Snippet_")
            ResolvedDeclaration(
                symbol = reference.text,
                file = if (isSnippet) "Snippet.kt" else containing.name,
                line = SourceUtils.lineOf(containing.text, declPsi.textRange.startOffset),
                fqn = fqn,
                signature = signatureOf(declPsi),
                source = if (isSnippet) ResolvedSource.SNIPPET else ResolvedSource.WORKSPACE
            )
        } else {
            ResolvedDeclaration(
                symbol = reference.text,
                file = "<external>",
                line = 0,
                fqn = fqn,
                signature = null,
                source = ResolvedSource.EXTERNAL
            )
        }
    }

    override fun fqNameOfDeclaration(session: K2AnalysisSession, declaration: KtNamedDeclaration): String? {
        if (closed) return null
        val descriptor = session.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
        return descriptor?.let { safeFqn(it) } ?: declaration.fqName?.asString()
    }

    override fun typeOfExpression(session: K2AnalysisSession, expression: KtExpression): String? {
        if (closed) return null
        val info = session.bindingContext[BindingContext.EXPRESSION_TYPE_INFO, expression]
        return info?.type?.let { DescriptorRenderer.FQ_NAMES_IN_TYPES.renderType(it) }
    }

    override fun referencesForSymbol(session: K2AnalysisSession, symbol: String, workspacePath: String?): List<ResolvedReference> {
        if (closed) return emptyList()
        val ctx = session.bindingContext

        data class Occurrence(val rel: String, val file: KtFile, val node: PsiElement, val kind: String)

        val occurrences = mutableListOf<Occurrence>()
        fun collect(rel: String, file: KtFile) {
            file.accept(object : KtTreeVisitorVoid() {
                override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                    if (declaration.name == symbol) {
                        occurrences += Occurrence(rel, file, declaration, "decl")
                    }
                    super.visitNamedDeclaration(declaration)
                }

                override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                    if (expression.getReferencedName() == symbol) {
                        occurrences += Occurrence(rel, file, expression, "ref")
                    }
                    super.visitSimpleNameExpression(expression)
                }
            })
        }

        collect("Snippet.kt", session.file)
        workspaceFiles(workspacePath).forEach { collect(it.rel, it.psi) }

        val declOccurrences = occurrences.filter { it.kind == "decl" }
        fun descriptorOf(decl: KtNamedDeclaration): DeclarationDescriptor? =
            ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, decl]

        // Target declarations: prefer symbols with a real (non-local) FQN across
        // snippet + workspace. When the name has no real declaration anywhere,
        // fall back to same-name local declarations so snippet-local symbols
        // (e.g. a function parameter) still resolve to their usages.
        val realTargets = declOccurrences
            .mapNotNull { descriptorOf(it.node as KtNamedDeclaration) }
            .filter { !DescriptorUtils.isLocal(it) && isRealFqn(safeFqn(it)) }
        val targets: List<DeclarationDescriptor> =
            if (realTargets.isNotEmpty()) realTargets
            else declOccurrences.mapNotNull { descriptorOf(it.node as KtNamedDeclaration) }

        fun toRow(occ: Occurrence, offset: Int, fqn: String?): ResolvedReference {
            val line = SourceUtils.lineOf(occ.file.text, offset)
            val lineStart = occ.file.text.lastIndexOf('\n', offset - 1) + 1
            val column = offset - lineStart + 1
            val end = occ.file.text.indexOf('\n', offset).let { if (it == -1) occ.file.text.length else it }
            val lineText = occ.file.text.substring(lineStart, end).trim()
            return ResolvedReference(symbol, occ.rel, line, column, lineText, occ.kind, fqn)
        }

        val rows = mutableListOf<ResolvedReference>()
        occurrences.forEach { occ ->
            when (occ.kind) {
                "decl" -> {
                    val decl = occ.node as KtNamedDeclaration
                    val d = descriptorOf(decl)
                    if (d != null && targets.any { sameTarget(it, d) }) {
                        val offset = decl.nameIdentifier?.textRange?.startOffset ?: decl.textRange.startOffset
                        val fqn = if (!DescriptorUtils.isLocal(d)) {
                            safeFqn(d)?.takeIf { isRealFqn(it) }
                        } else {
                            runCatching { decl.fqName?.asString() }.getOrNull()?.takeIf { isRealFqn(it) }
                        }
                        rows += toRow(occ, offset, fqn)
                    }
                }
                "ref" -> {
                    val expr = occ.node as KtSimpleNameExpression
                    val target = ctx[BindingContext.REFERENCE_TARGET, expr]
                    val bound = target != null && targets.any { sameTarget(it, target) }
                    if (bound) {
                        val effective = effectiveDescriptor(target)
                        val fqn = if (effective != null && !DescriptorUtils.isLocal(effective)) {
                            safeFqn(effective)?.takeIf { isRealFqn(it) }
                        } else {
                            null
                        }
                        rows += toRow(occ, expr.textRange.startOffset, fqn)
                    }
                }
            }
        }
        return rows.distinct()
    }

    override fun projectClasspath(workspacePath: String?): List<String> =
        if (closed) emptyList() else SnippetCompiler.detectProjectClasspath(workspacePath)

    override fun close() {
        closed = true
        snapshot = null
    }

    private fun workspaceFiles(workspacePath: String?): List<WorkspaceFile> {
        if (workspacePath.isNullOrBlank()) return emptyList()
        val root = File(workspacePath)
        if (!root.isDirectory) return emptyList()
        synchronized(this) {
            val current = root.walkTopDown().onEnter { dir ->
                val name = dir.name
                name != "build" && name != ".gradle" && name != ".git" && name != "out" && name != "node_modules"
            }.filter { it.isFile && it.extension == "kt" }.sortedBy { it.absolutePath }.toList()

            val cached = snapshot
            if (cached != null && cached.root == root && cached.files.size == current.size &&
                current.all { file -> cached.lastModified[file] == file.lastModified() }
            ) {
                return cached.files
            }

            val parsed = current.mapNotNull { file ->
                val rel = try { root.toPath().relativize(file.toPath()).toString() } catch (e: Exception) { file.name }
                val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                val psi = runCatching { K2SnippetFrontend.psiFactory.createFile(rel, text) }.getOrNull() ?: return@mapNotNull null
                WorkspaceFile(rel, file, psi)
            }
            snapshot = WorkspaceSnapshot(root, parsed, current.associateWith { it.lastModified() })
            workspaceRebuilds++
            return parsed
        }
    }

    private fun declarationPsiFor(
        session: K2AnalysisSession,
        files: List<Pair<String, KtFile>>,
        target: DeclarationDescriptor
    ): KtNamedDeclaration? {
        val ctx = session.bindingContext
        val effective = (target as? ClassConstructorDescriptor)?.constructedClass ?: target
        fun scan(match: (DeclarationDescriptor) -> Boolean): KtNamedDeclaration? {
            for ((_, file) in files) {
                var hit: KtNamedDeclaration? = null
                file.accept(object : KtTreeVisitorVoid() {
                    override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                        if (hit == null) {
                            val d = ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
                            if (d != null && (d === effective || match(d))) hit = declaration
                        }
                        super.visitNamedDeclaration(declaration)
                    }
                })
                if (hit != null) return hit
            }
            return null
        }
        scan { d -> d == effective }?.let { return it }
        return scan { d -> sameTarget(d, effective) }
    }

    private fun effectiveDescriptor(d: DeclarationDescriptor): DeclarationDescriptor =
        (d as? ClassConstructorDescriptor)?.constructedClass ?: d

    private fun sameTarget(a: DeclarationDescriptor, b: DeclarationDescriptor): Boolean {
        if (a == b) return true
        val ea = effectiveDescriptor(a)
        val eb = effectiveDescriptor(b)
        if (ea == eb) return true
        val fa = safeFqn(ea)
        val fb = safeFqn(eb)
        return fa != null && fb != null && isRealFqn(fa) && isRealFqn(fb) && fa == fb
    }

    private fun safeFqn(descriptor: DeclarationDescriptor?): String? =
        if (descriptor == null) null else runCatching { DescriptorUtils.getFqNameSafe(descriptor).asString() }.getOrNull()

    private fun isRealFqn(fqn: String?): Boolean =
        !fqn.isNullOrEmpty() && fqn != "<root>"

    private fun signatureOf(psi: PsiElement): String {
        val text = psi.text.take(140)
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        for (c in text) {
            if (c.isWhitespace()) {
                pendingSpace = sb.isNotEmpty()
            } else {
                if (pendingSpace) sb.append(' ')
                pendingSpace = false
                sb.append(c)
            }
        }
        return sb.toString().trim()
    }
}