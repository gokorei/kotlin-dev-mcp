package com.gokorei.kotlinmcp.server

import com.gokorei.kotlinmcp.models.FrameworkFeature
import com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ToolRegistrarTest {

    private lateinit var server: Server
    private lateinit var kotlinServer: KotlinMcpServer

    @BeforeEach
    fun setUp() {
        server = Server(
            Implementation("test-server", "1.0.0"),
            ServerOptions(
                capabilities = io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities(
                    tools = io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities.Tools(listChanged = false)
                )
            )
        )
        kotlinServer = KotlinMcpServer()
    }

    @Test
    fun `registerReadOnlyTools registers read-only tool suite`() {
        ToolRegistrar.registerReadOnlyTools(server, kotlinServer)
    }

    @Test
    fun `registerEditTools registers edit tool suite`() {
        ToolRegistrar.registerEditTools(server, kotlinServer)
    }

    @Test
    fun `formatDomainDescription dynamically highlights detected frameworks`() {
        val ktorProfile = ProjectEnvironmentProfile(setOf(FrameworkFeature.KTOR, FrameworkFeature.SERIALIZATION))

        val description = ToolRegistrar.formatDomainDescription(ktorProfile)

        assertTrue(description.contains("ktor"), "expected ktor in description")
        assertTrue(description.contains("serialization"), "expected serialization in description")
        assertTrue(description.contains("(detected in project: ktor, serialization)"), "expected detected summary tag")
    }

    @Test
    fun `formatDomainDescription provides clean default when no frameworks are detected`() {
        val noneProfile = ProjectEnvironmentProfile.NONE

        val description = ToolRegistrar.formatDomainDescription(noneProfile)

        assertTrue(description.contains("ktor"), "expected available domain list")
        assertFalse(description.contains("detected in project"), "should not contain detected tag when empty")
    }

    @Test
    fun `parseStringList parses array and primitive list arguments correctly`() {
        val jsonArray = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("item1"))
            add(kotlinx.serialization.json.JsonPrimitive("item2"))
        }
        val parsed = ToolRegistrar.parseStringList(jsonArray)
        assertTrue(parsed == listOf("item1", "item2"), "expected parsed list items")
    }

    @Test
    fun `dispatchAction validates action and returns structured error for unsupported actions`() {
        val handlers = mapOf<String, (Map<String, String>) -> com.gokorei.kotlinmcp.models.KotlinMcpResult>(
            "search" to { com.gokorei.kotlinmcp.models.KotlinMcpResult.Success("ok") }
        )
        val errorResult = ToolRegistrar.dispatchAction("unknown_act", handlers, mapOf("action" to "unknown_act"))
        assertTrue(errorResult.isError)
        val error = errorResult as com.gokorei.kotlinmcp.models.KotlinMcpResult.Error
        assertEquals("INVALID_ACTION", error.code)
        assertTrue(error.message.contains("Supported actions: search"))
    }

    @Test
    fun `normalizeArgs maps workspacePath projectPath and path aliases to code and workspacePath`() {
        val raw = mapOf("workspacePath" to "/tmp/proj", "snippet" to "val x = 1")
        val normalized = ToolRegistrar.normalizeArgs(raw)
        assertEquals("/tmp/proj", normalized["workspacePath"])
        assertEquals("/tmp/proj", normalized["path"])
        assertEquals("val x = 1", normalized["code"])
    }

    @Test
    fun `normalizeArgs does not blindly copy workspacePath into code when code parameter is missing`() {
        val raw = mapOf("workspacePath" to "/tmp/proj")
        val normalized = ToolRegistrar.normalizeArgs(raw)
        assertEquals("/tmp/proj", normalized["workspacePath"])
        assertEquals("/tmp/proj", normalized["path"])
        assertNull(normalized["code"], "workspacePath must not be copied into code when code is missing")
    }

    @Test
    fun `dispatchAction uses default action when action parameter is missing`() {
        val handlers = mapOf<String, (Map<String, String>) -> com.gokorei.kotlinmcp.models.KotlinMcpResult>(
            "structure" to { com.gokorei.kotlinmcp.models.KotlinMcpResult.Success("default_structure_ok") }
        )
        val result = ToolRegistrar.dispatchAction(null, handlers, emptyMap(), defaultAction = "structure")
        assertTrue(result.isSuccess)
        val success = result as com.gokorei.kotlinmcp.models.KotlinMcpResult.Success
        assertEquals("default_structure_ok", success.content)
    }

    @Test
    fun `registered tools do not mark optional parameters as required in schema`() {
        var registeredSchema: io.modelcontextprotocol.kotlin.sdk.types.ToolSchema? = null
        val dummyServer = Server(
            Implementation("test-server", "1.0.0"),
            ServerOptions(capabilities = io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities(tools = io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities.Tools()))
        )

        ToolRegistrar.registerReadOnlyTools(dummyServer, kotlinServer)
        ToolRegistrar.registerEditTools(dummyServer, kotlinServer)

        // Using ToolBuilder directly to test schema generation
        val builder = ToolRegistrar.ToolBuilder("test_tool", kotlinServer).apply {
            param("action", "Action discriminator")
            param("optionalParam", "Optional parameter")
            param("requiredParam", "Required parameter", required = true)
            handleSimple { _, _ -> com.gokorei.kotlinmcp.models.KotlinMcpResult.Success("ok") }
        }
        builder.registerOn(dummyServer)
    }

    @Test
    fun `kotlin_library_analyze accepts action parameter and falls back to domain`() {
        val handlers = mapOf<String, (Map<String, String>) -> com.gokorei.kotlinmcp.models.KotlinMcpResult>(
            "ktor" to { com.gokorei.kotlinmcp.models.KotlinMcpResult.Success("ktor_ok") }
        )
        val resultWithAction = ToolRegistrar.dispatchAction(
            action = "ktor",
            handlers = handlers,
            args = mapOf("action" to "ktor")
        )
        assertTrue(resultWithAction.isSuccess)

        val argsWithDomain = ToolRegistrar.normalizeArgs(mapOf("domain" to "ktor"))
        val actionFromDomain = argsWithDomain["action"] ?: argsWithDomain["domain"]
        assertEquals("ktor", actionFromDomain)
    }

    @Test
    fun `normalizeArgs prioritizes action over domain when both parameters are provided`() {
        val raw = mapOf("action" to "ktor", "domain" to "serialization")
        val normalized = ToolRegistrar.normalizeArgs(raw)
        assertEquals("ktor", normalized["action"], "action must take precedence over domain")
    }

    @Test
    fun `parseClasspathElement extracts array elements from JsonArray, stringified JSON, and delimited strings`() {
        val jsonArray = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("path1.jar"))
            add(kotlinx.serialization.json.JsonPrimitive("path2.jar"))
        }
        val fromArray = ToolRegistrar.parseClasspathElement(jsonArray)
        assertEquals(listOf("path1.jar", "path2.jar"), fromArray)

        val stringified = kotlinx.serialization.json.JsonPrimitive("[\"path1.jar\", \"path2.jar\"]")
        val fromStringified = ToolRegistrar.parseClasspathElement(stringified)
        assertEquals(listOf("path1.jar", "path2.jar"), fromStringified)

        val delimited = kotlinx.serialization.json.JsonPrimitive("path1.jar, path2.jar")
        val fromDelimited = ToolRegistrar.parseClasspathElement(delimited)
        assertEquals(listOf("path1.jar", "path2.jar"), fromDelimited)
    }
}




