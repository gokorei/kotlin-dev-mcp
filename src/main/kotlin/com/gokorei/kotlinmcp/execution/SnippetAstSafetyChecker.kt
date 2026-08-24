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
        val processHandleAliases = mutableSetOf("ProcessHandle", "java.lang.ProcessHandle")
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
                    "java.lang.ProcessHandle", "ProcessHandle" -> {
                        if (alias != null) processHandleAliases.add(alias)
                    }
                }
            }
        }

        // Find user-declared functions/classes/variables in the snippet
        val userDeclaredFunctionNames = mutableSetOf<String>()
        val userDeclaredClassNames = mutableSetOf<String>()
        val userDeclaredVarNames = mutableSetOf<String>()
        val runtimeInstanceVariables = mutableSetOf<String>()
        val processHandleVariables = mutableSetOf<String>()

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
                val name = property.name
                if (name != null) {
                    userDeclaredVarNames.add(name)
                    val initText = property.initializer?.text?.trim().orEmpty()
                    val typeText = property.typeReference?.text?.trim().orEmpty()
                    if (initText.contains("getRuntime()") || typeText in runtimeAliases || typeText.endsWith(".Runtime")) {
                        runtimeInstanceVariables.add(name)
                    }
                    if (initText.contains("ProcessHandle") || typeText in processHandleAliases || typeText.endsWith(".ProcessHandle")) {
                        processHandleVariables.add(name)
                    }
                }
                super.visitProperty(property)
            }
            override fun visitParameter(parameter: KtParameter) {
                parameter.name?.let { userDeclaredVarNames.add(it) }
                super.visitParameter(parameter)
            }
            override fun visitDestructuringDeclarationEntry(multiDeclarationEntry: KtDestructuringDeclarationEntry) {
                multiDeclarationEntry.name?.let { userDeclaredVarNames.add(it) }
                super.visitDestructuringDeclarationEntry(multiDeclarationEntry)
            }
        })

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
                val callableName = expression.callableReference.text.trim()
                val receiver = expression.receiverExpression?.text?.trim()

                if (callableName == "exit" && (receiver == null || receiver in systemAliases || receiver in runtimeAliases)) {
                    foundDangerous = true
                }
                if (callableName == "exitProcess" && (receiver == null || receiver == "kotlin.system")) {
                    foundDangerous = true
                }
                if (callableName == "halt") {
                    foundDangerous = true
                }
                if ((callableName == "destroy" || callableName == "destroyForcibly") && (receiver == null || receiver in processHandleVariables || receiver.contains("ProcessHandle"))) {
                    foundDangerous = true
                }
                super.visitCallableReferenceExpression(expression)
            }

            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression
                val calleeName = callee?.text?.trim()
                val parent = expression.parent
                val isQualifiedSelector = parent is KtDotQualifiedExpression && parent.selectorExpression == expression

                if (!isQualifiedSelector && calleeName != null) {
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

                if (isQualifiedSelector && parent is KtDotQualifiedExpression) {
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
                    if (selector in exitProcessAliases && receiver == "kotlin.system") {
                        foundDangerous = true
                    }

                    // Check Runtime.getRuntime().halt / exit or aliased getRuntime().halt / exit or instance variable
                    if (selector == "halt" || selector == "exit") {
                        if (receiver in runtimeInstanceVariables) {
                            foundDangerous = true
                        }

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

                    // Check ProcessHandle.current().destroy() / destroyForcibly()
                    if (selector == "destroy" || selector == "destroyForcibly") {
                        if (receiver in processHandleVariables || receiver.contains("ProcessHandle") || receiver.endsWith(".current()")) {
                            foundDangerous = true
                        }
                    }
                }

                super.visitCallExpression(expression)
            }
        })

        return foundDangerous
    }
}
