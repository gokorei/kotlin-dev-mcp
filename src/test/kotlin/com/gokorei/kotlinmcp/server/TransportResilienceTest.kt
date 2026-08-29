package com.gokorei.kotlinmcp.server

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private class TestInProcessTransport(
    private val peerProvider: () -> TestInProcessTransport
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

    suspend fun deliver(message: JSONRPCMessage) {
        handlerReady.await()
        messageHandler?.invoke(message)
    }
}

class TransportResilienceTest {

    private lateinit var server: Server
    private lateinit var kotlinServer: KotlinMcpServer

    @BeforeEach
    fun setUp() {
        server = Server(
            Implementation("test-server", "1.0.0"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            )
        )
        kotlinServer = KotlinMcpServer()
    }

    private fun connectedClient(server: Server): Triple<Client, Job, TestInProcessTransport> {
        lateinit var serverTransport: TestInProcessTransport
        val clientTransport = TestInProcessTransport { serverTransport }
        serverTransport = TestInProcessTransport { clientTransport }

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

    private suspend fun cleanup(client: Client, sessionJob: Job, clientTransport: TestInProcessTransport) {
        runCatching { client.close() }
        runCatching { clientTransport.close() }
        runCatching { withTimeout(2000) { sessionJob.cancelAndJoin() } }
    }

    @Test
    fun `tool execution catches NoClassDefFoundError and returns structured error result`() = runBlocking {
        val toolBuilder = ToolRegistrar.ToolBuilder("faulty_linkage_tool", kotlinServer).apply {
            description = "Simulates NoClassDefFoundError during execution"
            handle { _, _ ->
                throw NoClassDefFoundError("com/gokorei/kotlinmcp/models/SimulatedMissingClass")
            }
        }
        toolBuilder.registerOn(server)

        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val result = client.callTool("faulty_linkage_tool", mapOf("key" to "val"))
            assertTrue(result.isError == true, "Expected callTool to return isError=true on linkage failure")
            val text = (result.content.first() as TextContent).text
            assertTrue(
                text.contains("INTERNAL_SERVER_ERROR") || text.contains("SimulatedMissingClass"),
                "Expected structured error reporting linkage failure, got: $text"
            )
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `tool execution catches ZipException and returns structured error result`() = runBlocking {
        val toolBuilder = ToolRegistrar.ToolBuilder("faulty_zip_tool", kotlinServer).apply {
            description = "Simulates ZipFile bad signature error during execution"
            handle { _, _ ->
                throw java.util.zip.ZipException("ZipFile invalid LOC header (bad signature)")
            }
        }
        toolBuilder.registerOn(server)

        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            val result = client.callTool("faulty_zip_tool", emptyMap<String, Any?>())
            assertTrue(result.isError == true, "Expected callTool to return isError=true on ZipException")
            val text = (result.content.first() as TextContent).text
            assertTrue(
                text.contains("ZipFile invalid LOC header") || text.contains("INTERNAL_SERVER_ERROR"),
                "Expected structured error reporting zip failure, got: $text"
            )
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }

    @Test
    fun `tool execution rethrows CancellationException rather than wrapping in error result`() = runBlocking {
        val toolBuilder = ToolRegistrar.ToolBuilder("cancelling_tool", kotlinServer).apply {
            description = "Simulates coroutine cancellation during execution"
            handle { _, _ ->
                throw CancellationException("Operation cancelled")
            }
        }
        toolBuilder.registerOn(server)

        val (client, sessionJob, clientTransport) = connectedClient(server)
        try {
            org.junit.jupiter.api.assertThrows<CancellationException> {
                client.callTool("cancelling_tool", emptyMap<String, Any?>())
            }
        } finally {
            cleanup(client, sessionJob, clientTransport)
        }
    }
}
