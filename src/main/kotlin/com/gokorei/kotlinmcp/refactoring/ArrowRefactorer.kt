package com.gokorei.kotlinmcp.refactoring

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTryExpression

/**
 * Strategy component for Arrow 1.x (Either monad) vs 2.x (Raise DSL paradigm) refactorings.
 */
class ArrowRefactorer {

    fun migrateArrowRaise(code: String): KotlinMcpResult {
        return refactorToArrow(code, null)
    }

    fun refactorToArrow(code: String, legacy: String?): KotlinMcpResult {
        val legacyMode = legacy?.trim()?.equals("true", ignoreCase = true) == true
        val psi = K2SnippetFrontend.parsePsi(code)

        var eitherRewrite: String? = null
        var raiseRewrite: String? = null

        if (psi != null) {
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitNamedFunction(function: KtNamedFunction) {
                    val fnName = function.name.orEmpty()
                    val params = function.valueParameterList?.text?.removeSurrounding("(", ")").orEmpty()
                    val returnType = function.typeReference?.text.orEmpty()
                    val body = function.bodyExpression

                    var hasTryCatch = false
                    var catchReturnsNullOrDefault = false
                    var tryExprText = ""

                    body?.accept(object : KtTreeVisitorVoid() {
                        override fun visitTryExpression(expression: KtTryExpression) {
                            hasTryCatch = true
                            val tryBlock = expression.tryBlock
                            val firstStmt = tryBlock.statements.firstOrNull()
                            tryExprText = (firstStmt as? KtReturnExpression)?.returnedExpression?.text
                                ?: firstStmt?.text
                                ?: tryBlock.text.removeSurrounding("{", "}").trim()

                            expression.catchClauses.forEach { clause ->
                                val catchText = clause.catchBody?.text.orEmpty()
                                if (catchText.contains("return ") || clause.catchBody?.children?.any { it is KtReturnExpression } == true) {
                                    catchReturnsNullOrDefault = true
                                }
                            }
                            super.visitTryExpression(expression)
                        }
                    })

                    if (fnName.isNotBlank() && hasTryCatch && catchReturnsNullOrDefault) {
                        val successType = if (returnType.endsWith("?")) returnType.removeSuffix("?") else returnType.ifBlank { "Any" }
                        val expr = tryExprText.ifBlank { successType }
                        val imports = if (legacyMode) {
                            "import arrow.core.Either\nimport arrow.core.left\nimport arrow.core.right"
                        } else {
                            "import arrow.core.Either"
                        }
                        eitherRewrite = if (legacyMode) {
                            """
                            $imports

                            fun $fnName($params): Either<Throwable, $successType> =
                                runCatching { $expr }.fold(
                                    onSuccess = { it.right() },
                                    onFailure = { it.left() }
                                )
                            """.trimIndent()
                        } else {
                            """
                            $imports

                            fun $fnName($params): Either<Throwable, $successType> =
                                Either.catch { $expr }
                            """.trimIndent()
                        }
                    }

                    super.visitNamedFunction(function)
                }
            })

            var hasValidationList = false
            var hasListAddCall = false
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitProperty(property: KtProperty) {
                    val propName = property.name.orEmpty()
                    val initText = property.initializer?.text.orEmpty()
                    if ((propName == "errors" || propName == "problems") && initText.contains("mutableListOf")) {
                        hasValidationList = true
                    }
                    super.visitProperty(property)
                }
                override fun visitCallExpression(expression: KtCallExpression) {
                    if (expression.calleeExpression?.text == "add") {
                        hasListAddCall = true
                    }
                    super.visitCallExpression(expression)
                }
            })

            if (hasValidationList && hasListAddCall) {
                val imports = "import arrow.core.nonEmptyListOf\nimport arrow.core.raise.raise\nimport arrow.core.validNel\nimport arrow.core.invalidNel\nimport arrow.core.raise.ensure"
                raiseRewrite = """
                    $imports

                    // Arrow 2.x Raise DSL validation paradigm
                    // Ensure invariants declaratively instead of accumulating error lists manually.
                """.trimIndent()
            }
        }

        val targetVersion = if (legacyMode) "Arrow 1.x (Either monad syntax)" else "Arrow 2.x (Raise DSL paradigm)"
        val content = buildString {
            appendLine("# Arrow Refactoring ($targetVersion)")
            appendLine()
            if (eitherRewrite != null) {
                appendLine("## Exception Handling → Either")
                appendLine("```kotlin")
                appendLine(eitherRewrite)
                appendLine("```")
            } else if (raiseRewrite != null) {
                appendLine("## Accumulation Validation → Raise DSL")
                appendLine("```kotlin")
                appendLine(raiseRewrite)
                appendLine("```")
            } else {
                appendLine("No refactorable try-catch or manual validation list accumulation detected.")
                appendLine("Code left unchanged.")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("targetVersion" to targetVersion)
        )
    }

    fun suggestArrowRefactorings(code: String): KotlinMcpResult {
        return refactorToArrow(code, null)
    }
}
