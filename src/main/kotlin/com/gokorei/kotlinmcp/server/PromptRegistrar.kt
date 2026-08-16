package com.gokorei.kotlinmcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * Registers the `kotlin-task` guidance prompt, the analog of svelte-mcp's
 * `svelte-task`: it tells the LLM how to use the server — try knowledge first,
 * use docs on demand, and drive the compile → fix → re-check loop.
 */
object PromptRegistrar {

    const val KOTLIN_TASK_PROMPT = "kotlin-task"
    const val KOTLIN_ARCHITECTURE_PROMPT = "kotlin-architecture"
    val KOTLIN_MCP_QUICKSTART_PROMPT = LlmGuidance.LLM_GUIDE_PROMPT_NAME

    fun registerAll(server: Server) {
        server.addPrompt(
            name = KOTLIN_MCP_QUICKSTART_PROMPT,
            description = "Load concise Kotlin usage guidance before working with the Kotlin MCP server. Use when selecting tools, presets, or avoiding token-heavy calls.",
            arguments = listOf(
                io.modelcontextprotocol.kotlin.sdk.types.PromptArgument(
                    name = "goal",
                    description = "Target goal or user request context",
                    required = false
                )
            )
        ) { request ->
            val goal = request.arguments?.get("goal")
            val text = LlmGuidance.buildLlmUsageGuide(goal = goal)
            GetPromptResult(
                messages = listOf(PromptMessage(Role.User, TextContent(text = text))),
                description = "Kotlin MCP LLM Usage Guidance"
            )
        }

        server.addPrompt(
            name = KOTLIN_TASK_PROMPT,
            description = "Guidance for working with Kotlin via the kotlin-mcp server: when to consult docs, the compile-fix-recheck loop, and verifying behaviour with kotlin_run_snippet.",
            arguments = emptyList()
        ) { _ ->
            GetPromptResult(
                messages = listOf(PromptMessage(Role.User, TextContent(text = kotlinTaskPromptText()))),
                description = null
            )
        }
        server.addPrompt(
            name = KOTLIN_ARCHITECTURE_PROMPT,
            description = "Architectural and testability guidelines for Kotlin code: UI vs business-logic boundary isolation, explicit DTO-to-domain mapping, and boundary testability. Apply these before producing or reviewing architecture-level code.",
            arguments = emptyList()
        ) { _ ->
            GetPromptResult(
                messages = listOf(PromptMessage(Role.User, TextContent(text = architecturePromptText()))),
                description = null
            )
        }
    }

    private fun architecturePromptText(): String =
        readResourceText("/prompts/kotlin_architecture.md")

    private fun kotlinTaskPromptText(): String =
        readResourceText("/prompts/kotlin_task.md")

    private fun readResourceText(path: String): String =
        PromptRegistrar::class.java.getResourceAsStream(path)?.use { it.bufferedReader().readText() }
            ?: error("Missing required resource: $path")
}

