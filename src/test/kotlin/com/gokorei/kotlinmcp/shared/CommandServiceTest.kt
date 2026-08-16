package com.gokorei.kotlinmcp.shared

import com.gokorei.kotlinmcp.analysis.*

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

enum class DummyAction { PING }

class CommandServiceTest {

    @Test
    fun `command service executes action functional SAM lambda correctly`() {
        val service = CommandService<DummyAction> { action, code ->
            KotlinMcpResult.Success("Executed $action on $code")
        }

        val result = service.execute(DummyAction.PING, "val x = 1")
        assertTrue(result.isSuccess)
        assertEquals("Executed PING on val x = 1", (result as KotlinMcpResult.Success).content)
    }

    @Test
    fun `code analysis service implements command service`() {
        val service: CommandService<CodeAnalysisAction> = DefaultCodeAnalysisService()
        val result = service.execute(CodeAnalysisAction.INSPECT_SYMBOL, "val y = 2")
        assertTrue(result.isSuccess)
    }
}
