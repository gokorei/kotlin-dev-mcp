package com.gokorei.kotlinmcp.shared

import com.gokorei.kotlinmcp.models.KotlinMcpResult

/**
 * Core command service interface for executing action-based operations on Kotlin code or project inputs.
 */
fun interface CommandService<A> {
    fun execute(action: A, code: String): KotlinMcpResult
}
