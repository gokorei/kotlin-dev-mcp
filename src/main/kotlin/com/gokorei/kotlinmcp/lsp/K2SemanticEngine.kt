@file:Suppress("K1_ANALYSIS", "DEPRECATION")
@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
package com.gokorei.kotlinmcp.lsp

import com.gokorei.kotlinmcp.execution.SnippetCompiler
import com.gokorei.kotlinmcp.shared.SourceUtils
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.types.KotlinType
import java.io.File

/** Where a resolved symbol's declaration lives. */
enum class ResolvedSource { SNIPPET, WORKSPACE, EXTERNAL, UNRESOLVED }

/** Default cap on `.kt` files semantically analyzed per workspace (`WORKSPACE_SEMANTIC_MAX_FILES` overrides). */
const val SEMANTIC_FILE_CAP: Int = 2000

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

/** Completion candidates for a typed prefix: receiver-type members + in-scope names. */
data class KotlinCompletionCandidates(
    val members: List<String>,
    val scope: List<String>
)

/** A byte-range edit that renames one bound occurrence of a symbol. */
data class ResolvedRenameEdit(
    val file: String,
    val offset: Int,
    val length: Int
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

/** Resolved hover info for a symbol in a snippet: type, signature, KDoc, location. */
data class KtHoverInfo(
    val symbol: String,
    val type: String?,
    val signature: String?,
    val fqn: String?,
    val source: ResolvedSource,
    val file: String?,
    val line: Int?,
    val kdoc: String?
)

/** Stats for the semantically analyzed workspace: file-count cap applied. */
data class WorkspaceStats(
    val totalKtFiles: Int,
    val analyzedFiles: Int,
    val truncated: Boolean
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
    private val fileCap: Int = System.getenv("WORKSPACE_SEMANTIC_MAX_FILES")?.toIntOrNull() ?: SEMANTIC_FILE_CAP
) : K2SemanticEngine {

    override val workspaceFileCap: Int get() = fileCap

    private data class WorkspaceFile(val rel: String, val file: File, val psi: KtFile)

    private data class WorkspaceSnapshot(
        val root: File,
        val files: List<WorkspaceFile>,
        val lastModified: Map<File, Long>,
        val totalFiles: Int = files.size
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

    override fun completionCandidates(session: K2AnalysisSession, prefix: String): KotlinCompletionCandidates {
        if (closed) return KotlinCompletionCandidates(emptyList(), emptyList())
        val dot = prefix.lastIndexOf('.')
        val memberPrefix = if (dot >= 0) prefix.substring(dot + 1) else ""
        val receiver = if (dot >= 0) prefix.substring(0, dot).trim() else ""
        val members = if (receiver.isNotEmpty()) {
            receiverType(session, receiver)?.let { memberNamesOf(it, memberPrefix, session.moduleDescriptor) }.orEmpty()
        } else {
            emptyList()
        }
        return KotlinCompletionCandidates(members, scopeCandidates(session, prefix))
    }

    /** Best resolver type for a receiver expression text, preferring the last occurrence. */
    private fun receiverType(session: K2AnalysisSession, receiver: String): KotlinType? {
        val ctx = session.bindingContext
        val hits = mutableListOf<Pair<Int, KotlinType>>()
        session.file.accept(object : KtTreeVisitorVoid() {
            override fun visitExpression(expression: KtExpression) {
                if (expression.text == receiver) {
                    ctx[BindingContext.EXPRESSION_TYPE_INFO, expression]?.type?.let { hits.add(expression.textRange.startOffset to it) }
                }
                super.visitExpression(expression)
            }

            override fun visitProperty(property: KtProperty) {
                if (property.name == receiver) {
                    val t = property.initializer?.let { ctx[BindingContext.EXPRESSION_TYPE_INFO, it]?.type }
                        ?: property.typeReference?.let { ctx[BindingContext.TYPE, it] }
                    t?.let { hits.add(property.textRange.startOffset to it) }
                }
                super.visitProperty(property)
            }

            override fun visitParameter(parameter: KtParameter) {
                if (parameter.name == receiver) {
                    parameter.typeReference?.let { ctx[BindingContext.TYPE, it]?.let { t -> hits.add(parameter.textRange.startOffset to t) } }
                }
                super.visitParameter(parameter)
            }
        })
        return hits.maxByOrNull { it.first }?.second
    }

    private fun memberNamesOf(type: KotlinType, prefix: String, module: ModuleDescriptor?): List<String> {
        val cls = type.constructor.declarationDescriptor as? ClassDescriptor ?: return emptyList()
        val names = linkedSetOf<String>()
        runCatching {
            cls.unsubstitutedMemberScope.getContributedDescriptors(DescriptorKindFilter.ALL, MemberScope.ALL_NAME_FILTER).forEach { d ->
                val n = d.name.asString()
                val vis = (d as? MemberDescriptor)?.visibility
                if (!n.startsWith("<") && vis != DescriptorVisibilities.PRIVATE && vis != DescriptorVisibilities.PRIVATE_TO_THIS) {
                    if (prefix.isBlank() || n.startsWith(prefix, ignoreCase = true)) names.add(n)
                }
            }
        }
        if (module != null) {
            for (pkgName in listOf("kotlin.text", "kotlin.collections", "kotlin")) {
                runCatching {
                    val pkg = module.getPackage(org.jetbrains.kotlin.name.FqName(pkgName))
                    pkg.memberScope.getContributedDescriptors(DescriptorKindFilter.CALLABLES, MemberScope.ALL_NAME_FILTER)
                        .filterIsInstance<FunctionDescriptor>()
                        .forEach { fn ->
                            val n = fn.name.asString()
                            if (!n.startsWith("<")) {
                                val recv = fn.extensionReceiverParameter?.type
                                if (recv != null && safeFqn(recv.constructor.declarationDescriptor) == safeFqn(cls) &&
                                    (prefix.isBlank() || n.startsWith(prefix, ignoreCase = true))
                                ) {
                                    names.add(n)
                                }
                            }
                        }
                }
            }
        }
        return names.sorted()
    }

    /** In-scope names: declarations, parameters and imported names matching [prefix]. */
    private fun scopeCandidates(session: K2AnalysisSession, prefix: String): List<String> {
        val names = linkedSetOf<String>()
        session.file.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                if (declaration !is org.jetbrains.kotlin.psi.KtPrimaryConstructor) {
                    declaration.name?.let { names.add(it) }
                }
                super.visitNamedDeclaration(declaration)
            }

            override fun visitParameter(parameter: KtParameter) {
                parameter.name?.let { names.add(it) }
                super.visitParameter(parameter)
            }
        })
        session.file.importDirectives.forEach { dir ->
            dir.importedFqName?.let { names.add(it.shortName().asString()) }
            dir.aliasName?.let { names.add(it) }
        }
        return names
            .filter { prefix.isBlank() || it.startsWith(prefix, ignoreCase = true) || it.contains(prefix, ignoreCase = true) }
            .sortedBy { it.lowercase() }
            .distinct()
    }

    override fun renameEditsForSymbol(session: K2AnalysisSession, symbol: String, workspacePath: String?): List<ResolvedRenameEdit> {
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

        fun descriptorOf(decl: KtNamedDeclaration): DeclarationDescriptor? =
            ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, decl]

        fun pickTargets(candidates: List<DeclarationDescriptor>): List<DeclarationDescriptor> {
            val real = candidates.filter { !DescriptorUtils.isLocal(it) && isRealFqn(safeFqn(it)) }
            return (if (real.isNotEmpty()) real else candidates).distinct()
        }

        val decls = occurrences.filter { it.kind == "decl" }
        val snippetDecls = decls.filter { it.rel == "Snippet.kt" }
        // Rename anchor, in order of preference:
        //  1. a declaration inside the snippet (the LLM's context),
        //  2. the declarations the snippet's references actually resolve to,
        //  3. any workspace/global declaration with the name.
        // An unrelated same-name symbol elsewhere is never a rename target.
        val targets: List<DeclarationDescriptor> = when {
            snippetDecls.isNotEmpty() -> pickTargets(snippetDecls.mapNotNull { descriptorOf(it.node as KtNamedDeclaration) })
            else -> {
                val snippetRefTargets = occurrences
                    .filter { it.kind == "ref" && it.rel == "Snippet.kt" }
                    .mapNotNull { ctx[BindingContext.REFERENCE_TARGET, it.node as KtSimpleNameExpression] }
                if (snippetRefTargets.isNotEmpty()) {
                    pickTargets(snippetRefTargets)
                } else {
                    pickTargets(decls.mapNotNull { descriptorOf(it.node as KtNamedDeclaration) })
                }
            }
        }

        data class Edit(val rel: String, val offset: Int)

        val edits = mutableListOf<Edit>()
        occurrences.forEach { occ ->
            when (occ.kind) {
                "decl" -> {
                    val decl = occ.node as KtNamedDeclaration
                    val d = descriptorOf(decl)
                    if (d != null && targets.any { sameTarget(it, d) }) {
                        val offset = decl.nameIdentifier?.textRange?.startOffset ?: decl.textRange.startOffset
                        edits += Edit(occ.rel, offset)
                    }
                }
                "ref" -> {
                    val expr = occ.node as KtSimpleNameExpression
                    val target = ctx[BindingContext.REFERENCE_TARGET, expr]
                    if (target != null && targets.any { sameTarget(it, target) }) {
                        edits += Edit(occ.rel, expr.textRange.startOffset)
                    }
                }
            }
        }
        return edits.distinct().map { ResolvedRenameEdit(it.rel, it.offset, symbol.length) }
    }

    override fun typeHierarchy(session: K2AnalysisSession, symbol: String, workspacePath: String?): KtTypeHierarchyResult {
        if (closed) return KtTypeHierarchyResult(symbol, emptyList(), emptyList())
        val ctx = session.bindingContext

        data class ClassEntry(val rel: String, val psi: KtFile, val node: org.jetbrains.kotlin.psi.KtClassOrObject)

        val classes = mutableListOf<ClassEntry>()
        fun collect(rel: String, psi: KtFile) {
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitClassOrObject(classOrObject: org.jetbrains.kotlin.psi.KtClassOrObject) {
                    if (classOrObject.name == symbol) classes += ClassEntry(rel, psi, classOrObject)
                    super.visitClassOrObject(classOrObject)
                }
            })
        }
        collect("Snippet.kt", session.file)
        workspaceFiles(workspacePath).forEach { collect(it.rel, it.psi) }

        val ordered = classes.filter { it.rel == "Snippet.kt" } + classes.filter { it.rel != "Snippet.kt" }
        val descriptors = ordered.mapNotNull { ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, it.node] as? ClassDescriptor }
        val realDescriptors = descriptors.filter { isRealFqn(safeFqn(it)) }
        val target = (realDescriptors.ifEmpty { descriptors }).firstOrNull()
        if (target == null) return KtTypeHierarchyResult(symbol, emptyList(), emptyList())

        val supertypes = target.typeConstructor.supertypes
            .mapNotNull { runCatching { DescriptorRenderer.FQ_NAMES_IN_TYPES.renderType(it) }.getOrNull() }
            .distinct()

        fun inherits(d: ClassDescriptor): Boolean {
            val queue = ArrayDeque<ClassDescriptor>()
            val seen = hashSetOf<DeclarationDescriptor>()
            queue.add(d)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (!seen.add(cur)) continue
                for (superType in cur.typeConstructor.supertypes) {
                    val dd = superType.constructor.declarationDescriptor as? ClassDescriptor ?: continue
                    if (sameTarget(dd, target)) return true
                    queue.add(dd)
                }
            }
            return false
        }

        val subtypes = mutableListOf<KtTypeOccurrence>()
        fun scanForSubtypes(rel: String, psi: KtFile) {
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitClassOrObject(classOrObject: org.jetbrains.kotlin.psi.KtClassOrObject) {
                    val d = ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, classOrObject] as? ClassDescriptor
                    val name = classOrObject.name
                    if (d != null && inherits(d) && name != null) {
                        val offset = classOrObject.nameIdentifier?.textRange?.startOffset ?: classOrObject.textRange.startOffset
                        subtypes += KtTypeOccurrence(name, rel, SourceUtils.lineOf(psi.text, offset))
                    }
                    super.visitClassOrObject(classOrObject)
                }
            })
        }
        scanForSubtypes("Snippet.kt", session.file)
        workspaceFiles(workspacePath).forEach { scanForSubtypes(it.rel, it.psi) }

        return KtTypeHierarchyResult(symbol, supertypes, subtypes.distinctBy { Pair(it.name, it.file) })
    }

    override fun callHierarchy(session: K2AnalysisSession, symbol: String, workspacePath: String?): KtCallHierarchyResult {
        if (closed) return KtCallHierarchyResult(symbol, emptyList())
        val ctx = session.bindingContext

        fun pickTargets(candidates: List<DeclarationDescriptor>): List<DeclarationDescriptor> {
            val real = candidates.filter { !DescriptorUtils.isLocal(it) && isRealFqn(safeFqn(it)) }
            return (if (real.isNotEmpty()) real else candidates).distinct()
        }

        val functionDecls = mutableListOf<Pair<String, KtNamedDeclaration>>()
        val snippetCallTargets = mutableListOf<DeclarationDescriptor>()
        fun collect(rel: String, psi: KtFile) {
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitNamedFunction(function: org.jetbrains.kotlin.psi.KtNamedFunction) {
                    if (function.name == symbol) functionDecls += rel to function
                    super.visitNamedFunction(function)
                }

                override fun visitCallExpression(expression: org.jetbrains.kotlin.psi.KtCallExpression) {
                    val callee = expression.calleeExpression as? org.jetbrains.kotlin.psi.KtSimpleNameExpression
                    if (callee != null && callee.getReferencedName() == symbol && rel == "Snippet.kt") {
                        ctx[BindingContext.REFERENCE_TARGET, callee]?.let { snippetCallTargets.add(it) }
                    }
                    super.visitCallExpression(expression)
                }
            })
        }
        collect("Snippet.kt", session.file)
        workspaceFiles(workspacePath).forEach { collect(it.rel, it.psi) }

        val snippetDeclTargets = functionDecls
            .filter { it.first == "Snippet.kt" }
            .mapNotNull { (_, decl) -> ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, decl] }
        val targets: List<DeclarationDescriptor> = when {
            snippetDeclTargets.isNotEmpty() -> pickTargets(snippetDeclTargets)
            snippetCallTargets.isNotEmpty() -> pickTargets(snippetCallTargets)
            else -> pickTargets(functionDecls.mapNotNull { (_, decl) -> ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, decl] })
        }
        if (targets.isEmpty()) return KtCallHierarchyResult(symbol, emptyList())

        fun enclosingFunction(node: org.jetbrains.kotlin.com.intellij.psi.PsiElement): org.jetbrains.kotlin.psi.KtNamedFunction? {
            var ancestor = node.parent
            while (ancestor != null) {
                if (ancestor is org.jetbrains.kotlin.psi.KtNamedFunction) return ancestor
                ancestor = ancestor.parent
            }
            return null
        }

        val callers = mutableListOf<KtCallOccurrence>()
        fun scanCalls(rel: String, psi: KtFile) {
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: org.jetbrains.kotlin.psi.KtCallExpression) {
                    val callee = expression.calleeExpression as? org.jetbrains.kotlin.psi.KtSimpleNameExpression
                    val resolved = callee?.let { ctx[BindingContext.REFERENCE_TARGET, it] }
                    if (resolved != null && targets.any { sameTarget(it, resolved) }) {
                        val enclosing = enclosingFunction(expression)
                        val line = SourceUtils.lineOf(psi.text, expression.textRange.startOffset)
                        callers += KtCallOccurrence(enclosing?.name, rel, line, expression.text.take(80))
                    }
                    super.visitCallExpression(expression)
                }
            })
        }
        scanCalls("Snippet.kt", session.file)
        workspaceFiles(workspacePath).forEach { scanCalls(it.rel, it.psi) }

        return KtCallHierarchyResult(symbol, callers.distinct())
    }

    override fun projectClasspath(workspacePath: String?): List<String> =
        if (closed) emptyList() else SnippetCompiler.detectProjectClasspath(workspacePath)

    override fun hover(session: K2AnalysisSession, symbol: String, workspacePath: String?): KtHoverInfo? {
        if (closed) return null
        val ctx = session.bindingContext
        val files = listOf("Snippet.kt" to session.file) + workspaceFiles(workspacePath).map { it.rel to it.psi }

        var reference: KtSimpleNameExpression? = null
        var declaration: KtNamedDeclaration? = null
        session.file.accept(object : KtTreeVisitorVoid() {
            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                if (reference == null && expression.getReferencedName() == symbol) {
                    reference = expression
                }
                super.visitSimpleNameExpression(expression)
            }

            override fun visitNamedDeclaration(decl: KtNamedDeclaration) {
                if (declaration == null && decl !is org.jetbrains.kotlin.psi.KtPrimaryConstructor && decl.name == symbol) {
                    declaration = decl
                }
                super.visitNamedDeclaration(decl)
            }
        })
        if (reference == null && declaration == null) return null

        val descriptor: DeclarationDescriptor? = reference?.let { ctx[BindingContext.REFERENCE_TARGET, it] }
            ?: declaration?.let { ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, it] }
        if (descriptor == null) return null
        val effective = effectiveDescriptor(descriptor)

        val expressionType: String? = reference?.let { ref ->
            val typeNode: KtExpression = (ref.parent as? KtCallExpression) ?: ref
            ctx[BindingContext.EXPRESSION_TYPE_INFO, typeNode]?.type
                ?.let { DescriptorRenderer.FQ_NAMES_IN_TYPES.renderType(it) }
        }
        val declaredType: String? = (declaration as? KtProperty)?.let { prop ->
            val t = prop.initializer?.let { ctx[BindingContext.EXPRESSION_TYPE_INFO, it]?.type }
                ?: prop.typeReference?.let { ctx[BindingContext.TYPE, it] }
            t?.let { DescriptorRenderer.FQ_NAMES_IN_TYPES.renderType(it) }
        }
        val type = expressionType ?: declaredType

        val signature = runCatching { DescriptorRenderer.FQ_NAMES_IN_TYPES.render(descriptor) }.getOrNull()
        val fqn = safeFqn(effective)

        val declPsi = declarationPsiFor(session, files, descriptor)
        val source: ResolvedSource
        val file: String?
        val line: Int?
        val kdoc: String?
        if (declPsi != null) {
            val containing = declPsi.containingFile as KtFile
            val isSnippet = containing == session.file || containing.name.startsWith("Snippet_")
            source = if (isSnippet) ResolvedSource.SNIPPET else ResolvedSource.WORKSPACE
            file = if (isSnippet) "Snippet.kt" else containing.name
            line = SourceUtils.lineOf(containing.text, declPsi.textRange.startOffset)
            kdoc = declPsi.docComment?.text?.trim()?.takeIf { it.isNotBlank() }
        } else {
            source = ResolvedSource.EXTERNAL
            file = null
            line = null
            kdoc = null
        }
        return KtHoverInfo(symbol, type, signature, fqn, source, file, line, kdoc)
    }

    override fun workspaceStats(workspacePath: String?): WorkspaceStats {
        if (closed) return WorkspaceStats(0, 0, false)
        val root = if (workspacePath.isNullOrBlank()) null else File(workspacePath)
        if (root == null || !root.isDirectory) return WorkspaceStats(0, 0, false)
        val total = root.walkTopDown().onEnter { dir ->
            dir.name != "build" && dir.name != ".gradle" && dir.name != ".git" && dir.name != "out" && dir.name != "node_modules"
        }.count { it.isFile && it.extension == "kt" }
        val analyzed = minOf(total, fileCap)
        return WorkspaceStats(total, analyzed, total > fileCap)
    }

    override fun close() {
        closed = true
        snapshot = null
    }

    private fun workspaceFiles(workspacePath: String?): List<WorkspaceFile> {
        if (workspacePath.isNullOrBlank()) return emptyList()
        val root = File(workspacePath)
        if (!root.isDirectory) return emptyList()
        synchronized(this) {
            val allFiles = root.walkTopDown().onEnter { dir ->
                val name = dir.name
                name != "build" && name != ".gradle" && name != ".git" && name != "out" && name != "node_modules"
            }.filter { it.isFile && it.extension == "kt" }.sortedBy { it.absolutePath }.toList()

            val cached = snapshot
            if (cached != null && cached.root == root && cached.totalFiles == allFiles.size &&
                allFiles.all { file -> cached.lastModified[file] == file.lastModified() }
            ) {
                return cached.files
            }

            val parsed = allFiles.take(fileCap).mapNotNull { file ->
                val rel = try { root.toPath().relativize(file.toPath()).toString() } catch (e: Exception) { file.name }
                val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                val psi = runCatching { K2SnippetFrontend.psiFactory.createFile(rel, text) }.getOrNull() ?: return@mapNotNull null
                WorkspaceFile(rel, file, psi)
            }
            snapshot = WorkspaceSnapshot(root, parsed, allFiles.associateWith { it.lastModified() }, allFiles.size)
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