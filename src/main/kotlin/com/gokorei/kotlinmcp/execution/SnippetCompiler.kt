package com.gokorei.kotlinmcp.execution

import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * A single parsed diagnostic produced by the embedded K2 compiler, with the
 * `Snippet.kt:LINE:COL` location already extracted.
 */
data class CompilerDiagnostic(
    val severity: String,
    val line: Int?,
    val column: Int?,
    val message: String
)

/** Outcome of an in-process snippet compilation. */
sealed class CompileResult {
    data class Compiled(
        val outDir: Path,
        val diagnostics: List<CompilerDiagnostic>,
        val tempRoot: Path
    ) : CompileResult()

    data class Failed(val message: String, val code: String) : CompileResult()
}

/**
 * Shared wrapper around the embedded Kotlin compiler (K2). Writes the snippet
 * to a temp `Snippet.kt` and compiles it in-process against the server's own
 * classpath, optionally extended with an extra classpath (a project's compiled
 * classes and dependency jars).
 */
internal object SnippetCompiler {

    const val SOURCE_FILE_NAME = "Snippet.kt"
    const val MAIN_CLASS = "SnippetKt"

    fun detectProjectClasspath(projectPath: String?): List<String> {
        if (projectPath.isNullOrBlank()) return emptyList()
        val root = File(projectPath)
        if (!root.exists() || !root.isDirectory) return emptyList()

        val found = mutableListOf<String>()

        root.walkTopDown().maxDepth(25).onEnter { dir ->
            val name = dir.name
            name != ".gradle" && name != ".git" && name != "out" && name != "node_modules" && name != ".idea"
        }.forEach { file ->
            if (file.isDirectory) {
                val name = file.invariantSeparatorsPath
                if (name.endsWith("build/classes/kotlin/main") ||
                    name.endsWith("build/classes/java/main") ||
                    name.endsWith("build/classes/kotlin/commonMain") ||
                    name.endsWith("build/classes/kotlin/jvm/main") ||
                    name.contains("build/generated/ksp/") ||
                    name.contains("build/generated/source/kapt/") ||
                    name.contains("build/generated/sqldelight/") ||
                    name.contains("build/intermediates/javac/") ||
                    name.contains("build/intermediates/compile_app_classes_jar/")) {
                    found.add(file.absolutePath)
                } else if (file.name == "libs" && file.parentFile?.name == "build") {
                    file.listFiles { _, fileName -> fileName.endsWith(".jar") }
                        ?.forEach { found.add(it.absolutePath) }
                }
            }
        }

        return found.distinct()
    }

    private val defaultImportsClasspath: List<String> = System.getProperty("java.class.path")
        ?.split(File.pathSeparator)
        .orEmpty()
        .filter { it.isNotBlank() }
        .filter { entry ->
            val name = entry.substringAfterLast('/').lowercase()
            name.contains("kotlin-stdlib") ||
                name.contains("kotlinx-coroutines") ||
                name.contains("kotlinx-serialization") ||
                name.contains("kotlinx-datetime") ||
                name.contains("arrow-core") ||
                name.contains("mockk") ||
                name.contains("turbine") ||
                name.contains("ktor")
        }

    val runtimeExecutionClasspath: List<String>
        get() = defaultImportsClasspath

    fun compile(
        code: String,
        extraClasspath: List<String> = emptyList(),
        projectPath: String? = null
    ): CompileResult {
        val tempDir: Path
        val sourceFile: Path
        val outDir: Path
        try {
            tempDir = Files.createTempDirectory("kmcp-compile")
            sourceFile = tempDir.resolve(SOURCE_FILE_NAME)
            outDir = tempDir.resolve("out")
            Files.createDirectories(outDir)
            Files.writeString(sourceFile, code)
        } catch (e: Exception) {
            return CompileResult.Failed("Failed to prepare snippet for compilation: ${e.message}", "IO_ERROR")
        }

        val autoClasspath = detectProjectClasspath(projectPath)
        val effectiveClasspath = (extraClasspath + defaultImportsClasspath + autoClasspath)
            .distinct()
            .filter { it.isNotBlank() }
            .joinToString(File.pathSeparator)

        val args = org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments().apply {
            destination = outDir.toString()
            classpath = effectiveClasspath
            freeArgs = listOf(sourceFile.toString())
            jvmTarget = resolveTargetJvmVersion()
        }
        return try {
            val collector = CapturingMessageCollector()
            val compiler = K2JVMCompiler()
            compiler.exec(collector, org.jetbrains.kotlin.config.Services.EMPTY, args)
            val diagnostics = collector.reports.mapNotNull { report ->
                val loc = report.location
                CompilerDiagnostic(
                    severity = report.severity,
                    line = loc?.line,
                    column = loc?.column,
                    message = report.message
                )
            }
            CompileResult.Compiled(outDir, diagnostics, tempDir)
        } catch (e: Throwable) {
            tempDir.toFile().deleteRecursively()
            CompileResult.Failed("Embedded compiler failed to run: ${e.message}", "COMPILER_INVOCATION_ERROR")
        }
    }

    private class CapturingMessageCollector : org.jetbrains.kotlin.cli.common.messages.MessageCollector {
        data class Report(
            val severity: String,
            val message: String,
            val location: org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation?
        )

        val reports = mutableListOf<Report>()

        override fun clear() = reports.clear()

        override fun report(
            severity: org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity,
            message: String,
            location: org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation?
        ) {
            if (severity.isError) {
                reports.add(Report("error", message, location))
            } else if (severity.isWarning) {
                reports.add(Report("warning", message, location))
            }
        }

        override fun hasErrors(): Boolean = reports.any { it.severity == "error" }
    }

    fun cleanup(result: CompileResult) {
        if (result is CompileResult.Compiled) {
            runCatching { result.tempRoot.toFile().deleteRecursively() }
        }
    }

    private fun resolveTargetJvmVersion(): String {
        val javaVer = System.getProperty("java.specification.version") ?: "21"
        val major = javaVer.removePrefix("1.").toIntOrNull() ?: 21
        return if (major in 8..21) major.toString() else "21"
    }
}
