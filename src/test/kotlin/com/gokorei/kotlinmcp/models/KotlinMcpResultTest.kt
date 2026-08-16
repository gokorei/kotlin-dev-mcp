package com.gokorei.kotlinmcp.models

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KotlinMcpResultTest {

    @Test
    fun `success result formats content and metadata correctly`() {
        val success = KotlinMcpResult.Success(
            content = "Kotlin stdlib documentation for List",
            metadata = mapOf("source" to "stdlib", "version" to "2.1.0")
        )

        assertTrue(success.isSuccess)
        assertFalse(success.isError)
        assertEquals("Kotlin stdlib documentation for List", success.content)
        assertEquals("2.1.0", success.metadata["version"])
        assertTrue(success.toFormattedText().contains("Kotlin stdlib documentation for List"))
    }

    @Test
    fun `error result carries structured diagnostic context`() {
        val error = KotlinMcpResult.Error(
            message = "Unresolved reference: foo",
            code = "COMPILER_ERROR",
            details = mapOf("line" to "12", "column" to "5")
        )

        assertFalse(error.isSuccess)
        assertTrue(error.isError)
        assertEquals("Unresolved reference: foo", error.message)
        assertEquals("COMPILER_ERROR", error.code)
        assertEquals("12", error.details["line"])
        assertTrue(error.toFormattedText().contains("COMPILER_ERROR"))
        assertTrue(error.toFormattedText().contains("Unresolved reference: foo"))
    }
}
