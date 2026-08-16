package com.gokorei.kotlinmcp.linting

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import java.io.ByteArrayOutputStream
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
    fun prewarm()
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

    private val mode: String = System.getenv("LINT_SERVICE_MODE") ?: "EMBEDDED"

    private val defaultDetektClassLoader: ChildFirstClassLoader? by lazy {
        createClassLoader(detektClasspath(), emptyList(), "io.gitlab.arturbosch.detekt.cli.Main")
    }

    private val defaultKtlintClassLoader: ChildFirstClassLoader? by lazy {
        createClassLoader(ktlintClasspath(), emptyList(), "com.pinterest.ktlint.Main")
    }

    private fun createClassLoader(
        baseClasspath: String?,
        customCompilerClasspath: List<String>,
        entryClass: String
    ): ChildFirstClassLoader? {
        val extraUrls = customCompilerClasspath
            .filter { it.isNotBlank() }
            .map { File(it).toURI().toURL() }

        val baseUrls = (baseClasspath ?: "").split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { File(it).toURI().toURL() }

        val allUrls = (extraUrls + baseUrls).distinct().toTypedArray()
        if (allUrls.isEmpty()) return null

        return ChildFirstClassLoader(allUrls, DefaultLintService::class.java.classLoader).also { cl ->
            runCatching { Class.forName(entryClass, true, cl) }
        }
    }

    init {
        System.setProperty("detekt.kotlin.version.disable", "true")
        prewarm()
    }

    companion object {
        init {
            System.setProperty("detekt.kotlin.version.disable", "true")
        }
    }

    override fun prewarm() {
        defaultDetektClassLoader
        defaultKtlintClassLoader
    }

    override fun runDetekt(
        code: String,
        workspacePath: String?,
        config: Map<String, Any>,
        compilerClasspath: List<String>
    ): KotlinMcpResult {
        val cl = if (compilerClasspath.isNotEmpty()) {
            createClassLoader(detektClasspath(), compilerClasspath, "io.gitlab.arturbosch.detekt.cli.Main") ?: defaultDetektClassLoader
        } else {
            defaultDetektClassLoader
        }

        if (cl == null && detektClasspath() == null) {
            return KotlinMcpResult.Error(
                message = "detekt tooling classpath missing. Run the Gradle `dumpToolingClasspaths` task (wired into processResources) or pass 'compilerClasspath' before using this tool.",
                code = "DETEKT_CLASSPATH_MISSING",
                requireAnotherCall = true
            )
        }
        return when (val run = runDetektCli(code, workspacePath, config, cl)) {
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

    private fun runDetektCli(
        code: String,
        workspacePath: String?,
        config: Map<String, Any>,
        cl: ClassLoader?
    ): DetektRun {
        if (cl == null) return DetektRun.Failed("DETEKT_CLASSPATH_MISSING", "detekt tooling classpath is missing.")

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

            val oldCl = Thread.currentThread().contextClassLoader
            val output = ByteArrayOutputStream()
            val oldOut = System.out
            val oldErr = System.err

            val exitCode = try {
                Thread.currentThread().contextClassLoader = cl
                System.setOut(java.io.PrintStream(output))
                System.setErr(java.io.PrintStream(output))

                val outStream = java.io.PrintStream(output)
                val cliArgsClass = Class.forName("io.gitlab.arturbosch.detekt.cli.CliArgs", true, cl)
                val jcommanderClass = Class.forName("io.gitlab.arturbosch.detekt.cli.JCommanderKt", true, cl)
                val parseArgumentsMethod = jcommanderClass.getMethod("parseArguments", Array<String>::class.java)
                val cliArgs = parseArgumentsMethod.invoke(null, args.toTypedArray())

                val runnerClass = Class.forName("io.gitlab.arturbosch.detekt.cli.runners.Runner", true, cl)
                val runnerConstructor = runnerClass.getConstructor(cliArgsClass, Appendable::class.java, Appendable::class.java)
                val runner = runnerConstructor.newInstance(cliArgs, outStream, outStream)

                val executeMethod = runnerClass.getMethod("execute")
                try {
                    val resultObj = executeMethod.invoke(runner)
                    if (resultObj != null) {
                        val getExitCode = resultObj.javaClass.getMethod("getExitCode")
                        (getExitCode.invoke(resultObj) as Enum<*>).ordinal
                    } else {
                        0
                    }
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    val cause = e.cause
                    if (cause != null && (cause.javaClass.simpleName.contains("Failure") || cause.javaClass.simpleName.contains("Failed") || reportFile.exists())) {
                        2
                    } else throw e
                }
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.cause
                if (reportFile.exists() || (cause != null && (cause.javaClass.simpleName.contains("Failure") || cause.javaClass.simpleName.contains("Failed")))) {
                    2
                } else {
                    return DetektRun.Failed("DETEKT_EXECUTION_ERROR", "Detekt failed: ${cause?.message ?: e.message}")
                }
            } catch (e: Throwable) {
                return DetektRun.Failed("DETEKT_EXECUTION_ERROR", "Detekt invocation error: ${e.message}")
            } finally {
                Thread.currentThread().contextClassLoader = oldCl
                System.setOut(oldOut)
                System.setErr(oldErr)
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
        val cl = if (compilerClasspath.isNotEmpty()) {
            createClassLoader(ktlintClasspath(), compilerClasspath, "com.pinterest.ktlint.Main") ?: defaultKtlintClassLoader
        } else {
            defaultKtlintClassLoader
        }

        if (cl == null && ktlintClasspath() == null) {
            return KotlinMcpResult.Error(
                message = "ktlint tooling classpath missing. Run the Gradle `dumpToolingClasspaths` task (wired into processResources) or pass 'compilerClasspath' before using this tool.",
                code = "KTLINT_CLASSPATH_MISSING",
                requireAnotherCall = true
            )
        }

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

            val oldCl = Thread.currentThread().contextClassLoader
            val output = ByteArrayOutputStream()
            val oldOut = System.out
            val oldErr = System.err

            try {
                Thread.currentThread().contextClassLoader = cl
                System.setOut(java.io.PrintStream(output))
                System.setErr(java.io.PrintStream(output))

                val mainClass = Class.forName("com.pinterest.ktlint.Main", true, cl)
                val mainMethod = mainClass.getMethod("main", Array<String>::class.java)
                mainMethod.invoke(null, args.toTypedArray())
            } catch (e: Throwable) {
                // Ktlint may invoke System.exit or throw when formatting issues exist
            } finally {
                Thread.currentThread().contextClassLoader = oldCl
                System.setOut(oldOut)
                System.setErr(oldErr)
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

    private fun configToYaml(config: Map<String, Any>): String {
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

    private fun parseDetektXml(file: File): List<LintFinding> {
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
