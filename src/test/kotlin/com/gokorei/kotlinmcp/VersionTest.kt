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
            Version.CURRENT.matches(Regex("""^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$""")),
            "Expected semantic version format, got: ${Version.CURRENT}"
        )
    }
}
