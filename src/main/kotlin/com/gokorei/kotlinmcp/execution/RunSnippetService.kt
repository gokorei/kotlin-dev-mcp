package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.shared.LogTruncator
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Compiles and executes a Kotlin snippet with a top-level `fun main()`,
 * returning combined stdout/stderr plus the exit code. This is the runtime
 * analog of a playground/verify step: the LLM can confirm behaviour instead of
 * only relying on static diagnostics.
 */
interface RunSnippetService {
    /**
     * Executes the given Kotlin snippet code.
     *
     * @param code The Kotlin source code to compile and execute.
     * @param timeoutMillis Execution timeout in milliseconds.
     * @param classpath Optional extra classpath entries.
     * @param runner Execution runner engine ("host_jvm" or "in_process").
     * @param jvmArgs Extra JVM arguments for execution.
     * @param javaPath Optional explicit path to java executable.
     * @param projectPath Optional project directory path.
     * @return [KotlinMcpResult] containing runtime output or execution error details.
     */
    fun execute(
        code: String,
        timeoutMillis: Long,
        classpath: List<String> = emptyList(),
        runner: String = "host_jvm",
        jvmArgs: List<String> = emptyList(),
        javaPath: String? = null,
        projectPath: String? = null
    ): KotlinMcpResult

    /**
     * Parses JUnit XML test reports from a project build directory.
     *
     * @param projectPath Root path of the project.
     * @return [KotlinMcpResult] summarizing test results.
     */
    fun parseTestReport(projectPath: String): KotlinMcpResult
}

/**
 * Default implementation of [RunSnippetService] executing snippets via host JVM process or in-process.
 *
 * @property javaResolver Resolver used to locate the system Java executable.
 * @property fastSnippetRunner Fast runner for in-process snippet execution.
 */
class DefaultRunSnippetService(
    private val javaResolver: JavaResolver = DefaultJavaResolver(),
    private val fastSnippetRunner: FastSnippetRunner = DefaultFastSnippetRunner()
) : RunSnippetService {

    /**
     * Compiles and executes the Kotlin snippet.
     */
    override fun execute(
        code: String,
        timeoutMillis: Long,
        classpath: List<String>,
        runner: String,
        jvmArgs: List<String>,
        javaPath: String?,
        projectPath: String?
    ): KotlinMcpResult {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            return KotlinMcpResult.Error(
                message = "Empty snippet — provide Kotlin source with a top-level `fun main()` to run.",
                code = "EMPTY_SNIPPET"
            )
        }
        val executableCode = prepareExecutableCode(trimmed)
        return when (val compiled = SnippetCompiler.compile(executableCode, classpath, projectPath)) {
            is CompileResult.Failed -> KotlinMcpResult.Error(
                message = compiled.message,
                code = compiled.code,
                requireAnotherCall = true
            )
            is CompileResult.Compiled -> {
                val outcome = runFromCompiled(compiled, executableCode, timeoutMillis, classpath, runner, jvmArgs, javaPath, projectPath)
                SnippetCompiler.cleanup(compiled)
                outcome
            }
        }
    }

    /**
     * Synthesizes a top-level `fun main()` wrapper for scratchpad expressions while preserving
     * package and import directives via K2 PSI traversal.
     *
     * @param code Input Kotlin source code.
     * @return Executable Kotlin code with a valid `fun main()` entry point.
     */
    internal fun prepareExecutableCode(code: String): String {
        if (hasMainFunction(code)) return code
        val file = K2SnippetFrontend.parsePsi(code) ?: return "fun main() {\n$code\n}"
        val pkgDirective = file.packageDirective
        val pkg = pkgDirective?.text?.trim().orEmpty()
        val imports = file.importDirectives.map { it.text.trim() }

        val directives = listOfNotNull(pkgDirective?.takeIf { it.text.isNotBlank() }) + file.importDirectives
        val body = if (directives.isNotEmpty()) {
            val maxOffset = directives.maxOf { it.textRange.endOffset }
            code.substring(maxOffset).trim()
        } else {
            code.trim()
        }

        return buildString {
            if (pkg.isNotBlank()) {
                appendLine(pkg)
                appendLine()
            }
            if (imports.isNotEmpty()) {
                imports.forEach { appendLine(it) }
                appendLine()
            }
            appendLine("fun main() {")
            appendLine(body)
            appendLine("}")
        }
    }

    private fun resolveMainClassName(code: String): String {
        val file = K2SnippetFrontend.parsePsi(code) ?: return SnippetCompiler.MAIN_CLASS
        val pkg = file.packageFqName.asString()

        var fileJvmName: String? = null
        for (annotationEntry in file.fileAnnotationList?.annotationEntries.orEmpty()) {
            val shortName = annotationEntry.shortName?.asString()
            if (shortName == "JvmName") {
                val valueArgument = annotationEntry.valueArguments.firstOrNull()?.getArgumentExpression()
                if (valueArgument is org.jetbrains.kotlin.psi.KtStringTemplateExpression) {
                    val entries = valueArgument.entries
                    if (entries.size == 1 && entries[0] is org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry) {
                        fileJvmName = entries[0].text
                    }
                }
            }
        }

        var objectMainName: String? = null
        file.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: org.jetbrains.kotlin.psi.KtNamedFunction) {
                if (function.name == "main" && isExecutableMain(function)) {
                    val isJvmStatic = function.annotationEntries.any { it.shortName?.asString() == "JvmStatic" }
                    val parent = function.parent?.parent
                    if (isJvmStatic && parent is org.jetbrains.kotlin.psi.KtObjectDeclaration) {
                        parent.name?.let { objectMainName = it }
                    }
                }
                super.visitNamedFunction(function)
            }
        })

        val simpleClassName = objectMainName ?: fileJvmName ?: SnippetCompiler.MAIN_CLASS
        return if (pkg.isNotBlank()) {
            "$pkg.$simpleClassName"
        } else {
            simpleClassName
        }
    }

    private fun runFromCompiled(
        compiled: CompileResult.Compiled,
        trimmed: String,
        timeoutMillis: Long,
        extraClasspath: List<String>,
        runner: String,
        jvmArgs: List<String>,
        javaPath: String?,
        projectPath: String?
    ): KotlinMcpResult {
        val errors = compiled.diagnostics.filter { it.severity == "error" }
        if (errors.isNotEmpty()) {
            val rendered = errors.joinToString("\n") {
                val loc = listOfNotNull(it.line, it.column).joinToString(":")
                " - $loc ${it.message}".trim()
            }
            return KotlinMcpResult.Error(
                message = "Compilation failed with ${errors.size} error(s):\n$rendered",
                code = "COMPILER_ERROR",
                details = mapOf(
                    "diagnostics" to errors.joinToString(" | ") {
                        listOfNotNull(it.line?.toString(), it.column?.toString()).joinToString(":") + " " + it.message
                    }
                ),
                requireAnotherCall = true
            )
        }
        if (!hasMainFunction(trimmed)) {
            return KotlinMcpResult.Error(
                message = "No top-level `fun main()` found. Declare a main function to run the snippet.",
                code = "NO_MAIN_FOUND"
            )
        }
        val autoClasspath = SnippetCompiler.detectProjectClasspath(projectPath)
        val executionClasspath = (extraClasspath + autoClasspath).distinct().filter { it.isNotBlank() }
        val mainClassName = resolveMainClassName(trimmed)

        val shouldUseHostJvm = runner != "in_process" ||
            jvmArgs.isNotEmpty() ||
            !javaPath.isNullOrBlank() ||
            SnippetAstSafetyChecker.containsHostTerminatingCalls(trimmed)

        return if (shouldUseHostJvm) {
            runHostJvm(compiled.outDir, timeoutMillis, executionClasspath, jvmArgs, javaPath, mainClassName)
        } else {
            fastSnippetRunner.run(
                outDir = compiled.outDir,
                timeoutMillis = timeoutMillis,
                extraClasspath = executionClasspath
            )
        }
    }

    private fun hasMainFunction(code: String): Boolean {
        val file = K2SnippetFrontend.parsePsi(code) ?: return false
        val mains = mutableListOf<org.jetbrains.kotlin.psi.KtNamedFunction>()
        val objectMains = mutableListOf<org.jetbrains.kotlin.psi.KtNamedFunction>()

        fun findMains(element: org.jetbrains.kotlin.com.intellij.psi.PsiElement) {
            if (element is org.jetbrains.kotlin.psi.KtNamedFunction && element.name == "main") {
                val p = element.parent
                val grandP = p?.parent
                if (p is org.jetbrains.kotlin.psi.KtFile || p is org.jetbrains.kotlin.psi.KtScript || p is org.jetbrains.kotlin.psi.KtScriptInitializer) {
                    mains.add(element)
                }
                if (element.annotationEntries.any { ann -> ann.shortName?.asString() == "JvmStatic" } && grandP is org.jetbrains.kotlin.psi.KtObjectDeclaration) {
                    objectMains.add(element)
                }
            }
            element.children.forEach { findMains(it) }
        }

        findMains(file)

        return mains.any { isExecutableMain(it) } || objectMains.any { isExecutableMain(it) }
    }

    private fun isExecutableMain(main: org.jetbrains.kotlin.psi.KtNamedFunction): Boolean {
        return when (main.valueParameters.size) {
            0 -> true
            1 -> {
                val typeText = main.valueParameters.firstOrNull()?.typeReference?.text?.replace(" ", "")
                typeText == "Array<String>" || typeText == "String[]" || typeText?.startsWith("Array<") == true
            }
            else -> false
        }
    }

    private fun runHostJvm(
        outDir: Path,
        timeoutMillis: Long,
        extraClasspath: List<String>,
        jvmArgs: List<String>,
        javaPath: String?,
        mainClass: String = SnippetCompiler.MAIN_CLASS
    ): KotlinMcpResult {
        val javaExecutable = javaResolver.resolve(javaPath)
            ?: return KotlinMcpResult.Error(
                message = "No Java installation detected across JAVA_HOME, system PATH, SDKMAN, or package managers. " +
                        "Please provide a valid 'javaPath' parameter (e.g. '/usr/lib/jvm/java-21/bin/java') to use host_jvm execution runner.",
                code = "MISSING_JAVA_HOME",
                requireAnotherCall = true
            )

        val violations = javaResolver.validateJvmArgs(jvmArgs)
        if (violations.isNotEmpty()) {
            return KotlinMcpResult.Error(
                message = "Rejected unsafe JVM argument '${violations.first()}'. Dangerous agent and module arguments are forbidden.",
                code = "UNSAFE_JVM_ARGUMENT"
            )
        }

        val fullCp = listOf(outDir.toString()) + extraClasspath + SnippetCompiler.runtimeExecutionClasspath
        val cpString = fullCp.filter { it.isNotBlank() }.distinct().joinToString(File.pathSeparator)

        val cmd = mutableListOf<String>()
        cmd.add(javaExecutable.absolutePath)
        cmd.addAll(jvmArgs.filter { it.isNotBlank() })
        cmd.add("-cp")
        cmd.add(cpString)
        cmd.add(mainClass)

        return try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            val output = ByteArrayOutputStream()
            val readerThread = Thread {
                output.write(process.inputStream.readBytes())
            }.apply {
                isDaemon = true
                start()
            }

            val startNanos = System.nanoTime()
            val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000

            if (!completed) {
                process.destroyForcibly()
                readerThread.join(1000)
                KotlinMcpResult.Error(
                    message = "Execution timed out after ${timeoutMillis}ms; process destroyed.",
                    code = "EXECUTION_TIMEOUT",
                    details = mapOf("timeoutMillis" to timeoutMillis.toString())
                )
            } else {
                try {
                    readerThread.join()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                val rawText = String(output.toByteArray(), Charsets.UTF_8)
                val text = LogTruncator.truncate(rawText)
                val exit = process.exitValue()
                if (exit == 0) {
                    val content = if (text.isBlank()) {
                        "✅ Ran successfully on host JVM (exit 0, no output)."
                    } else {
                        "✅ Ran successfully on host JVM (exit 0).\n\n$text".trim()
                    }
                    KotlinMcpResult.Success(
                        content = content,
                        metadata = mapOf(
                            "mode" to "host_jvm",
                            "exitCode" to "0",
                            "durationMs" to durationMs.toString()
                        )
                    )
                } else {
                    KotlinMcpResult.Error(
                        message = "Snippet exited on host JVM with code $exit:\n${if (text.isBlank()) "(no output)" else text}".trim(),
                        code = "RUNTIME_ERROR",
                        details = mapOf(
                            "mode" to "host_jvm",
                            "exitCode" to exit.toString(),
                            "durationMs" to durationMs.toString()
                        ),
                        requireAnotherCall = true
                    )
                }
            }
        } catch (e: Exception) {
            KotlinMcpResult.Error(
                message = "Failed to launch host JVM: ${e.message}",
                code = "LAUNCH_ERROR"
            )
        }
    }

    private fun runCompiled(outDir: Path, timeoutMillis: Long, extraClasspath: List<String>): KotlinMcpResult {
        return runHostJvm(outDir, timeoutMillis, extraClasspath, emptyList(), null)
    }

    override fun parseTestReport(projectPath: String): KotlinMcpResult {
        val root = File(projectPath)
        if (!root.exists() || !root.isDirectory) {
            return KotlinMcpResult.Error(
                message = "Project path does not exist or is not a directory: '$projectPath'",
                code = "PROJECT_NOT_FOUND"
            )
        }

        val xmlFiles = mutableListOf<File>()
        root.walkTopDown().maxDepth(6).onEnter { dir ->
            val name = dir.name
            name != ".gradle" && name != ".git" && name != "node_modules" && name != "out"
        }.forEach { file ->
            if (file.isFile && file.extension == "xml" && file.name.startsWith("TEST-")) {
                xmlFiles.add(file)
            }
        }

        if (xmlFiles.isEmpty()) {
            return KotlinMcpResult.Error(
                message = "No test XML report files found under $projectPath. Run `./gradlew test` first.",
                code = "NOT_FOUND"
            )
        }

        val failures = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var totalCount = 0
        var failureCount = 0
        var errorCount = 0
        var skippedCount = 0

        xmlFiles.forEach { file ->
            val doc = try {
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            } catch (e: Exception) {
                return KotlinMcpResult.Error(
                    message = "Failed to parse JUnit XML report ${file.name}: ${e.message}",
                    code = "PARSE_ERROR"
                )
            }
            val cases = doc.getElementsByTagName("testcase")
            for (i in 0 until cases.length) {
                val case = cases.item(i) as org.w3c.dom.Element
                totalCount++
                val testName = case.getAttribute("name")
                val className = case.getAttribute("classname")

                var outcome = "passed"
                var failureMsg = ""
                for (j in 0 until case.childNodes.length) {
                    val node = case.childNodes.item(j)
                    if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                    when (node.nodeName) {
                        "failure" -> { outcome = "failed"; failureMsg = node.textContent?.trim().orEmpty() }
                        "error" -> { outcome = "errored"; failureMsg = node.textContent?.trim().orEmpty() }
                        "skipped" -> { outcome = "skipped" }
                    }
                }
                when (outcome) {
                    "failed" -> {
                        failureCount++
                        failures.add("- `$className > $testName` FAILED${if (failureMsg.isNotBlank()) ": ${failureMsg.take(200)}" else ""}")
                    }
                    "errored" -> {
                        errorCount++
                        errors.add("- `$className > $testName` ERROR${if (failureMsg.isNotBlank()) ": ${failureMsg.take(200)}" else ""}")
                    }
                    "skipped" -> {
                        skippedCount++
                        skipped.add("- `$className > $testName` SKIPPED")
                    }
                }
            }
        }

        val content = buildString {
            appendLine("# JUnit Test Execution Report")
            appendLine("- Total Tests: $totalCount")
            appendLine("- Failures: $failureCount")
            appendLine("- Errors: $errorCount")
            appendLine("- Skipped: $skippedCount")
            appendLine()
            if (failures.isNotEmpty()) {
                appendLine("## Failed Tests")
                failures.forEach { appendLine(it) }
            }
            if (errors.isNotEmpty()) {
                appendLine("## Errored Tests")
                errors.forEach { appendLine(it) }
            }
            if (skipped.isNotEmpty()) {
                appendLine("## Skipped Tests")
                skipped.forEach { appendLine(it) }
            }
            if (failures.isEmpty() && errors.isEmpty()) {
                appendLine(if (skipped.isNotEmpty()) "ℹ️ All run tests passed (${skipped.size} skipped)." else "✅ All tests passed!")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf(
                "total" to totalCount.toString(),
                "failures" to failureCount.toString(),
                "errors" to errorCount.toString(),
                "skipped" to skippedCount.toString()
            )
        )
    }
}
