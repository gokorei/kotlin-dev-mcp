@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
package com.gokorei.kotlinmcp.execution

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.ProjectId
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * A single parsed diagnostic produced by the compiler, with the
 * `Snippet.kt:LINE:COL` location already extracted.
 *
 * @property severity Diagnostic severity string ("error", "warning", "info").
 * @property line 1-based source line number, if available.
 * @property column 1-based source column number, if available.
 * @property message The human-readable diagnostic message.
 */
data class CompilerDiagnostic(
    val severity: String,
    val line: Int?,
    val column: Int?,
    val message: String
)

/** Outcome of an in-process snippet compilation. */
sealed class CompileResult {
    /**
     * Successful compilation or compilation with structured diagnostics.
     *
     * @property outDir Directory containing compiled `.class` bytecode files.
     * @property diagnostics List of diagnostics reported during compilation.
     * @property tempRoot Temporary directory backing the compilation workspace.
     */
    data class Compiled(
        val outDir: Path,
        val diagnostics: List<CompilerDiagnostic>,
        val tempRoot: Path
    ) : CompileResult()

    /**
     * Catastrophic compiler invocation failure.
     *
     * @property message Error explanation.
     * @property code Machine-readable error code.
     */
    data class Failed(val message: String, val code: String) : CompileResult()
}

/**
 * Shared wrapper around the Kotlin Build Tools API (BTA) compiler. Writes the snippet
 * to a temp `Snippet.kt` and compiles it in-process against the server's own
 * classpath, optionally extended with an extra classpath (a project's compiled
 * classes and dependency jars).
 */
object SnippetCompiler {

    private val logger = KotlinLogging.logger {}

    const val SOURCE_FILE_NAME = "Snippet.kt"
    const val MAIN_CLASS = "SnippetKt"

    var toolchainManager: BuildToolsToolchainManager = DefaultBuildToolsToolchainManager.instance

    /**
     * Scans a workspace project directory to detect compiled class outputs and generated sources.
     *
     * @param projectPath The root directory path of the project.
     * @return List of discovered classpath entries on disk.
     */
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

    private val defaultImportsClasspath: List<String> by lazy {
        resolveDefaultImports(System.getProperty("java.class.path").orEmpty())
    }

    /**
     * Resolves the default classpath for snippet compilation/execution.
     *
     * @param javaClassPath System classpath string separated by path separators.
     * @return List of verified classpath JAR and directory paths.
     */
    fun resolveDefaultImports(javaClassPath: String): List<String> {
        val fromSystem = javaClassPath
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filter { entry -> isSnippetLibraryEntry(entry) }
        return if (fromSystem.isNotEmpty()) fromSystem else materializeBundledSnippetClasspath()
    }

    private fun isSnippetLibraryEntry(entry: String): Boolean {
        val name = entry.substringAfterLast('/').lowercase()
        val allowInternals = java.lang.Boolean.getBoolean("kmcp.include_internal_classpath")
        return name.contains("kotlin-stdlib") ||
            name.contains("kotlinx-coroutines") ||
            name.contains("kotlinx-serialization") ||
            name.contains("kotlinx-datetime") ||
            name.contains("arrow-core") ||
            name.contains("mockk") ||
            name.contains("turbine") ||
            name.contains("ktor") ||
            name.contains("kotlin-logging") ||
            name.contains("slf4j") ||
            (allowInternals && (
                name.contains("kotlin-compiler") ||
                name.contains("kotlin-sdk") ||
                name.contains("modelcontextprotocol") ||
                name.contains("kotlin-build-tools") ||
                entry.contains("build/classes/kotlin/main") ||
                entry.contains("build/classes/kotlin/test")
            ))
    }

    @Volatile
    private var bundledSnippetClasspath: List<String>? = null

    /**
     * Materializes the library jars bundled as resources by `dumpSnippetClasspath`
     * into a temp dir and returns their on-disk paths.
     *
     * @param classLoader The [ClassLoader] from which to extract bundled jar resources.
     * @return List of extracted JAR file paths on disk.
     */
    internal fun materializeBundledSnippetClasspath(
        classLoader: ClassLoader? = SnippetCompiler::class.java.classLoader
    ): List<String> {
        bundledSnippetClasspath?.let { return it }
        synchronized(this) {
            bundledSnippetClasspath?.let { return it }
            val loader = classLoader ?: return emptyList()
            val manifest = loader.getResourceAsStream("snippet-classpath/snippet.classpath.txt") ?: return emptyList()
            val dir = try {
                Files.createTempDirectory("kmcp-snippet-classpath")
            } catch (e: Exception) {
                logger.warn(e) { "Could not create temp dir for bundled snippet library classpath; compiling without it" }
                return emptyList()
            }
            val names = manifest.use { it.bufferedReader().lineSequence().map { l -> l.trim() }.filter { n -> n.isNotBlank() }.toList() }
            val resolved = names.mapNotNull { name ->
                try {
                    val input = loader.getResourceAsStream("snippet-classpath/$name") ?: return@mapNotNull null
                    val target = dir.resolve(name.substringAfterLast('/'))
                    input.use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                    val targetFile = target.toFile()
                    if (isValidZipFile(targetFile)) {
                        target.toAbsolutePath().toString()
                    } else {
                        logger.warn { "Bundled snippet JAR '$name' failed zip validation; skipping" }
                        targetFile.delete()
                        null
                    }
                } catch (e: Throwable) {
                    logger.warn(e) { "Failed to extract bundled snippet JAR '$name'; skipping" }
                    null
                }
            }
            if (resolved.isEmpty()) {
                dir.toFile().deleteRecursively()
                return emptyList()
            }
            Runtime.getRuntime().addShutdownHook(
                Thread { dir.toFile().deleteRecursively() }
            )
            bundledSnippetClasspath = resolved
            return resolved
        }
    }

    private fun isValidZipFile(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                val entries = zip.entries()
                val buffer = ByteArray(8192)
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        zip.getInputStream(entry).use { stream ->
                            while (stream.read(buffer) != -1) {
                                // Drain stream to verify CRC checksum
                            }
                        }
                    }
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Resets the cached bundled snippet classpath.
     */
    internal fun resetBundledSnippetClasspathCache() {
        bundledSnippetClasspath = null
    }

    /**
     * The runtime execution classpath containing default snippet standard libraries.
     */
    val runtimeExecutionClasspath: List<String>
        get() = defaultImportsClasspath

    /**
     * Compiles Kotlin source code in-process using the Kotlin Build Tools API.
     *
     * @param code Kotlin source snippet.
     * @param extraClasspath Additional classpath entries to include during compilation.
     * @param projectPath Optional project root path for automatic workspace class discovery.
     * @return [CompileResult.Compiled] on successful compilation invocation, or [CompileResult.Failed] on crash.
     */
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

        return try {
            val service = toolchainManager.getCompilationService()
            val strategyConfig = service.makeCompilerExecutionStrategyConfiguration().useInProcessStrategy()
            val jvmConfig = service.makeJvmCompilationConfiguration()
            val collector = BtaDiagnosticCollector()
            jvmConfig.useLogger(collector)

            val compilerArgs = listOf(
                "-d", outDir.toString(),
                "-classpath", effectiveClasspath,
                "-jvm-target", resolveTargetJvmVersion()
            )

            val projectId = ProjectId.ProjectUUID(UUID.randomUUID())
            val compilationResult = try {
                service.compileJvm(
                    projectId,
                    strategyConfig,
                    jvmConfig,
                    listOf(sourceFile.toFile()),
                    compilerArgs
                )
            } finally {
                runCatching { service.finishProjectCompilation(projectId) }
            }

            val diagnostics = collector.diagnostics
            if (compilationResult != CompilationResult.COMPILATION_SUCCESS && diagnostics.none { it.severity == "error" }) {
                // If compiler returned non-success but no errors were recorded, synthesize a diagnostic
                val fallbackDiagnostics = diagnostics + CompilerDiagnostic(
                    severity = "error",
                    line = null,
                    column = null,
                    message = "Compilation failed with result: $compilationResult"
                )
                CompileResult.Compiled(outDir, fallbackDiagnostics, tempDir)
            } else {
                CompileResult.Compiled(outDir, diagnostics, tempDir)
            }
        } catch (e: Throwable) {
            tempDir.toFile().deleteRecursively()
            CompileResult.Failed("Embedded compiler failed to run: ${e.message}", "COMPILER_INVOCATION_ERROR")
        }
    }

    private class BtaDiagnosticCollector : KotlinLogger {
        val diagnostics = mutableListOf<CompilerDiagnostic>()

        override val isDebugEnabled: Boolean = false

        override fun error(msg: String, throwable: Throwable?) {
            val parsed = parseDiagnosticMessage("error", msg)
            diagnostics.add(parsed)
        }

        override fun warn(msg: String, throwable: Throwable?) {
            val parsed = parseDiagnosticMessage("warning", msg)
            diagnostics.add(parsed)
        }

        override fun info(msg: String) {}
        override fun debug(msg: String) {}
        override fun lifecycle(msg: String) {}

        private fun parseDiagnosticMessage(severity: String, rawMsg: String): CompilerDiagnostic {
            var text = rawMsg.trim()
            if (text.startsWith("e: ") || text.startsWith("w: ") || text.startsWith("i: ")) {
                text = text.substring(3).trim()
            }

            var line: Int? = null
            var column: Int? = null
            var cleanMessage = text

            if (text.contains(SOURCE_FILE_NAME)) {
                val afterFile = text.substringAfter(SOURCE_FILE_NAME)
                if (afterFile.startsWith(": (") || afterFile.startsWith(" (")) {
                    val coordPart = afterFile.substringAfter("(").substringBefore(")")
                    val coords = coordPart.split(",")
                    if (coords.size == 2) {
                        line = coords[0].trim().toIntOrNull()
                        column = coords[1].trim().toIntOrNull()
                        cleanMessage = afterFile.substringAfter("):").trim()
                            .removePrefix("error:").removePrefix("warning:").trim()
                    }
                } else if (afterFile.startsWith(":")) {
                    val remainder = afterFile.removePrefix(":")
                    val firstColon = remainder.indexOf(':')
                    if (firstColon != -1) {
                        val lineStr = remainder.substring(0, firstColon).trim()
                        val parsedLine = lineStr.toIntOrNull()
                        if (parsedLine != null) {
                            line = parsedLine
                            val afterLine = remainder.substring(firstColon + 1)
                            val secondColon = afterLine.indexOf(':')
                            if (secondColon != -1) {
                                val colStr = afterLine.substring(0, secondColon).trim()
                                val parsedCol = colStr.takeWhile { it.isDigit() }.toIntOrNull()
                                if (parsedCol != null) {
                                    column = parsedCol
                                    cleanMessage = afterLine.substring(secondColon + 1).trim()
                                        .removePrefix("error:").removePrefix("warning:").trim()
                                } else {
                                    cleanMessage = afterLine.trim().removePrefix("error:").removePrefix("warning:").trim()
                                }
                            } else {
                                val colDigits = afterLine.trim().takeWhile { it.isDigit() }
                                column = colDigits.toIntOrNull()
                                cleanMessage = afterLine.trim().drop(colDigits.length).trim()
                                    .removePrefix("error:").removePrefix("warning:").trim()
                            }
                        }
                    }
                }
            }

            if (cleanMessage.isBlank()) {
                cleanMessage = rawMsg
            }

            return CompilerDiagnostic(
                severity = severity,
                line = line,
                column = column,
                message = cleanMessage
            )
        }
    }

    /**
     * Cleans up the temporary directory backing a [CompileResult.Compiled] instance.
     *
     * @param result The compilation result whose resources should be deleted.
     */
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
