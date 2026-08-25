package com.gokorei.kotlinmcp.linting

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Structured detekt finding for LLM consumption.
 */
data class LintFinding(
    val rule: String,
    val severity: String,
    val file: String,
    val line: Int,
    val column: Int,
    val message: String
)

/**
 * Service exposing the real detekt rule engine and ktlint formatting.
 */
interface LintService {
    fun prewarm(): Pair<String?, String?> = Pair(null, null)
    fun runDetekt(
        code: String,
        workspacePath: String? = null,
        config: Map<String, Any> = emptyMap(),
        compilerClasspath: List<String> = emptyList()
    ): KotlinMcpResult

    fun formatKtlint(
        code: String,
        apply: Boolean = true,
        compilerClasspath: List<String> = emptyList()
    ): KotlinMcpResult

    fun baselineRead(workspacePath: String): KotlinMcpResult
    fun baselineDump(workspacePath: String, findings: List<LintFinding>? = null): KotlinMcpResult

    /** Ingests and formats an Android Lint XML report (lint-results.xml). */
    fun parseAndroidLintReport(xmlContentOrPath: String, workspacePath: String? = null): KotlinMcpResult =
        AndroidLintParser().parseReport(xmlContentOrPath, workspacePath)
}

class ChildFirstClassLoader(
    urls: Array<java.net.URL>,
    parent: ClassLoader
) : java.net.URLClassLoader(urls, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            var c = findLoadedClass(name)
            if (c == null) {
                if (shouldPreferChild(name)) {
                    try {
                        c = findClass(name)
                    } catch (_: ClassNotFoundException) {
                    }
                }
                if (c == null) {
                    c = super.loadClass(name, resolve)
                }
            }
            if (resolve) {
                resolveClass(c)
            }
            return c
        }
    }

    fun shouldPreferChild(name: String): Boolean {
        return name.startsWith("io.gitlab.arturbosch.detekt") ||
               name.startsWith("com.pinterest.ktlint") ||
               name.startsWith("picocli") ||
               name.startsWith("kotlin.") ||
               name.startsWith("org.jetbrains.kotlin") ||
               name.startsWith("org.jetbrains.kotlinx") ||
               name.startsWith("com.beust.jcommander") ||
               name.startsWith("org.yaml.snakeyaml") ||
               name.startsWith("org.antlr") ||
               name.startsWith("com.charleskorn.kaml") ||
               name.startsWith("com.ec4j") ||
               name.startsWith("kotlinx.html") ||
               name.startsWith("org.checkerframework")
    }

}

private sealed interface DetektRun {
    data class Findings(val list: List<LintFinding>) : DetektRun
    data class Failed(val code: String, val message: String) : DetektRun
}

class DefaultLintService(
    private val resourceOverrides: Map<String, String?> = emptyMap()
) : LintService {

    private val mode: String = System.getenv("LINT_SERVICE_MODE") ?: "SUBPROCESS"

    init {
        prewarm()
    }

    companion object {
        init {
            System.setProperty("detekt.kotlin.version.disable", "true")
        }
    }

    override fun prewarm(): Pair<String?, String?> {
        return detektClasspath() to ktlintClasspath()
    }

    override fun runDetekt(
        code: String,
        workspacePath: String?,
        config: Map<String, Any>,
        compilerClasspath: List<String>
    ): KotlinMcpResult {
        val baseClasspath = detektClasspath()
        if (baseClasspath == null) {
            return KotlinMcpResult.Error(
                message = "detekt tooling classpath missing. Run the Gradle `dumpToolingClasspaths` task (wired into processResources) or pass 'compilerClasspath' before using this tool.",
                code = "DETEKT_CLASSPATH_MISSING",
                requireAnotherCall = true
            )
        }
        val classpathEntries = listOf(baseClasspath) + compilerClasspath.filter { it.isNotBlank() }
        return when (val run = runDetektSubprocess(code, workspacePath, config, classpathEntries)) {
            is DetektRun.Failed -> KotlinMcpResult.Error(message = run.message, code = run.code, requireAnotherCall = true)
            is DetektRun.Findings -> {
                val findings = run.list
                val content = buildString {
                    appendLine("# Detekt Findings (mode=$mode, ${findings.size} finding(s))")
                    findings.forEach { f ->
                        appendLine("- [${f.rule}] ${f.severity} ${f.file}:${f.line}:${f.column} ${f.message}")
                    }
                    if (findings.isEmpty()) {
                        appendLine("No detekt findings.")
                    }
                }
                KotlinMcpResult.Success(
                    content = content.trim(),
                    metadata = mapOf(
                        "mode" to mode,
                        "findingCount" to findings.size.toString()
                    )
                )
            }
        }
    }

    private fun detektClasspath(): String? =
        resourceText("detekt.classpath.txt")

    private fun ktlintClasspath(): String? =
        resourceText("ktlint.classpath.txt")

    private fun resourceText(name: String): String? =
        if (resourceOverrides.containsKey(name)) {
            resourceOverrides[name]
        } else {
            DefaultLintService::class.java.classLoader
                ?.getResourceAsStream(name)
                ?.bufferedReader()
                ?.use { it.readText().trim() }
        }

    data class ToolRun(val exitCode: Int, val tailOutput: String)

    /**
     * Executes an external CLI tool (detekt/ktlint) in its own JVM. Running
     * them in-process via a child-first classloader deadlocked the MCP transport
     * because their embedded compilers fight the server's kotlin-compiler
     * over the System streams and class-loading lock during a Stdio tool call.
     * A separate process keeps the server's own threads and System.out intact.
     */
    fun runJavaTool(
        mainClass: String,
        classpathEntries: List<String>,
        args: List<String>,
        dir: Path?,
        timeoutSeconds: Long
    ): ToolRun {
        val javaBin = runCatching {
            val javaHome = System.getProperty("java.home") ?: error("java.home not set")
            val candidate = File(javaHome, "bin/java")
            if (candidate.exists()) candidate.absolutePath else "java"
        }.getOrDefault("java")

        val cmd = buildList {
            add(javaBin)
            // One-shot CLI JVM: keep startup fast by using only C1 compilation
            // (no C2 profile-guided warmup, which these short-lived processes
            // never pay back) and cap heap so concurrent forks/subprocesses
            // never exhaust the host.
            add("-XX:TieredStopAtLevel=1")
            add("-Xmx1g")
            add("-cp")
            add(classpathEntries.joinToString(File.pathSeparator))
            add(mainClass)
            addAll(args)
        }
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        if (dir != null) {
            pb.directory(dir.toFile())
        }

        val process = try {
            pb.start()
        } catch (e: Throwable) {
            return ToolRun(-1, "could not start subprocess: ${e.message}")
        }

        val tail = StringBuilder()
        val drainer = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (tail.length < 4096) {
                            val keep = line.length.coerceAtMost(4096 - tail.length)
                            tail.append(line.substring(0, keep)).append('\n')
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        drainer.isDaemon = true
        drainer.start()

        return try {
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                ToolRun(-1, "timed out after ${timeoutSeconds}s")
            } else {
                ToolRun(process.exitValue(), tail.toString())
            }
        } catch (e: Throwable) {
            process.destroyForcibly()
            ToolRun(-1, "subprocess error: ${e.message}")
        } finally {
            drainer.interrupt()
        }
    }

    private fun runDetektSubprocess(
        code: String,
        workspacePath: String?,
        config: Map<String, Any>,
        classpathEntries: List<String>
    ): DetektRun {

        val tempDir = try {
            Files.createTempDirectory("detekt-run")
        } catch (e: Exception) {
            return DetektRun.Failed("IO_ERROR", "Failed to create temp directory: ${e.message}")
        }

        try {
            val reportXml = tempDir.resolve("detekt-report.xml").toAbsolutePath().toString()
            val reportFile = File(reportXml)

            val args = mutableListOf<String>()

            val inputDir = if (!workspacePath.isNullOrBlank() && File(workspacePath).isDirectory) {
                workspacePath
            } else {
                val snippetFile = tempDir.resolve("Snippet.kt")
                Files.writeString(snippetFile, code)
                snippetFile.toAbsolutePath().toString()
            }

            args.add("--input")
            args.add(inputDir)

            val wsConfigFile = if (!workspacePath.isNullOrBlank()) File(workspacePath, "detekt.yml") else null
            if (config.isNotEmpty()) {
                val configFile = tempDir.resolve("detekt.yml")
                val yamlText = configToYaml(config)
                Files.writeString(configFile, yamlText)
                args.add("--config")
                args.add(configFile.toAbsolutePath().toString())
            } else if (wsConfigFile != null && wsConfigFile.exists()) {
                args.add("--config")
                args.add(wsConfigFile.absolutePath)
            }

            args.add("--report")
            args.add("xml:$reportXml")

            val run = runJavaTool(
                mainClass = "io.gitlab.arturbosch.detekt.cli.Main",
                classpathEntries = classpathEntries,
                args = args,
                dir = tempDir,
                timeoutSeconds = 180L
            )
            if (run.exitCode < 0 || (run.exitCode != 0 && !reportFile.exists())) {
                return DetektRun.Failed("DETEKT_EXECUTION_ERROR", "detekt invocation failed (exit=${run.exitCode}): ${run.tailOutput.take(300)}")
            }

            val findings = if (reportFile.exists()) {
                parseDetektXml(reportFile)
            } else {
                emptyList()
            }

            return DetektRun.Findings(findings)
        } finally {
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    override fun formatKtlint(
        code: String,
        apply: Boolean,
        compilerClasspath: List<String>
    ): KotlinMcpResult {
        val baseClasspath = ktlintClasspath()
        if (baseClasspath == null) {
            return KotlinMcpResult.Error(
                message = "ktlint tooling classpath missing. Run the Gradle `dumpToolingClasspaths` task (wired into processResources) or pass 'compilerClasspath' before using this tool.",
                code = "KTLINT_CLASSPATH_MISSING",
                requireAnotherCall = true
            )
        }
        val classpathEntries = listOf(baseClasspath) + compilerClasspath.filter { it.isNotBlank() }

        val tempDir = try {
            Files.createTempDirectory("ktlint-run")
        } catch (e: Exception) {
            return KotlinMcpResult.Error(message = "Failed to create temp directory: ${e.message}", code = "IO_ERROR")
        }

        try {
            val snippetFile = tempDir.resolve("Snippet.kt")
            Files.writeString(snippetFile, code)

            val args = mutableListOf<String>()
if (apply) {
                    args.add("-F")
                }
                args.add(snippetFile.toAbsolutePath().toString())

                val run = runJavaTool(
                    mainClass = "com.pinterest.ktlint.Main",
                    classpathEntries = classpathEntries,
                    args = args,
                    dir = tempDir,
                    timeoutSeconds = 120L
                )
                if (run.exitCode < 0 || (run.exitCode != 0 && run.exitCode != 1)) {
                    return KotlinMcpResult.Error(
                        message = "ktlint invocation failed (exit=${run.exitCode}): ${run.tailOutput.take(300)}",
                        code = "KTLINT_EXECUTION_ERROR"
                    )
                }

            val formattedCode = try {
                Files.readString(snippetFile)
            } catch (e: Exception) {
                code
            }

            val content = buildString {
                appendLine("# Ktlint Format Result (apply=$apply)")
                if (apply && formattedCode != code) {
                    appendLine("Formatted code applied successfully.")
                    appendLine()
                    appendLine("```kotlin")
                    appendLine(formattedCode)
                    appendLine("```")
                } else if (apply) {
                    appendLine("Code is already correctly formatted according to ktlint rules.")
                } else {
                    appendLine("ktlint check complete.")
                }
            }

            return KotlinMcpResult.Success(
                content = content.trim(),
                metadata = mapOf("formatted" to (formattedCode != code).toString(), "apply" to apply.toString())
            )
        } finally {
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    override fun baselineRead(workspacePath: String): KotlinMcpResult {
        val root = File(workspacePath)
        val baselineFile = File(root, "detekt-baseline.xml")
        if (!baselineFile.exists()) {
            return KotlinMcpResult.Error(
                message = "No detekt baseline found at ${baselineFile.path}.",
                code = "NOT_FOUND"
            )
        }
        val text = baselineFile.readText()
        return KotlinMcpResult.Success(
            content = "# Detekt Baseline Inventory\n```xml\n$text\n```",
            metadata = mapOf("workspace" to workspacePath, "file" to baselineFile.name)
        )
    }

    override fun baselineDump(workspacePath: String, findings: List<LintFinding>?): KotlinMcpResult {
        val root = File(workspacePath)
        if (!root.isDirectory) {
            return KotlinMcpResult.Error(
                message = "workspacePath '$workspacePath' must be an existing directory.",
                code = "INVALID_ARGUMENTS"
            )
        }

        val targetFile = File(root, "detekt-baseline.xml")

        val actualFindings = if (findings != null) {
            findings
        } else {
            val res = runDetekt("", workspacePath = workspacePath)
            if (res is KotlinMcpResult.Success) {
                emptyList()
            } else {
                emptyList()
            }
        }

        val xmlContent = buildString {
            appendLine("<?xml version=\"1.0\" ?>")
            appendLine("<SmellBaseline>")
            appendLine("  <ManuallySuppressedIssues></ManuallySuppressedIssues>")
            appendLine("  <CurrentIssues>")
            actualFindings.forEach { f ->
                appendLine("    <ID>${f.rule}:${f.file}\$${f.message.take(30).replace(" ", "")}</ID>")
            }
            appendLine("  </CurrentIssues>")
            appendLine("</SmellBaseline>")
        }

        targetFile.writeText(xmlContent)

        return KotlinMcpResult.Success(
            content = "Detekt baseline written to ${targetFile.path} with ${actualFindings.size} suppressed issue(s).",
            metadata = mapOf("file" to targetFile.path, "issueCount" to actualFindings.size.toString())
        )
    }

    fun configToYaml(config: Map<String, Any>): String {
        val sb = StringBuilder()
        fun writeMap(map: Map<*, *>, indent: Int) {
            val prefix = " ".repeat(indent)
            map.forEach { (k, v) ->
                when (v) {
                    is Map<*, *> -> {
                        sb.appendLine("$prefix$k:")
                        writeMap(v, indent + 2)
                    }
                    is List<*> -> {
                        sb.appendLine("$prefix$k:")
                        v.forEach { item -> sb.appendLine("$prefix  - $item") }
                    }
                    else -> {
                        val s = v.toString().lowercase()
                        if (s == "off" || s == "false" || s == "disabled") {
                            sb.appendLine("$prefix$k:")
                            sb.appendLine("$prefix  active: false")
                        } else if (s == "on" || s == "true" || s == "enabled") {
                            sb.appendLine("$prefix$k:")
                            sb.appendLine("$prefix  active: true")
                        } else {
                            sb.appendLine("$prefix$k: $v")
                        }
                    }
                }
            }
        }
        writeMap(config, 0)
        return sb.toString()
    }

    fun parseDetektXml(file: File): List<LintFinding> {
        val list = mutableListOf<LintFinding>()
        try {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val fileNodes = doc.getElementsByTagName("file")
            for (i in 0 until fileNodes.length) {
                val fileElem = fileNodes.item(i) as org.w3c.dom.Element
                val filePath = fileElem.getAttribute("name")
                val errorNodes = fileElem.getElementsByTagName("error")
                for (j in 0 until errorNodes.length) {
                    val errElem = errorNodes.item(j) as org.w3c.dom.Element
                    list.add(
                        LintFinding(
                            rule = errElem.getAttribute("source").substringAfterLast('.'),
                            severity = errElem.getAttribute("severity"),
                            file = filePath,
                            line = errElem.getAttribute("line").toIntOrNull() ?: 1,
                            column = errElem.getAttribute("column").toIntOrNull() ?: 1,
                            message = errElem.getAttribute("message")
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }
}
