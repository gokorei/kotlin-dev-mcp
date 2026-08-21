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
        val exitProcessAliases = mutableSetOf("exitProcess")
        val systemAliases = mutableSetOf("System", "java.lang.System")
        val runtimeAliases = mutableSetOf("Runtime", "java.lang.Runtime")
        val directExitAliases = mutableSetOf<String>()
        val getRuntimeAliases = mutableSetOf<String>()

        var hasWildcardKotlinSystem = false
        var hasWildcardJavaLang = false

        for (imp in imports) {
            val fqn = imp.importedFqName?.asString() ?: continue
            val alias = imp.aliasName

            if (imp.isAllUnder) {
                if (fqn == "kotlin.system") hasWildcardKotlinSystem = true
                if (fqn == "java.lang") hasWildcardJavaLang = true
            } else {
                when (fqn) {
                    "kotlin.system.exitProcess" -> {
                        if (alias != null) exitProcessAliases.add(alias)
                        else exitProcessAliases.add("exitProcess")
                    }
                    "java.lang.System.exit" -> {
                        if (alias != null) directExitAliases.add(alias)
                        else directExitAliases.add("exit")
                    }
                    "java.lang.Runtime.getRuntime" -> {
                        if (alias != null) getRuntimeAliases.add(alias)
                        else getRuntimeAliases.add("getRuntime")
                    }
                    "java.lang.System", "System" -> {
                        if (alias != null) systemAliases.add(alias)
                    }
                    "java.lang.Runtime", "Runtime" -> {
                        if (alias != null) runtimeAliases.add(alias)
                    }
                }
            }
        }

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

                if (calleeName != null) {
                    // Check direct exitProcess calls or aliases
                    if (calleeName in exitProcessAliases || (hasWildcardKotlinSystem && calleeName == "exitProcess")) {
                        if (calleeName !in userDeclaredFunctionNames || imports.any { it.importedFqName?.asString() == "kotlin.system.exitProcess" }) {
                            foundDangerous = true
                        }
                    }

                    // Check direct java.lang.System.exit import aliases
                    if (calleeName in directExitAliases) {
                        if (calleeName !in userDeclaredFunctionNames) {
                            foundDangerous = true
                        }
                    }
                }

                val parent = expression.parent
                if (parent is KtDotQualifiedExpression && parent.selectorExpression == expression) {
                    val receiverExpr = parent.receiverExpression
                    val receiver = receiverExpr.text.trim()
                    val selector = calleeName

                    // Check System.exit or aliased System
                    if (selector == "exit") {
                        val matchedSystemAlias = systemAliases.firstOrNull { it == receiver }
                        if (matchedSystemAlias != null && matchedSystemAlias !in userDeclaredClassNames && matchedSystemAlias !in userDeclaredVarNames) {
                            foundDangerous = true
                        }
                        if (receiver == "kotlin.system") {
                            foundDangerous = true
                        }
                    }

                    // Check kotlin.system.exitProcess
                    if ((selector == "exitProcess" || selector in exitProcessAliases) && receiver == "kotlin.system") {
                        foundDangerous = true
                    }

                    // Check Runtime.getRuntime().halt / exit or aliased getRuntime().halt / exit
                    if (selector == "halt" || selector == "exit") {
                        // Check if receiver is a call to an imported getRuntime alias (e.g. hostRuntime().halt(1))
                        val receiverCallName = if (receiverExpr is KtCallExpression) {
                            receiverExpr.calleeExpression?.text?.trim()
                        } else null

                        if (receiverCallName != null && receiverCallName in getRuntimeAliases) {
                            if (receiverCallName !in userDeclaredFunctionNames) {
                                foundDangerous = true
                            }
                        }

                        // Check receiver matching any runtime alias (e.g. SysRuntime.getRuntime())
                        for (r in runtimeAliases) {
                            if (receiver == "$r.getRuntime()" || receiver == "$r.getRuntime()." || receiver.endsWith("$r.getRuntime()")) {
                                if (r !in userDeclaredClassNames && r !in userDeclaredVarNames) {
                                    foundDangerous = true
                                }
                            }
                        }
                    }
                }

                super.visitCallExpression(expression)
            }
        })

        return foundDangerous
    }
}
