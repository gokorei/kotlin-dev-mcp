package com.gokorei.kotlinmcp.mutation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AstMutantGeneratorTest {

    private val generator = AstMutantGenerator()

    @Test
    fun `generates relational boundary mutants on comparison expressions`() {
        val code = """
            fun isAdult(age: Int): Boolean {
                return age >= 18
            }
        """.trimIndent()

        val mutants = generator.generate(code)
        val relational = mutants.filter { it.operator == MutationOperator.RELATIONAL_BOUNDARY }

        assertTrue(relational.isNotEmpty(), "Expected at least one relational boundary mutant")
        val mutant = relational.first()
        assertTrue(mutant.mutatedSource.contains("age > 18") || mutant.mutatedSource.contains("age < 18"))
        assertEquals(2, mutant.line)
    }

    @Test
    fun `generates boolean inversion mutants on literals and prefix expressions`() {
        val code = """
            fun evaluate(flag: Boolean): Boolean {
                if (!flag) {
                    return true
                }
                return false
            }
        """.trimIndent()

        val mutants = generator.generate(code)
        val booleanMutants = mutants.filter { it.operator == MutationOperator.BOOLEAN_INVERSION }

        assertTrue(booleanMutants.isNotEmpty(), "Expected boolean inversion mutants")
        val invertedCondition = booleanMutants.find { it.mutatedSource.contains("if (flag)") }
        assertNotNull(invertedCondition, "Expected negation prefix !flag to be inverted to flag")
    }

    @Test
    fun `generates arithmetic operator mutants on math expressions`() {
        val code = """
            fun calculate(a: Int, b: Int): Int {
                return a + b * 2
            }
        """.trimIndent()

        val mutants = generator.generate(code)
        val arithmetic = mutants.filter { it.operator == MutationOperator.ARITHMETIC_OPERATOR }

        assertTrue(arithmetic.size >= 2, "Expected arithmetic mutants for + and *")
        assertTrue(arithmetic.any { it.mutatedSource.contains("a - b") })
        assertTrue(arithmetic.any { it.mutatedSource.contains("b / 2") })
    }

    @Test
    fun `generates return value mutators for primitive and nullable returns`() {
        val code = """
            fun compute(): Int {
                val x = 10
                return x * 2
            }
        """.trimIndent()

        val mutants = generator.generate(code)
        val returnMutants = mutants.filter { it.operator == MutationOperator.RETURN_VALUE }

        assertTrue(returnMutants.isNotEmpty(), "Expected return value mutants")
        assertTrue(returnMutants.any { it.mutatedSource.contains("return 0") || it.mutatedSource.contains("return -1") })
    }

    @Test
    fun `generates boolean inversion mutants on logical and or operators`() {
        val code = """
            fun checkBoth(a: Boolean, b: Boolean): Boolean {
                return a && b
            }
            fun checkEither(a: Boolean, b: Boolean): Boolean {
                return a || b
            }
        """.trimIndent()

        val mutants = generator.generate(code)
        val booleanMutants = mutants.filter { it.operator == MutationOperator.BOOLEAN_INVERSION }

        assertTrue(booleanMutants.any { it.mutatedSource.contains("a || b") && it.description.contains("Replaced && with ||") })
        assertTrue(booleanMutants.any { it.mutatedSource.contains("a && b") && it.description.contains("Replaced || with &&") })
    }

    @Test
    fun `generates return value mutators for string literals`() {
        val code = """
            fun greet(name: String): String {
                return "Hello, " + name
            }
        """.trimIndent()

        val mutants = generator.generate(code)
        val returnMutants = mutants.filter { it.operator == MutationOperator.RETURN_VALUE }

        assertTrue(returnMutants.isNotEmpty(), "Expected return value mutants")
        assertTrue(returnMutants.any { it.mutatedSource.contains("return \"\"") }, "Expected return empty string mutant")
        assertTrue(returnMutants.any { it.mutatedSource.contains("return \"mutated\"") }, "Expected return altered string mutant")
    }

    @Test
    fun `generates void call omission mutants for standalone statements`() {
        val code = """
            fun process(item: String) {
                logMessage(item)
                saveToDb(item)
            }
        """.trimIndent()

        val mutants = generator.generate(code)
        val voidCallMutants = mutants.filter { it.operator == MutationOperator.VOID_CALL_REMOVAL }

        assertTrue(voidCallMutants.size >= 2, "Expected void call omission mutants for both statements")
        assertTrue(voidCallMutants.any { !it.mutatedSource.contains("logMessage(item)") && it.mutatedSource.contains("saveToDb(item)") })
        assertTrue(voidCallMutants.any { it.mutatedSource.contains("logMessage(item)") && !it.mutatedSource.contains("saveToDb(item)") })
    }

    @Test
    fun `generates extreme condition replacement and literal mutants when extreme mode enabled`() {
        val code = """
            fun validate(score: Int): Boolean {
                if (score > 100) {
                    return false
                }
                return true
            }
        """.trimIndent()

        val mutants = generator.generate(code, includeExtremeOperators = true)

        assertTrue(mutants.any { it.operator == MutationOperator.CONDITION_REPLACEMENT })
        assertTrue(mutants.any { it.operator == MutationOperator.LITERAL_MUTATION })
        assertTrue(mutants.any { it.mutatedSource.contains("if (true)") || it.mutatedSource.contains("if (false)") })
        assertTrue(mutants.any { it.mutatedSource.contains("101") || it.mutatedSource.contains("99") })
    }

    @Test
    fun `generates higher-order compound mutants combining multiple mutations`() {
        val code = """
            fun calculate(x: Int): Int {
                val factor = 2
                return if (x > 0) x * factor else 0
            }
        """.trimIndent()

        val mutants = generator.generate(code, includeExtremeOperators = true, maxOrder = 2)
        val hom = mutants.filter { it.order == 2 }

        assertTrue(hom.isNotEmpty(), "Expected 2nd order compound mutants to be generated")
        assertEquals(MutationOperator.HIGHER_ORDER_COMPOUND, hom.first().operator)
    }
}
