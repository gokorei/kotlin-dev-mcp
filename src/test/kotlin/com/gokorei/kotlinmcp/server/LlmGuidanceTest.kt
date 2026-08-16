package com.gokorei.kotlinmcp.server

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmGuidanceTest {

    @Test
    fun `constants are properly defined`() {
        assertTrue(LlmGuidance.LLM_GUIDE_RESOURCE_URI == "kotlin://server/usage-guide.md")
        assertTrue(LlmGuidance.LLM_GUIDE_RESOURCE_NAME == "kotlin-server-usage-guide")
        assertTrue(LlmGuidance.LLM_GUIDE_PROMPT_NAME == "kotlin_mcp_quickstart")
    }

    @Test
    fun `buildLlmUsageGuide generates complete static markdown guide`() {
        val guide = LlmGuidance.buildLlmUsageGuide()

        assertTrue(guide.contains("# Kotlin MCP LLM Usage Guide"))
        assertTrue(guide.contains("## Tool Action & Parameter Matrix"))
        assertTrue(guide.contains("## Efficiency Defaults"))
        assertTrue(guide.contains("## Write Safety"))
        assertTrue(guide.contains("## Client Gotchas"))
        assertTrue(guide.contains("## Quick Examples"))
        assertTrue(guide.contains("## Decision Shortcuts"))
        assertTrue(guide.contains("Unmocked Live Network Calls"))
        assertTrue(guide.contains("Non-Daemon Subprocess Output Threads"))
        assertFalse(guide.contains("## Current Goal"))
    }

    @Test
    fun `buildLlmUsageGuide injects goal section when goal is provided`() {
        val guide = LlmGuidance.buildLlmUsageGuide(goal = "Refactor Java file to Kotlin")

        assertTrue(guide.contains("## Current Goal"))
        assertTrue(guide.contains("Prioritize the guidance below for: Refactor Java file to Kotlin"))
    }
}
