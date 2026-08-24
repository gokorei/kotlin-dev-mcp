package com.gokorei.kotlinmcp.mutation

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MutationExecutionPipelineTest {

    private val pipeline = DefaultMutationExecutionPipeline()

    @AfterAll
    fun tearDown() {
        pipeline.close()
    }

    @Test
    fun `strong assertions kill all mutants with high mutation score`() {
        val code = """
            fun sign(x: Int): Int {
                if (x > 0) return 1
                if (x < 0) return -1
                return 0
            }
        """.trimIndent()

        val testCode = """
            fun main() {
                check(sign(5) == 1) { "positive" }
                check(sign(-5) == -1) { "negative" }
                check(sign(0) == 0) { "zero" }
            }
        """.trimIndent()

        val report = pipeline.run(code, testCode)

        assertTrue(report.totalMutants > 0, "Mutants should be generated")
        assertEquals(0, report.survivedCount, "Strong tests should kill all mutants without survivals")
        assertEquals(report.effectiveMutants, report.killedCount)
        assertEquals(100.0, report.score)
        assertTrue(report.isStrong)
    }

    @Test
    fun `weak assertions allow mutants to survive and flag survival in report`() {
        val code = """
            fun isPositive(x: Int): Boolean {
                return x > 0
            }
        """.trimIndent()

        // Weak test only tests x = 10, completely missing x = 0 (boundary) and negative inputs
        val weakTest = """
            fun main() {
                check(isPositive(10)) { "10 > 0" }
            }
        """.trimIndent()

        val report = pipeline.run(code, weakTest)

        assertTrue(report.totalMutants > 0)
        assertTrue(report.survivedCount > 0, "Mutants (e.g. x >= 0) must survive weak tests")
        assertTrue(report.score < 100.0)

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        assertTrue(survived.isNotEmpty())
        assertTrue(survived.any { it.mutant.operator == MutationOperator.RELATIONAL_BOUNDARY })
    }

    @Test
    fun `baseline test failure aborts mutation and reports baseline error`() {
        val code = """
            fun add(a: Int, b: Int): Int = a + b
        """.trimIndent()

        val brokenTest = """
            fun main() {
                check(add(2, 2) == 5) { "Intentionally broken baseline assertion" }
            }
        """.trimIndent()

        val report = pipeline.run(code, brokenTest)

        assertEquals(0, report.totalMutants, "Pipeline must abort without running mutants if baseline fails")
        assertEquals(0.0, report.score)
        assertEquals(1, report.results.size)
        assertEquals(MutantStatus.BASELINE_ERROR, report.results.first().status)
        assertTrue(report.results.first().details?.contains("Baseline test failed") == true)
    }

    @Test
    fun `when all mutants fail compilation score is zero and not strong`() {
        // Snippet where return mutations produce type mismatch (e.g. non-primitive return with no other operators)
        val code = """
            class CustomObj(val v: Int)
            fun create(): CustomObj {
                return CustomObj(42)
            }
        """.trimIndent()

        val testCode = """
            fun main() {
                check(create().v == 42)
            }
        """.trimIndent()

        val report = pipeline.run(code, testCode)

        assertTrue(report.totalMutants > 0, "Mutants should be generated for return expression")
        assertEquals(0, report.effectiveMutants, "All generated mutants should fail compilation for CustomObj")
        assertEquals(0.0, report.score, "Score must be 0.0 when all mutants fail compilation")
        assertFalse(report.isStrong, "Suite with no executable mutants must not be reported as strong")
    }

    @Test
    fun `snippet and test with separate imports merge and execute cleanly`() {
        val code = """
            import java.util.UUID

            fun generateId(): String = UUID.randomUUID().toString()
        """.trimIndent()

        val testCode = """
            import java.time.Instant

            fun main() {
                val id = generateId()
                val now = Instant.now()
                check(id.isNotBlank())
                check(now.toEpochMilli() > 0)
            }
        """.trimIndent()

        val report = pipeline.run(code, testCode)
        assertEquals(0, report.compilationErrorCount, "Imports from both code and testCode should merge without compilation error")
    }

    @Test
    fun `in-memory pipeline executes successive mutant iterations`() {
        val code = """
            fun checkRange(x: Int): Boolean {
                if (x < 0) return false
                if (x > 100) return false
                return true
            }
        """.trimIndent()

        val testCode = """
            fun main() {
                check(!checkRange(-1))
                check(!checkRange(101))
                check(checkRange(50))
                check(checkRange(0))
                check(checkRange(100))
            }
        """.trimIndent()

        val report = pipeline.run(code, testCode)

        assertTrue(report.totalMutants >= 3)
        assertTrue(report.results.isNotEmpty())
    }
}
