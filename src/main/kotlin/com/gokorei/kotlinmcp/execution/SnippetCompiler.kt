package com.gokorei.kotlinmcp.execution

import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import io.github.oshai.kotlinlogging.KotlinLogging

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
object SnippetCompiler {

    private val logger = KotlinLogging.logger {}

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

    private val defaultImportsClasspath: List<String> by lazy {
        // TK-94DK6TD1: when launched as `java -jar <all.jar>` the JVM sets
        // java.class.path to a single flat jar whose name matches none of the
        // library substrings below, so the system list is empty. Fall back to
        // the library jars bundled as resources by the `dumpSnippetClasspath`
        // Gradle task (materialized to a temp dir so K2 sees real file paths).
        resolveDefaultImports(System.getProperty("java.class.path").orEmpty())
    }

    /**
     * Resolves the default classpath for snippet compilation/execution.
     *
     * Prefers the name-filtered system `java.class.path` (the `gradle test` /
     * `gradle run` layout). When that yields nothing — e.g. under
     * `java -jar <all.jar>`, where the JVM reports the single flat fat jar —
     * falls back to the library jars bundled as resources by the
     * `dumpSnippetClasspath` Gradle task.
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
                entry.contains("build/classes/kotlin/main") ||
                entry.contains("build/classes/kotlin/test")
            ))
    }

    @Volatile
    private var bundledSnippetClasspath: List<String>? = null

    /**
     * Materializes the library jars bundled as resources by `dumpSnippetClasspath`
     * into a temp dir and returns their on-disk paths (K2 needs real file paths).
     *
     * The result is cached for the process lifetime and the temp dir is deleted on
     * JVM shutdown. Returns an empty list (with a warning) if materialization is
     * impossible, leaving callers to compile without the bundled libraries.
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
                                // Drain stream to verify CRC checksum and decompression integrity
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

    internal fun resetBundledSnippetClasspathCache() {
        bundledSnippetClasspath = null
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
