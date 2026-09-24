package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.shared.LogTruncator
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.*

/**
 * Thread-safe PrintStream interceptor that routes printed output to a thread-local
 * stream when registered, or delegates to the underlying root stream (stdout/stderr).
 * This eliminates concurrency cross-talk and race conditions during in-process snippet runs.
 */
class ThreadLocalPrintStream(private val defaultStream: PrintStream) : PrintStream(object : OutputStream() {
    override fun write(b: Int) {
        val target = activeTarget.get() ?: defaultStream
        target.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val target = activeTarget.get() ?: defaultStream
        target.write(b, off, len)
    }

    override fun flush() {
        val target = activeTarget.get() ?: defaultStream
        target.flush()
    }
}, true, Charsets.UTF_8.name()) {

    override fun close() {
        // Guard: prevent closing the process-wide System.out / System.err interceptor
        flush()
    }

    companion object {
        private val activeTarget = InheritableThreadLocal<PrintStream?>()

        fun <T> withCapture(stream: PrintStream, block: () -> T): T {
            val prev = activeTarget.get()
            activeTarget.set(stream)
            return try {
                block()
            } finally {
                activeTarget.set(prev)
            }
        }

        @Volatile
        private var installed = false

        @Synchronized
        fun install() {
            if (!installed) {
                val outInterceptor = ThreadLocalPrintStream(System.out)
                val errInterceptor = ThreadLocalPrintStream(System.err)
                System.setOut(outInterceptor)
                System.setErr(errInterceptor)
                installed = true
            }
        }
    }
}

/**
 * High-performance, in-memory snippet execution runner.
 * Loads and invokes compiled snippet bytecode dynamically via an isolated [URLClassLoader],
 * avoiding JVM fork/exec subprocess overhead and providing sub-50ms execution times.
 */
interface FastSnippetRunner : AutoCloseable {
    fun run(
        outDir: Path,
        timeoutMillis: Long,
        extraClasspath: List<String> = emptyList()
    ): KotlinMcpResult
}

class DefaultFastSnippetRunner(
    threadPoolSize: Int = 4
) : FastSnippetRunner {

    private val executor: ExecutorService = try {
        Executors.newVirtualThreadPerTaskExecutor()
    } catch (_: Throwable) {
        Executors.newFixedThreadPool(threadPoolSize) { r ->
            Thread(r, "FastSnippetRunner-Worker").apply { isDaemon = true }
        }
    }

    init {
        ThreadLocalPrintStream.install()
    }

    override fun run(
        outDir: Path,
        timeoutMillis: Long,
        extraClasspath: List<String>
    ): KotlinMcpResult {
        val startNanos = System.nanoTime()

        val fullCp = listOf(outDir.toUri().toURL()) +
            extraClasspath.filter { it.isNotBlank() }.map { File(it).toURI().toURL() }

        val capturedOut = ByteArrayOutputStream()
        val customPrintStream = PrintStream(capturedOut, true, Charsets.UTF_8.name())

        val task = Callable {
            val classLoader = object : URLClassLoader(fullCp.toTypedArray(), this::class.java.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    val classFile = outDir.resolve(name.replace('.', '/') + ".class").toFile()
                    if (classFile.exists()) {
                        val loaded = findLoadedClass(name)
                        if (loaded != null) return loaded
                        return findClass(name)
                    }
                    return super.loadClass(name, resolve)
                }
            }
            try {
                // Find main class
                val mainClass = try {
                    classLoader.loadClass(SnippetCompiler.MAIN_CLASS)
                } catch (e: ClassNotFoundException) {
                    val candidate = outDir.toFile().walkTopDown()
                        .firstOrNull { it.isFile && it.extension == "class" && !it.name.contains("$") }
                    val rel = candidate?.relativeTo(outDir.toFile())?.path?.removeSuffix(".class")?.replace('/', '.') ?: "SnippetKt"
                    classLoader.loadClass(rel)
                }

                val mainMethod = try {
                    mainClass.getMethod("main", Array<String>::class.java)
                } catch (_: NoSuchMethodException) {
                    mainClass.getMethod("main")
                }

                // Intercept stdout/stderr thread-safely via ThreadLocalPrintStream
                ThreadLocalPrintStream.withCapture(customPrintStream) {
                    val invokeArgs = if (mainMethod.parameterCount == 1) arrayOf<Any>(emptyArray<String>()) else emptyArray()
                    mainMethod.invoke(null, *invokeArgs)
                }
            } finally {
                runCatching { classLoader.close() }
            }
        }

        var future: Future<*>? = null
        return try {
            val f = executor.submit(task)
            future = f
            f.get(timeoutMillis, TimeUnit.MILLISECONDS)
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000
            val rawText = capturedOut.toString(Charsets.UTF_8.name()).trim()
            val text = LogTruncator.truncate(rawText)

            val content = if (text.isBlank()) {
                "✅ Ran successfully in-memory (exit 0, no output)."
            } else {
                "✅ Ran successfully in-memory (exit 0).\n\n$text".trim()
            }

            KotlinMcpResult.Success(
                content = content,
                metadata = mapOf(
                    "mode" to "in_memory",
                    "exitCode" to "0",
                    "durationMs" to durationMs.toString()
                )
            )
        } catch (e: TimeoutException) {
            future?.cancel(true)
            KotlinMcpResult.Error(
                message = "Execution timed out after ${timeoutMillis}ms; in-memory task cancelled.",
                code = "EXECUTION_TIMEOUT"
            )
        } catch (e: ExecutionException) {
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000
            val target = (e.cause as? InvocationTargetException)?.targetException ?: e.cause ?: e
            val rawOut = capturedOut.toString(Charsets.UTF_8.name()).trim()
            val outPrefix = if (rawOut.isNotBlank()) "$rawOut\n" else ""
            val stackSummary = target.stackTrace.take(5).joinToString("\n") { "  at $it" }
            val errorMsg = "$outPrefix${target.javaClass.simpleName}: ${target.message.orEmpty()}\n$stackSummary".trim()

            KotlinMcpResult.Error(
                message = "Snippet execution threw an unhandled exception:\n$errorMsg",
                code = "RUNTIME_ERROR",
                details = mapOf("exception" to target.javaClass.name, "durationMs" to durationMs.toString()),
                requireAnotherCall = true
            )
        } catch (e: Exception) {
            KotlinMcpResult.Error(
                message = "Failed to execute in-memory snippet: ${e.message}",
                code = "EXECUTION_ERROR"
            )
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

internal class HostJvmCompiledSnippetRunner(
    private val javaResolver: JavaResolver = DefaultJavaResolver(),
) : FastSnippetRunner {
    override fun run(
        outDir: Path,
        timeoutMillis: Long,
        extraClasspath: List<String>,
    ): KotlinMcpResult = executeCompiledSnippet(outDir, timeoutMillis, extraClasspath, javaResolver)

    override fun close() = Unit
}

@Suppress("ReturnCount")
private fun executeCompiledSnippet(
    outDir: Path,
    timeoutMillis: Long,
    extraClasspath: List<String>,
    javaResolver: JavaResolver,
): KotlinMcpResult {
    val javaExecutable =
        javaResolver.resolve(null)
            ?: return KotlinMcpResult.Error(
                message = "No Java installation detected for isolated mutation execution.",
                code = "MISSING_JAVA_HOME",
                requireAnotherCall = true,
            )
    val readinessToken =
        java.util.UUID
            .randomUUID()
            .toString()
    val bootstrapResource =
        HostJvmCompiledSnippetRunner::class.java.classLoader.getResourceAsStream(HOST_EXECUTION_BOOTSTRAP_RESOURCE)
            ?: return KotlinMcpResult.Error(
                message = "Isolated mutation bootstrap resource is unavailable.",
                code = "LAUNCH_ERROR",
            )
    try {
        bootstrapResource.use { input ->
            val bootstrapFile = outDir.resolve(classFilePath(HOST_EXECUTION_BOOTSTRAP_CLASS))
            java.nio.file.Files
                .createDirectories(bootstrapFile.parent)
            java.nio.file.Files
                .copy(
                    input,
                    bootstrapFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
        }
    } catch (e: java.io.IOException) {
        return KotlinMcpResult.Error(
            message = "Failed to prepare isolated mutation bootstrap: ${e.message}",
            code = "LAUNCH_ERROR",
        )
    }
    val command = buildCompiledCommand(outDir, extraClasspath, javaExecutable, readinessToken)
    return runCompiledProcess(command, timeoutMillis, readinessToken)
}

internal fun buildCompiledCommand(
    outDir: Path,
    extraClasspath: List<String>,
    javaExecutable: File,
    readinessToken: String =
        java.util.UUID
            .randomUUID()
            .toString(),
): List<String> {
    val fullCp =
        (listOf(outDir.toString()) + extraClasspath + SnippetCompiler.runtimeExecutionClasspath)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(File.pathSeparator)
    val targetMainClass = selectCompiledMainClass(outDir)
    return listOf(
        javaExecutable.absolutePath,
        "-cp",
        fullCp,
        HOST_EXECUTION_BOOTSTRAP_CLASS,
        targetMainClass,
        readinessToken,
    )
}

private fun selectCompiledMainClass(outDir: Path): String {
    val defaultMainFile = outDir.resolve(classFilePath(SnippetCompiler.MAIN_CLASS)).toFile()
    return if (defaultMainFile.isFile) {
        SnippetCompiler.MAIN_CLASS
    } else {
        outDir
            .toFile()
            .walkTopDown()
            .firstOrNull { it.isFile && it.extension == "class" && !it.name.contains("$") }
            ?.relativeTo(outDir.toFile())
            ?.invariantSeparatorsPath
            ?.removeSuffix(".class")
            ?.replace('/', '.')
            ?: SnippetCompiler.MAIN_CLASS
    }
}

private fun classFilePath(className: String): String = className.replace('.', '/') + ".class"

internal const val HOST_EXECUTION_BOOTSTRAP_CLASS = "com.gokorei.kotlinmcp.execution.bootstrap.HostExecutionBootstrap"
private const val HOST_EXECUTION_BOOTSTRAP_RESOURCE =
    "com/gokorei/kotlinmcp/execution/bootstrap/" +
        "HostExecutionBootstrap.class"
private const val PROCESS_STARTUP_ALLOWANCE_MS = 2_000L
private const val PROCESS_READY_POLL_INTERVAL_MS = 5L
private const val PROCESS_START_SIGNAL: Byte = 1
private const val PROCESS_DRAIN_JOIN_TIMEOUT_MS = 1_000L
private const val PROCESS_OUTPUT_JOIN_TIMEOUT_MS = 2_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L

@Suppress("ReturnCount")
private fun runCompiledProcess(
    command: List<String>,
    timeoutMillis: Long,
    readinessToken: String,
): KotlinMcpResult {
    val process =
        try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            return KotlinMcpResult.Error(
                message = "Failed to launch isolated mutation JVM: ${e.message}",
                code = "LAUNCH_ERROR",
            )
        } catch (e: SecurityException) {
            return KotlinMcpResult.Error(
                message = "Failed to launch isolated mutation JVM: ${e.message}",
                code = "LAUNCH_ERROR",
            )
        }
    val drainHandle =
        com.gokorei.kotlinmcp.shared.BoundedStreamDrainer
            .drain(process.inputStream)
    val startNanos = System.nanoTime()
    beginCompiledExecution(process, drainHandle, readinessToken)?.let { return it }
    waitForCompiledExecution(process, drainHandle, timeoutMillis)?.let { return it }
    drainHandle.join(PROCESS_OUTPUT_JOIN_TIMEOUT_MS)
    val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLISECOND
    val text = LogTruncator.truncate(drainHandle.readUtf8().replace(readinessToken, ""))
    val exit = process.exitValue()
    return if (exit == 0) {
        KotlinMcpResult.Success(
            content = if (text.isBlank()) "Ran successfully in isolated mutation JVM." else text,
            metadata = mapOf("mode" to "host_jvm", "exitCode" to "0", "durationMs" to durationMs.toString()),
        )
    } else {
        KotlinMcpResult.Error(
            message = "Isolated mutation JVM exited with code $exit:\n$text",
            code = "RUNTIME_ERROR",
            details = mapOf("mode" to "host_jvm", "exitCode" to exit.toString(), "durationMs" to durationMs.toString()),
            requireAnotherCall = true,
        )
    }
}

@Suppress("ReturnCount")
private fun beginCompiledExecution(
    process: Process,
    drainHandle: com.gokorei.kotlinmcp.shared.BoundedDrainHandle,
    readinessToken: String,
): KotlinMcpResult? {
    val ready = awaitProcessReadiness(process, drainHandle, readinessToken)
    return when {
        Thread.currentThread().isInterrupted -> {
            terminateAndDrain(process, drainHandle)
            KotlinMcpResult.Error(
                message = "Waiting for isolated mutation JVM readiness was interrupted.",
                code = "EXECUTION_ERROR",
            )
        }
        !ready && process.isAlive -> {
            terminateAndDrain(process, drainHandle)
            KotlinMcpResult.Error(
                message = "Isolated mutation JVM startup timed out after ${PROCESS_STARTUP_ALLOWANCE_MS}ms.",
                code = "EXECUTION_TIMEOUT",
                details = mapOf("phase" to "startup", "timeoutMillis" to PROCESS_STARTUP_ALLOWANCE_MS.toString()),
            )
        }
        !ready -> {
            drainHandle.join(PROCESS_OUTPUT_JOIN_TIMEOUT_MS)
            val exit = process.exitValue()
            val text = LogTruncator.truncate(drainHandle.readUtf8().replace(readinessToken, ""))
            KotlinMcpResult.Error(
                message = "Isolated mutation JVM exited before readiness with code $exit:\n$text",
                code = "LAUNCH_ERROR",
                details = mapOf("exitCode" to exit.toString()),
            )
        }
        else ->
            try {
                process.outputStream.use { stream ->
                    stream.write(PROCESS_START_SIGNAL.toInt())
                    stream.flush()
                }
                null
            } catch (e: java.io.IOException) {
                terminateAndDrain(process, drainHandle)
                KotlinMcpResult.Error(
                    message = "Failed to start isolated mutation execution: ${e.message}",
                    code = "LAUNCH_ERROR",
                )
            }
    }
}

@Suppress("ReturnCount")
private fun waitForCompiledExecution(
    process: Process,
    drainHandle: com.gokorei.kotlinmcp.shared.BoundedDrainHandle,
    timeoutMillis: Long,
): KotlinMcpResult? {
    val completed =
        try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            destroyProcessTree(process)
            return KotlinMcpResult.Error(
                message = "Waiting for isolated mutation JVM was interrupted: ${e.message}",
                code = "EXECUTION_ERROR",
            )
        }
    if (!completed) {
        terminateAndDrain(process, drainHandle)
        return KotlinMcpResult.Error(
            message = "Execution timed out after ${timeoutMillis}ms; isolated process destroyed.",
            code = "EXECUTION_TIMEOUT",
            details = mapOf("phase" to "execution", "timeoutMillis" to timeoutMillis.toString()),
        )
    }
    return null
}

private fun terminateAndDrain(
    process: Process,
    drainHandle: com.gokorei.kotlinmcp.shared.BoundedDrainHandle,
) {
    destroyProcessTree(process)
    drainHandle.join(PROCESS_DRAIN_JOIN_TIMEOUT_MS)
}

private fun awaitProcessReadiness(
    process: Process,
    drainHandle: com.gokorei.kotlinmcp.shared.BoundedDrainHandle,
    readinessToken: String,
): Boolean {
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_STARTUP_ALLOWANCE_MS)
    val pollIntervalNanos = TimeUnit.MILLISECONDS.toNanos(PROCESS_READY_POLL_INTERVAL_MS)
    var ready = drainHandle.readUtf8().contains(readinessToken)
    while (!ready && !Thread.currentThread().isInterrupted && process.isAlive) {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0) break
        java.util.concurrent.locks.LockSupport
            .parkNanos(minOf(remainingNanos, pollIntervalNanos))
        ready = drainHandle.readUtf8().contains(readinessToken)
    }
    return ready
}

private fun destroyProcessTree(process: Process) {
    runCatching { process.descendants().forEach { it.destroyForcibly() } }
    process.destroyForcibly()
}
