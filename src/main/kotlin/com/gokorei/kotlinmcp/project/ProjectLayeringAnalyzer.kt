package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import java.io.File

/**
 * Strategy component for package topology analysis and architectural layering advisories.
 */
class ProjectLayeringAnalyzer {

    fun analyzeProjectLayering(buildScriptContent: String, projectPath: String?): KotlinMcpResult {
        val root = projectPath?.let { File(it) }
        val packages = mutableSetOf<String>()
        var ktFileCount = 0

        if (root != null && root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    ktFileCount++
                    val pkgLine = file.useLines { lines -> lines.firstOrNull { it.trimStart().startsWith("package ") } }
                    if (pkgLine != null) {
                        val pkgName = pkgLine.substringAfter("package ").trim().substringBefore(" ")
                        if (pkgName.isNotBlank()) packages.add(pkgName)
                    }
                }
        }

        val hasDomain = packages.any { it.contains(".domain") || it.contains(".model") }
        val hasData = packages.any { it.contains(".data") || it.contains(".repository") }
        val hasUi = packages.any { it.contains(".ui") || it.contains(".view") || it.contains(".presentation") }

        val output = buildString {
            appendLine("# Project Package Layering Analysis")
            appendLine("Analyzed $ktFileCount Kotlin source file(s) across ${packages.size} package(s).")
            appendLine()
            appendLine("## Detected Packages")
            if (packages.isNotEmpty()) {
                packages.sorted().forEach { appendLine(" - `$it`") }
            } else {
                appendLine(" - (no explicit package declarations found)")
            }
            appendLine()
            appendLine("## Architectural Layer Health")
            appendLine("- Domain Layer: ${if (hasDomain) "✅ Detected" else "ℹ️ Missing explicit .domain / .model package"}")
            appendLine("- Data Layer: ${if (hasData) "✅ Detected" else "ℹ️ Missing explicit .data / .repository package"}")
            appendLine("- UI Layer: ${if (hasUi) "✅ Detected" else "ℹ️ Missing explicit .ui / .presentation package"}")
        }

        return KotlinMcpResult.Success(
            content = output,
            metadata = mapOf(
                "ktFileCount" to ktFileCount.toString(),
                "packageCount" to packages.size.toString()
            )
        )
    }
}
