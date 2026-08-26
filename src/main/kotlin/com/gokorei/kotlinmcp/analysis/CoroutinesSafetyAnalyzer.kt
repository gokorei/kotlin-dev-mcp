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
        inspectAndroidCoroutinesScopes(psi, findings)

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


    private fun inspectAndroidCoroutinesScopes(psi: KtFile, findings: MutableList<String>) {
        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitClass(ktClass: org.jetbrains.kotlin.psi.KtClass) {
                val superNames = ktClass.superTypeListEntries.mapNotNull { it.typeReference?.text?.trim() }
                val isViewModel = superNames.any { it == "ViewModel" || it == "AndroidViewModel" || it.endsWith(".ViewModel") || it.endsWith(".AndroidViewModel") }
                val isAndroidUi = superNames.any {
                    it in setOf("Activity", "ComponentActivity", "AppCompatActivity", "Fragment", "DialogFragment") ||
                        it.endsWith("Activity") || it.endsWith("Fragment")
                }

                if (isViewModel) {
                    ktClass.accept(object : KtTreeVisitorVoid() {
                        override fun visitClass(nestedClass: org.jetbrains.kotlin.psi.KtClass) {
                            if (nestedClass != ktClass) return // Do not traverse nested classes as part of the outer ViewModel
                            super.visitClass(nestedClass)
                        }

                        override fun visitCallExpression(call: org.jetbrains.kotlin.psi.KtCallExpression) {
                            val callee = call.calleeExpression?.text
                            if (callee == "launch" || callee == "async") {
                                var hasViewModelScope = false

                                // Check direct receiver
                                val parentDot = call.parent as? org.jetbrains.kotlin.psi.KtDotQualifiedExpression
                                if (parentDot?.receiverExpression?.text?.contains("viewModelScope") == true) {
                                    hasViewModelScope = true
                                }

                                // Check ancestor scopes (inherited CoroutineScope in viewModelScope.launch { ... })
                                var ancestor: org.jetbrains.kotlin.com.intellij.psi.PsiElement? = call.parent
                                while (ancestor != null && ancestor != ktClass && !hasViewModelScope) {
                                    if (ancestor is org.jetbrains.kotlin.psi.KtDotQualifiedExpression) {
                                        if (ancestor.receiverExpression.text.contains("viewModelScope")) {
                                            hasViewModelScope = true
                                        }
                                    } else if (ancestor is org.jetbrains.kotlin.psi.KtCallExpression) {
                                        val ancCallee = ancestor.calleeExpression?.text
                                        if (ancCallee in setOf("coroutineScope", "supervisorScope", "withContext")) {
                                            hasViewModelScope = true
                                        }
                                    }
                                    ancestor = ancestor.parent
                                }

                                if (!hasViewModelScope) {
                                    findings.add("⚠️ Coroutine launched in ViewModel `${ktClass.name}` without `viewModelScope`. Use `viewModelScope.launch { ... }` so coroutines cancel automatically when the ViewModel is cleared.")
                                }
                            }
                            super.visitCallExpression(call)
                        }
                    })
                }

                if (isAndroidUi) {
                    ktClass.accept(object : KtTreeVisitorVoid() {
                        override fun visitClass(nestedClass: org.jetbrains.kotlin.psi.KtClass) {
                            if (nestedClass != ktClass) return
                            super.visitClass(nestedClass)
                        }

                        override fun visitCallExpression(call: org.jetbrains.kotlin.psi.KtCallExpression) {
                            val callee = call.calleeExpression?.text
                            if (callee == "collect" || callee == "collectLatest") {
                                var inRepeatOnLifecycle = false

                                // Check receiver chain for .flowWithLifecycle(...)
                                val parentDot = call.parent as? org.jetbrains.kotlin.psi.KtDotQualifiedExpression
                                val receiverExpr = parentDot?.receiverExpression
                                if (receiverExpr is org.jetbrains.kotlin.psi.KtCallExpression && receiverExpr.calleeExpression?.text == "flowWithLifecycle") {
                                    inRepeatOnLifecycle = true
                                } else if (receiverExpr is org.jetbrains.kotlin.psi.KtDotQualifiedExpression && receiverExpr.selectorExpression?.text?.startsWith("flowWithLifecycle") == true) {
                                    inRepeatOnLifecycle = true
                                }

                                // Check ancestor calls for repeatOnLifecycle or flowWithLifecycle
                                var p: org.jetbrains.kotlin.com.intellij.psi.PsiElement? = call.parent
                                while (p != null && p != ktClass && !inRepeatOnLifecycle) {
                                    if (p is org.jetbrains.kotlin.psi.KtCallExpression) {
                                        val pCallee = p.calleeExpression?.text.orEmpty()
                                        if (pCallee == "repeatOnLifecycle" || pCallee == "flowWithLifecycle") {
                                            inRepeatOnLifecycle = true
                                        }
                                    }
                                    p = p.parent
                                }

                                if (!inRepeatOnLifecycle) {
                                    findings.add("⚠️ Flow collection in Android UI component `${ktClass.name}` detected without `repeatOnLifecycle(Lifecycle.State.STARTED)`. Wrap collection in `lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { ... } }` to prevent UI leaks and background collection.")
                                }
                            }
                            super.visitCallExpression(call)
                        }
                    })
                }

                super.visitClass(ktClass)
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
