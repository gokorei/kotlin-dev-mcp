package com.gokorei.kotlinmcp.mutation

import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AstMutatorsTest {

    private fun parseAndMutate(code: String, mutator: AstMutator): List<AstEdit> {
        val file = K2SnippetFrontend.parsePsi(code) ?: return emptyList()
        val context = MutationContext(code, file)
        val edits = mutableListOf<AstEdit>()
        file.accept(object : KtTreeVisitorVoid() {
            override fun visitElement(element: PsiElement) {
                if (mutator.canMutate(element)) {
                    edits.addAll(mutator.mutate(element, context))
                }
                super.visitElement(element)
            }
        })
        return edits
    }

    @Test
    fun `RelationalBoundaryMutator mutates comparison operators`() {
        val mutator = RelationalBoundaryMutator()
        val code = "fun test(x: Int) = x > 10 && x <= 20 && x == 15"
        val edits = parseAndMutate(code, mutator)

        assertTrue(edits.isNotEmpty())
        assertTrue(edits.any { it.replacement == ">=" && it.description.contains("Replaced > with >=") })
        assertTrue(edits.any { it.replacement == "<" && it.description.contains("Replaced <= with <") })
        assertTrue(edits.any { it.replacement == "!=" && it.description.contains("Replaced == with !=") })
    }

    @Test
    fun `ArithmeticOperatorMutator mutates arithmetic binary operations`() {
        val mutator = ArithmeticOperatorMutator()
        val code = "fun calc(a: Int, b: Int) = a + b * 2 - (a / b) % 3"
        val edits = parseAndMutate(code, mutator)

        assertTrue(edits.isNotEmpty())
        assertTrue(edits.any { it.replacement == "-" })
        assertTrue(edits.any { it.replacement == "/" })
        assertTrue(edits.any { it.replacement == "+" })
        assertTrue(edits.any { it.replacement == "*" })
    }

    @Test
    fun `BooleanInversionMutator inverts negation prefixes and boolean literals`() {
        val mutator = BooleanInversionMutator()
        val code = "val x = !isActive || isReady == true && isBlocked == false"
        val edits = parseAndMutate(code, mutator)

        assertTrue(edits.isNotEmpty())
        assertTrue(edits.any { it.replacement == "isActive" && it.description.contains("Negation inverted") })
        assertTrue(edits.any { it.replacement == "false" && it.originalText == "true" })
        assertTrue(edits.any { it.replacement == "true" && it.originalText == "false" })
        assertTrue(edits.any { it.replacement == "&&" && it.description.contains("Replaced || with &&") })
        assertTrue(edits.any { it.replacement == "||" && it.description.contains("Replaced && with ||") })
    }

    @Test
    fun `ReturnValueMutator mutates non-default return expressions`() {
        val mutator = ReturnValueMutator()
        val code = """
            fun compute(): Int {
                return 42
            }
            fun greet(): String {
                return "hello"
            }
        """.trimIndent()
        val edits = parseAndMutate(code, mutator)

        assertTrue(edits.isNotEmpty())
        assertTrue(edits.any { it.replacement == "0" })
        assertTrue(edits.any { it.replacement == "false" })
        assertTrue(edits.any { it.replacement == "\"\"" })
        assertTrue(edits.any { it.replacement == "\"mutated\"" })
    }

    @Test
    fun `VoidMethodCallMutator omits standalone statement calls`() {
        val mutator = VoidMethodCallMutator()
        val code = """
            fun execute() {
                println("hello")
                logger.info("logging")
                val x = getValue()
            }
        """.trimIndent()
        val edits = parseAndMutate(code, mutator)

        assertTrue(edits.isNotEmpty())
        assertTrue(edits.any { it.replacement == "Unit" && it.originalText.contains("println") })
        assertTrue(edits.any { it.replacement == "Unit" && it.originalText.contains("logger.info") })
        assertFalse(edits.any { it.originalText.contains("getValue") })
    }

    @Test
    fun `LiteralMutationMutator mutates numeric constants in extreme mode`() {
        val mutator = LiteralMutationMutator()
        val code = "val threshold = 100"
        val edits = parseAndMutate(code, mutator)

        assertTrue(edits.isNotEmpty())
        assertTrue(edits.any { it.replacement == "101" })
        assertTrue(edits.any { it.replacement == "99" })
    }

    @Test
    fun `CollectionOperatorMutator inverts stdlib collection higher-order methods`() {
        val mutator = CollectionOperatorMutator()
        val code = "val items = list.filter { it > 0 }.any { it == 1 }.take(5).first()"
        val edits = parseAndMutate(code, mutator)

        assertTrue(edits.isNotEmpty())
        assertTrue(edits.any { it.replacement == "filterNot" })
        assertTrue(edits.any { it.replacement == "all" })
        assertTrue(edits.any { it.replacement == "drop" })
        assertTrue(edits.any { it.replacement == "last" })
    }

    @Test
    fun `ConditionReplacementMutator replaces if conditions with true and false`() {
        val mutator = ConditionReplacementMutator()
        val code = """
            fun check(x: Int) {
                if (x > 0) {
                    println("pos")
                }
            }
        """.trimIndent()
        val edits = parseAndMutate(code, mutator)

        assertTrue(edits.isNotEmpty())
        assertTrue(edits.any { it.replacement == "true" })
        assertTrue(edits.any { it.replacement == "false" })
    }

    @Test
    fun `MutatorRegistry allows registering custom mutators and filtering standard vs extreme`() {
        val registry = MutatorRegistry.default()
        val standardMutators = registry.mutators(includeExtreme = false)
        val allMutators = registry.mutators(includeExtreme = true)

        assertTrue(standardMutators.size < allMutators.size)
        assertTrue(standardMutators.all { it.category == MutatorCategory.STANDARD })

        // Custom domain mutator
        val customMutator = object : AstMutator {
            override val operator: MutationOperator = MutationOperator.EMPTY_METHOD_BODY
            override val category: MutatorCategory = MutatorCategory.STANDARD
            override fun canMutate(element: PsiElement): Boolean = false
            override fun mutate(element: PsiElement, context: MutationContext): List<AstEdit> = emptyList()
        }

        registry.register(customMutator)
        assertTrue(registry.mutators(includeExtreme = false).contains(customMutator))
    }
}
