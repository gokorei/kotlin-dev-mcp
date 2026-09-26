package com.gokorei.kotlinmcp.mutation

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.models.ResponsePreset
import com.gokorei.kotlinmcp.models.ResponseProjection
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MutationServiceTest {

    private val service = DefaultMutationService()

    @AfterAll
    fun tearDown() {
        service.close()
    }

    @Test
    fun `mutateAndTest returns success report with score and metadata on strong suite`() {
        val code = """
            fun isAdult(age: Int): Boolean = age >= 18
        """.trimIndent()

        val testCode = """
            fun main() {
                check(isAdult(18)) { "exact boundary" }
                check(!isAdult(17)) { "one below" }
                check(isAdult(19)) { "one above" }
            }
        """.trimIndent()

        val result = service.mutateAndTest(code, testCode)

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Mutation Testing Report"))
        assertTrue(success.content.contains("All mutants killed") || success.content.contains("100.0%"))
        assertEquals("100.0", success.metadata["score"])
        assertEquals("true", success.metadata["isStrong"])
    }

    @Test
    fun `mutateAndTest details survived mutants with diff blocks on weak suite`() {
        val code = """
            fun isAdult(age: Int): Boolean = age >= 18
        """.trimIndent()

        // Weak test missing age = 18 boundary
        val weakTest = """
            fun main() {
                check(isAdult(25))
            }
        """.trimIndent()

        val result = service.mutateAndTest(code, weakTest)

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Survived Mutants"))
        assertTrue(success.content.contains("```diff"))
        assertEquals("false", success.metadata["isStrong"])
        assertTrue((success.metadata["survivedCount"]?.toInt() ?: 0) > 0)
    }

    @Test
    fun `mutateAndTest returns error when baseline test fails`() {
        val code = "fun isEven(x: Int): Boolean = x % 2 == 0"
        val brokenTest = "fun main() { check(isEven(3)) { 'Must fail' } }"

        val result = service.mutateAndTest(code, brokenTest)
        assertTrue(result.isError)
        val error = result as KotlinMcpResult.Error
        assertEquals("BASELINE_FAILURE", error.code)
    }

    @Test
    fun `mutateAndTest handles zero effective mutants without false all-killed claim`() {
        val code = """
            class Token(val raw: String)
            fun createToken(): Token {
                return Token("abc")
            }
        """.trimIndent()
        val testCode = "fun main() { check(createToken().raw == \"abc\") }"

        val result = service.mutateAndTest(code, testCode)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        val isStrong = success.metadata["isStrong"]
        assertEquals("false", isStrong)
        assertTrue(success.content.contains("NO EFFECTIVE MUTANTS"), "Content should indicate no effective mutants")
        assertTrue(success.content.contains("No executable mutants could be compiled"), "Content should explain mutants failed compilation")
    }

    @Test
    fun `mutateAndTest respects compact response preset`() {
        val code = "fun square(x: Int): Int = x * x"
        val testCode = "fun main() { check(square(3) == 9) }"

        val projection = ResponseProjection(preset = ResponsePreset.COMPACT)
        val result = service.mutateAndTest(code, testCode, projection)

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertNotNull(success.content)
    }

    @Test
    fun `mixed infrastructure errors are not presented as strong or all killed`() {
        val mutant =
            AstMutant(
                id = "mutant",
                operator = MutationOperator.RETURN_VALUE,
                line = 1,
                column = 1,
                originalSnippet = "1",
                mutatedSnippet = "0",
                mutatedSource = "fun value(): Int = 0",
                description = "return value mutation",
            )
        val report =
            MutationReport(
                score = 100.0,
                totalMutants = 2,
                killedCount = 1,
                survivedCount = 0,
                compilationErrorCount = 0,
                timeoutCount = 0,
                results =
                    listOf(
                        MutantResult(mutant, MutantStatus.KILLED),
                        MutantResult(mutant.copy(id = "infrastructure"), MutantStatus.INFRASTRUCTURE_ERROR),
                    ),
            )
        val localService =
            DefaultMutationService(
                object : MutationExecutionPipeline {
                    override fun run(
                        code: String,
                        testCode: String?,
                        timeoutPerMutantMs: Long,
                        includeExtremeOperators: Boolean,
                        maxOrder: Int,
                    ): MutationReport = report

                    override fun close() = Unit
                },
            )

        try {
            val result = localService.mutateAndTest("fun value(): Int = 1", "fun main() {}")
            assertTrue(result.isSuccess)
            val success = result as KotlinMcpResult.Success
            assertEquals("false", success.metadata["isStrong"])
            assertFalse(success.content.contains("STRONG"))
            assertFalse(success.content.contains("All mutants killed"))
            assertTrue(
                success.content.contains("Infrastructure Errors (Excluded from Score):"),
                success.content,
            )
            assertEquals("1", success.metadata["infrastructureErrorCount"])
            assertEquals("1", success.metadata["effectiveMutants"])
        } finally {
            localService.close()
        }
    }

    @Test
    fun `timeouts are counted as killed in both the report line and the metadata`() {
        val mutant = mutant("mutant")
        val report =
            MutationReport(
                score = 100.0,
                totalMutants = 3,
                killedCount = 0,
                survivedCount = 0,
                compilationErrorCount = 0,
                timeoutCount = 3,
                results =
                    listOf(
                        MutantResult(mutant.copy(id = "t1"), MutantStatus.TIMEOUT),
                        MutantResult(mutant.copy(id = "t2"), MutantStatus.TIMEOUT),
                        MutantResult(mutant.copy(id = "t3"), MutantStatus.TIMEOUT),
                    ),
            )
        val localService = serviceReturning(report)

        try {
            val result = localService.mutateAndTest("fun value(): Int = 1", "fun main() {}")
            assertTrue(result.isSuccess)
            val success = result as KotlinMcpResult.Success
            assertTrue(
                success.content.contains("**Mutants Killed:** 3 / 3"),
                success.content,
            )
            assertTrue(success.content.contains("**Timeouts (Counted as Killed):** 3"), success.content)
            assertEquals("3", success.metadata["timeoutCount"])
            assertEquals("0", success.metadata["killedCount"])
            assertEquals("3", success.metadata["effectiveMutants"])
        } finally {
            localService.close()
        }
    }

    @Test
    fun `report counts infrastructure errors separately from compilation errors`() {
        val report = mutantReport()
        assertEquals(2, report.infrastructureErrorCount)
        assertEquals(1, report.compilationErrorCount)
        assertEquals(3, report.effectiveMutants)
        assertEquals(3, report.killedIncludingTimeoutCount)
        assertEquals(2, report.timeoutKilledCount)
        assertFalse(report.isStrong)
    }

    @Test
    fun `report header names the isolated child JVM rather than in-memory execution`() {
        val localService = serviceReturning(mutantReport())

        try {
            val result = localService.mutateAndTest("fun value(): Int = 1", "fun main() {}")
            assertTrue(result.isSuccess)
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.startsWith("# 🧬 Mutation Testing Report (Isolated Child JVM)"), success.content)
            assertFalse(success.content.contains("In-Memory"), success.content)
        } finally {
            localService.close()
        }
    }

    private fun mutant(id: String = "mutant") =
        AstMutant(
            id = id,
            operator = MutationOperator.RETURN_VALUE,
            line = 1,
            column = 1,
            originalSnippet = "1",
            mutatedSnippet = "0",
            mutatedSource = "fun value(): Int = 0",
            description = "return value mutation",
        )

    private fun mutantReport(): MutationReport {
        val base = mutant()
        return MutationReport(
            score = 100.0,
            totalMutants = 6,
            killedCount = 1,
            survivedCount = 0,
            compilationErrorCount = 1,
            timeoutCount = 2,
            results =
                listOf(
                    MutantResult(base.copy(id = "k"), MutantStatus.KILLED),
                    MutantResult(base.copy(id = "t1"), MutantStatus.TIMEOUT),
                    MutantResult(base.copy(id = "t2"), MutantStatus.TIMEOUT),
                    MutantResult(base.copy(id = "c"), MutantStatus.COMPILATION_ERROR),
                    MutantResult(base.copy(id = "i1"), MutantStatus.INFRASTRUCTURE_ERROR),
                    MutantResult(base.copy(id = "i2"), MutantStatus.INFRASTRUCTURE_ERROR),
                ),
        )
    }

    private fun serviceReturning(report: MutationReport): DefaultMutationService =
        DefaultMutationService(
            object : MutationExecutionPipeline {
                override fun run(
                    code: String,
                    testCode: String?,
                    timeoutPerMutantMs: Long,
                    includeExtremeOperators: Boolean,
                    maxOrder: Int,
                ): MutationReport = report

                override fun close() = Unit
            },
        )
}
