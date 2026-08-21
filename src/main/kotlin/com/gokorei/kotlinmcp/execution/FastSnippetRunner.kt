package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.shared.LogTruncator
import io.github.oshai.kotlinlogging.KotlinLogging
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

    override fun close() {
        val target = activeTarget.get()
        if (target != null) {
            target.close()
        }
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

    private val logger = KotlinLogging.logger {}
    private val executor: ExecutorService = Executors.newFixedThreadPool(threadPoolSize) { r ->
        Thread(r, "FastSnippetRunner-Worker").apply { isDaemon = true }
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
            val classLoader = URLClassLoader(fullCp.toTypedArray(), this::class.java.classLoader)
            try {
                // Find main class
                val mainClass = try {
                    classLoader.loadClass(SnippetCompiler.MAIN_CLASS)
                } catch (e: ClassNotFoundException) {
                    classLoader.loadClass("SnippetKt")
                }

                val mainMethod = try {
                    mainClass.getMethod("main", Array<String>::class.java)
                } catch (_: NoSuchMethodException) {
                    mainClass.getMethod("main")
                }

                // Intercept stdout/stderr thread-safely via ThreadLocalPrintStream
                ThreadLocalPrintStream.withCapture(customPrintStream) {
                    if (mainMethod.parameterCount == 1) {
                        mainMethod.invoke(null, emptyArray<String>())
                    } else {
                        mainMethod.invoke(null)
                    }
                }
            } finally {
                runCatching { classLoader.close() }
            }
        }

        val future = executor.submit(task)
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
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
            future.cancel(true)
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
