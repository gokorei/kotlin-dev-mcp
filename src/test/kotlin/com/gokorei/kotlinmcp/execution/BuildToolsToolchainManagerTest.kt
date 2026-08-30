@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
package com.gokorei.kotlinmcp.execution

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BuildToolsToolchainManagerTest {

    @Test
    fun `toolchain service initializes and caches compilation service`() {
        val manager = DefaultBuildToolsToolchainManager()
        val service1 = manager.getCompilationService()
        assertNotNull(service1, "CompilationService should not be null")
        val service2 = manager.getCompilationService()
        assertSame(service1, service2, "CompilationService should be cached across calls")
    }

    @Test
    fun `withSession provides scoped execution session`() {
        val manager = DefaultBuildToolsToolchainManager()
        var sessionExecuted = false
        val result = manager.withSession { session ->
            assertNotNull(session, "Session should be provided")
            assertNotNull(session.compilationService, "CompilationService should be accessible in session")
            sessionExecuted = true
            "success-session"
        }
        assertTrue(sessionExecuted, "Session block should be executed")
        assertEquals("success-session", result)
    }

    @Test
    fun `toolchain manager can reset and release resources`() {
        val manager = DefaultBuildToolsToolchainManager()
        val service1 = manager.getCompilationService()
        assertNotNull(service1)
        manager.close()
        // After close, a new service is initialized on demand
        val service2 = manager.getCompilationService()
        assertNotNull(service2)
        assertNotSame(service1, service2, "Closing manager should invalidate the cached instance")
        manager.close()
    }
}
