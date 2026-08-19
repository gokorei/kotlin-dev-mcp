package com.gokorei.kotlinmcp.server

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * In-process JSON-RPC transport pair that bridges the MCP [Server] and a
 * [Client] without touching stdio, enabling end-to-end tool verification
 * inside the test JVM.
 */
private class InProcessTransport(
    private val peerProvider: () -> InProcessTransport
) : Transport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handlerReady = CompletableDeferred<Unit>()

    @Volatile
    private var messageHandler: (suspend (JSONRPCMessage) -> Unit)? = null
    private var closeCallback: () -> Unit = {}

    override suspend fun start() {}

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        peerProvider().deliver(message)
    }

    override suspend fun close() {
        scope.cancel()
        closeCallback()
        handlerReady.complete(Unit)
    }

    override fun onClose(block: () -> Unit) {
        closeCallback = block
    }

    override fun onError(block: (Throwable) -> Unit) {}

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        messageHandler = block
        handlerReady.complete(Unit)
    }

    /**
     * Delivers a message to the peer's registered onMessage handler. The MCP
     * handshake races: `client.connect()` can emit `initialize` before the
     * server session coroutine has subscribed via onMessage. A channel +
     * null-handler guard would silently consume and drop that early message,
     * leaving the SDK client awaiting its response forever. Waiting on a
     * CompletableDeferred until the peer handler is subscribed closes that race.
     */
    private suspend fun deliver(message: JSONRPCMessage) {
        handlerReady.await()
        messageHandler?.invoke(message)
    }
}

class KotlinMcpIntegrationTest {

    private fun serverWithTools(kotlinServer: KotlinMcpServer = KotlinMcpServer()): Server {
        val server = Server(
            serverInfo = Implementation(name = "kotlin-mcp-test", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
                    prompts = ServerCapabilities.Prompts(listChanged = false)
                )
            )
        )
        ToolRegistrar.registerReadOnlyTools(server, kotlinServer)
        ToolRegistrar.registerEditTools(server, kotlinServer)
        ResourceRegistrar.registerAll(server, kotlinServer.docService)
        PromptRegistrar.registerAll(server)
        return server
    }

    private fun connectedClient(server: Server): Triple<Client, Job, InProcessTransport> {
        lateinit var serverTransport: InProcessTransport
        val clientTransport = InProcessTransport { serverTransport }
        serverTransport = InProcessTransport { clientTransport }

        val sessionJob = CoroutineScope(Dispatchers.Default).launch {
            server.createSession(serverTransport)
        }

        val client = Client(
            clientInfo = Implementation(name = "test-client", version = "1.0.0"),
            options = ClientOptions()
        )
        runBlocking { withTimeout(30_000) { client.connect(clientTransport) } }
        return Triple(client, sessionJob, clientTransport)
    }

    private suspend fun cleanup(client: Client, sessionJob: Job, clientTransport: InProcessTransport) {
        runCatching { client.close() }
        runCatching { clientTransport.close() }
        runCatching { withTimeout(2000) { sessionJob.cancelAndJoin() } }
    }


    @Test
    fun `client lists default 9 consolidated tools`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val result = client.listTools(ListToolsRequest())
            val names = result.tools.map { it.name }.sorted()

            assertTrue(names.contains("kotlin_docs_read"), "expected kotlin_docs_read in $names")
            assertTrue(names.contains("kotlin_docs_edit"), "expected kotlin_docs_edit in $names")
            assertTrue(names.contains("kotlin_check_snippet"), "expected kotlin_check_snippet in $names")
            assertTrue(names.contains("kotlin_code_analyze"), "expected kotlin_code_analyze in $names")
            assertTrue(names.contains("kotlin_text_lsp_read"), "expected kotlin_text_lsp_read in $names")
            assertTrue(names.contains("kotlin_text_lsp_edit"), "expected kotlin_text_lsp_edit in $names")
            assertTrue(names.contains("kotlin_project_inspect"), "expected kotlin_project_inspect in $names")
            assertTrue(names.contains("kotlin_refactor"), "expected kotlin_refactor in $names")
            assertTrue(names.contains("kotlin_library_analyze"), "expected kotlin_library_analyze in $names")
            assertTrue(names.contains("kotlin_lint"), "expected kotlin_lint in $names")
            assertTrue(names.contains("kotlin_run"), "expected kotlin_run in $names")
            assertEquals(11, names.size, "expected 11 consolidated core tools, got: $names")
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `optional tool parameters are not marked as required in input schema`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val result = client.listTools(ListToolsRequest())
            val docsRead = result.tools.first { it.name == "kotlin_docs_read" }
            val requiredDocsRead = docsRead.inputSchema.required.orEmpty()
            assertFalse(requiredDocsRead.contains("preset"), "preset parameter is optional")
            assertFalse(requiredDocsRead.contains("classpath"), "classpath parameter is optional")

            val projectInspect = result.tools.first { it.name == "kotlin_project_inspect" }
            val requiredProjectInspect = projectInspect.inputSchema.required.orEmpty()
            assertFalse(requiredProjectInspect.contains("settingsContent"), "settingsContent is optional")
            assertFalse(requiredProjectInspect.contains("gradlePropertiesContent"), "gradlePropertiesContent is optional")
            assertFalse(requiredProjectInspect.contains("packageName"), "packageName is optional")
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }


    @Test
    fun `mutating tools are not registered with readOnlyHint true`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val result = client.listTools(ListToolsRequest())
            val annotationsByTool = result.tools.associate { it.name to it.annotations?.readOnlyHint }

            val mutatingTools = listOf("kotlin_refactor", "kotlin_library_analyze", "kotlin_docs_edit", "kotlin_text_lsp_edit", "kotlin_lint", "kotlin_run")
            mutatingTools.forEach { name ->
                assertFalse(
                    annotationsByTool[name] == true,
                    "tool '$name' performs mutating actions and must NOT advertise readOnlyHint=true"
                )
            }

            val readOnlyTools = listOf("kotlin_code_analyze", "kotlin_project_inspect", "kotlin_check_snippet", "kotlin_docs_read", "kotlin_text_lsp_read")
            readOnlyTools.forEach { name ->
                assertTrue(
                    annotationsByTool[name] == true,
                    "tool '$name' is read-only and should advertise readOnlyHint=true"
                )
            }
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client receives successful result for a clean tool call`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val result = client.callTool("kotlin_docs_read", mapOf("action" to "search", "query" to "coroutines"))
            assertFalse(result.isError == true, "expected success, got: ${result.content}")
            assertTrue(
                result.content.joinToString { it.toString() }.contains("coroutines", ignoreCase = true),
                "expected coroutines in: ${result.content}"
            )
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client receives isError true for an error tool call`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val result = client.callTool("kotlin_docs_read", mapOf("action" to "lookup", "query" to "totallyUnknownSymbol999"))
            assertTrue(result.isError == true, "expected isError=true, got: ${result.content}")
            assertTrue(
                result.content.joinToString { it.toString() }.contains("SYMBOL_NOT_FOUND"),
                "expected SYMBOL_NOT_FOUND in: ${result.content}"
            )
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client lists a small curated resource set and reads entries via the template`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val resources = client.listResources(io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest())
            val uris = resources.resources.map { it.uri }
            // Progressive discovery: only the stable curated resources are listed,
            // NOT one resource per stdlib symbol/feature entry.
            assertTrue(uris.contains(ResourceRegistrar.DOCS_INDEX_URI), "expected doc index in $uris")
            assertTrue(uris.contains(ResourceRegistrar.GUIDELINES_URI), "expected guidelines in $uris")
            assertTrue(uris.contains(ResourceRegistrar.RESILIENCE_GUIDELINES_URI), "expected resilience guidelines in $uris")
            assertFalse(uris.contains(ResourceRegistrar.KMP_STORAGE_GUIDELINES_URI), "specialized kmp storage guideline must be gated behind progressive discovery, not statically listed in root resources")
            assertTrue(
                uris.none { it.startsWith("kotlin://docs/symbol/") || it.startsWith("kotlin://docs/feature/") },
                "bulk per-entry resources must not be registered: $uris"
            )

            val read = client.readResource(
                io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest(
                    io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams(ResourceRegistrar.DOCS_INDEX_URI)
                )
            )
            val text = read.contents.joinToString { it.toString() }
            assertTrue(text.contains("Kotlin Documentation Index"), "expected index content, got: $text")

            val resilienceRead = client.readResource(
                io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest(
                    io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams(ResourceRegistrar.RESILIENCE_GUIDELINES_URI)
                )
            )
            val resilienceText = resilienceRead.contents.joinToString { it.toString() }
            assertTrue(resilienceText.contains("Kotlin Backend Resilience"), "expected resilience content, got: $resilienceText")

            val kmpStorageRead = client.readResource(
                io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest(
                    io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams(ResourceRegistrar.KMP_STORAGE_GUIDELINES_URI)
                )
            )
            val kmpStorageText = kmpStorageRead.contents.joinToString { it.toString() }
            assertTrue(kmpStorageText.contains("Kotlin Multiplatform Storage"), "expected kmp storage content on-demand, got: $kmpStorageText")

            val templateRead = client.readResource(
                io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest(
                    io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams("kotlin://docs/feature/sealed%20class")
                )
            )
            val templateText = templateRead.contents.joinToString { it.toString() }
            assertTrue(templateText.isNotBlank(), "template must resolve a known entry by name, got: $templateText")
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client lists and gets the kotlin-task prompt`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val prompts = client.listPrompts(io.modelcontextprotocol.kotlin.sdk.types.ListPromptsRequest())
            assertTrue(prompts.prompts.any { it.name == PromptRegistrar.KOTLIN_TASK_PROMPT }, "expected kotlin-task prompt")

            val got = client.getPrompt(
                io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest(
                    io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams(PromptRegistrar.KOTLIN_TASK_PROMPT)
                )
            )
            val text = got.messages.joinToString { it.toString() }
            assertTrue(text.contains("kotlin_check_snippet"), "expected tool guidance in prompt, got: $text")
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client registers a dynamic symbol then looks it up`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val registered = client.callTool(
                "kotlin_docs_edit",
                mapOf("action" to "register_symbol", "name" to "kotlin.sequences.Sequence", "content" to "# Sequence\nA lazy collection pipeline.")
            )
            assertFalse(registered.isError == true, "expected register success, got: ${registered.content}")

            val lookedUp = client.callTool("kotlin_docs_read", mapOf("action" to "lookup", "query" to "kotlin.sequences.Sequence"))
            assertFalse(lookedUp.isError == true, "expected lookup success, got: ${lookedUp.content}")
            assertTrue(
                lookedUp.content.joinToString { it.toString() }.contains("lazy collection pipeline"),
                "expected registered content in: ${lookedUp.content}"
            )
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client runs kotlin_lint_detekt over the MCP boundary`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val result = client.callTool(
                "kotlin_lint",
                mapOf(
                    "action" to "detekt",
                    "code" to "import java.util.UUID\nimport kotlinx.coroutines.GlobalScope\n\nfun process(name: String?) {\n    val unused = name!!.length\n    GlobalScope.launch { }\n}"
                )
            )
            assertFalse(result.isError == true, "expected success, got: ${result.content}")
            val text = result.content.joinToString { it.toString() }
            assertTrue(text.contains("Detekt Findings"), "expected detekt output in: $text")
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client runs kotlin_format_ktlint over the MCP boundary`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val result = client.callTool(
                "kotlin_lint",
                mapOf("action" to "format_ktlint", "code" to "fun main() { println(\"hi\") }\n")
            )
            assertFalse(result.isError == true, "expected success, got: ${result.content}")
            val text = result.content.joinToString { it.toString() }
            assertTrue(text.contains("Ktlint Format Result", ignoreCase = true), "expected ktlint output in: $text")
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client runs kotlin_lint_baseline over the MCP boundary`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-int-baseline").toString()
        try {
            val result = client.callTool(
                "kotlin_lint",
                mapOf("action" to "baseline_dump", "workspacePath" to workspace)
            )
            assertFalse(result.isError == true, "expected dump success, got: ${result.content}")
            val text = result.content.joinToString { it.toString() }
            assertTrue(text.contains("Baseline Dump") || text.contains("SmellBaseline") || text.contains("Detekt baseline written"), "expected baseline dump in: $text")
        } finally {
            java.io.File(workspace).deleteRecursively()
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client kotlin_check_snippet resolves workspace types via workspacePath`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-int-ws-check")
        try {
            workspaceWithCompiledGreeter(workspace)

            val result = client.callTool(
                "kotlin_check_snippet",
                mapOf(
                    "code" to "import demo.Greeter\nfun main() { println(Greeter().greet()) }",
                    "workspacePath" to workspace.toAbsolutePath().toString()
                )
            )
            assertFalse(result.isError == true, "expected success, got: ${result.content}")
            val text = result.content.joinToString { it.toString() }
            assertTrue(text.contains("Compilation succeeded", ignoreCase = true), "expected clean compile, got: $text")
        } finally {
            workspace.toFile().deleteRecursively()
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client kotlin_run snippet action resolves and runs workspace types via workspacePath`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-int-ws-run")
        try {
            workspaceWithCompiledGreeter(workspace)

            val result = client.callTool(
                "kotlin_run",
                mapOf(
                    "action" to "snippet",
                    "code" to "import demo.Greeter\nfun main() { println(Greeter(\"kota\").greet()) }",
                    "workspacePath" to workspace.toAbsolutePath().toString(),
                    "timeoutSeconds" to "30"
                )
            )
            assertFalse(result.isError == true, "expected success, got: ${result.content}")
            val text = result.content.joinToString { it.toString() }
            assertTrue(text.contains("hi, kota"), "expected workspace type output, got: $text")
        } finally {
            workspace.toFile().deleteRecursively()
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client lsp definition jumps into a workspace file`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-int-def")
        try {
            workspace.resolve("Model.kt").toFile().writeText(
                "package app\nclass Cart(val items: Int) { fun totalFees() = items * 2 }\n"
            )
            val result = client.callTool(
                "kotlin_text_lsp_read",
                mapOf(
                    "action" to "definition",
                    "code" to "package app\nfun demo(c: Cart) { c.totalFees() }",
                    "symbol" to "totalFees",
                    "workspacePath" to workspace.toAbsolutePath().toString()
                )
            )
            assertFalse(result.isError == true, "expected success, got: ${result.content}")
            val text = result.content.joinToString { it.toString() }
            assertTrue(text.contains("Model.kt"), "definition must jump into the workspace file: $text")
            assertTrue(text.contains("fun totalFees"), "expected declaration signature: $text")
        } finally {
            workspace.toFile().deleteRecursively()
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client lsp rename updates bound sites across files and nothing else`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-int-rename")
        try {
            workspace.resolve("Cart.kt").toFile().writeText("package app\nclass Cart(val n: Int) { fun totalFees() = n }\n")
            workspace.resolve("UseA.kt").toFile().writeText("package app\nfun a(c: Cart) = c.totalFees()\n")
            workspace.resolve("Other.kt").toFile().writeText("package app\nval totalFees = 1\n")

            val result = client.callTool(
                "kotlin_text_lsp_edit",
                mapOf(
                    "action" to "rename",
                    "code" to "package app\nfun demo(c: Cart) = c.totalFees()",
                    "oldName" to "totalFees",
                    "newName" to "cost",
                    "workspacePath" to workspace.toAbsolutePath().toString()
                )
            )
            assertFalse(result.isError == true, "expected success, got: ${result.content}")

            val cart = workspace.resolve("Cart.kt").toFile().readText()
            val useA = workspace.resolve("UseA.kt").toFile().readText()
            val other = workspace.resolve("Other.kt").toFile().readText()
            assertTrue("fun cost()" in cart, "bound declaration must be renamed in Cart.kt: $cart")
            assertTrue("c.cost()" in useA, "bound usage must be renamed in UseA.kt: $useA")
            assertTrue(other.contains("val totalFees"), "unrelated same-name symbol must stay untouched: $other")
        } finally {
            workspace.toFile().deleteRecursively()
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client lsp completion is type-aware over the boundary`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val result = client.callTool(
                "kotlin_text_lsp_read",
                mapOf(
                    "action" to "completion",
                    "code" to "data class Point(val x: Int, val y: Int)\nfun f(p: Point) = p.",
                    "symbol" to "p."
                )
            )
            assertFalse(result.isError == true, "expected success, got: ${result.content}")
            val text = result.content.joinToString { it.toString() }
            assertTrue(text.contains("## Semantic candidates"), "expected semantic candidates: $text")
            val semanticSection = text.substringAfter("## Semantic candidates").substringBefore("## Idiom suggestions")
            assertTrue(" - `x`" in semanticSection, "receiver members must include x: $text")
            assertTrue(" - `y`" in semanticSection, "receiver members must include y: $text")
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client lsp references are complete and shadow-correct over the boundary`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-int-refs")
        try {
            workspace.resolve("Model.kt").toFile().writeText("package app\nval counter = 0\n")
            workspace.resolve("UseA.kt").toFile().writeText("package app\nfun a() { println(counter) }\n")
            workspace.resolve("UseB.kt").toFile().writeText("package app\nfun b() { val counter = 1; println(counter) }\n")

            val result = client.callTool(
                "kotlin_text_lsp_read",
                mapOf(
                    "action" to "references",
                    "code" to "",
                    "symbol" to "counter",
                    "workspacePath" to workspace.toAbsolutePath().toString()
                )
            )
            assertFalse(result.isError == true, "expected success, got: ${result.content}")
            val text = result.content.joinToString { it.toString() }
            assertTrue(text.contains("Model.kt"), "declaration must be listed: $text")
            assertTrue(text.contains("UseA.kt"), "bound usage must be listed: $text")
            assertFalse(text.contains("UseB.kt"), "shadowed local must not appear: $text")
        } finally {
            workspace.toFile().deleteRecursively()
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client lsp hover returns resolved signature and KDoc over the boundary`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val result = client.callTool(
                "kotlin_text_lsp_read",
                mapOf(
                    "action" to "hover",
                    "code" to "/** Doubles any value. */\nfun twice(x: Int): Int = x * 2\nfun main() { twice(3) }",
                    "symbol" to "twice"
                )
            )
            assertFalse(result.isError == true, "expected success, got: ${result.content}")
            val text = result.content.joinToString { it.toString() }
            assertTrue(text.contains("fun twice"), "expected resolved signature: $text")
            assertTrue(text.contains("Doubles any value"), "expected KDoc: $text")
            assertTrue(text.contains("Type: `kotlin.Int`"), "expected return type: $text")
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client lsp type hierarchy finds workspace implementations over the boundary`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-int-typeh")
        try {
            workspace.resolve("Domain.kt").toFile().writeText(
                "package com.example.domain\ninterface BaseService\n"
            )
            workspace.resolve("Impl.kt").toFile().writeText(
                "package com.example.service\nimport com.example.domain.BaseService\nclass CustomService : BaseService\n"
            )
            val result = client.callTool(
                "kotlin_text_lsp_read",
                mapOf(
                    "action" to "type_hierarchy",
                    "code" to "package com.example.app\nimport com.example.domain.BaseService\nclass AppService : BaseService",
                    "symbol" to "BaseService",
                    "workspacePath" to workspace.toAbsolutePath().toString()
                )
            )
            assertFalse(result.isError == true, "expected success, got: ${result.content}")
            val text = result.content.joinToString { it.toString() }
            assertTrue(text.contains("CustomService"), "workspace implementation must be listed: $text")
            assertTrue(text.contains("AppService"), "snippet implementation must be listed: $text")
        } finally {
            workspace.toFile().deleteRecursively()
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `client lsp call hierarchy lists resolved callers over the boundary`() = runBlocking {
        val server = serverWithTools()
        val (client, sessionJob, clientTransport) = connectedClient(server)
        val workspace = java.nio.file.Files.createTempDirectory("kmcp-int-callh")
        try {
            workspace.resolve("Utils.kt").toFile().writeText(
                "package com.example.utils\nfun executeAction() {}\n"
            )
            workspace.resolve("Runner.kt").toFile().writeText(
                "package com.example.runner\nimport com.example.utils.executeAction\nfun runAll() { executeAction() }\n"
            )
            val result = client.callTool(
                "kotlin_text_lsp_read",
                mapOf(
                    "action" to "call_hierarchy",
                    "code" to "package com.example.main\nimport com.example.utils.executeAction\nfun start() { executeAction() }",
                    "symbol" to "executeAction",
                    "workspacePath" to workspace.toAbsolutePath().toString()
                )
            )
            assertFalse(result.isError == true, "expected success, got: ${result.content}")
            val text = result.content.joinToString { it.toString() }
            assertTrue(text.contains("runAll"), "workspace caller must be listed: $text")
            assertTrue(text.contains("start"), "snippet caller must be listed: $text")
        } finally {
            workspace.toFile().deleteRecursively()
            cleanup(client, sessionJob, clientTransport)
        }
    }

    private fun workspaceWithCompiledGreeter(workspace: java.nio.file.Path) {
        val lib = com.gokorei.kotlinmcp.execution.SnippetCompiler.compile("""
            package demo
            class Greeter(val name: String = "world") { fun greet(): String = "hi, ${'$'}name" }
        """.trimIndent())
        val libOut = (lib as com.gokorei.kotlinmcp.execution.CompileResult.Compiled).outDir
        val classesDir = workspace.resolve("build/classes/kotlin/main")
        java.nio.file.Files.createDirectories(classesDir)
        libOut.toFile().walkTopDown().forEach { f ->
            if (f.isFile) {
                val dest = classesDir.resolve(libOut.relativize(f.toPath()).toString())
                java.nio.file.Files.createDirectories(dest.parent)
                java.nio.file.Files.copy(f.toPath(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        com.gokorei.kotlinmcp.execution.SnippetCompiler.cleanup(lib)
    }
}

