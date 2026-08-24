package com.gokorei.kotlinmcp.mutation

import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import java.util.UUID

/**
 * In-memory AST mutation generator using K2 PSI.
 * Traverses Kotlin source code in-memory and produces syntax mutants
 * across 5 core mutation operator categories.
 */
class AstMutantGenerator {

    fun generate(code: String): List<AstMutant> {
        if (code.isBlank()) return emptyList()
        val file = K2SnippetFrontend.parsePsi(code) ?: return emptyList()
        val mutants = mutableListOf<AstMutant>()

        file.accept(object : KtTreeVisitorVoid() {

            // 1. Relational Boundary & Arithmetic Mutations
            override fun visitBinaryExpression(expression: KtBinaryExpression) {
                val opRef = expression.operationReference
                val opText = opRef.text
                val opElement = opRef.operationSignTokenType

                val relationalReplacements = when (opElement) {
                    KtTokens.LT -> listOf("<=" to "Replaced < with <=")
                    KtTokens.LTEQ -> listOf("<" to "Replaced <= with <")
                    KtTokens.GT -> listOf(">=" to "Replaced > with >=")
                    KtTokens.GTEQ -> listOf(">" to "Replaced >= with >")
                    KtTokens.EQEQ -> listOf("!=" to "Replaced == with !=")
                    KtTokens.EXCLEQ -> listOf("==" to "Replaced != with ==")
                    KtTokens.ANDAND -> listOf("||" to "Replaced && with ||")
                    KtTokens.OROR -> listOf("&&" to "Replaced || with &&")
                    else -> emptyList()
                }

                for ((replacement, desc) in relationalReplacements) {
                    val range = opRef.textRange
                    val mutatedCode = replaceRange(code, range.startOffset, range.endOffset, replacement)
                    val (line, col) = computeLineAndColumn(code, range.startOffset)
                    val exprStart = expression.textRange.startOffset
                    val localSnippet = replaceRange(
                        expression.text,
                        range.startOffset - exprStart,
                        range.endOffset - exprStart,
                        replacement
                    )
                    val operator = if (opElement == KtTokens.ANDAND || opElement == KtTokens.OROR) {
                        MutationOperator.BOOLEAN_INVERSION
                    } else {
                        MutationOperator.RELATIONAL_BOUNDARY
                    }
                    mutants.add(
                        AstMutant(
                            id = "mutant-${mutants.size + 1}-${UUID.randomUUID().toString().take(6)}",
                            operator = operator,
                            line = line,
                            column = col,
                            originalSnippet = expression.text,
                            mutatedSnippet = localSnippet,
                            mutatedSource = mutatedCode,
                            description = desc
                        )
                    )
                }

                val arithmeticReplacements = when (opElement) {
                    KtTokens.PLUS -> listOf("-" to "Replaced + with -")
                    KtTokens.MINUS -> listOf("+" to "Replaced - with +")
                    KtTokens.MUL -> listOf("/" to "Replaced * with /")
                    KtTokens.DIV -> listOf("*" to "Replaced / with *")
                    KtTokens.PERC -> listOf("*" to "Replaced % with *")
                    else -> emptyList()
                }

                for ((replacement, desc) in arithmeticReplacements) {
                    val range = opRef.textRange
                    val mutatedCode = replaceRange(code, range.startOffset, range.endOffset, replacement)
                    val (line, col) = computeLineAndColumn(code, range.startOffset)
                    val exprStart = expression.textRange.startOffset
                    val localSnippet = replaceRange(
                        expression.text,
                        range.startOffset - exprStart,
                        range.endOffset - exprStart,
                        replacement
                    )
                    mutants.add(
                        AstMutant(
                            id = "mutant-${mutants.size + 1}-${UUID.randomUUID().toString().take(6)}",
                            operator = MutationOperator.ARITHMETIC_OPERATOR,
                            line = line,
                            column = col,
                            originalSnippet = expression.text,
                            mutatedSnippet = localSnippet,
                            mutatedSource = mutatedCode,
                            description = desc
                        )
                    )
                }

                super.visitBinaryExpression(expression)
            }

            // 2. Boolean Inversions on Prefix Expressions (!flag -> flag)
            override fun visitPrefixExpression(expression: KtPrefixExpression) {
                if (expression.operationToken == KtTokens.EXCL) {
                    val baseExpr = expression.baseExpression
                    if (baseExpr != null) {
                        val range = expression.textRange
                        val replacement = baseExpr.text
                        val mutatedCode = replaceRange(code, range.startOffset, range.endOffset, replacement)
                        val (line, col) = computeLineAndColumn(code, range.startOffset)
                        mutants.add(
                            AstMutant(
                                id = "mutant-${mutants.size + 1}-${UUID.randomUUID().toString().take(6)}",
                                operator = MutationOperator.BOOLEAN_INVERSION,
                                line = line,
                                column = col,
                                originalSnippet = expression.text,
                                mutatedSnippet = replacement,
                                mutatedSource = mutatedCode,
                                description = "Negation inverted (removed '!')"
                            )
                        )
                    }
                }
                super.visitPrefixExpression(expression)
            }

            // 3. Boolean Literal Inversions (true <-> false)
            override fun visitConstantExpression(expression: KtConstantExpression) {
                val text = expression.text
                if (text == "true" || text == "false") {
                    val replacement = if (text == "true") "false" else "true"
                    val range = expression.textRange
                    val mutatedCode = replaceRange(code, range.startOffset, range.endOffset, replacement)
                    val (line, col) = computeLineAndColumn(code, range.startOffset)
                    mutants.add(
                        AstMutant(
                            id = "mutant-${mutants.size + 1}-${UUID.randomUUID().toString().take(6)}",
                            operator = MutationOperator.BOOLEAN_INVERSION,
                            line = line,
                            column = col,
                            originalSnippet = text,
                            mutatedSnippet = replacement,
                            mutatedSource = mutatedCode,
                            description = "Inverted boolean literal from '$text' to '$replacement'"
                        )
                    )
                }
                super.visitConstantExpression(expression)
            }

            // 4. Return Value Mutations
            override fun visitReturnExpression(expression: KtReturnExpression) {
                val returnedExpr = expression.returnedExpression
                if (returnedExpr != null) {
                    val range = returnedExpr.textRange
                    val text = returnedExpr.text
                    val replacements = mutableListOf<Pair<String, String>>()

                    when (returnedExpr) {
                        is KtStringTemplateExpression -> {
                            if (returnedExpr.entries.isEmpty()) {
                                replacements.add("\"mutated\"" to "Replaced empty return string with \"mutated\"")
                            } else {
                                replacements.add("\"\"" to "Replaced return string with \"\"")
                            }
                        }
                        is KtConstantExpression -> {
                            val constText = returnedExpr.text
                            val tokenType = returnedExpr.node.elementType
                            if (tokenType == KtTokens.TRUE_KEYWORD || constText == "true") {
                                replacements.add("false" to "Replaced return boolean with false")
                            } else if (tokenType == KtTokens.FALSE_KEYWORD || constText == "false") {
                                replacements.add("true" to "Replaced return boolean with true")
                            } else if (tokenType == KtTokens.NULL_KEYWORD || constText == "null") {
                                replacements.add("\"\"" to "Replaced return null with \"\"")
                            } else if (constText == "0" || constText == "0L") {
                                replacements.add("1" to "Replaced return 0 with 1")
                            } else {
                                replacements.add("0" to "Replaced return value with 0")
                            }
                        }
                        is KtPrefixExpression -> {
                            if (returnedExpr.operationToken == KtTokens.EXCL) {
                                val base = returnedExpr.baseExpression
                                if (base != null) {
                                    replacements.add(base.text to "Removed negation from return expression")
                                }
                            } else {
                                replacements.add("0" to "Replaced return value with 0")
                                replacements.add("false" to "Replaced return value with false")
                                replacements.add("null" to "Replaced return value with null")
                            }
                        }
                        else -> {
                            // General fallback replacements for complex/object return expressions
                            replacements.add("0" to "Replaced return value with 0")
                            replacements.add("false" to "Replaced return value with false")
                            replacements.add("null" to "Replaced return value with null")
                        }
                    }

                    for ((replacement, desc) in replacements) {
                        if (text != replacement) {
                            val mutatedCode = replaceRange(code, range.startOffset, range.endOffset, replacement)
                            val (line, col) = computeLineAndColumn(code, range.startOffset)
                            mutants.add(
                                AstMutant(
                                    id = "mutant-${mutants.size + 1}-${UUID.randomUUID().toString().take(6)}",
                                    operator = MutationOperator.RETURN_VALUE,
                                    line = line,
                                    column = col,
                                    originalSnippet = expression.text,
                                    mutatedSnippet = "return $replacement",
                                    mutatedSource = mutatedCode,
                                    description = desc
                                )
                            )
                        }
                    }
                }
                super.visitReturnExpression(expression)
            }

            // 5. Void Call Omissions
            override fun visitCallExpression(expression: KtCallExpression) {
                val parent = expression.parent
                val isStandaloneStatement = parent is KtBlockExpression ||
                    (parent is KtDotQualifiedExpression && parent.parent is KtBlockExpression)

                val targetElement: PsiElement = if (parent is KtDotQualifiedExpression && parent.parent is KtBlockExpression) {
                    parent
                } else expression

                if (isStandaloneStatement) {
                    val range = targetElement.textRange
                    val mutatedCode = replaceRange(code, range.startOffset, range.endOffset, "Unit")
                    val (line, col) = computeLineAndColumn(code, range.startOffset)
                    mutants.add(
                        AstMutant(
                            id = "mutant-${mutants.size + 1}-${UUID.randomUUID().toString().take(6)}",
                            operator = MutationOperator.VOID_CALL_REMOVAL,
                            line = line,
                            column = col,
                            originalSnippet = targetElement.text,
                            mutatedSnippet = "/* omitted */ Unit",
                            mutatedSource = mutatedCode,
                            description = "Omitted statement '${targetElement.text.take(30)}'"
                        )
                    )
                }
                super.visitCallExpression(expression)
            }
        })

        return mutants.distinctBy { it.mutatedSource }
    }

    private fun replaceRange(source: String, start: Int, end: Int, replacement: String): String {
        return source.substring(0, start) + replacement + source.substring(end)
    }

    private fun computeLineAndColumn(source: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var lastLineBreak = -1
        for (i in 0 until offset.coerceAtMost(source.length)) {
            if (source[i] == '\n') {
                line++
                lastLineBreak = i
            }
        }
        val col = offset - lastLineBreak
        return Pair(line, col)
    }
}
