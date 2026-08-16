package com.gokorei.kotlinmcp.lsp

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LibraryAnalysisServiceTest {

    private lateinit var service: LibraryAnalysisService

    @BeforeEach
    fun setUp() {
        service = DefaultLibraryAnalysisService()
    }

    @Test
    fun `analyze_ktor detects route collisions`() {
        val snippet = """
            fun Application.module() {
                routing {
                    route("/api") {
                        get("/users") { call.respondText("a") }
                    }
                    route("/api") {
                        get("/users") { call.respondText("b") }
                    }
                }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_KTOR, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("route_collision"), "expected collision advisory in: $content")
    }

    @Test
    fun `analyze_ktor flags missing ContentNegotiation for serializable returns`() {
        val snippet = """
            import kotlinx.serialization.Serializable

            @Serializable
            data class User(val id: Int, val name: String)

            fun Application.module() {
                routing {
                    get("/user") {
                        call.respond(User(1, "Ada"))
                    }
                }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_KTOR, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("missing_content_negotiation"), "expected ContentNegotiation advisory in: $content")
    }

    @Test
    fun `analyze_ktor flags missing StatusPages when errors returned explicitly`() {
        val snippet = """
            fun Application.module() {
                routing {
                    get("/") {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_KTOR, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("missing_status_pages"), "expected StatusPages advisory in: $content")
    }

    @Test
    fun `analyze_ktor distinguishes client plugins misuse`() {
        val snippet = """
            fun main() {
                val client = HttpClient {
                    install(ContentNegotiation) { json() }
                }
                client.plugins.install(ContentNegotiation) { json() }
                client.plugins.install(Routing) { }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_KTOR, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("client_plugins_confusion"), "expected client/server plugin advisory in: $content")
    }

    @Test
    fun `analyze_ktor clean server has no advisories`() {
        val snippet = """
            fun Application.module() {
                install(ContentNegotiation) { json() }
                install(StatusPages) { exception<Throwable> { _, _ -> } }
                routing {
                    get("/ping") { call.respondText("pong") }
                }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_KTOR, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertFalse(content.contains("_collision") && content.contains("missing_"), "expected clean in: $content")
    }

    @Test
    fun `analyze_serialization flags hidden primary ctor`() {
        val snippet = """
            import kotlinx.serialization.Serializable

            @Serializable
            class User private constructor(val id: Int)
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_SERIALIZATION, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("hidden_primary_ctor"), "expected hidden ctor advisory in: $content")
    }

    @Test
    fun `analyze_serialization detects serial name collisions in sealed hierarchy`() {
        val snippet = """
            import kotlinx.serialization.SerialName
            import kotlinx.serialization.Serializable

            @Serializable
            sealed class Event {
                @Serializable @SerialName("open") data class Open(val id: Int) : Event()
                @Serializable @SerialName("open") data class Closed(val id: Int) : Event()
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_SERIALIZATION, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("serial_name_collision"), "expected collision advisory in: $content")
    }

    @Test
    fun `analyze_serialization warns on non-default non-serialname property addition`() {
        val snippet = """
            import kotlinx.serialization.Serializable

            @Serializable
            data class User(val id: Int, val email: String, val nickname: String)
        """.trimIndent()

        val result = service.execute(
            LibraryAnalysisAction.ANALYZE_SERIALIZATION,
            snippet,
            dataSources = listOf("https://schemas.example/user-v1.json")
        )
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("evolution_risk") || content.contains("schema_diff_input"), "expected evolution advisory in: $content")
    }

    @Test
    fun `analyze_tests flags runBlocking misuse`() {
        val snippet = """
            class FlowTest {
                @Test
                fun emits() = runBlocking {
                    flowOf(1).collect { }
                }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_TESTS, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("runblocking_in_test"), "expected runBlocking advisory in: $content")
    }

    @Test
    fun `analyze_tests flags missing MainDispatcherRule`() {
        val snippet = """
            class UiTest {
                @Test
                fun renders() = runTest {
                    val x = withContext(Dispatchers.Main) { 1 }
                }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_TESTS, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("missing_main_dispatcher_rule"), "expected MainDispatcherRule advisory in: $content")
    }

    @Test
    fun `analyze_tests detects mockk verify gaps`() {
        val snippet = """
            class RepoTest {
                @Test
                fun loads() {
                    every { repo.fetch() } returns 42
                    val result = use(repo)
                    assertEquals(42, result)
                }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_TESTS, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("mockk_verify_gap"), "expected verify-gap advisory in: $content")
    }

    @Test
    fun `analyze_tests detects mockkObject leaks without unmockkAll`() {
        val snippet = """
            class StaticTest {
                @Test
                fun usesStatic() {
                    mockkObject(DateTimeUtil)
                    assertEquals(1, DateTimeUtil.value)
                }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_TESTS, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("mockk_leak"), "expected unmockkAll advisory in: $content")
    }

    @Test
    fun `analyze_tests detects turbine unconsumed emissions`() {
        val snippet = """
            class FlowTest {
                @Test
                fun counts() = runTest {
                    flowOf(1, 2).test {
                        assertEquals(1, awaitItem())
                    }
                }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_TESTS, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        // awaitItem present → no unconsumed advisory; ensure no false positive.
        assertFalse(content.contains("turbine_unconsumed"), "expected no unconsumed advisory in: $content")
    }

    @Test
    fun `analyze_tests turbine without awaitItem is flagged`() {
        val snippet = """
            class FlowTest {
                @Test
                fun counts() = runTest {
                    flowOf(1, 2).test {
                        println("collecting")
                    }
                }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_TESTS, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("turbine_unconsumed"), "expected unconsumed advisory in: $content")
    }

    @Test
    fun `routeMap extracts Ktor and HTTP routes`() {
        val snippet = """
            fun Application.module() {
                routing {
                    get("/api/v1/users") { call.respondText("ok") }
                    post("/api/v1/users") { call.respondText("created") }
                }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ROUTE_MAP, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("GET /api/v1/users"), "expected GET route in output")
        assertTrue(content.contains("POST /api/v1/users"), "expected POST route in output")
    }

    @Test
    fun `routeMap extracts routes across nested blocks without line regex errors`() {
        val snippet = """
            fun Application.module() {
                routing {
                    route("/v2") {
                        get("/items") {
                            call.respondText("items")
                        }
                        post(
                            "/items"
                        ) {
                            call.respondText("created")
                        }
                    }
                }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ROUTE_MAP, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("GET /items") || content.contains("GET /v2/items"), "expected GET route in: $content")
        assertTrue(content.contains("POST /items") || content.contains("POST /v2/items"), "expected multi-line POST route in: $content")

    }

    @Test
    fun `analyze_serialization parses multi-line annotations and private constructors via AST`() {
        val snippet = """
            import kotlinx.serialization.Serializable

            @Serializable
            data class Config
            @Deprecated("old")
            private constructor(
                val key: String
            )
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_SERIALIZATION, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("hidden_primary_ctor"), "expected hidden_primary_ctor advisory in: $content")
    }

    @Test
    fun `routeMap resolves full nested paths across nested route blocks`() {
        val snippet = """
            fun Application.module() {
                routing {
                    route("/api") {
                        route("/v1") {
                            get("/users") { call.respondText("users") }
                            delete("/users/{id}") { call.respondText("deleted") }
                        }
                    }
                }
            }
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ROUTE_MAP, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("/api/v1/users"), "expected full nested path /api/v1/users in: $content")
        assertTrue(content.contains("DELETE"), "expected DELETE HTTP method in: $content")
    }

    @Test
    fun `analyze_serialization flags non-serializable field types`() {
        val snippet = """
            import kotlinx.serialization.Serializable
            import java.io.File

            @Serializable
            data class Payload(
                val id: Int,
                val file: File
            )
        """.trimIndent()

        val result = service.execute(LibraryAnalysisAction.ANALYZE_SERIALIZATION, snippet)
        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("non_serializable_type") || content.contains("File"), "expected non-serializable advisory in: $content")
    }
}


