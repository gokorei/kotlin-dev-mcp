package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FastSnippetRunnerTest {

    @Test
    fun `executes compiled snippet in-memory and captures standard output`() {
        val code = """
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
        val code = """
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
    fun `enforces execution timeout when snippet runs infinitely`() {
        val code = """
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
