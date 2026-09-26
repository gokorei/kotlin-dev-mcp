package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class FastSnippetRunnerTest {
    @Test
    fun `executes compiled snippet in-memory and captures standard output`() {
        val code =
            """
            fun main() {
                println("in-memory-fast-execution")
            }
            """.trimIndent()

        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val res = (compiled as CompileResult.Compiled)

        DefaultFastSnippetRunner().use { runner ->
            val result = runner.run(res.outDir, timeoutMillis = 5_000L)
            SnippetCompiler.cleanup(res)

            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("in-memory-fast-execution"))
            assertEquals("in_memory", success.metadata["mode"])
            assertEquals("0", success.metadata["exitCode"])
        }
    }

    @Test
    fun `captures runtime exceptions with formatted error details`() {
        val code =
            """
            fun main() {
                error("Deliberate fast runner test error")
            }
            """.trimIndent()

        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val res = (compiled as CompileResult.Compiled)

        DefaultFastSnippetRunner().use { runner ->
            val result = runner.run(res.outDir, timeoutMillis = 5_000L)
            SnippetCompiler.cleanup(res)

            assertTrue(result.isError)
            val err = result as KotlinMcpResult.Error
            assertEquals("RUNTIME_ERROR", err.code)
            assertTrue(err.message.contains("Deliberate fast runner test error"))
        }
    }

    @Test
    fun `host runner command passes generated main class to readiness bootstrap`() {
        val outDir = Files.createTempDirectory("host-runner-main-selection")
        try {
            Files.writeString(outDir.resolve("CustomObj.class"), "")
            Files.writeString(outDir.resolve("SnippetKt.class"), "")
            val readinessToken = "test-readiness-token"

            val command = buildCompiledCommand(outDir, emptyList(), File("java"), readinessToken)

            assertEquals(HOST_EXECUTION_BOOTSTRAP_CLASS, command[3])
            assertEquals(listOf(SnippetCompiler.MAIN_CLASS, readinessToken), command.drop(4))
        } finally {
            outDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `host runner executes snippet after readiness handshake`() {
        val code =
            """
            fun main() {
                println("host-ready-execution")
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 1_000L)
                }

            assertTrue(executionResult.isSuccess, "expected success, got: ${executionResult.toFormattedText()}")
            val success = executionResult as KotlinMcpResult.Success
            assertTrue(success.content.contains("host-ready-execution"))
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner times out finite snippet after readiness`() {
        val code =
            """
            fun main() {
                Thread.sleep(750)
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 200L)
                }

            assertTrue(executionResult.isError)
            val error = executionResult as KotlinMcpResult.Error
            assertEquals("EXECUTION_TIMEOUT", error.code)
            assertEquals("execution", error.details["phase"])
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `enforces execution timeout when snippet runs infinitely`() {
        val code =
            """
            fun main() {
                while (true) {
                    Thread.sleep(50)
                }
            }
            """.trimIndent()

        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val res = (compiled as CompileResult.Compiled)

        DefaultFastSnippetRunner().use { runner ->
            val result = runner.run(res.outDir, timeoutMillis = 500L)
            SnippetCompiler.cleanup(res)

            assertTrue(result.isError)
            val err = result as KotlinMcpResult.Error
            assertEquals("EXECUTION_TIMEOUT", err.code)
        }
    }

    @Test
    fun `host runner cleans descendant processes after snippet exits`() {
        val code =
            """
            fun main() {
                val child = ProcessBuilder("sleep", "37").start()
                println("DESCENDANT_PID=${'$'}{child.pid()}")
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 5_000L)
                }

            assertTrue(executionResult.isSuccess, "expected success, got: ${executionResult.toFormattedText()}")
            val success = executionResult as KotlinMcpResult.Success
            val pid =
                success.content
                    .lineSequence()
                    .first { it.startsWith("DESCENDANT_PID=") }
                    .substringAfter('=')
                    .toLong()
            val child = ProcessHandle.of(pid).orElse(null)
            assertTrue(child == null || !child.isAlive)
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner preserves an existing interrupt status`() {
        val compiled = SnippetCompiler.compile("fun main() { Thread.sleep(1_000) }")
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            Thread.currentThread().interrupt()
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 5_000L)
                }

            assertTrue(executionResult.isError)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner preserves an interrupt received during execution`() {
        val marker = Files.createTempFile("host-runner-interrupt", ".ready")
        val markerPath = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        val code =
            """
            fun main() {
                java.nio.file.Files.writeString(java.nio.file.Path.of("$markerPath"), "ready")
                Thread.sleep(5_000)
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled
        val interrupted = arrayOfNulls<Boolean>(1)
        val executionThread =
            Thread({
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 10_000L)
                }
                interrupted[0] = Thread.currentThread().isInterrupted
            }, "host-runner-interrupt-test")

        try {
            executionThread.start()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!Files.exists(marker) && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(Files.exists(marker), "snippet did not reach execution")
            executionThread.interrupt()
            executionThread.join(5_000)
            assertTrue(!executionThread.isAlive)
            assertEquals(true, interrupted[0])
        } finally {
            executionThread.interrupt()
            executionThread.join(5_000)
            Files.deleteIfExists(marker)
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner includes target class initialization in execution timeout`() {
        val code =
            """
            val initializationDelay = run {
                Thread.sleep(750)
                true
            }
            fun main() {
                println("initialized=${'$'}initializationDelay")
            }
            """.trimIndent()
        val compiled = SnippetCompiler.compile(code)
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            val executionResult =
                HostJvmCompiledSnippetRunner().use { runner ->
                    runner.run(result.outDir, timeoutMillis = 200L)
                }

            assertTrue(executionResult.isError)
            val error = executionResult as KotlinMcpResult.Error
            assertEquals("EXECUTION_TIMEOUT", error.code)
            assertEquals("execution", error.details["phase"])
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }

    @Test
    fun `host runner can execute the same compiled output repeatedly`() {
        val compiled = SnippetCompiler.compile("fun main() { println(\"repeated-bootstrap\") }")
        assertTrue(compiled is CompileResult.Compiled)
        val result = compiled as CompileResult.Compiled

        try {
            HostJvmCompiledSnippetRunner().use { runner ->
                repeat(2) {
                    val executionResult = runner.run(result.outDir, timeoutMillis = 1_000L)
                    assertTrue(executionResult.isSuccess, "expected success, got: ${executionResult.toFormattedText()}")
                }
            }
        } finally {
            SnippetCompiler.cleanup(result)
        }
    }
}
