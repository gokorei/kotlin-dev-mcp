package com.gokorei.kotlinmcp.semantic

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KtSessionProviderTest {

    private lateinit var sessionProvider: KtSessionProvider

    @BeforeEach
    fun setUp() {
        sessionProvider = KtSessionProvider(maxSessions = 4)
    }

    @Test
    fun `creates and caches analysis session for code snippet`() {
        val code = """
            package test
            sealed interface Result
            data class Success(val value: String) : Result
            data class Failure(val error: Throwable) : Result
        """.trimIndent()

        val session1 = sessionProvider.acquireSession(code, emptyList())
        assertNotNull(session1, "expected session to be created")
        assertNotNull(session1?.file, "expected KtFile in session")

        val session2 = sessionProvider.acquireSession(code, emptyList())
        assertNotNull(session2)
        assertEquals(session1?.file?.packageFqName?.asString(), session2?.file?.packageFqName?.asString())
    }

    @Test
    fun `bounds cache size to maxSessions via LRU eviction`() {
        val provider = KtSessionProvider(maxSessions = 2)

        val cp1 = listOf("/tmp/fake1.jar")
        val cp2 = listOf("/tmp/fake2.jar")
        val cp3 = listOf("/tmp/fake3.jar")

        provider.acquireSession("val a = 1", cp1)
        provider.acquireSession("val b = 2", cp2)
        assertEquals(2, provider.cachedSessionCount)

        // Adding 3rd should evict cp1
        provider.acquireSession("val c = 3", cp3)
        assertEquals(2, provider.cachedSessionCount)
    }

    @Test
    fun `respects ENABLE_SEMANTIC false flag and returns null session for pure syntactic fallback`() {
        val disabledProvider = KtSessionProvider(enableSemantic = false)
        val session = disabledProvider.acquireSession("val x = 42", emptyList())
        assertNull(session, "disabled provider should return null session to trigger fallback")
    }

    @Test
    fun `resolves symbols from stdlib and provided classpath`() {
        val code = """
            fun calculate(): List<String> {
                return listOf("a", "b", "c").map { it.uppercase() }
            }
        """.trimIndent()

        val session = sessionProvider.acquireSession(code, emptyList())
        assertNotNull(session)
        assertNotNull(session?.bindingContext)
    }
}
