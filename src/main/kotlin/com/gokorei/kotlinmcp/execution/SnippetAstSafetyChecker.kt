package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.psi.*

/**
 * Static AST safety inspector using K2 PSI.
 * Analyzes snippet code to detect operations that would terminate or disrupt
 * the host JVM (e.g. System.exit, exitProcess, Runtime.halt) so they can be
 * forced into isolated subprocess execution.
 */
object SnippetAstSafetyChecker {

    fun containsHostTerminatingCalls(code: String): Boolean {
        if (code.isBlank()) return false
        val psi = K2SnippetFrontend.parsePsi(code) ?: return true
        var foundDangerous = false

        // Check imports
        val imports = psi.importDirectives
        val exitProcessAlias = imports.firstOrNull {
            val fqn = it.importedFqName?.asString()
            fqn == "kotlin.system.exitProcess"
        }?.aliasName ?: "exitProcess"

        val hasWildcardKotlinSystem = imports.any { it.importedFqName?.asString() == "kotlin.system" && it.isAllUnder }
        val hasExplicitExitProcessImport = imports.any { it.importedFqName?.asString() == "kotlin.system.exitProcess" }

        // Find user-declared functions/classes/variables in the snippet
        val userDeclaredFunctionNames = mutableSetOf<String>()
        val userDeclaredClassNames = mutableSetOf<String>()
        val userDeclaredVarNames = mutableSetOf<String>()

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                function.name?.let { userDeclaredFunctionNames.add(it) }
                super.visitNamedFunction(function)
            }
            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                classOrObject.name?.let { userDeclaredClassNames.add(it) }
                super.visitClassOrObject(classOrObject)
            }
            override fun visitProperty(property: KtProperty) {
                property.name?.let { userDeclaredVarNames.add(it) }
                super.visitProperty(property)
            }
            override fun visitParameter(parameter: KtParameter) {
                parameter.name?.let { userDeclaredVarNames.add(it) }
                super.visitParameter(parameter)
            }
        })

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression
                val calleeName = callee?.text?.trim()

                // Check for exitProcess or its alias
                if (calleeName != null) {
                    if (calleeName == exitProcessAlias || (hasWildcardKotlinSystem && calleeName == "exitProcess")) {
                        if (hasExplicitExitProcessImport || hasWildcardKotlinSystem || calleeName !in userDeclaredFunctionNames) {
                            foundDangerous = true
                        }
                    }
                }

                val parent = expression.parent
                if (parent is KtDotQualifiedExpression && parent.selectorExpression == expression) {
                    val receiver = parent.receiverExpression.text.trim()
                    val selector = calleeName

                    if (selector == "exit" && (receiver == "System" || receiver == "java.lang.System")) {
                        if (receiver !in userDeclaredClassNames && receiver !in userDeclaredVarNames) {
                            foundDangerous = true
                        }
                    }

                    if ((selector == "halt" || selector == "exit") &&
                        (receiver == "Runtime.getRuntime()" || receiver == "java.lang.Runtime.getRuntime()")) {
                        foundDangerous = true
                    }
                }

                super.visitCallExpression(expression)
            }
        })

        return foundDangerous
    }
}
