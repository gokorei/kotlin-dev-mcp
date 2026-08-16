package com.gokorei.kotlinmcp.refactoring

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTryExpression

/**
 * Strategy component for inspecting AST snippets and providing idiomatic Kotlin code suggestions
 * (runCatching, scope functions, extension mappers).
 */
class IdiomaticKotlinSuggestor {

    fun suggestIdiomaticKotlin(code: String): KotlinMcpResult {
        return suggestIdioms(code)
    }

    fun suggestIdioms(code: String): KotlinMcpResult {
        val suggestions = mutableListOf<String>()
        val psi = K2SnippetFrontend.parsePsi(code)

        if (psi != null) {
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitTryExpression(expression: KtTryExpression) {
                    if (expression.catchClauses.isNotEmpty()) {
                        suggestions.add("""
                            ### Use `runCatching` / `Result` for Exception Safety:
                            ```kotlin
                            fun parse(input: String): Result<Int> = runCatching {
                                input.toInt()
                            }
                            ```
                        """.trimIndent())
                    }
                    super.visitTryExpression(expression)
                }

                override fun visitIfExpression(expression: KtIfExpression) {
                    val condText = expression.condition?.text.orEmpty()
                    if (condText.contains("!= null")) {
                        suggestions.add("""
                            ### Leverage Kotlin Scope Functions (`let` / `run`):
                            ```kotlin
                            input?.let { safeValue ->
                                process(safeValue)
                            }
                            ```
                        """.trimIndent())
                    }
                    super.visitIfExpression(expression)
                }

                override fun visitClass(klass: KtClass) {
                    if (klass.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD)) {
                        val mapperName = klass.name.orEmpty()
                        val typeParams = klass.typeParameters.map { it.name.orEmpty() }.filter { it.isNotBlank() }
                        val (fromType, toType) = if (typeParams.size >= 2) typeParams[0] to typeParams[1] else "E" to "T"

                        var sourceType = fromType
                        klass.declarations.filterIsInstance<KtNamedFunction>().forEach { fn ->
                            if (fn.name == "map") {
                                val paramType = fn.valueParameters.firstOrNull()?.typeReference?.text.orEmpty()
                                if (paramType.isNotBlank()) sourceType = paramType
                            }
                        }

                        val extName = "${toType.lowercase()}From$sourceType"
                        suggestions.add("""
                            ### Replace Abstract Mapper with Top-Level Extension Mapper:
                            The abstract class `$mapperName<$fromType, $toType>` adds boilerplate (a map method that must be
                            implemented per subclass). Prefer a concise, stateless top-level extension function:
                            ```kotlin
                            fun $toType.$extName(): $sourceType {
                                // TODO: migrate the mapping logic here
                                return TODO()
                            }
                            ```
                            Top-level extension mappers are pure, trivially unit-testable, and remove the subclass/override ceremony.
                        """.trimIndent())
                    }
                    super.visitClass(klass)
                }
            })
        }

        val content = if (suggestions.isNotEmpty()) {
            "# Idiomatic Kotlin Code Suggestions\n\n" + suggestions.distinct().joinToString("\n\n")
        } else {
            "# Idiomatic Kotlin Code Suggestions\nCode already uses clean Kotlin idioms! Consider value classes or sealed interfaces if expanding hierarchy."
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("suggestionsCount" to suggestions.distinct().size.toString())
        )
    }
}
