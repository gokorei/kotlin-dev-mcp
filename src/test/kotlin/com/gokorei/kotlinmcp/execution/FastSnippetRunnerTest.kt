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
}
