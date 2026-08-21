package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Static AST safety inspector using K2 PSI.
 * Analyzes snippet code to detect operations that would terminate or disrupt
 * the host JVM (e.g. System.exit, exitProcess, Runtime.halt) so they can be
 * forced into isolated subprocess execution.
 */
object SnippetAstSafetyChecker {

    private val terminatingFunctionNames = setOf(
        "exitProcess", "exit", "halt"
    )

    fun containsHostTerminatingCalls(code: String): Boolean {
        val psi = K2SnippetFrontend.parsePsi(code) ?: return false
        var foundDangerous = false

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression
                val calleeName = callee?.text?.trim()
                if (calleeName in terminatingFunctionNames) {
                    val parent = expression.parent
                    if (parent is KtDotQualifiedExpression) {
                        val receiver = parent.receiverExpression.text.trim()
                        if (receiver.endsWith("System") || receiver.contains("Runtime") || receiver.contains("Runtime.getRuntime()")) {
                            foundDangerous = true
                        }
                    } else if (calleeName == "exitProcess" || calleeName == "exit") {
                        foundDangerous = true
                    }
                }
                super.visitCallExpression(expression)
            }

            override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                val text = expression.text.replace("\\s+".toRegex(), "")
                if (text.contains("System.exit(") || text.contains("Runtime.getRuntime().halt(") || text.contains("Runtime.getRuntime().exit(")) {
                    foundDangerous = true
                }
                super.visitDotQualifiedExpression(expression)
            }
        })

        return foundDangerous
    }
}
