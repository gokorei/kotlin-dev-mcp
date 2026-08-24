package com.gokorei.kotlinmcp.execution

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class JavaResolverTest {

    private val resolver: JavaResolver = DefaultJavaResolver()

    @Test
    fun `validateJvmArgs detects all forbidden agent and module attack vectors case-insensitively`() {
        val forbidden = listOf(
            "-JAVAAGENT:/path/agent.jar",
            "-AgentLib:jdwp=transport=dt_socket",
            "-agentpath:/path/lib.so",
            "-Xbootclasspath/a:/path",
            "--ADD-OPENS=java.base/java.lang=ALL-UNNAMED",
            "--add-exports=java.base/sun.security.util=ALL-UNNAMED",
            "--add-reads=m1=m2",
            "--patch-module=java.base=patch.jar",
            "--Allow-Attach-Self",
            "-Djdk.attach.allowAttachSelf=true"
        )

        val violations = resolver.validateJvmArgs(forbidden)
        assertEquals(forbidden.size, violations.size, "All forbidden flags must be detected")
        forbidden.forEach { flag ->
            assertTrue(violations.contains(flag), "Violations must contain $flag")
        }
    }

    @Test
    fun `validateJvmArgs allows standard safe JVM flags`() {
        val safeArgs = listOf("-Xmx512m", "-Xms128m", "-Dfile.encoding=UTF-8", "-ea")
        val violations = resolver.validateJvmArgs(safeArgs)
        assertTrue(violations.isEmpty(), "Safe args must produce no violations")
    }

    @Test
    fun `resolve handles explicit valid path and non-existent binary`() {
        val validJava = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
        val resolved = resolver.resolve(validJava)
        assertNotNull(resolved, "Valid explicit java path must resolve")

        val nonExistent = "/non/existent/path/to/java_dummy"
        assertNull(resolver.resolve(nonExistent), "Non-existent path must return null")
    }

    @Test
    fun `resolve falls back to java home binary when input is null or blank`() {
        val expectedHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME")

        val nullResolved = resolver.resolve(null)
        assertNotNull(nullResolved, "null input must resolve fallback java binary")
        assertTrue(nullResolved!!.exists(), "Fallback binary must exist")
        if (!expectedHome.isNullOrBlank()) {
            assertTrue(nullResolved.absolutePath.startsWith(expectedHome), "Resolved binary must originate from java.home")
        }

        val blankResolved = resolver.resolve("   ")
        assertNotNull(blankResolved, "Blank input must resolve fallback java binary")
        assertTrue(blankResolved!!.exists(), "Fallback binary must exist")
        if (!expectedHome.isNullOrBlank()) {
            assertTrue(blankResolved.absolutePath.startsWith(expectedHome), "Resolved binary must originate from java.home")
        }
    }
}
