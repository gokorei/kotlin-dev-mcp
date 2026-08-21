package com.gokorei.kotlinmcp.mutation

import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import java.util.UUID

/**
 * Internal descriptor of a discrete AST replacement edit.
 */
private data class AstEdit(
    val startOffset: Int,
    val endOffset: Int,
    val replacement: String,
    val originalText: String,
    val operator: MutationOperator,
    val description: String,
    val line: Int,
    val column: Int
)

/**
 * In-memory AST mutation generator using K2 PSI.
 * Traverses Kotlin source code in-memory and produces syntax mutants
 * across standard, extreme structural, and higher-order compound operators.
 */
class AstMutantGenerator {

    fun generate(
        code: String,
        includeExtremeOperators: Boolean = false,
        maxOrder: Int = 1
    ): List<AstMutant> {
        if (code.isBlank()) return emptyList()
        val file = K2SnippetFrontend.parsePsi(code) ?: return emptyList()
        val edits = mutableListOf<AstEdit>()

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
                    else -> emptyList()
                }

                for ((replacement, desc) in relationalReplacements) {
                    val range = opRef.textRange
                    val (line, col) = computeLineAndColumn(code, range.startOffset)
                    edits.add(
                        AstEdit(
                            startOffset = range.startOffset,
                            endOffset = range.endOffset,
                            replacement = replacement,
                            originalText = expression.text,
                            operator = MutationOperator.RELATIONAL_BOUNDARY,
                            description = desc,
                            line = line,
                            column = col
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
                    val (line, col) = computeLineAndColumn(code, range.startOffset)
                    edits.add(
                        AstEdit(
                            startOffset = range.startOffset,
                            endOffset = range.endOffset,
                            replacement = replacement,
                            originalText = expression.text,
                            operator = MutationOperator.ARITHMETIC_OPERATOR,
                            description = desc,
                            line = line,
                            column = col
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
                        val (line, col) = computeLineAndColumn(code, range.startOffset)
                        edits.add(
                            AstEdit(
                                startOffset = range.startOffset,
                                endOffset = range.endOffset,
                                replacement = replacement,
                                originalText = expression.text,
                                operator = MutationOperator.BOOLEAN_INVERSION,
                                description = "Negation inverted (removed '!')",
                                line = line,
                                column = col
                            )
                        )
                    }
                }
                super.visitPrefixExpression(expression)
            }

            // 3. Boolean Literal & Constant Mutations
            override fun visitConstantExpression(expression: KtConstantExpression) {
                val text = expression.text
                val range = expression.textRange
                val (line, col) = computeLineAndColumn(code, range.startOffset)

                if (text == "true" || text == "false") {
                    val replacement = if (text == "true") "false" else "true"
                    edits.add(
                        AstEdit(
                            startOffset = range.startOffset,
                            endOffset = range.endOffset,
                            replacement = replacement,
                            originalText = text,
                            operator = MutationOperator.BOOLEAN_INVERSION,
                            description = "Inverted boolean literal from '$text' to '$replacement'",
                            line = line,
                            column = col
                        )
                    )
                } else if (includeExtremeOperators) {
                    val intVal = text.toIntOrNull()
                    if (intVal != null) {
                        listOf((intVal + 1).toString(), (intVal - 1).toString()).forEach { mutatedNum ->
                            edits.add(
                                AstEdit(
                                    startOffset = range.startOffset,
                                    endOffset = range.endOffset,
                                    replacement = mutatedNum,
                                    originalText = text,
                                    operator = MutationOperator.LITERAL_MUTATION,
                                    description = "Altered integer constant $text -> $mutatedNum",
                                    line = line,
                                    column = col
                                )
                            )
                        }
                    }
                }
                super.visitConstantExpression(expression)
            }

            // 4. Return Value Mutations
            override fun visitReturnExpression(expression: KtReturnExpression) {
                val returnedExpr = expression.returnedExpression
                if (returnedExpr != null) {
                    val range = returnedExpr.textRange
                    val (line, col) = computeLineAndColumn(code, range.startOffset)
                    val replacements = listOf(
                        "0" to "Replaced return value with 0",
                        "false" to "Replaced return value with false"
                    )

                    for ((replacement, desc) in replacements) {
                        if (returnedExpr.text.trim() != replacement) {
                            edits.add(
                                AstEdit(
                                    startOffset = range.startOffset,
                                    endOffset = range.endOffset,
                                    replacement = replacement,
                                    originalText = expression.text,
                                    operator = MutationOperator.RETURN_VALUE,
                                    description = desc,
                                    line = line,
                                    column = col
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
                    val (line, col) = computeLineAndColumn(code, range.startOffset)
                    edits.add(
                        AstEdit(
                            startOffset = range.startOffset,
                            endOffset = range.endOffset,
                            replacement = "Unit",
                            originalText = targetElement.text,
                            operator = MutationOperator.VOID_CALL_REMOVAL,
                            description = "Omitted statement '${targetElement.text.take(30)}'",
                            line = line,
                            column = col
                        )
                    )
                }

                if (includeExtremeOperators) {
                    val calleeName = expression.calleeExpression?.text
                    val collectionReplacements = when (calleeName) {
                        "filter" -> listOf("filterNot" to "Inverted filter -> filterNot")
                        "filterNot" -> listOf("filter" to "Inverted filterNot -> filter")
                        "any" -> listOf("all" to "Inverted any -> all")
                        "all" -> listOf("any" to "Inverted all -> any")
                        "take" -> listOf("drop" to "Inverted take -> drop")
                        "drop" -> listOf("take" to "Inverted drop -> take")
                        "first" -> listOf("last" to "Inverted first -> last")
                        "last" -> listOf("first" to "Inverted last -> first")
                        else -> emptyList()
                    }
                    val callee = expression.calleeExpression
                    if (callee != null) {
                        for ((rep, desc) in collectionReplacements) {
                            val range = callee.textRange
                            val (line, col) = computeLineAndColumn(code, range.startOffset)
                            edits.add(
                                AstEdit(
                                    startOffset = range.startOffset,
                                    endOffset = range.endOffset,
                                    replacement = rep,
                                    originalText = callee.text,
                                    operator = MutationOperator.COLLECTION_OPERATOR,
                                    description = desc,
                                    line = line,
                                    column = col
                                )
                            )
                        }
                    }
                }

                super.visitCallExpression(expression)
            }

            // 6. Extreme: Condition Replacement (if (expr) -> if (true) / if (false))
            override fun visitIfExpression(expression: KtIfExpression) {
                if (includeExtremeOperators) {
                    val cond = expression.condition
                    if (cond != null && cond.text != "true" && cond.text != "false") {
                        val range = cond.textRange
                        val (line, col) = computeLineAndColumn(code, range.startOffset)
                        listOf("true", "false").forEach { boolRep ->
                            edits.add(
                                AstEdit(
                                    startOffset = range.startOffset,
                                    endOffset = range.endOffset,
                                    replacement = boolRep,
                                    originalText = cond.text,
                                    operator = MutationOperator.CONDITION_REPLACEMENT,
                                    description = "Replaced condition '${cond.text}' with '$boolRep'",
                                    line = line,
                                    column = col
                                )
                            )
                        }
                    }
                }
                super.visitIfExpression(expression)
            }
        })

        val mutants = mutableListOf<AstMutant>()

        // Generate First-Order Mutants (FOM)
        edits.forEachIndexed { index, edit ->
            val mutatedSource = replaceRange(code, edit.startOffset, edit.endOffset, edit.replacement)
            mutants.add(
                AstMutant(
                    id = "mutant-1st-${index + 1}-${UUID.randomUUID().toString().take(6)}",
                    operator = edit.operator,
                    line = edit.line,
                    column = edit.column,
                    originalSnippet = edit.originalText,
                    mutatedSnippet = edit.replacement,
                    mutatedSource = mutatedSource,
                    description = edit.description,
                    order = 1
                )
            )
        }

        // Generate Higher-Order Mutants (HOM) if maxOrder >= 2
        if (maxOrder >= 2 && edits.size >= 2) {
            val nonOverlappingPairs = mutableListOf<Pair<AstEdit, AstEdit>>()
            for (i in edits.indices) {
                for (j in (i + 1) until edits.size) {
                    val e1 = edits[i]
                    val e2 = edits[j]
                    // Ensure edits do not overlap in text range
                    if (e1.endOffset <= e2.startOffset || e2.endOffset <= e1.startOffset) {
                        nonOverlappingPairs.add(Pair(e1, e2))
                    }
                }
            }

            // Cap higher-order combinations to maintain sub-second performance
            val sampledPairs = nonOverlappingPairs.take(20)
            sampledPairs.forEachIndexed { idx, (e1, e2) ->
                // Apply right-to-left so offsets remain valid
                val sorted = listOf(e1, e2).sortedByDescending { it.startOffset }
                var src = code
                for (e in sorted) {
                    src = replaceRange(src, e.startOffset, e.endOffset, e.replacement)
                }

                mutants.add(
                    AstMutant(
                        id = "mutant-2nd-${idx + 1}-${UUID.randomUUID().toString().take(6)}",
                        operator = MutationOperator.HIGHER_ORDER_COMPOUND,
                        line = e1.line,
                        column = e1.column,
                        originalSnippet = "${e1.originalText} & ${e2.originalText}",
                        mutatedSnippet = "${e1.replacement} & ${e2.replacement}",
                        mutatedSource = src,
                        description = "Compound 2nd-order mutant: [${e1.description}] + [${e2.description}]",
                        order = 2
                    )
                )
            }
        }

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
