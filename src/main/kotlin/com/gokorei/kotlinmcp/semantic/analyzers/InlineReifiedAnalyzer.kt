package com.gokorei.kotlinmcp.semantic.analyzers

import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Validates `inline` function modifiers, `reified` type parameter correctness, and bytecode expansion heuristics.
 */
class InlineReifiedAnalyzer {

    fun analyze(session: K2AnalysisSession): KotlinMcpResult {
        val file = session.file
        val findings = mutableListOf<String>()
        var inlineFuncCount = 0

        file.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)
                val fnName = function.name ?: "anonymous"
                val isInline = function.hasModifier(KtTokens.INLINE_KEYWORD)
                if (isInline) inlineFuncCount++

                // 1. Reified type parameters require inline modifier
                val typeParams = function.typeParameters
                for (tp in typeParams) {
                    if (tp.hasModifier(KtTokens.REIFIED_KEYWORD) && !isInline) {
                        findings.add("⚠️ `fun $fnName`: Type parameter `<reified ${tp.name}>` can only be used on `inline` functions. Mark the function as `inline fun <reified ${tp.name}> ...`.")
                    }
                }

                if (isInline) {
                    // 2. Large inline body bytecode explosion check
                    val body = function.bodyExpression
                    if (body is KtBlockExpression) {
                        val statementCount = body.statements.size
                        if (statementCount > 15) {
                            findings.add("⚠️ `inline fun $fnName`: Large inline function body ($statementCount statements). Inlining large functions duplicates bytecode across every call site, causing APK/JAR bloat. Consider moving non-generic logic into a private non-inline helper.")
                        }
                    }

                    // 3. Functional parameters check
                    val params = function.valueParameters
                    for (param in params) {
                        val typeRef = param.typeReference?.text?.trim() ?: continue
                        val isFunctionalType = typeRef.contains("->") || typeRef.startsWith("Function") || typeRef.startsWith("() ->")
                        if (!isFunctionalType && params.size == 1) {
                            findings.add("ℹ️ `inline fun $fnName`: Function is marked `inline` but accepts no functional parameters. Inlining is primarily beneficial when taking lambda parameters or using reified types.")
                        }
                    }
                }
            }
        })

        val content = buildString {
            appendLine("# Kotlin Inline & Reified Generics Analysis")
            if (findings.isNotEmpty()) {
                appendLine("Found ${findings.size} issue(s) or optimization note(s):")
                appendLine()
                findings.forEach { appendLine("- $it") }
            } else {
                appendLine("✅ All inline functions and reified type parameters follow compiler constraints and best practices.")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("findingsCount" to findings.size.toString(), "inlineFuncCount" to inlineFuncCount.toString())
        )
    }
}
