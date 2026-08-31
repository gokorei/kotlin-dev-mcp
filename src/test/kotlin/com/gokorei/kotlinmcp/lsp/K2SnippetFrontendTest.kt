package com.gokorei.kotlinmcp.lsp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class K2SnippetFrontendTest {

    @Test
    fun `parsePsi preserves exact line numbers without wrapper offset corruption`() {
        val snippet = """
            val x = 10
            val y = 20
        """.trimIndent()

        val psi = K2SnippetFrontend.parsePsi(snippet)
        assertNotNull(psi)
        val text = psi!!.text
        assertEquals(snippet, text, "parsed AST text must equal input snippet without wrapper artifacts")
        assertFalse(text.contains("__snippet_wrapper"), "synthetic wrapper must not corrupt source text")
    }

    @Test
    fun `parsePsi handles concurrent calls across threads without lock contention`() {
        val pool = Executors.newFixedThreadPool(8)
        val count = 50
        val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()

        for (i in 0 until count) {
            pool.submit {
                val code = "fun fn_$i(): Int = $i"
                val psi = K2SnippetFrontend.parsePsi(code)
                results.add(psi != null && psi.text == code)
            }
        }

        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(count, results.size)
        assertTrue(results.all { it }, "all concurrent parse calls must succeed")
    }

    @Test
    fun `analyzeSession performs full frontend resolution and populates BindingContext`() {
        val code = """
            package com.example.test

            fun calculateSum(a: Int, b: Int): Int = a + b

            fun main() {
                val result = calculateSum(10, 20)
            }
        """.trimIndent()

        val session = K2SnippetFrontend.analyzeSession(code)
        assertNotNull(session, "expected non-null session")
        assertNotNull(session!!.bindingContext, "expected non-null bindingContext in session")
        assertNotEquals(org.jetbrains.kotlin.resolve.BindingContext.EMPTY, session.bindingContext, "bindingContext should not be EMPTY")

        val fn = session.file.declarations.firstOrNull { it.name == "calculateSum" } as? org.jetbrains.kotlin.psi.KtNamedFunction
        assertNotNull(fn, "expected calculateSum function declaration")

        val descriptor = session.bindingContext.get(org.jetbrains.kotlin.resolve.BindingContext.DECLARATION_TO_DESCRIPTOR, fn)
        assertNotNull(descriptor, "expected DeclarationDescriptor for calculateSum")
        assertEquals("com.example.test.calculateSum", org.jetbrains.kotlin.resolve.DescriptorUtils.getFqName(descriptor!!).asString())

    }

    @Test
    fun `analyzeSession resolves expression types via BindingContext`() {
        val code = """
            package com.example.test

            val greeting = "Hello World"
            val count = 42
        """.trimIndent()

        val session = K2SnippetFrontend.analyzeSession(code)
        assertNotNull(session)

        val properties = session!!.file.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtProperty>()
        val greetingProp = properties.firstOrNull { it.name == "greeting" }
        assertNotNull(greetingProp)

        val greetingExpr = greetingProp!!.initializer
        assertNotNull(greetingExpr)

        val greetingType = session.bindingContext.getType(greetingExpr!!)
        assertNotNull(greetingType, "expected bindingContext to resolve type for greeting initializer")
        assertEquals("String", greetingType?.constructor?.declarationDescriptor?.name?.asString())
    }

    @Test
    fun `resetEnvironment recycles disposables and allows subsequent parsing`() {
        val code1 = "val a = 10"
        val psi1 = K2SnippetFrontend.parsePsi(code1)
        assertNotNull(psi1)

        K2SnippetFrontend.resetEnvironment()

        val code2 = "val b = 20"
        val psi2 = K2SnippetFrontend.parsePsi(code2)
        assertNotNull(psi2)
        assertEquals("val b = 20", psi2!!.text)
    }

    @Test
    fun `parsePsi and resetEnvironment safely synchronize under concurrent execution`() {
        val pool = Executors.newFixedThreadPool(8)
        val count = 40
        val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()

        for (i in 0 until count) {
            pool.submit {
                if (i % 10 == 0) {
                    K2SnippetFrontend.resetEnvironment()
                }
                val code = "val conc_$i = $i"
                val psi = K2SnippetFrontend.parsePsi(code)
                results.add(psi != null && psi.text == code)
            }
        }

        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(count, results.size)
        assertTrue(results.all { it }, "all concurrent parse and reset operations must succeed")
    }
}


