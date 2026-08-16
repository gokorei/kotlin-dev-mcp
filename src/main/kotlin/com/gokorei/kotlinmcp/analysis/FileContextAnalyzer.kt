package com.gokorei.kotlinmcp.analysis

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.lsp.OccurrenceKind
import com.gokorei.kotlinmcp.lsp.WorkspaceSemanticIndexer
import java.io.File

/**
 * Strategy component for summarizing target file packages, imported types, and inbound/outbound workspace dependencies.
 */
class FileContextAnalyzer {

    fun fileContext(code: String, workspacePath: String?, indexer: WorkspaceSemanticIndexer): KotlinMcpResult {
        val targetFile = File(code)
        if (!targetFile.isFile || !targetFile.extension.equals("kt", true)) {
            return KotlinMcpResult.Error(
                message = "code must be the absolute path of a Kotlin (.kt) file for file_context.",
                code = "INVALID_ARGUMENTS"
            )
        }
        val root = if (!workspacePath.isNullOrBlank()) File(workspacePath) else targetFile.parentFile
        if (root == null || !root.isDirectory) {
            return KotlinMcpResult.Error(
                message = "workspacePath must be a readable directory for file_context.",
                code = "INVALID_ARGUMENTS"
            )
        }
        val rootCanonical = root.canonicalFile.toPath()
        val targetCanonical = targetFile.canonicalFile.toPath()
        if (!targetCanonical.startsWith(rootCanonical)) {
            return KotlinMcpResult.Error(
                message = "Target file path must be inside the workspace root directory.",
                code = "INVALID_ARGUMENTS"
            )
        }

        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val index = indexer.index(files, root.invariantSeparatorsPath)
        val targetRel = try {
            root.toPath().relativize(targetFile.toPath()).toString()
        } catch (e: Exception) {
            targetFile.name
        }

        val targetOccs = index.occurrences.filter { it.file == targetRel }
        if (targetOccs.isEmpty()) {
            return KotlinMcpResult.Error(
                message = "Target file was not found under the workspace.",
                code = "NOT_FOUND"
            )
        }
        val targetDecls = index.declarations.filter { it.file == targetRel }
        val targetFqns = targetDecls.mapNotNull { it.fqn }.toSet()
        val targetNames = targetDecls.map { it.name }.toSet()

        val outbound = index.occurrences
            .filter { occ ->
                occ.kind == OccurrenceKind.REFERENCE && occ.file != targetRel &&
                    (if (targetFqns.isNotEmpty()) occ.fqn in targetFqns else occ.name in targetNames)
            }
            .groupBy({ it.fqn ?: it.name }, { it.file })
            .mapValues { (_, filesList) -> filesList.distinct().sorted() }

        val inbound = targetOccs
            .filter { it.kind == OccurrenceKind.REFERENCE }
            .mapNotNull { occ ->
                val otherDecl = index.declarations.firstOrNull {
                    it.file != targetRel && occ.fqn != null && it.fqn == occ.fqn
                }
                if (otherDecl != null) occ.name to otherDecl.file else null
            }
            .distinct()

        val targetPsi = targetFile.readText().let { K2SnippetFrontend.parsePsi(it) }
        val packageName = targetPsi?.packageFqName?.asString().orEmpty()
        val imports = targetPsi?.importList?.imports
            ?.mapNotNull { it.importedFqName?.asString() }
            ?.distinct().orEmpty()

        val content = buildString {
            appendLine("# File Context: `$targetRel`")
            appendLine("- Package: ${packageName.ifEmpty { "(default)" }}")
            appendLine("- Imports: ${imports.takeIf { it.isNotEmpty() }?.joinToString(", ").orEmpty().ifEmpty { "(none)" }}")
            appendLine()

            appendLine("## Outbound Dependencies (declared here, used elsewhere)")
            if (outbound.isNotEmpty()) {
                outbound.forEach { (symbol, filesList) ->
                    appendLine("- `$symbol` -> ${filesList.joinToString(", ")}")
                }
            } else {
                appendLine("- (none)")
            }
            appendLine()

            appendLine("## Inbound Dependencies (declared elsewhere, used here)")
            if (inbound.isNotEmpty()) {
                inbound.forEach { (symbol, file) ->
                    appendLine("- `$symbol` -> $file")
                }
            } else {
                appendLine("- (none)")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf(
                "file" to targetRel,
                "package" to packageName,
                "outboundCount" to outbound.size.toString(),
                "inboundCount" to inbound.size.toString()
            )
        )
    }
}
