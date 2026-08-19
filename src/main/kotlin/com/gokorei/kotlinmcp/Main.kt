package com.gokorei.kotlinmcp

import com.gokorei.kotlinmcp.server.KotlinMcpServer
import com.gokorei.kotlinmcp.server.PromptRegistrar
import com.gokorei.kotlinmcp.server.ResourceRegistrar
import com.gokorei.kotlinmcp.server.ToolRegistrar
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

// MCP stdio transport owns stdout for JSON-RPC frames. Disable the
// kotlin-logging startup banner (kotlin-logging-jvm 8.x) which writes to
// System.out via a static initializer. This property initializer runs before
// the logger below is initialized (top-level initializers run in file order
// during class load), so the banner never reaches stdout.
@Suppress("unused")
private val disableLoggingBanner: String = System.setProperty("kotlin-logging.logStartupMessage", "false") ?: ""

private val logger = KotlinLogging.logger {}

fun main() = runBlocking {
    val kotlinServer = KotlinMcpServer()

    logger.info { "Starting Kotlin Developer MCP Server (kotlin-mcp)..." }

    val serverInfo = Implementation(name = "kotlin-mcp", version = "1.1.0")
    val options = ServerOptions(
        capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = false),
            resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
            prompts = ServerCapabilities.Prompts(listChanged = false)
        )
    )

    val server = Server(serverInfo, options)

    ToolRegistrar.registerReadOnlyTools(server, kotlinServer)
    ToolRegistrar.registerEditTools(server, kotlinServer)
    ResourceRegistrar.registerAll(server, kotlinServer.docService)
    PromptRegistrar.registerAll(server)

    logger.info { "Kotlin MCP Server setup completed successfully." }

    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered()
    ) { }
    val session = server.createSession(transport)
    val serverClosed = Job()
    session.onClose { serverClosed.complete() }
    // Release the semantic engine's workspace PSI cache and the shared K2
    // KotlinCoreEnvironment (native PSI resources) on exit so a long-running
    // stdio server never leaks them.
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { kotlinServer.close() }
            .onFailure { logger.warn(it) { "Failed to close the LSP semantic engine during shutdown." } }
        runCatching { K2SnippetFrontend.dispose() }
            .onFailure { logger.warn(it) { "Failed to dispose the K2 environment during shutdown." } }
    })
    serverClosed.join()
}
