package com.gokorei.kotlinmcp.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LogTruncatorTest {

    @Test
    fun `truncate returns original text when under limits`() {
        val input = "Line 1\nLine 2\nLine 3"
        val result = LogTruncator.truncate(input, maxLines = 10, maxBytes = 1000)
        assertEquals(input, result)
    }

    @Test
    fun `truncate caps line count and appends truncation header`() {
        val input = (1..50).joinToString("\n") { "Line $it" }
        val result = LogTruncator.truncate(input, maxLines = 10, maxBytes = 10000)
        assertTrue(result.contains("Line 41"))
        assertTrue(result.contains("Line 50"))
        assertTrue(result.contains("truncated 40 preceding lines"))
    }

    @Test
    fun `truncate caps byte length when line count is high`() {
        val longLine = "A".repeat(500)
        val input = (1..20).joinToString("\n") { "$it: $longLine" }
        val result = LogTruncator.truncate(input, maxLines = 100, maxBytes = 1000)
        assertTrue(result.length < 1500)
        assertTrue(result.contains("truncated"))
    }
}
