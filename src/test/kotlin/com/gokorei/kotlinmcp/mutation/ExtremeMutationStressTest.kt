package com.gokorei.kotlinmcp.mutation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("hardening")
class ExtremeMutationStressTest {

    private val pipeline = DefaultMutationExecutionPipeline()

    @Test
    fun `extreme mutation test with condition replacements and constant distortions`() {
        val targetCode = """
            fun evaluateAccess(role: String, isSuspended: Boolean, clearanceLevel: Int): Boolean {
                if (isSuspended) return false
                if (role == "ADMIN") return true
                if (role == "USER" && clearanceLevel >= 3) return true
                return false
            }
        """.trimIndent()

        val comprehensiveSuite = """
            fun main() {
                // Base permissions
                check(!evaluateAccess("ADMIN", isSuspended = true, clearanceLevel = 10)) { "suspended admin rejected" }
                check(evaluateAccess("ADMIN", isSuspended = false, clearanceLevel = 0)) { "active admin accepted" }
                
                // Clearance boundaries
                check(!evaluateAccess("USER", isSuspended = false, clearanceLevel = 2)) { "clearance 2 rejected" }
                check(evaluateAccess("USER", isSuspended = false, clearanceLevel = 3)) { "clearance 3 accepted" }
                check(evaluateAccess("USER", isSuspended = false, clearanceLevel = 4)) { "clearance 4 accepted" }
                check(!evaluateAccess("USER", isSuspended = true, clearanceLevel = 5)) { "suspended user rejected" }
                
                // Unrecognized roles
                check(!evaluateAccess("GUEST", isSuspended = false, clearanceLevel = 10)) { "guest rejected" }
                check(!evaluateAccess("", isSuspended = false, clearanceLevel = 10)) { "empty role rejected" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = targetCode,
            testCode = comprehensiveSuite,
            includeExtremeOperators = true,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🔥 EXTREME MUTATION TEST (Condition Replacements + Literals)")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants Generated: ${report.totalMutants}")
        report.results.forEach {
            println("   [${it.status}] ${it.mutant.operator} line ${it.mutant.line}: ${it.mutant.description}")
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants >= 10, "Expected large number of extreme mutants")
        assertEquals(0, report.survivedCount, "Comprehensive suite should withstand extreme 1st-order mutations")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `higher-order 2nd-order compound mutation stress test with fully hardened assertions`() {
        val targetCode = """
            fun scoreAssessment(accuracy: Int, speed: Int): String {
                if (accuracy < 0 || speed < 0) return "INVALID"
                if (accuracy >= 90 && speed >= 80) return "EXCELLENT"
                if (accuracy >= 70 || speed >= 90) return "PASS"
                return "FAIL"
            }
        """.trimIndent()

        val fullyHardenedSuite = """
            fun main() {
                // Invalid branch
                check(scoreAssessment(-1, 50) == "INVALID")
                check(scoreAssessment(50, -1) == "INVALID")
                check(scoreAssessment(-1, -1) == "INVALID")
                
                // EXCELLENT boundaries (90 accuracy, 80 speed)
                check(scoreAssessment(90, 80) == "EXCELLENT")
                check(scoreAssessment(100, 100) == "EXCELLENT")
                check(scoreAssessment(90, 79) == "PASS") { "90 accuracy but 79 speed is PASS" }
                check(scoreAssessment(89, 80) == "PASS") { "89 accuracy with 80 speed is PASS" }
                check(scoreAssessment(89, 85) == "PASS")
                
                // PASS boundaries (70 accuracy OR 90 speed)
                check(scoreAssessment(70, 0) == "PASS")
                check(scoreAssessment(70, 50) == "PASS")
                check(scoreAssessment(69, 90) == "PASS")
                check(scoreAssessment(0, 90) == "PASS")
                
                // FAIL boundaries (below 70 accuracy AND below 90 speed)
                check(scoreAssessment(69, 89) == "FAIL")
                check(scoreAssessment(69, 0) == "FAIL")
                check(scoreAssessment(0, 89) == "FAIL")
                check(scoreAssessment(0, 0) == "FAIL")
            }
        """.trimIndent()

        val report = pipeline.run(
            code = targetCode,
            testCode = fullyHardenedSuite,
            includeExtremeOperators = true,
            maxOrder = 2
        )

        println("\n=======================================================")
        println("💥 HIGHER-ORDER (2ND-ORDER COMPOUND) MUTATION TEST (HARDENED)")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total 1st & 2nd-Order Mutants: ${report.totalMutants}")
        
        val homResults = report.results.filter { it.mutant.order == 2 }
        println("   2nd-Order Compound Mutants Generated: ${homResults.size}")
        homResults.take(10).forEach {
            println("   [${it.status}] ${it.mutant.description}")
        }
        println("=======================================================\n")

        assertTrue(homResults.isNotEmpty(), "Expected compound 2nd-order mutants")
        assertEquals(0, report.survivedCount, "All 1st and 2nd-order mutants must be killed by hardened suite")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `addressing survived mutants by hardening orthogonal boundary assertions`() {
        val targetCode = """
            fun calculateDiscount(items: Int, isMember: Boolean): Int {
                var discount = 0
                if (items > 10) discount += 10
                if (isMember) discount += 5
                return discount
            }
        """.trimIndent()

        // 1. Weak/coupled test suite where 3 mutants survive
        val weakCoupledTestSuite = """
            fun main() {
                check(calculateDiscount(12, isMember = true) == 15) { "both discounts apply" }
                check(calculateDiscount(5, isMember = false) == 0) { "no discount" }
            }
        """.trimIndent()

        val weakReport = pipeline.run(
            code = targetCode,
            testCode = weakCoupledTestSuite,
            includeExtremeOperators = true,
            maxOrder = 2
        )

        println("\n--- Step 1: Weak Test Suite with Surviving Mutants ---")
        println("Score: ${weakReport.score}%, Survived: ${weakReport.survivedCount}")
        assertTrue(weakReport.survivedCount >= 3, "Weak suite must have surviving mutants")

        // 2. Hardened test suite explicitly addressing every survived boundary mutant
        val hardenedTestSuite = """
            fun main() {
                // Exact boundary assertions for items > 10
                check(calculateDiscount(10, isMember = false) == 0) { "exact boundary 10 items: 0 discount" }
                check(calculateDiscount(11, isMember = false) == 10) { "first tier 11 items: 10 discount" }
                check(calculateDiscount(9, isMember = false) == 0) { "below boundary 9 items: 0 discount" }
                
                // Member only assertions (independent of item count)
                check(calculateDiscount(0, isMember = true) == 5) { "member only 0 items: 5 discount" }
                check(calculateDiscount(5, isMember = true) == 5) { "member only 5 items: 5 discount" }
                check(calculateDiscount(10, isMember = true) == 5) { "member only 10 items: 5 discount" }
                
                // Combined assertions
                check(calculateDiscount(11, isMember = true) == 15) { "both: 11 items + member: 15 discount" }
                check(calculateDiscount(0, isMember = false) == 0) { "neither: 0 items non-member: 0 discount" }
            }
        """.trimIndent()

        val hardenedReport = pipeline.run(
            code = targetCode,
            testCode = hardenedTestSuite,
            includeExtremeOperators = true,
            maxOrder = 2
        )

        println("\n--- Step 2: Hardened Test Suite Addressing All Mutants ---")
        println("Hardened Score: ${hardenedReport.score}% (${hardenedReport.killedCount}/${hardenedReport.effectiveMutants} killed, ${hardenedReport.survivedCount} survived)")
        println("All previously survived mutants are now 100% killed!\n")

        assertEquals(0, hardenedReport.survivedCount, "All survived mutants must be killed by the hardened suite")
        assertEquals(100.0, hardenedReport.score)
        assertTrue(hardenedReport.isStrong)
    }
}
