package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.WorkspaceSemanticIndexer
import java.io.File

/**
 * Strategy component for dumping public API surfaces (classes, functions, properties) of packages.
 */
class PackageApiExporter {

    fun exportPackageApi(buildScriptContent: String, projectPath: String?): KotlinMcpResult {
        return packageApi(projectPath, null, WorkspaceSemanticIndexer())
    }

    fun packageApi(projectPath: String?, packageName: String?, indexer: WorkspaceSemanticIndexer): KotlinMcpResult {
        if (projectPath.isNullOrBlank()) {
            return KotlinMcpResult.Error(
                message = "projectPath is required for package_api.",
                code = "INVALID_ARGUMENTS"
            )
        }
        val root = File(projectPath)
        if (!root.isDirectory) {
            return KotlinMcpResult.Error(
                message = "projectPath must be a readable directory for package_api.",
                code = "INVALID_ARGUMENTS"
            )
        }
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val (elements, _) = indexer.publicApiOf(files, root.invariantSeparatorsPath, packageName)
        if (elements.isEmpty()) {
            return KotlinMcpResult.Error(
                message = "No public declarations found for package '${packageName ?: "(any)"}'.",
                code = "NOT_FOUND"
            )
        }

        val content = buildString {
            appendLine("# Public API Surface — ${packageName ?: "all packages"} (${elements.size} declarations)")
            appendLine()
            elements.groupBy { it.file }.forEach { (file, list) ->
                appendLine("## `$file`")
                list.forEach { el ->
                    val doc = el.docSummary?.let { " — $it" }.orEmpty()
                    appendLine("- `${el.visibility} ${el.signature}`$doc")
                }
                appendLine()
            }
            appendLine("> Mode: semantic (inferred return types resolved)")
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf(
                "packageName" to (packageName ?: ""),
                "declarationCount" to elements.size.toString(),
                "fileCount" to elements.map { it.file }.distinct().size.toString()
            )
        )
    }
}
