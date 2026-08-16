package com.gokorei.kotlinmcp.server

import com.gokorei.kotlinmcp.doc.DefaultDocService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ResourceRegistrarTest {

    private lateinit var server: Server
    private lateinit var docService: DefaultDocService

    @BeforeEach
    fun setUp() {
        server = Server(
            Implementation("test-server", "1.0.0"),
            ServerOptions(
                capabilities = io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities(
                    resources = io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities.Resources(subscribe = false, listChanged = false)
                )
            )
        )
        docService = DefaultDocService()
    }

    @Test
    fun `registerAll registers server usage guide resource`() {
        ResourceRegistrar.registerAll(server, docService)

        assertTrue(ResourceRegistrar.SERVER_GUIDE_URI == "kotlin://server/usage-guide.md")
        val guideText = ResourceRegistrar.USAGE_GUIDE_TEXT
        assertTrue(guideText.contains("# Kotlin MCP Server Instruction Guide for LLMs"))
        assertTrue(guideText.contains("kotlin_code_analyze"))
        assertTrue(guideText.contains("kotlin_refactor"))
        assertTrue(guideText.contains("Read-Only Tools"))
        assertTrue(guideText.contains("Edit / Mutating Tools"))
    }
}
