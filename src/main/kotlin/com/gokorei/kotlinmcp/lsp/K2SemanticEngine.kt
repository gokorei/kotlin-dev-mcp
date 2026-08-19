@file:Suppress("K1_ANALYSIS", "DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
package com.gokorei.kotlinmcp.lsp

import com.gokorei.kotlinmcp.execution.SnippetCompiler
import com.gokorei.kotlinmcp.shared.SourceUtils
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import java.io.File

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
     * FQN, signature). Returns a [ResolvedDeclaration] with
     * [ResolvedSource.UNRESOLVED] when the reference has no resolvable target.
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

    /**
     * Completion candidates for a typed [prefix] against [session]'s snippet:
     * `members` are the visible members of the receiver expression before the
     * last `.` (resolved via the binding context, including default-imported
     * stdlib extensions), and `scope` are the in-scope declaration/import
     * names matching the prefix.
     */
    fun completionCandidates(session: K2AnalysisSession, prefix: String): KotlinCompletionCandidates

    /**
     * Byte-range edits renaming every bound occurrence of [symbol] — the
     * declaration plus all usages that resolve to it — across the snippet
     * (`"Snippet.kt"`) and the workspace. Declarations are chosen snippet-first:
     * if the snippet declares the name, that (possibly local) declaration is
     * the rename target and unrelated same-name symbols elsewhere are left
     * alone; otherwise the workspace-level declaration is renamed.
     */
    fun renameEditsForSymbol(session: K2AnalysisSession, symbol: String, workspacePath: String?): List<ResolvedRenameEdit>

    /**
     * Type hierarchy for a class/interface/object named [symbol], derived from
     * resolved types: supertype FQNs from the class descriptor and every
     * class/object whose resolved supertype chain reaches it.
     */
    fun typeHierarchy(session: K2AnalysisSession, symbol: String, workspacePath: String?): KtTypeHierarchyResult

    /**
     * Call hierarchy for [symbol]: every resolved call site (across snippet and
     * workspace) whose callee binds to a target function named [symbol], with
     * its enclosing function. Derived from call sites' resolved targets, not
     * name matching.
     */
    fun callHierarchy(session: K2AnalysisSession, symbol: String, workspacePath: String?): KtCallHierarchyResult

    /**
     * Hover info for a symbol in the snippet: the resolved type of the
     * reference/call, the rendered descriptor signature, the FQN, where the
     * declaration lives (snippet / workspace / external), and its KDoc when the
     * declaration is source-visible. Returns `null` when [symbol] has neither a
     * matching reference nor declaration in the snippet.
     */
    fun hover(session: K2AnalysisSession, symbol: String, workspacePath: String?): KtHoverInfo?

    /**
     * Semantically-analyzed workspace file cap: workspaces with more `.kt`
     * files than this are truncated on analysis and their results should be
     * surfaced as potentially incomplete.
     */
    val workspaceFileCap: Int

    /** Total vs analyzed `.kt` file counts for a workspace, with the cap applied. */
    fun workspaceStats(workspacePath: String?): WorkspaceStats

    /** Compiled-class / build-libs classpath detected under the workspace. */
    fun projectClasspath(workspacePath: String?): List<String>

    /** Number of times the workspace `.kt` files were (re)parsed since construction. */
    val workspaceRebuilds: Int

    /** Releases cached PSI state; the shared environment stays owned by [K2SnippetFrontend]. */
    fun close()
}

class DefaultK2SemanticEngine(
    fileCap: Int = System.getenv("WORKSPACE_SEMANTIC_MAX_FILES")?.toIntOrNull()?.takeIf { it > 0 } ?: SEMANTIC_FILE_CAP
) : K2SemanticEngine {

    private val fileCap: Int = fileCap.coerceAtLeast(1)

    override val workspaceFileCap: Int get() = fileCap

    private data class WorkspaceFile(val rel: String, val file: File, val psi: KtFile)

    private data class WorkspaceSnapshot(
        val root: File,
        val files: List<WorkspaceFile>,
        val lastModified: Map<File, Long>,
        val totalFiles: Int = files.size
    )

    /** Bounded least-recently-used cache keyed by canonical workspace root. */
    private val snapshotCache = object : LinkedHashMap<File, WorkspaceSnapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<File, WorkspaceSnapshot>?): Boolean =
            size > MAX_CACHED_WORKSPACES
    }

    private companion object {
        const val MAX_CACHED_WORKSPACES = 4
    }

    @Volatile
    private var closed = false

    @Volatile
    override var workspaceRebuilds: Int = 0
        private set

    override fun session(workspacePath: String?, snippet: String): K2AnalysisSession? {
        if (closed) return null
        return K2SnippetFrontend.analyzeSession(snippet, workspaceFiles(workspacePath).map { it.psi })
    }

    override fun resolveReference(
        session: K2AnalysisSession,
        reference: KtReferenceExpression,
        workspaceRoot: String?
    ): ResolvedDeclaration? {
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
        val fqn = ((descriptor as? ClassConstructorDescriptor)?.constructedClass?.let { K2ResolutionUtils.safeFqn(it) }
            ?: K2ResolutionUtils.safeFqn(descriptor))
        val files = listOf("Snippet.kt" to session.file) + workspaceFiles(workspaceRoot).map { it.rel to it.psi }
        val declPsi = K2ResolutionUtils.declarationPsiFor(session, files, descriptor)
        return if (declPsi != null) {
            val containing = declPsi.containingFile as KtFile
            val isSnippet = containing == session.file || containing.name.startsWith("Snippet_")
            val resolvedFile = if (isSnippet) "Snippet.kt" else files.firstOrNull { it.second == containing }?.first ?: containing.name
            ResolvedDeclaration(
                symbol = reference.text,
                file = resolvedFile,
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
        return descriptor?.let { K2ResolutionUtils.safeFqn(it) } ?: declaration.fqName?.asString()
    }

    override fun typeOfExpression(session: K2AnalysisSession, expression: KtExpression): String? {
        if (closed) return null
        val info = session.bindingContext[BindingContext.EXPRESSION_TYPE_INFO, expression]
        return info?.type?.let { DescriptorRenderer.FQ_NAMES_IN_TYPES.renderType(it) }
    }

    override fun referencesForSymbol(session: K2AnalysisSession, symbol: String, workspacePath: String?): List<ResolvedReference> {
        if (closed) return emptyList()
        val ctx = session.bindingContext

        val occurrences = K2ResolutionUtils.collectSymbolOccurrences(
            symbol,
            listOf("Snippet.kt" to session.file) + workspaceFiles(workspacePath).map { it.rel to it.psi }
        )

        val declOccurrences = occurrences.filter { it.kind == "decl" }
        fun descriptorOf(decl: KtNamedDeclaration): DeclarationDescriptor? =
            ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, decl]

        val declTargets = declOccurrences.mapNotNull { descriptorOf(it.node as KtNamedDeclaration) }
        val refTargets = occurrences
            .filter { it.kind == "ref" }
            .mapNotNull { ctx[BindingContext.REFERENCE_TARGET, it.node as KtSimpleNameExpression] }
        val targets: List<DeclarationDescriptor> = K2ResolutionUtils.pickTargets(declTargets.ifEmpty { refTargets })

        fun toRow(occ: K2ResolutionUtils.SymbolOccurrence, offset: Int, fqn: String?): ResolvedReference {
            val file = occ.file
            val lineText: String
            val line: Int
            val column: Int
            val document = file.viewProvider.document
            if (document != null) {
                val lineIndex = document.getLineNumber(offset)
                line = lineIndex + 1
                val lineStart = document.getLineStartOffset(lineIndex)
                column = offset - lineStart + 1
                val lineEnd = document.getLineEndOffset(lineIndex)
                lineText = document.getText().substring(lineStart, lineEnd).trim()
            } else {
                val text = file.text
                line = SourceUtils.lineOf(text, offset)
                val lineStart = text.lastIndexOf('\n', offset - 1) + 1
                column = offset - lineStart + 1
                val end = text.indexOf('\n', offset).let { if (it == -1) text.length else it }
                lineText = text.substring(lineStart, end).trim()
            }
            return ResolvedReference(symbol, occ.rel, line, column, lineText, occ.kind, fqn)
        }

        val rows = mutableListOf<ResolvedReference>()
        occurrences.forEach { occ ->
            when (occ.kind) {
                "decl" -> {
                    val decl = occ.node as KtNamedDeclaration
                    val d = descriptorOf(decl)
                    if (d != null && targets.any { K2ResolutionUtils.sameTarget(it, d) }) {
                        val offset = decl.nameIdentifier?.textRange?.startOffset ?: decl.textRange.startOffset
                        val fqn = if (!DescriptorUtils.isLocal(d)) {
                            K2ResolutionUtils.safeFqn(d)?.takeIf { K2ResolutionUtils.isRealFqn(it) }
                        } else {
                            runCatching { decl.fqName?.asString() }.getOrNull()?.takeIf { K2ResolutionUtils.isRealFqn(it) }
                        }
                        rows += toRow(occ, offset, fqn)
                    }
                }
                "ref" -> {
                    val expr = occ.node as KtSimpleNameExpression
                    val target = ctx[BindingContext.REFERENCE_TARGET, expr]
                    val bound = target != null && targets.any { K2ResolutionUtils.sameTarget(it, target) }
                    if (bound) {
                        val effective = K2ResolutionUtils.effectiveDescriptor(target)
                        val fqn = if (!DescriptorUtils.isLocal(effective)) {
                            K2ResolutionUtils.safeFqn(effective)?.takeIf { K2ResolutionUtils.isRealFqn(it) }
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

    override fun completionCandidates(session: K2AnalysisSession, prefix: String): KotlinCompletionCandidates =
        if (closed) KotlinCompletionCandidates(emptyList(), emptyList()) else K2CompletionResolver.completionCandidates(session, prefix)

    override fun renameEditsForSymbol(session: K2AnalysisSession, symbol: String, workspacePath: String?): List<ResolvedRenameEdit> =
        if (closed) emptyList() else K2RenameResolver.renameEditsForSymbol(session, symbol, workspaceFiles(workspacePath).map { it.rel to it.psi })

    override fun typeHierarchy(session: K2AnalysisSession, symbol: String, workspacePath: String?): KtTypeHierarchyResult =
        if (closed) KtTypeHierarchyResult(symbol, emptyList(), emptyList()) else K2HierarchyResolver.typeHierarchy(session, symbol, workspaceFiles(workspacePath).map { it.rel to it.psi })

    override fun callHierarchy(session: K2AnalysisSession, symbol: String, workspacePath: String?): KtCallHierarchyResult =
        if (closed) KtCallHierarchyResult(symbol, emptyList()) else K2HierarchyResolver.callHierarchy(session, symbol, workspaceFiles(workspacePath).map { it.rel to it.psi })

    override fun hover(session: K2AnalysisSession, symbol: String, workspacePath: String?): KtHoverInfo? =
        if (closed) null else K2HoverResolver.hover(session, symbol, listOf("Snippet.kt" to session.file) + workspaceFiles(workspacePath).map { it.rel to it.psi })

    override fun workspaceStats(workspacePath: String?): WorkspaceStats {
        if (closed) return WorkspaceStats(0, 0, false)
        val root = if (workspacePath.isNullOrBlank()) null else File(workspacePath)
        if (root == null || !root.isDirectory) return WorkspaceStats(0, 0, false)
        val key = runCatching { root.canonicalFile }.getOrDefault(root)
        synchronized(this) {
            if (closed) return WorkspaceStats(0, 0, false)
            val allFiles = ktFilesUnder(root)
            val total = allFiles.size
            val cached = snapshotCache[key]
            if (cached != null && (cached.totalFiles != total || !allFiles.all { cached.lastModified[it] == it.lastModified() })) {
                snapshotCache.remove(key)
            }
            val analyzed = minOf(total, fileCap)
            return WorkspaceStats(total, analyzed, total > fileCap)
        }
    }

    override fun projectClasspath(workspacePath: String?): List<String> =
        if (closed) emptyList() else SnippetCompiler.detectProjectClasspath(workspacePath)

    override fun close() {
        synchronized(this) {
            closed = true
            snapshotCache.clear()
        }
    }

    private fun workspaceFiles(workspacePath: String?): List<WorkspaceFile> {
        if (workspacePath.isNullOrBlank()) return emptyList()
        val root = File(workspacePath)
        if (!root.isDirectory) return emptyList()
        val key = runCatching { root.canonicalFile }.getOrDefault(root)
        synchronized(this) {
            if (closed) return emptyList()

            val allFiles = ktFilesUnder(root)

            val cached = snapshotCache[key]
            if (cached != null && cached.totalFiles == allFiles.size &&
                allFiles.all { file -> cached.lastModified[file] == file.lastModified() }
            ) {
                return cached.files
            }

            val parsed = allFiles.take(fileCap).mapNotNull { file ->
                val rel = try { root.toPath().relativize(file.toPath()).toString() } catch (_: Exception) { file.name }
                val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                val psi = runCatching { K2SnippetFrontend.psiFactory.createFile(rel, text) }.getOrNull() ?: return@mapNotNull null
                WorkspaceFile(rel, file, psi)
            }
            snapshotCache[key] = WorkspaceSnapshot(key, parsed, allFiles.associateWith { it.lastModified() }, allFiles.size)
            workspaceRebuilds++
            return parsed
        }
    }

    private fun ktFilesUnder(root: File): List<File> =
        root.walkTopDown().onEnter { dir -> !K2ResolutionUtils.isExcludedWorkspaceDir(dir) }
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.absolutePath }
            .toList()

    private fun signatureOf(psi: PsiElement): String =
        SourceUtils.collapseWhitespace(psi.text, maxLength = 140)
}