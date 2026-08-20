package com.gokorei.kotlinmcp

import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.server.KotlinMcpServer
import com.gokorei.kotlinmcp.server.PromptRegistrar
import com.gokorei.kotlinmcp.server.ResourceRegistrar
import com.gokorei.kotlinmcp.server.ToolRegistrar
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
import java.util.concurrent.atomic.AtomicBoolean

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
    val isDisposed = AtomicBoolean(false)

    fun cleanup() {
        if (isDisposed.compareAndSet(false, true)) {
            runCatching { kotlinServer.close() }
            runCatching { K2SnippetFrontend.dispose() }
                .onFailure { logger.warn(it) { "Failed to dispose K2 environment during shutdown." } }
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread { cleanup() })

    logger.info { "Starting Kotlin Developer MCP Server (${Version.NAME} v${Version.CURRENT})..." }

    val serverInfo = Implementation(name = Version.NAME, version = Version.CURRENT)
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

    try {
        serverClosed.join()
    } finally {
        cleanup()
    }
}
