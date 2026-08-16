package com.gokorei.kotlinmcp.server

import com.gokorei.kotlinmcp.models.KotlinMcpResult

import com.gokorei.kotlinmcp.analysis.*
import com.gokorei.kotlinmcp.doc.*
import com.gokorei.kotlinmcp.execution.*
import com.gokorei.kotlinmcp.linting.*
import com.gokorei.kotlinmcp.lsp.*
import com.gokorei.kotlinmcp.project.*
import com.gokorei.kotlinmcp.refactoring.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KotlinMcpServerTest {

    private lateinit var server: KotlinMcpServer

    @BeforeEach
    fun setUp() {
        server = KotlinMcpServer(
            docService = DefaultDocService(),
            codeAnalysisService = DefaultCodeAnalysisService(),
            diagnosticService = DefaultDiagnosticService(),
            projectService = DefaultProjectService(),
            refactoringService = DefaultRefactoringService()
        )
    }

    private fun assertSuccess(result: KotlinMcpResult, vararg contains: String) {
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        for (needle in contains) {
            assertTrue(
                result.toFormattedText().contains(needle, ignoreCase = true),
                "expected '${needle}' in: ${result.toFormattedText()}"
            )
        }
    }

    private fun assertError(result: KotlinMcpResult, vararg contains: String) {
        assertTrue(result.isError, "expected error, got: ${result.toFormattedText()}")
        for (needle in contains) {
            assertTrue(
                result.toFormattedText().contains(needle, ignoreCase = true),
                "expected '${needle}' in: ${result.toFormattedText()}"
            )
        }
    }

    @Test
    fun `docsSearch returns matches for coroutines`() {
        assertSuccess(server.docsSearch("coroutines"), "coroutines")
    }

    @Test
    fun `docsLookupSymbol finds a stdlib symbol`() {
        assertSuccess(server.docsLookupSymbol("mapNotNull"), "mapNotNull")
    }

    @Test
    fun `docsLookupSymbol returns signature-only in compact mode`() {
        val full = server.docsLookupSymbol("mapNotNull")
        val compact = server.docsLookupSymbol("mapNotNull", preset = "compact")
        assertTrue(compact.isSuccess)
        assertTrue(compact.toFormattedText().length < full.toFormattedText().length, "compact output must consume fewer tokens")
    }

    @Test
    fun `docsExplainFeature explains a language feature`() {
        assertSuccess(server.docsExplainFeature("sealed interface"), "sealed")
    }

    @Test
    fun `docsLookupSymbol returns structured error for unknown symbol`() {
        assertError(server.docsLookupSymbol("totallyUnknownSymbol123"), "SYMBOL_NOT_FOUND")
    }

    @Test
    fun `codeInspectSymbol lists declared elements`() {
        assertSuccess(server.codeInspectSymbol("data class User(val id: Int)"), "User")
    }

    @Test
    fun `codeAnalyzeNullability flags unsafe dereference`() {
        val result = server.codeAnalyzeNullability("val x: String? = null\nval y = x.length")
        assertTrue(result.toFormattedText().contains("Unsafe dereference", ignoreCase = true))
    }

    @Test
    fun `codeExplainCoroutines flags blocking sleep`() {
        assertSuccess(server.codeExplainCoroutines("fun f() { Thread.sleep(1000) }"), "sleep")
    }

    @Test
    fun `checkSnippet succeeds on clean code`() {
        assertSuccess(server.checkSnippet("fun hello() = 42"))
    }

    @Test
    fun `checkSnippet reports compiler error`() {
        assertError(server.checkSnippet("fun hello() = 42 +"), "error")
    }

    @Test
    fun `runProjectLayout returns error for invalid path`() {
        assertError(server.runProjectLayout("/no/such/path/xyz"), "PROJECT_NOT_FOUND")
    }

    @Test
    fun `projectInspectStructure detects plugins`() {
        assertSuccess(server.projectInspectStructure("plugins { kotlin(\"jvm\") version \"1.9.0\" }"), "jvm")
    }

    @Test
    fun `projectListKmpTargets detects jvm target`() {
        assertSuccess(server.projectListKmpTargets("kotlin { jvm() }"), "jvm")
    }

    @Test
    fun `projectAnalyzeDependencies extracts declared deps`() {
        assertSuccess(
            server.projectAnalyzeDependencies("dependencies { implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.0\") }"),
            "kotlinx-coroutines-core"
        )
    }

    @Test
    fun `refactorJavaToKotlin converts a simple class`() {
        val java = "public class Person { private String name; public String getName() { return name; } }"
        assertSuccess(server.refactorJavaToKotlin(java), "data class")
    }

    @Test
    fun `refactorImperativeToFunctional maps an accumulation loop`() {
        val code = "val doubled = mutableListOf<Int>()\nfor (x in xs) { doubled.add(x * 2) }"
        assertSuccess(server.refactorImperativeToFunctional(code), "map")
    }

    @Test
    fun `refactorImperativeToFunctional returns UNSUPPORTED_PATTERN for unrecognized code`() {
        assertError(server.refactorImperativeToFunctional("when (x) { 1 -> 1 else -> 2 }"), "UNSUPPORTED_PATTERN")
    }

    @Test
    fun `refactorSuggestIdioms suggests runCatching`() {
        val code = "val result = try { risky() } catch (e: Exception) { null }"
        assertSuccess(server.refactorSuggestIdioms(code), "runCatching")
    }

    @Test
    fun `parseClasspath handles both JSON array and string inputs`() {
        val jsonArray = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("/lib/a.jar"))
            add(kotlinx.serialization.json.JsonPrimitive("/lib/b.jar"))
        }
        val parsedFromArray = ToolRegistrar.parseClasspathElement(jsonArray)
        assertEquals(listOf("/lib/a.jar", "/lib/b.jar"), parsedFromArray)

        val jsonString = kotlinx.serialization.json.JsonPrimitive("/lib/c.jar${java.io.File.pathSeparator}/lib/d.jar")
        val parsedFromString = ToolRegistrar.parseClasspathElement(jsonString)
        assertEquals(listOf("/lib/c.jar", "/lib/d.jar"), parsedFromString)
    }
}
