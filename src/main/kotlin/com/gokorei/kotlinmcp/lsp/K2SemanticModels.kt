package com.gokorei.kotlinmcp.lsp

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
    val fqn: String? = null
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
