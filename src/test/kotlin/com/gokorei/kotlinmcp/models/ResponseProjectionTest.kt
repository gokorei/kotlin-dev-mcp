package com.gokorei.kotlinmcp.models

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ResponseProjectionTest {

    @Test
    fun `ResponsePreset fromString handles case-insensitive aliases`() {
        assertEquals(ResponsePreset.COMPACT, ResponsePreset.fromString("compact"))
        assertEquals(ResponsePreset.COMPACT, ResponsePreset.fromString("COMPACT"))
        assertEquals(ResponsePreset.SUMMARY, ResponsePreset.fromString("summary"))
        assertEquals(ResponsePreset.FULL, ResponsePreset.fromString("full"))
        assertEquals(ResponsePreset.FULL, ResponsePreset.fromString(null))
        assertEquals(ResponsePreset.FULL, ResponsePreset.fromString("unknown"))
    }

    @Test
    fun `ProjectionFilter prunes metadata keys when fields filter is provided`() {
        val original = KotlinMcpResult.Success(
            content = "Sample content with details",
            metadata = mapOf(
                "symbol" to "MyClass",
                "kind" to "class",
                "internalAstOffset" to "120..450",
                "verboseDebugInfo" to "dump"
            )
        )

        val projection = ResponseProjection(
            preset = ResponsePreset.FULL,
            fields = setOf("symbol", "kind")
        )

        val filtered = ProjectionFilter.apply(original, projection)
        assertTrue(filtered.isSuccess)
        val success = filtered as KotlinMcpResult.Success
        assertEquals(2, success.metadata.size)
        assertTrue(success.metadata.containsKey("symbol"))
        assertTrue(success.metadata.containsKey("kind"))
        assertFalse(success.metadata.containsKey("internalAstOffset"))
    }

    @Test
    fun `ProjectionFilter compact preset minimizes verbose content`() {
        val original = KotlinMcpResult.Success(
            content = "Detailed header\n--- Internal AST Dump ---\nRaw AST metadata\n--- Results ---\nKey summary here",
            metadata = mapOf("raw" to "large_dump", "count" to "1")
        )

        val projection = ResponseProjection(preset = ResponsePreset.COMPACT)
        val filtered = ProjectionFilter.apply(original, projection)

        assertTrue(filtered.isSuccess)
        val success = filtered as KotlinMcpResult.Success
        assertFalse(success.metadata.containsKey("raw"), "compact preset should prune verbose raw dumps")
        assertTrue(success.metadata.containsKey("count"))
    }

    @Test
    fun `ProjectionFilter preserves error codes and message while trimming debug metadata`() {
        val error = KotlinMcpResult.Error(
            message = "Syntax error in file",
            code = "SYNTAX_ERROR",
            details = mapOf("stacktrace" to "verbose stack", "file" to "Main.kt")
        )

        val projection = ResponseProjection(
            preset = ResponsePreset.COMPACT,
            fields = setOf("file")
        )

        val filtered = ProjectionFilter.apply(error, projection)
        assertTrue(filtered.isError)
        val err = filtered as KotlinMcpResult.Error
        assertEquals("SYNTAX_ERROR", err.code)
        assertEquals("Syntax error in file", err.message)
        assertEquals(mapOf("file" to "Main.kt"), err.details)
    }
}
