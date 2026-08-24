package com.gokorei.kotlinmcp.mutation

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.models.ProjectionFilter
import com.gokorei.kotlinmcp.models.ResponseProjection

/**
 * Service providing in-memory mutation testing analysis for Kotlin code and test suites.
 */
interface MutationService : AutoCloseable {
    fun mutateAndTest(
        code: String,
        testCode: String? = null,
        projection: ResponseProjection = ResponseProjection()
    ): KotlinMcpResult
}

class DefaultMutationService(
    private val pipeline: MutationExecutionPipeline = DefaultMutationExecutionPipeline()
) : MutationService {

    override fun mutateAndTest(
        code: String,
        testCode: String?,
        projection: ResponseProjection
    ): KotlinMcpResult {
        if (code.isBlank()) {
            return KotlinMcpResult.Error(
                message = "Code to mutation test cannot be empty.",
                code = "EMPTY_CODE"
            )
        }

        val report = runCatching {
            pipeline.run(code, testCode)
        }.getOrElse { ex ->
            return KotlinMcpResult.Error(
                message = "Mutation testing execution failed: ${ex.message ?: ex::class.simpleName}",
                code = "MUTATION_EXECUTION_ERROR",
                details = mapOf("exception" to (ex::class.qualifiedName ?: "UnknownException"))
            )
        }

        // Check if baseline failed
        if (report.totalMutants == 0 && report.results.isNotEmpty() && report.results.first().status == MutantStatus.BASELINE_ERROR) {
            val first = report.results.first()
            return KotlinMcpResult.Error(
                message = first.details ?: "Baseline test failed before mutation testing.",
                code = "BASELINE_FAILURE",
                details = mapOf("stage" to "baseline_verification")
            )
        }

        val content = buildString {
            appendLine("# 🧬 In-Memory Mutation Testing Report")
            appendLine()
            val badge = when {
                report.totalMutants == 0 -> "⚪ **NO MUTANTS GENERATED**"
                report.effectiveMutants == 0 -> "⚠️ **NO EFFECTIVE MUTANTS (All Discarded / Compilation Errors)**"
                report.isStrong -> "🟢 **STRONG (${report.score}%)**"
                else -> "🔴 **NEEDS IMPROVEMENT (${report.score}%)**"
            }
            appendLine("- **Mutation Score:** $badge")
            appendLine("- **Total Mutants Generated:** ${report.totalMutants}")
            appendLine("- **Mutants Killed:** ${report.killedCount} / ${report.effectiveMutants}")
            appendLine("- **Mutants Survived (Weak Tests):** ${report.survivedCount}")
            if (report.compilationErrorCount > 0) {
                appendLine("- **Compilation Errors (Discarded):** ${report.compilationErrorCount}")
            }
            if (report.timeoutCount > 0) {
                appendLine("- **Timeouts (Counted as Killed):** ${report.timeoutCount}")
            }
            appendLine()

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("## ⚠️ Survived Mutants (${survived.size})")
                appendLine("The following mutated code variations passed all test assertions without triggering a failure. Add assertions to guard these behaviors:")
                appendLine()
                survived.forEachIndexed { idx, res ->
                    val m = res.mutant
                    appendLine("### ${idx + 1}. ${m.operator.name} (Line ${m.line})")
                    appendLine("> ${m.description}")
                    appendLine()
                    appendLine("```diff")
                    appendLine("- ${m.originalSnippet}")
                    appendLine("+ ${m.mutatedSnippet}")
                    appendLine("```")
                    appendLine()
                }
            } else if (report.effectiveMutants == 0) {
                appendLine("⚠️ **No executable mutants could be compiled.** Generated mutations failed type checking or compilation against this snippet signature.")
            } else {
                appendLine("✅ **All mutants killed!** Your unit test assertions effectively catch all synthesized boundary, relational, and conditional alterations.")
            }
        }.trim()

        val rawResult = KotlinMcpResult.Success(
            content = content,
            metadata = mapOf(
                "score" to report.score.toString(),
                "totalMutants" to report.totalMutants.toString(),
                "killedCount" to report.killedCount.toString(),
                "survivedCount" to report.survivedCount.toString(),
                "isStrong" to report.isStrong.toString()
            )
        )

        return ProjectionFilter.apply(rawResult, projection)
    }

    override fun close() {
        pipeline.close()
    }
}
