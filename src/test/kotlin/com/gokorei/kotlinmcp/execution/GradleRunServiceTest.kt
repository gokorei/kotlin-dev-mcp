package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GradleRunServiceTest {

    private val service: GradleRunService = DefaultGradleRunService()

    @TempDir
    lateinit var tempDir: Path

    private fun fakeProject(script: String): File {
        val wrapper = tempDir.resolve("gradlew").toFile()
        wrapper.writeText(script)
        wrapper.setExecutable(true)
        return tempDir.toFile()
    }

    @Test
    fun `returns PROJECT_NOT_FOUND for a missing path`() {
        val result = service.execute("/nonexistent/kmcp-no-such-dir", "build", 5000)
        assertTrue(result.isError)
        assertTrue(result.toFormattedText().contains("PROJECT_NOT_FOUND"), result.toFormattedText())
    }

    @Test
    fun `returns EMPTY_TASK for a blank task`() {
        val dir = fakeProject("#!/bin/sh\nexit 0\n")
        val result = service.execute(dir.absolutePath, "   ", 5000)
        assertTrue(result.isError)
        assertTrue(result.toFormattedText().contains("EMPTY_TASK"), result.toFormattedText())
    }

    @Test
    fun `successful task returns exit 0 and output`() {
        val dir = fakeProject("#!/bin/sh\necho BUILD SUCCESSFUL\nexit 0\n")
        val result = service.execute(dir.absolutePath, "build", 15000)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("BUILD SUCCESSFUL"), success.content)
        assertEquals("0", success.metadata["exitCode"])
    }

    @Test
    fun `failed task returns GRADLE_BUILD_FAILED with requireAnotherCall`() {
        val dir = fakeProject("#!/bin/sh\necho 'e: some/file.kt:1:1: error: something broke'\nexit 1\n")
        val result = service.execute(dir.absolutePath, "build", 15000)
        assertTrue(result.isError, "expected error, got: ${result.toFormattedText()}")
        val error = result as KotlinMcpResult.Error
        assertEquals("GRADLE_BUILD_FAILED", error.code)
        assertTrue(error.requireAnotherCall, "expected loop hint on build failure")
    }

    @Test
    fun `extractDiagnostics filters out daemon setup lines and UP-TO-DATE banners`() {
        val rawLog = """
            To honour the JVM settings for this build a single-use Daemon process will be forked.
            Daemon will be stopped at the end of the build
            > Task :checkKotlinGradlePluginConfigurationErrors SKIPPED
            > Task :compileKotlin FAILED
            e: src/main/Snippet.kt:10:5 Unresolved reference: foo
            BUILD FAILED in 2s
        """.trimIndent()
        val dir = fakeProject("#!/bin/sh\ncat << 'EOF'\n$rawLog\nEOF\nexit 1\n")
        val result = service.execute(dir.absolutePath, "build", 15000)
        assertTrue(result.isError)
        val text = result.toFormattedText()
        assertTrue(text.contains("Unresolved reference: foo"))
        assertFalse(text.contains("To honour the JVM settings"), "Daemon boilerplate must be stripped: $text")
    }

    @Test
    fun `rejects --init-script RCE payload with VALIDATION_ERROR`() {
        val dir = fakeProject("#!/bin/sh\necho ran\nexit 0\n")
        val result = service.execute(dir.absolutePath, "build --init-script /tmp/payload.gradle", 15000)
        assertTrue(result.isError, "expected rejection, got: ${result.toFormattedText()}")
        val error = result as KotlinMcpResult.Error
        assertEquals("VALIDATION_ERROR", error.code)
        assertTrue(error.details["rejectedToken"] == "--init-script", "should identify rejected token: ${error.details}")
        assertTrue(result.toFormattedText().contains("VALIDATION_ERROR"), "surface the validation code")
    }

    @Test
    fun `rejects short form -I init script flag`() {
        val dir = fakeProject("#!/bin/sh\necho ran\nexit 0\n")
        val result = service.execute(dir.absolutePath, "test -I /tmp/payload.gradle", 15000)
        assertTrue(result.isError)
        assertEquals("VALIDATION_ERROR", (result as KotlinMcpResult.Error).code)
    }

    @Test
    fun `rejects project-dir p and system property flags`() {
        val dir = fakeProject("#!/bin/sh\necho ran\nexit 0\n")
        for (task in listOf("-p /some/path", "-Dorg.gradle.jvmargs=-Xmx1g", "-Pfoo=bar", "--build-file /tmp/x.gradle", "--settings-file /tmp/s.gradle", "--project-cache-dir /tmp/c")) {
            val result = service.execute(dir.absolutePath, task, 15000)
            assertTrue(result.isError, "expected rejection for '$task', got: ${result.toFormattedText()}")
            assertEquals("VALIDATION_ERROR", (result as KotlinMcpResult.Error).code, "task '$task' must be rejected")
        }
    }

    @Test
    fun `accepts test task with quoted tests filter`() {
        val dir = fakeProject("#!/bin/sh\necho BUILD SUCCESSFUL\nexit 0\n")
        val result = service.execute(dir.absolutePath, "test --tests 'com.example.FooTest'", 15000)
        assertTrue(result.isSuccess, "expected success for allowed filter, got: ${result.toFormattedText()}")
    }
}
