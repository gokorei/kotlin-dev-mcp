package com.gokorei.kotlinmcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionTest {

    @Test
    fun `Version properties provide server identity and valid semver`() {
        assertEquals("kotlin-mcp", Version.NAME)
        assertNotNull(Version.CURRENT)
        assertTrue(Version.CURRENT.isNotEmpty())
        assertTrue(
            Version.CURRENT.matches(Regex("""^\d+\.\d+\.\d+(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""")),
            "Expected semantic version format, got: ${Version.CURRENT}"
        )
    }

    @Test
    fun `FALLBACK_VERSION uses non-release development version identifier`() {
        assertEquals("0.0.0-dev", Version.FALLBACK_VERSION)
    }
}
