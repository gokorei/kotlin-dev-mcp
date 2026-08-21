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
    fun `higher-order 2nd-order compound mutation stress test`() {
        val targetCode = """
            fun scoreAssessment(accuracy: Int, speed: Int): String {
                if (accuracy < 0 || speed < 0) return "INVALID"
                if (accuracy >= 90 && speed >= 80) return "EXCELLENT"
                if (accuracy >= 70 || speed >= 90) return "PASS"
                return "FAIL"
            }
        """.trimIndent()

        val comprehensiveSuite = """
            fun main() {
                check(scoreAssessment(-1, 50) == "INVALID")
                check(scoreAssessment(50, -1) == "INVALID")
                check(scoreAssessment(90, 80) == "EXCELLENT")
                check(scoreAssessment(95, 85) == "EXCELLENT")
                check(scoreAssessment(89, 85) == "PASS")
                check(scoreAssessment(70, 50) == "PASS")
                check(scoreAssessment(50, 90) == "PASS")
                check(scoreAssessment(69, 89) == "FAIL")
                check(scoreAssessment(0, 0) == "FAIL")
            }
        """.trimIndent()

        val report = pipeline.run(
            code = targetCode,
            testCode = comprehensiveSuite,
            includeExtremeOperators = true,
            maxOrder = 2
        )

        println("\n=======================================================")
        println("💥 HIGHER-ORDER (2ND-ORDER COMPOUND) MUTATION TEST")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total 1st & 2nd-Order Mutants: ${report.totalMutants}")
        
        val homResults = report.results.filter { it.mutant.order == 2 }
        println("   2nd-Order Compound Mutants Generated: ${homResults.size}")
        homResults.take(10).forEach {
            println("   [${it.status}] ${it.mutant.description}")
        }
        println("=======================================================\n")

        assertTrue(homResults.isNotEmpty(), "Expected compound 2nd-order mutants")
        assertTrue(report.score >= 90.0, "Test suite should achieve >=90% kill rate against 2nd-order compound distortions")
    }

    @Test
    fun `breaking point analysis - discovers subtle compounding masking defects`() {
        val targetCode = """
            fun calculateDiscount(items: Int, isMember: Boolean): Int {
                var discount = 0
                if (items > 10) discount += 10
                if (isMember) discount += 5
                return discount
            }
        """.trimIndent()

        // Incomplete test suite: tests combined case (items=12, member=true -> 15) and (items=2, member=false -> 0),
        // but omits individual orthogonal branches (items=12, member=false) and (items=2, member=true)
        val coupledTestSuite = """
            fun main() {
                check(calculateDiscount(12, isMember = true) == 15) { "both discounts apply" }
                check(calculateDiscount(5, isMember = false) == 0) { "no discount" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = targetCode,
            testCode = coupledTestSuite,
            includeExtremeOperators = true,
            maxOrder = 2
        )

        println("\n=======================================================")
        println("🚨 BREAKING POINT DISCOVERY (Compounding Masking Mutants)")
        println("   Score on Coupled Test Suite: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        
        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        println("   Identified ${survived.size} Surviving Mutants:")
        survived.forEach {
            println("   ⚠️ SURVIVED (Order ${it.mutant.order}): ${it.mutant.description}")
            println("      Original: ${it.mutant.originalSnippet}")
            println("      Mutated:  ${it.mutant.mutatedSnippet}")
        }
        println("=======================================================\n")

        assertTrue(report.survivedCount >= 3, "Coupled tests should break under extreme higher-order mutations")
        assertTrue(report.score < 95.0, "Score should drop under surviving extreme mutants")
    }
}
