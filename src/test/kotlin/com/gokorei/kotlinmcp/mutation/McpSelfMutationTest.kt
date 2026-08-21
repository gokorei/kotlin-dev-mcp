package com.gokorei.kotlinmcp.mutation

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.models.ResponsePreset
import com.gokorei.kotlinmcp.models.ResponseProjection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("hardening")
class McpSelfMutationTest {

    private val pipeline = DefaultMutationExecutionPipeline()

    @Test
    fun `mutation tests internal SnippetAstSafetyChecker detection logic`() {
        val targetCode = """
            fun isDangerousCall(receiver: String, selector: String): Boolean {
                if (selector == "exit" && (receiver == "System" || receiver == "java.lang.System")) {
                    return true
                }
                if (selector == "halt" && receiver.endsWith("getRuntime()")) {
                    return true
                }
                return false
            }
        """.trimIndent()

        val strongTestSuite = """
            fun main() {
                check(isDangerousCall("System", "exit")) { "System.exit must be dangerous" }
                check(isDangerousCall("java.lang.System", "exit")) { "java.lang.System.exit must be dangerous" }
                check(!isDangerousCall("MySystem", "exit")) { "MySystem.exit is safe" }
                check(!isDangerousCall("System", "gc")) { "System.gc is safe" }
                check(isDangerousCall("Runtime.getRuntime()", "halt")) { "Runtime halt is dangerous" }
                check(!isDangerousCall("Runtime.getRuntime()", "availableProcessors")) { "processors is safe" }
                check(!isDangerousCall("Other", "halt")) { "other halt is safe" }
            }
        """.trimIndent()

        val report = pipeline.run(targetCode, strongTestSuite)

        assertTrue(report.totalMutants >= 4, "Expected multiple mutants on safety check branching")
        assertEquals(0, report.survivedCount, "All security guard mutants must be killed by strict test suite")
        assertEquals(100.0, report.score)
        assertTrue(report.isStrong)
    }

    @Test
    fun `mutation tests internal ProjectionFilter metadata pruner logic`() {
        val targetCode = """
            fun shouldPruneKey(key: String, preset: String): Boolean {
                if (preset != "COMPACT") return false
                return key == "raw" || key == "rawAst" || key == "debug"
            }
        """.trimIndent()

        val testSuite = """
            fun main() {
                check(shouldPruneKey("raw", "COMPACT"))
                check(shouldPruneKey("rawAst", "COMPACT"))
                check(shouldPruneKey("debug", "COMPACT"))
                check(!shouldPruneKey("symbol", "COMPACT"))
                check(!shouldPruneKey("raw", "FULL"))
                check(!shouldPruneKey("debug", "SUMMARY"))
            }
        """.trimIndent()

        val report = pipeline.run(targetCode, testSuite)

        assertTrue(report.totalMutants >= 3)
        assertEquals(0, report.survivedCount, "All projection filter mutants must be killed")
        assertEquals(100.0, report.score)
    }
}
