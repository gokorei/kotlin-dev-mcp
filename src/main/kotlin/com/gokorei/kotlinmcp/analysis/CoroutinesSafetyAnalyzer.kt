package com.gokorei.kotlinmcp.analysis

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.shared.SourceUtils
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtWhileExpression

/**
 * Strategy component for coroutine scope, cancellation safety, blocking calls, and unbounded loop inspection.
 */
class CoroutinesSafetyAnalyzer {

    private val blockingCalls = setOf("Thread.sleep")
    private val suspendPointCallees = setOf("delay", "yield", "awaitCancellation", "ensureActive")
    private val hardcodedDispatchers = setOf("IO", "Main", "Default", "Unconfined")

    fun explainCoroutines(code: String): KotlinMcpResult {
        val findings = mutableListOf<String>()
        val psi = K2SnippetFrontend.parsePsi(code)

        if (psi == null) {
            return KotlinMcpResult.Success(
                content = "# Coroutine Scope & Safety Analysis\nUnable to parse snippet as Kotlin PSI.",
                metadata = mapOf("warningsCount" to "0")
            )
        }

        val lineOf = { offset: Int -> SourceUtils.lineOf(code, offset) }

        inspectBlockingCallsAndGlobalScope(psi, findings)
        inspectHardcodedDispatchers(psi, findings)
        inspectUnboundedLoops(psi, lineOf, findings)

        val content = if (findings.isNotEmpty()) {
            "# Coroutine Scope & Safety Analysis\n" + findings.distinct().joinToString("\n\n")
        } else {
            "# Coroutine Scope & Safety Analysis\nNo coroutine anti-patterns detected."
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("warningsCount" to findings.distinct().size.toString())
        )
    }

    private fun inspectBlockingCallsAndGlobalScope(psi: KtFile, findings: MutableList<String>) {
        val blockingMethodNames = setOf(
            "wait", "park", "acquire", "blockingGet", "blockingFirst",
            "read", "readLine", "readBytes", "readText", "write", "flush",
            "executeQuery", "executeUpdate", "getConnection", "get", "join"
        )
        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val dotQualified = expression.parent as? KtDotQualifiedExpression
                val receiverText = (dotQualified?.receiverExpression as? KtNameReferenceExpression)?.getReferencedName()
                val callee = expression.calleeExpression?.text
                val qualifiedCallee = if (receiverText != null && callee != null) "$receiverText.$callee" else callee

                when {
                    qualifiedCallee == "Thread.sleep" ->
                        findings.add("⚠️ Blocking call `Thread.sleep` detected in coroutine context. Use `delay(...)` instead to avoid blocking worker threads.")
                    qualifiedCallee == "runBlocking" ->
                        findings.add("ℹ️ `runBlocking` detected. Ensure this is only used in main entry points or tests, not inside suspended execution chains.")
                    callee != null && callee in blockingMethodNames -> {
                        var inSuspendScope = false
                        var p: org.jetbrains.kotlin.com.intellij.psi.PsiElement? = expression.parent
                        while (p != null) {
                            if (p is org.jetbrains.kotlin.psi.KtNamedFunction && p.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SUSPEND_KEYWORD)) {
                                inSuspendScope = true
                                break
                            }
                            p = p.parent
                        }
                        if (inSuspendScope) {
                            findings.add("⚠️ Blocking call `$callee` detected in suspend function. Blocking calls stall worker threads; use async Kotlin equivalents (`await()`, `withContext(Dispatchers.IO)`) instead.")
                        }
                    }
                }
                super.visitCallExpression(expression)
            }
            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                if (expression.getReferencedName() == "GlobalScope") {
                    var inImport = false
                    var parent: org.jetbrains.kotlin.com.intellij.psi.PsiElement? = expression.parent
                    while (parent != null) {
                        if (parent is KtImportDirective) {
                            inImport = true
                            break
                        }
                        parent = parent.parent
                    }
                    if (!inImport) {
                        findings.add("⚠️ `GlobalScope` usage detected. Prefer structured concurrency via `coroutineScope` or passing `CoroutineScope` as context parameter.")
                    }
                }
                super.visitSimpleNameExpression(expression)
            }
        })
    }

    private fun inspectHardcodedDispatchers(psi: KtFile, findings: MutableList<String>) {
        val dispatchers = mutableListOf<String>()
        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                val receiver = (expression.receiverExpression as? KtNameReferenceExpression)?.getReferencedName()
                if (receiver == "Dispatchers") {
                    val selector = (expression.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
                    if (selector in hardcodedDispatchers) dispatchers.add("Dispatchers.$selector")
                }
                super.visitDotQualifiedExpression(expression)
            }
        })

        if (dispatchers.isNotEmpty()) {
            findings.add(
                "⚠️ Hardcoded dispatcher${if (dispatchers.size > 1) "s" else ""} ${dispatchers.distinct().joinToString(", ")} " +
                    "detected. Inject `CoroutineDispatcher` via the constructor/function parameter (default `Dispatchers.Default`) so tests can pass a " +
                    "`StandardTestDispatcher` and use virtual time (kotlinx-coroutines-test `runTest`)."
            )
        }
    }

    private fun inspectUnboundedLoops(psi: KtFile, lineOf: (Int) -> Int, findings: MutableList<String>) {
        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitWhileExpression(expression: KtWhileExpression) {
                val condText = expression.condition?.text?.trim().orEmpty()
                if (condText == "true" || condText == "1") {
                    var inCoroutineBlock = false
                    var parent: org.jetbrains.kotlin.com.intellij.psi.PsiElement? = expression.parent
                    while (parent != null) {
                        if (parent is org.jetbrains.kotlin.psi.KtNamedFunction && parent.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SUSPEND_KEYWORD)) {
                            inCoroutineBlock = true
                            break
                        }
                        val call = when (parent) {
                            is KtCallExpression -> parent
                            is KtLambdaExpression -> parent.parent as? KtCallExpression
                            else -> null
                        }
                        if (call != null) {
                            val callee = call.calleeExpression?.text
                            if (callee in setOf("launch", "async", "produce", "actor")) {
                                inCoroutineBlock = true
                                break
                            }
                        }
                        parent = parent.parent
                    }
                    if (inCoroutineBlock && !hasSuspendPoint(expression)) {
                        findings.add("Line ${lineOf(expression.textRange.startOffset)}: Unbounded `while(true)` loop inside launch/async/suspend with no delay/yield/isActive check — may leak or block the coroutine indefinitely.")
                    }
                }
                super.visitWhileExpression(expression)
            }
        })
    }


    private fun hasSuspendPoint(loop: KtLoopExpression): Boolean {
        var found = false
        loop.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression?.text
                if (callee in suspendPointCallees) found = true
                super.visitCallExpression(expression)
            }
            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                if (expression.getReferencedName() == "isActive") found = true
                super.visitSimpleNameExpression(expression)
            }
        })
        return found
    }
}
