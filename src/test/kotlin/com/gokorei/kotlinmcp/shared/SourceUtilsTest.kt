package com.gokorei.kotlinmcp.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SourceUtilsTest {

    @Test
    fun `lineOf calculates 1-indexed line numbers accurately`() {
        val text = "line 1\nline 2\nline 3"
        assertEquals(1, SourceUtils.lineOf(text, 0))
        assertEquals(1, SourceUtils.lineOf(text, 4))
        assertEquals(2, SourceUtils.lineOf(text, 7))
        assertEquals(3, SourceUtils.lineOf(text, 15))
    }

    @Test
    fun `lineAndColumnOf calculates line and column numbers`() {
        val text = "abc\ndefg"
        val (l1, c1) = SourceUtils.lineAndColumnOf(text, 0)
        assertEquals(1, l1)
        assertEquals(1, c1)

        val (l2, c2) = SourceUtils.lineAndColumnOf(text, 5) // 'e' in "defg"
        assertEquals(2, l2)
        assertEquals(2, c2)
    }

    @Test
    fun `lineSnippet extracts line containing target offset`() {
        val text = "  val x = 1  \n  val y = 2  \n  val z = 3  "
        val snippet = SourceUtils.lineSnippet(text, text.indexOf("y"))
        assertEquals("val y = 2", snippet)
    }

    @Test
    fun `extractBalancedBraces handles escaped quotes inside strings preceded by escaped backslashes`() {
        val code = "class Foo { fun bar() { val s = \"foo\\\\\" } }"
        val openIdx = code.indexOf('{')
        val body = SourceUtils.extractBalancedBraces(code, openIdx)
        assertNotNull(body)
        assertEquals("fun bar() { val s = \"foo\\\\\" }", body)
    }

    @Test
    fun `isSyntacticallyBalanced handles escaped quotes and backslashes in strings`() {
        assertTrue(SourceUtils.isSyntacticallyBalanced("""val x = "str \" with (parens) \\"""""))
        assertTrue(SourceUtils.isSyntacticallyBalanced("""val x = "{ [ (""""))
    }


    @Test
    fun `lineOf and lineAndColumnOf handle negative or out-of-bounds offsets safely`() {
        val text = "hello\nworld"
        assertEquals(1, SourceUtils.lineOf(text, -5))
        assertEquals(2, SourceUtils.lineOf(text, 100))

        val (l1, c1) = SourceUtils.lineAndColumnOf(text, -10)
        assertEquals(1, l1)
        assertEquals(1, c1)

        val (l2, c2) = SourceUtils.lineAndColumnOf(text, 100)
        assertEquals(2, l2)
        assertEquals(6, c2)
    }
}


