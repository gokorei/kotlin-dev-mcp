package com.gokorei.kotlinmcp.mutation

import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import java.util.UUID

/**
 * In-memory AST mutation generator using K2 PSI and pluggable AstMutator SPI.
 * Traverses Kotlin source code in-memory and delegates mutation synthesis to registered
 * AST mutator rules across standard, extreme structural, and higher-order compound operators.
 */
class AstMutantGenerator(
    private val registry: MutatorRegistry = MutatorRegistry.default()
) {

    fun generate(
        code: String,
        includeExtremeOperators: Boolean = false,
        maxOrder: Int = 1
    ): List<AstMutant> {
        if (code.isBlank()) return emptyList()
        val file = K2SnippetFrontend.parsePsi(code) ?: return emptyList()
        val context = MutationContext(code, file)
        val activeMutators = registry.mutators(includeExtremeOperators)
        val edits = mutableListOf<AstEdit>()

        file.accept(object : KtTreeVisitorVoid() {
            override fun visitElement(element: PsiElement) {
                for (mutator in activeMutators) {
                    if (mutator.canMutate(element)) {
                        edits.addAll(mutator.mutate(element, context))
                    }
                }
                super.visitElement(element)
            }
        })

        val mutants = mutableListOf<AstMutant>()

        // 1. Generate First-Order Mutants (FOM)
        edits.forEachIndexed { index, edit ->
            val mutatedSource = replaceRange(code, edit.startOffset, edit.endOffset, edit.replacement)
            val lineCol = computeLineAndColumn(code, edit.startOffset)
            mutants.add(
                AstMutant(
                    id = "mutant-1st-${index + 1}-${UUID.randomUUID().toString().take(6)}",
                    operator = edit.operator,
                    line = lineCol.first,
                    column = lineCol.second,
                    originalSnippet = edit.originalText,
                    mutatedSnippet = edit.replacement,
                    mutatedSource = mutatedSource,
                    description = edit.description,
                    order = 1
                )
            )
        }

        // 2. Generate Higher-Order Mutants (HOM) if maxOrder >= 2
        if (maxOrder >= 2 && edits.size >= 2) {
            val maxSampled = 20
            val sampledPairs = mutableListOf<Pair<AstEdit, AstEdit>>()
            val totalEdits = edits.size
            val stride = (totalEdits / 10).coerceAtLeast(1)

            outer@ for (i in 0 until totalEdits step stride) {
                for (j in (i + 1) until totalEdits) {
                    val e1 = edits[i]
                    val e2 = edits[j]
                    // Ensure edits do not overlap in text range
                    if (e1.endOffset <= e2.startOffset || e2.endOffset <= e1.startOffset) {
                        sampledPairs.add(Pair(e1, e2))
                        if (sampledPairs.size >= maxSampled) break@outer
                    }
                }
            }

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
}
