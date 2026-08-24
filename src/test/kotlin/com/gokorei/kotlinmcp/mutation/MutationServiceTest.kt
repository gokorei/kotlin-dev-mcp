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
}
