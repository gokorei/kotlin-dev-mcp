package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DiagnosticServiceTest {

    private lateinit var diagnosticService: DiagnosticService

    @BeforeEach
    fun setUp() {
        diagnosticService = DefaultDiagnosticService()
    }

    @Test
    fun `check_snippet reports clean compilation for valid snippet`() {
        val validCode = """
            fun add(a: Int, b: Int): Int = a + b
        """.trimIndent()

        val result = diagnosticService.execute(
            action = DiagnosticAction.CHECK_SNIPPET,
            code = validCode
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Compilation succeeded", ignoreCase = true))
    }

    @Test
    fun `check_snippet catches syntax or type errors in snippet`() {
        val invalidCode = """
            fun add(a: Int, b: Int): Int = "not an int"
        """.trimIndent()

        val result = diagnosticService.execute(
            action = DiagnosticAction.CHECK_SNIPPET,
            code = invalidCode
        )

        assertTrue(result.isError, "expected compiler error, got: ${result.toFormattedText()}")
        val text = result.toFormattedText()
        assertTrue(text.contains("Type mismatch") || text.contains("COMPILER_ERROR") || text.contains("String"))
    }

    @Test
    fun `check_snippet does not leak kmcp temp directories`() {
        val tmp = java.io.File(System.getProperty("java.io.tmpdir"))
        fun countKmcp(): Int = tmp.listFiles { f -> f.name.startsWith("kmcp-compile") }?.size ?: 0

        val before = countKmcp()
        diagnosticService.execute(action = DiagnosticAction.CHECK_SNIPPET, code = "fun f(): Int = 1")
        diagnosticService.execute(action = DiagnosticAction.CHECK_SNIPPET, code = "fun g(): Int = \"bad\"")
        val after = countKmcp()

        assertTrue(after <= before, "expected no kmcp-compile temp-dir growth, before=$before after=$after")
    }

    @Test
    fun `check_snippet sets requireAnotherCall on compile errors and clears it on success`() {
        val invalid = diagnosticService.execute(
            action = DiagnosticAction.CHECK_SNIPPET,
            code = """fun add(a: Int, b: Int): Int = "not an int"""".trimIndent()
        )
        assertTrue(invalid.isError)
        val error = invalid as KotlinMcpResult.Error
        assertTrue(error.requireAnotherCall, "expected requireAnotherCall on compiler error")
        assertTrue(error.toFormattedText().contains("requireAnotherCall"), "loop hint should be visible in output")

        val valid = diagnosticService.execute(
            action = DiagnosticAction.CHECK_SNIPPET,
            code = "fun add(a: Int, b: Int): Int = a + b"
        )
        assertTrue(valid.isSuccess)
        assertFalse((valid as KotlinMcpResult.Success).requireAnotherCall, "clean compile should not require another call")
    }

    @Test
    fun `check_snippet formats compiler errors using TOON format`() {
        val invalidCode = "fun main() { val x: Int = \"string\" }"
        val result = diagnosticService.execute(DiagnosticAction.CHECK_SNIPPET, invalidCode)
        assertTrue(result.isError)
        val text = result.toFormattedText()
        assertTrue(text.contains("[diagnostics: line|col|msg]"), "output must contain TOON header: $text")
    }

    @Test
    fun `check_snippet surfaces actionable guidance for unresolved references`() {
        val unresolvedCode = "fun main() { val x = UnresolvedCustomType() }"
        val result = diagnosticService.execute(DiagnosticAction.CHECK_SNIPPET, unresolvedCode)
        assertTrue(result.isError)
        val text = result.toFormattedText()
        assertTrue(text.contains("Unresolved reference") || text.contains("UNRESOLVED"), "should catch unresolved reference: $text")
        assertTrue(text.contains("classpath") || text.contains("projectPath"), "should suggest providing classpath or projectPath: $text")
    }

    @Test
    fun `detectProjectClasspath discovers subproject build classes`() {
        val tempDir = java.nio.file.Files.createTempDirectory("test-proj").toFile()
        try {
            val subMain = java.io.File(tempDir, "submodule/build/classes/kotlin/main").apply { mkdirs() }
            val detected = SnippetCompiler.detectProjectClasspath(tempDir.absolutePath)
            assertTrue(detected.contains(subMain.absolutePath), "expected subproject build dir to be detected: $detected")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
