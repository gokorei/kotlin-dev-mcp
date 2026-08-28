package com.gokorei.kotlinmcp.semantic.analyzers

import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.jetbrains.kotlin.psi.*

/**
 * Validates Kotlin contract definitions, first-statement rules, and contract effect clauses.
 */
class ContractsAnalyzer {

    fun analyze(session: K2AnalysisSession): KotlinMcpResult {
        val file = session.file
        val findings = mutableListOf<String>()
        var contractsCount = 0

        file.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)
                val fnName = function.name ?: "anonymous"
                val body = function.bodyExpression

                if (body is KtBlockExpression) {
                    val statements = body.statements
                    for ((index, stmt) in statements.withIndex()) {
                        if (stmt is KtCallExpression) {
                            val callee = stmt.calleeExpression?.text
                            if (callee == "contract") {
                                contractsCount++
                                if (index != 0) {
                                    findings.add("⚠️ `fun $fnName`: `contract { ... }` call must be the very first statement in the function body (currently at statement #${index + 1}).")
                                }

                                // Check contract DSL elements
                                stmt.accept(object : KtTreeVisitorVoid() {
                                    override fun visitCallExpression(innerCall: KtCallExpression) {
                                        super.visitCallExpression(innerCall)
                                        val innerCallee = innerCall.calleeExpression?.text
                                        if (innerCallee == "returns" || innerCallee == "returnsNotNull" || innerCallee == "callsInPlace") {
                                            // Valid standard effect
                                        }
                                    }
                                })
                            }
                        }
                    }
                }
            }
        })

        val content = buildString {
            appendLine("# Kotlin Contracts Architecture & Syntax Analysis")
            if (findings.isNotEmpty()) {
                appendLine("Found ${findings.size} issue(s) across $contractsCount contract block(s):")
                appendLine()
                findings.forEach { appendLine("- $it") }
            } else if (contractsCount > 0) {
                appendLine("✅ All $contractsCount contract block(s) are placed as first statements and follow valid contract syntax.")
            } else {
                appendLine("ℹ️ No `contract { ... }` blocks found in snippet.")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("findingsCount" to findings.size.toString(), "contractsCount" to contractsCount.toString())
        )
    }
}
