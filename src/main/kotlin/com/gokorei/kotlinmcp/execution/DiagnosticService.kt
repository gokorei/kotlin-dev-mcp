package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.shared.ToonUtils

enum class DiagnosticAction {
    CHECK_SNIPPET,
    RUN_PROJECT_LAYOUT
}

/**
 * Service interface for running embedded compiler diagnostics and project
 * layout inventory on Kotlin snippets and projects.
 */
interface DiagnosticService {
    fun execute(
        action: DiagnosticAction,
        code: String,
        projectPath: String? = null,
        classpath: List<String> = emptyList()
    ): KotlinMcpResult
}

class DefaultDiagnosticService : DiagnosticService {

    override fun execute(action: DiagnosticAction, code: String, projectPath: String?, classpath: List<String>): KotlinMcpResult {
        return when (action) {
            DiagnosticAction.CHECK_SNIPPET -> checkSnippetEmbedded(code, classpath)
            DiagnosticAction.RUN_PROJECT_LAYOUT -> runProjectLayout(projectPath ?: ".")
        }
    }

    fun checkSnippetEmbedded(code: String, classpath: List<String> = emptyList(), projectPath: String? = null): KotlinMcpResult {
        val result = SnippetCompiler.compile(code, classpath, projectPath)
        val mcp = buildCheckResult(result)
        SnippetCompiler.cleanup(result)
        return mcp
    }

    private fun buildCheckResult(result: CompileResult): KotlinMcpResult {
        return when (result) {
            is CompileResult.Failed -> KotlinMcpResult.Error(
                message = result.message,
                code = result.code,
                requireAnotherCall = true
            )
            is CompileResult.Compiled -> {
                val errors = result.diagnostics.filter { it.severity == "error" }
                val warnings = result.diagnostics.filter { it.severity == "warning" }

                if (errors.isNotEmpty()) {
                    val renderedToon = ToonUtils.encodeToonTable(
                        headerName = "diagnostics",
                        columns = listOf("line", "col", "msg"),
                        items = errors
                    ) { diag -> listOf(diag.line ?: "?", diag.column ?: "?", diag.message) }
                    val hasUnresolved = errors.any { it.message.contains("Unresolved reference", ignoreCase = true) || it.message.contains("UNRESOLVED", ignoreCase = true) }
                    val hint = if (hasUnresolved) {
                        "\n\nℹ️ Hint: Unresolved symbol references detected. If referencing project-internal types or external dependencies, supply 'projectPath' or 'classpath'."
                    } else ""
                    KotlinMcpResult.Error(
                        message = "Compilation failed with ${errors.size} error(s):\n$renderedToon$hint",
                        code = "COMPILER_ERROR",
                        details = mapOf(
                            "diagnostics" to errors.joinToString(" | ") {
                                listOfNotNull(it.line?.toString(), it.column?.toString()).joinToString(":") + " " + it.message
                            }
                        ),
                        requireAnotherCall = true
                    )
                } else {
                    val warningText = if (warnings.isNotEmpty()) {
                        "\nWarnings (${warnings.size}):\n" + warnings.joinToString("\n") {
                            val loc = listOfNotNull(it.line, it.column).joinToString(":")
                            " - $loc ${it.message}"
                        }
                    } else {
                        ""
                    }
                    KotlinMcpResult.Success(
                        content = "✅ Compilation succeeded.$warningText".trim(),
                        metadata = mapOf(
                            "mode" to "embedded",
                            "diagnosticCount" to result.diagnostics.size.toString(),
                            "errorCount" to errors.size.toString(),
                            "warningCount" to warnings.size.toString()
                        )
                    )
                }
            }
        }
    }

    private fun runProjectLayout(projectPath: String): KotlinMcpResult {
        val root = try {
            java.io.File(projectPath)
        } catch (e: Exception) {
            return KotlinMcpResult.Error(
                message = "Invalid project path '$projectPath': ${e.message}",
                code = "INVALID_PROJECT_PATH"
            )
        }

        if (!root.exists()) {
            return KotlinMcpResult.Error(
                message = "Project path does not exist: '$projectPath'",
                code = "PROJECT_NOT_FOUND"
            )
        }

        val buildFiles = root.walkTopDown().maxDepth(4).onEnter { dir ->
            val name = dir.name
            name != "build" && name != ".gradle" && name != ".git" && name != "out" && name != "node_modules"
        }.filter { f -> f.isFile && (f.name == "build.gradle.kts" || f.name == "build.gradle" || f.name == "settings.gradle.kts") }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()

        val srcDirs = mutableListOf<String>()
        root.walkTopDown().maxDepth(5).onEnter { dir ->
            val name = dir.name
            name != "build" && name != ".gradle" && name != ".git" && name != "out" && name != "node_modules"
        }.forEach { file ->
            if (file.isDirectory) {
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (rel == "src/main/kotlin" || rel == "src/main/java" ||
                    rel.endsWith("/src/main/kotlin") || rel.endsWith("/src/main/java") ||
                    rel.endsWith("/src/commonMain/kotlin") || rel.endsWith("/src/test/kotlin") ||
                    rel.endsWith("/src/androidTest/java") || rel.endsWith("/src/androidTest/kotlin")) {
                    srcDirs.add(rel)
                }
            }
        }

        val content = buildString {
            appendLine("# Project Layout Inventory [$root]")
            appendLine("- Build files: ${if (buildFiles.isEmpty()) "none found" else buildFiles.joinToString(", ")}")
            appendLine("- Source dirs: ${if (srcDirs.isEmpty()) "none detected" else srcDirs.joinToString(", ")}")
            appendLine("- Note: this is a layout inventory, not a compiler diagnostics pass. Run `kotlin_lint(detekt)` or `kotlin_run(gradle_task)` for real findings.")
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("projectPath" to projectPath, "mode" to "fs")
        )
    }
}
