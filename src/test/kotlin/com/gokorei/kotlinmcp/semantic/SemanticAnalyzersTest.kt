package com.gokorei.kotlinmcp.semantic

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SemanticAnalyzersTest {

    private lateinit var semanticService: SemanticService

    @BeforeEach
    fun setUp() {
        semanticService = DefaultSemanticService()
    }

    @Test
    fun `when exhaustiveness detects missing sealed interface branches and synthesizes stubs`() {
        val code = """
            sealed interface ScreenState {
                data object Loading : ScreenState
                data class Content(val items: List<String>) : ScreenState
                data class Error(val message: String) : ScreenState
            }

            fun render(state: ScreenState): String = when (state) {
                is ScreenState.Loading -> "loading..."
                is ScreenState.Error -> "error: " + state.message
            }
        """.trimIndent()

        val result = semanticService.checkWhenExhaustiveness(code)
        assertTrue(result.isSuccess, "expected success: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Content"), "expected missing 'Content' branch: ${success.content}")
        assertTrue(success.content.contains("is ScreenState.Content -> TODO()"), "expected synthesized stub in: ${success.content}")
    }

    @Test
    fun `when exhaustiveness passes when all sealed branches or else branch are present`() {
        val code = """
            sealed class Status {
                data object Active : Status()
                data object Inactive : Status()
            }

            fun handle(status: Status): String = when (status) {
                is Status.Active -> "active"
                is Status.Inactive -> "inactive"
            }
        """.trimIndent()

        val result = semanticService.checkWhenExhaustiveness(code)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("✅") || success.content.contains("exhaustive"), "expected exhaustive message: ${success.content}")
    }

    @Test
    fun `value class analyzer flags multiple constructor properties and var parameters`() {
        val invalidValueClass = """
            @JvmInline
            value class UserId(var id: Long, val tag: String)
        """.trimIndent()

        val result = semanticService.checkValueClass(invalidValueClass)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("single val parameter") || success.content.contains("must have exactly one"), "expected single val violation: ${success.content}")
    }

    @Test
    fun `value class analyzer passes on valid single val constructor`() {
        val validValueClass = """
            @JvmInline
            value class Email(val raw: String) {
                init {
                    require(raw.contains("@")) { "Invalid email" }
                }
            }
        """.trimIndent()

        val result = semanticService.checkValueClass(validValueClass)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("✅") || success.content.contains("valid"), "expected valid value class message: ${success.content}")
    }

    @Test
    fun `inline reified analyzer flags reified type parameter on non-inline function`() {
        val invalidReified = """
            fun <reified T> parse(json: String): T {
                TODO()
            }
        """.trimIndent()

        val result = semanticService.checkInlineReified(invalidReified)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("reified") && success.content.contains("inline"), "expected reified requires inline error: ${success.content}")
    }

    @Test
    fun `inline reified analyzer warns about large inline function bodies causing bytecode explosion`() {
        val largeInlineFunction = """
            inline fun <reified T> processLarge(items: List<T>) {
                println("1")
                println("2")
                println("3")
                println("4")
                println("5")
                println("6")
                println("7")
                println("8")
                println("9")
                println("10")
                println("11")
                println("12")
                println("13")
                println("14")
                println("15")
                println("16")
                println("17")
                println("18")
            }
        """.trimIndent()

        val result = semanticService.checkInlineReified(largeInlineFunction)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("bytecode") || success.content.contains("large inline"), "expected large inline warning: ${success.content}")
    }

    @Test
    fun `contracts analyzer flags contracts placed after first statement in function body`() {
        val misplacedContract = """
            import kotlin.contracts.ExperimentalContracts
            import kotlin.contracts.contract

            @OptIn(ExperimentalContracts::class)
            fun validate(value: String?) {
                println("validating...")
                contract {
                    returns() implies (value != null)
                }
            }
        """.trimIndent()

        val result = semanticService.checkContracts(misplacedContract)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("first statement"), "expected first statement contract rule: ${success.content}")
    }

    @Test
    fun `expect actual analyzer detects missing actual implementation and parameter mismatch`() {
        val expectActualCode = """
            expect class PlatformDate {
                fun getTimestamp(): Long
            }

            actual class PlatformDate {
                actual fun getTimestamp(): Int = 0
            }
        """.trimIndent()

        val result = semanticService.checkExpectActual(expectActualCode)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("getTimestamp") || success.content.contains("mismatch"), "expected return type mismatch finding: ${success.content}")
    }

    @Test
    fun `opt-in and deprecation analyzer detects un-opted experimental calls and replaces deprecated code`() {
        val experimentalCode = """
            @RequiresOptIn(message = "Experimental API")
            annotation class InternalApi

            @InternalApi
            fun dangerousOp() {}

            fun clientCode() {
                dangerousOp()
            }
        """.trimIndent()

        val result = semanticService.checkExperimentalOptIn(experimentalCode)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("InternalApi") || success.content.contains("@OptIn"), "expected @OptIn requirement: ${success.content}")
    }

    @Test
    fun `deprecated analyzer extracts ReplaceWith and generates replacement preview`() {
        val deprecatedCode = """
            @Deprecated("Use newCompute() instead", ReplaceWith("newCompute(factor = 1.0)"))
            fun oldCompute(): Double = 42.0

            fun run() {
                val x = oldCompute()
            }
        """.trimIndent()

        val result = semanticService.checkDeprecated(deprecatedCode)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("newCompute(factor = 1.0)"), "expected ReplaceWith expression: ${success.content}")
    }
}
