package com.gokorei.kotlinmcp.doc

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DocServiceTest {

    private lateinit var docService: DocService

    @BeforeEach
    fun setUp() {
        docService = DefaultDocService()
    }

    @Test
    fun `search returns relevant documentation topics`() {
        val result = docService.execute(
            action = DocAction.SEARCH,
            query = "coroutines"
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Coroutines", ignoreCase = true))
    }

    @Test
    fun `search returns compact TOON index with resource URIs`() {
        val result = docService.execute(DocAction.SEARCH, "coroutines")
        assertTrue(result.isSuccess)
        val text = (result as KotlinMcpResult.Success).content
        assertTrue(text.contains("[search_matches: kind|name|uri]"), "output must contain TOON header: $text")
        assertTrue(text.contains("kotlin://docs/"), "output must contain resource URIs: $text")
    }

    @Test
    fun `lookup_symbol returns stdlib symbol details for List`() {
        val result = docService.execute(
            action = DocAction.LOOKUP_SYMBOL,
            query = "kotlin.collections.List"
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("List"), "expected List in content: ${success.content}")
    }

    @Test
    fun `lookup_symbol returns structured error for unknown symbol`() {
        val result = docService.execute(
            action = DocAction.LOOKUP_SYMBOL,
            query = "non.existent.UnknownClass"
        )

        assertTrue(result.isError)
        val error = result as KotlinMcpResult.Error
        assertEquals("SYMBOL_NOT_FOUND", error.code)
    }

    @Test
    fun `explain_feature returns detailed guide for contracts`() {
        val result = docService.execute(
            action = DocAction.EXPLAIN_FEATURE,
            query = "contracts"
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Contract", ignoreCase = true))
    }

    @Test
    fun `search covers serialization, io, and testing topics`() {
        val serialization = docService.execute(DocAction.SEARCH, "serialization") as KotlinMcpResult.Success
        assertTrue(serialization.content.contains("serialization", ignoreCase = true), serialization.content)

        val json = docService.execute(DocAction.LOOKUP_SYMBOL, "kotlinx.serialization.json.Json") as KotlinMcpResult.Success
        assertTrue(json.content.contains("Json"), json.content)

        val file = docService.execute(DocAction.LOOKUP_SYMBOL, "readText") as KotlinMcpResult.Success
        assertTrue(file.content.contains("readText"), file.content)

        val testing = docService.execute(DocAction.EXPLAIN_FEATURE, "testing") as KotlinMcpResult.Success
        assertTrue(testing.content.contains("kotlin.test", ignoreCase = true), testing.content)

        val gradleDsl = docService.execute(DocAction.EXPLAIN_FEATURE, "gradle kotlin dsl") as KotlinMcpResult.Success
        assertTrue(gradleDsl.content.contains("build.gradle.kts"), gradleDsl.content)
    }

    @Test
    fun `registerDynamicSymbol allows resolving newly registered symbols dynamically`() {
        docService.registerDynamicSymbol("kotlin.collections.Sequence", "# `interface Sequence<out T>`\nA lazy collection stream.")

        val result = docService.execute(
            action = DocAction.LOOKUP_SYMBOL,
            query = "kotlin.collections.Sequence"
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("interface Sequence<out T>"))

        val doc = docService.docFor("symbol", "kotlin.collections.Sequence")
        assertNotNull(doc)
        assertTrue(doc!!.contains("lazy collection stream"))
    }

    @Test
    fun `registerDynamicFeature allows resolving newly registered language features dynamically`() {
        docService.registerDynamicFeature("value classes", "# Value Classes\nInline value wrappers with single property.")

        val result = docService.execute(
            action = DocAction.EXPLAIN_FEATURE,
            query = "value classes"
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Inline value wrappers"))

        val doc = docService.docFor("feature", "value classes")
        assertNotNull(doc)
        assertTrue(doc!!.contains("Inline value wrappers"))
    }

    @Test
    fun `registerNamespace makes symbols under the prefix resolvable`() {
        docService.registerDynamicNamespace("com.example.utils", "# Shared Utils\nString helpers for the example project.")

        val result = docService.execute(
            action = DocAction.LOOKUP_SYMBOL,
            query = "com.example.utils.toSlug"
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("com.example.utils"), "expected namespace hit in: ${success.content}")
    }

    @Test
    fun `registerNamespace persists across service instances`() {
        val persistencePath = java.nio.file.Files.createTempDirectory("kmcp-docs").resolve("registered-docs.json")
        val first = DefaultDocService()
        first.registerDynamicNamespace("com.acme.core", "# Acme Core\nCore utilities.")

        val second = first
        val result = second.execute(
            action = DocAction.LOOKUP_SYMBOL,
            query = "com.acme.core.helper"
        )

        assertTrue(result.isSuccess, "expected persisted lookup, got: ${result.toFormattedText()}")
    }

    @Test
    fun `synced stdlib index seeds at least 200 symbols into the docs DB`() {
        val service = DefaultDocService()
        assertTrue(service.symbolDocs.size >= 200, "expected >=200 symbols from packaged index, got ${service.symbolDocs.size}")
    }

    @Test
    fun `synced index entries are individually resolvable`() {
        val service = DefaultDocService()
        val result = service.execute(DocAction.LOOKUP_SYMBOL, "kotlin.collections.AbstractCollection")
        assertTrue(result.isSuccess, "expected synced symbol lookup, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("skeletal implementation"), "expected synced summary in: $content")
    }

    @Test
    fun `user-registered docs take precedence over synced index entries`() {
        val service = DefaultDocService()
        val syncedName = service.symbolDocs.keys.first { it.startsWith("kotlin.") }
        service.registerDynamicSymbol(syncedName, "# Override\nUser-picked content.")

        val second = service
        val result = second.execute(DocAction.LOOKUP_SYMBOL, syncedName)
        assertTrue(result.isSuccess, "expected lookup, got: ${result.toFormattedText()}")
        assertTrue(
            (result as KotlinMcpResult.Success).content.contains("User-picked content"),
            "user-registered doc must win over synced entry"
        )
    }

    @Test
    fun `Phase C library doc entries are present`() {
        val service = DefaultDocService()
        listOf("Either", "Raise", "validNel", "kotlinx.datetime.Instant", "runTest", "MainDispatcherRule", "Turbine.test", "mockk", "every", "verify", "Ktor/Routing", "Ktor/ContentNegotiation").forEach { sym ->
            val r = service.execute(DocAction.LOOKUP_SYMBOL, sym)
            assertTrue(r.isSuccess, "expected Phase C entry '$sym' resolvable, got: ${r.toFormattedText()}")
        }
    }

    @Test
    fun `appliesTo library entry is hidden when library not on caller classpath`() {
        val service = DefaultDocService()
        // No classpath → everything visible.
        val visible = service.execute(DocAction.LOOKUP_SYMBOL, "Either")
        assertTrue(visible.isSuccess, "expected Either visible with empty classpath")

        // Classpath without arrow → Either filtered out.
        val filtered = service.execute(
            DocAction.LOOKUP_SYMBOL, "Either", null,
            classpath = listOf("/libs/kotlin-stdlib.jar", "/libs/kotlinx-coroutines-core.jar")
        )
        assertTrue(filtered.isError, "expected Either hidden when arrow not on classpath: ${filtered.toFormattedText()}")
        assertTrue((filtered as KotlinMcpResult.Error).details["query"] == "Either", "expected query in details")

        // Classpath containing arrow → Either visible again.
        val back = service.execute(
            DocAction.LOOKUP_SYMBOL, "Either", null,
            classpath = listOf("/libs/arrow-core-2.0.1.jar")
        )
        assertTrue(back.isSuccess, "expected Either visible when arrow on classpath")
    }

    @Test
    fun `classpath filtering is per-call and does not bleed across calls`() {
        val service = DefaultDocService()
        // Call with arrow on classpath: visible.
        val withArrow = service.execute(
            DocAction.LOOKUP_SYMBOL, "Either", null,
            classpath = listOf("/libs/arrow-core-2.0.1.jar")
        )
        assertTrue(withArrow.isSuccess, "expected Either visible with arrow on the call classpath")

        // A subsequent lookup WITHOUT a classpath must NOT inherit the earlier one.
        val after = service.execute(DocAction.LOOKUP_SYMBOL, "Either")
        assertTrue(after.isSuccess, "per-call classpath must not bleed; Either should be visible again: ${after.toFormattedText()}")

        // And a subsequent lookup with a non-arrow classpath must filter again.
        val withoutArrow = service.execute(
            DocAction.LOOKUP_SYMBOL, "Either", null,
            classpath = listOf("/libs/kotlin-stdlib.jar")
        )
        assertTrue(withoutArrow.isError, "explicit non-arrow classpath must filter Either out")
    }

    @Test
    fun `appliesTo filtering applies to search results`() {
        val service = DefaultDocService()
        val search = service.execute(
            DocAction.SEARCH, "validNel", null,
            classpath = listOf("/libs/kotlin-stdlib.jar")
        ) as KotlinMcpResult.Success
        assertTrue(search.content.contains("No documentation entries matched"), "expected validNel filtered from search: ${search.content}")
    }

    @Test
    fun `docFor resolves underscore-containing symbol names verbatim`() {
        val service = DefaultDocService()
        service.registerDynamicSymbol("MY_CONSTANT", "# MY_CONSTANT\nA constant with underscores.")
        val direct = service.docFor("symbol", "MY_CONSTANT")
        assertTrue(direct != null && direct.contains("MY_CONSTANT"), "exact underscore name must resolve: $direct")
    }

    @Test
    fun `registerDynamicSymbol invalidates the doc cache`() {
        val service = DefaultDocService()
        val initial = service.docFor("symbol", "kotlin.collections.List")
        assertNotNull(initial)

        service.registerDynamicSymbol("kotlin.collections.List", "# Custom List Docs")
        val updated = service.docFor("symbol", "kotlin.collections.List")
        assertTrue(updated != null && updated.contains("Custom List Docs"), "cache must be invalidated on dynamic registration: $updated")
    }

    @Test
    fun `framework filtering boosts framework specific doc search entries`() {
        val service = DefaultDocService()
        val result = service.execute(DocAction.SEARCH, query = "routing", preset = null, classpath = listOf("/libs/ktor-server-core.jar"))
        assertTrue(result is KotlinMcpResult.Success)
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("Ktor") || content.contains("routing"), "expected Ktor routing in search: $content")
    }

    @Test
    fun `PersistedDocs handles unversioned JSON migration gracefully`() {
        val persistencePath = java.nio.file.Files.createTempDirectory("kmcp-docs-ver").resolve("registered-docs.json")
        // Write unversioned JSON
        persistencePath.toFile().writeText("""{"symbols":{"legacy.Symbol":"# Legacy"},"features":{},"namespaces":{}}""")

        val service = DefaultDocService(persistencePath.toString())
        val result = service.execute(DocAction.LOOKUP_SYMBOL, "legacy.Symbol")
        assertTrue(result.isSuccess, "unversioned JSON should migrate and resolve legacy symbols: ${result.toFormattedText()}")
    }
}


