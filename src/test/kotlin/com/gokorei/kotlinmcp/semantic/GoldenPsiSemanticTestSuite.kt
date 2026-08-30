package com.gokorei.kotlinmcp.semantic

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.server.KotlinMcpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GoldenPsiSemanticTestSuite {

    private lateinit var server: KotlinMcpServer

    @BeforeEach
    fun setUp() {
        server = KotlinMcpServer()
    }

    @Test
    fun `golden test - complex sealed hierarchy when exhaustiveness with nested and top-level subclasses`() {
        val snippet = """
            package com.example.domain

            sealed interface NetworkResult<out T> {
                data class Success<T>(val data: T) : NetworkResult<T>
                sealed interface Error : NetworkResult<Nothing> {
                    data class Http(val code: Int, val body: String) : Error
                    data class Network(val cause: Throwable) : Error
                    data object Timeout : Error
                }
                data object Loading : NetworkResult<Nothing>
            }

            fun <T> handleResult(res: NetworkResult<T>): String = when (res) {
                is NetworkResult.Success -> "Got data: " + res.data
                is NetworkResult.Error.Http -> "HTTP error " + res.code
                is NetworkResult.Error.Timeout -> "Request timed out"
                NetworkResult.Loading -> "Loading..."
            }
        """.trimIndent()

        val result = server.checkWhenExhaustiveness(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Network"), "should detect missing Network error branch: ${success.content}")
        assertTrue(success.content.contains("is NetworkResult.Error.Network -> TODO()") || success.content.contains("Network -> TODO()"))
    }

    @Test
    fun `golden test - value class constraints and interface boxing advisory`() {
        val snippet = """
            package com.example.model

            interface Identifiable {
                val rawId: String
            }

            @JvmInline
            value class AccountId(override val rawId: String) : Identifiable

            @JvmInline
            value class BrokenId(var id: Long)
        """.trimIndent()

        val result = server.checkValueClass(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("cannot be `var`"), "should flag mutable parameter in BrokenId: ${success.content}")
        assertTrue(success.content.contains("boxing allocation"), "should emit boxing advisory for Identifiable: ${success.content}")
    }

    @Test
    fun `golden test - inline reified parameter validity and body size heuristics`() {
        val snippet = """
            package com.example.inline

            inline fun <reified T : Any> createInstance(): T {
                return T::class.java.getDeclaredConstructor().newInstance()
            }

            fun <reified T> brokenNonInline() {
                println(T::class)
            }
        """.trimIndent()

        val result = server.checkInlineReified(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("brokenNonInline"), "should flag reified on non-inline function: ${success.content}")
    }

    @Test
    fun `golden test - contracts placement and returns implies validation`() {
        val snippet = """
            package com.example.contracts

            import kotlin.contracts.ExperimentalContracts
            import kotlin.contracts.contract

            @OptIn(ExperimentalContracts::class)
            fun requireValid(value: String?) {
                contract {
                    returns() implies (value != null)
                }
                if (value == null) throw IllegalArgumentException("value is null")
            }
        """.trimIndent()

        val result = server.checkContracts(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("✅") || success.content.contains("valid"), "should accept valid contract: ${success.content}")
    }

    @Test
    fun `golden test - multiplatform expect actual signature alignment`() {
        val snippet = """
            package com.example.kmp

            expect class UUIDGenerator {
                fun generate(): String
                fun parse(raw: String): Boolean
            }

            actual class UUIDGenerator {
                actual fun generate(): String = "1234-5678"
                actual fun parse(raw: String): Int = 0
            }
        """.trimIndent()

        val result = server.checkExpectActual(snippet)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("parse") && success.content.contains("mismatch"), "should flag return type mismatch in parse: ${success.content}")
    }

    @Test
    fun `golden test - experimental opt in enforcement and deprecated ReplaceWith migration`() {
        val snippet = """
            package com.example.api

            @RequiresOptIn("Internal engine API")
            annotation class InternalEngineApi

            @InternalEngineApi
            fun bootEngine() {}

            @Deprecated("Use startNewEngine()", ReplaceWith("startNewEngine(fast = true)"))
            fun startOldEngine() {}

            fun clientUsage() {
                bootEngine()
                startOldEngine()
            }
        """.trimIndent()

        val optInResult = server.checkExperimentalOptIn(snippet)
        assertTrue(optInResult.isSuccess)
        val optInSuccess = optInResult as KotlinMcpResult.Success
        assertEquals("1", optInSuccess.metadata["findingsCount"])
        assertTrue(optInSuccess.content.contains("bootEngine") && optInSuccess.content.contains("InternalEngineApi"))

        val depResult = server.checkDeprecated(snippet)
        assertTrue(depResult.isSuccess)
        val depSuccess = depResult as KotlinMcpResult.Success
        assertEquals("1", depSuccess.metadata["findingsCount"])
        assertTrue(depSuccess.content.contains("startOldEngine") && depSuccess.content.contains("startNewEngine(fast = true)"))
    }
}
