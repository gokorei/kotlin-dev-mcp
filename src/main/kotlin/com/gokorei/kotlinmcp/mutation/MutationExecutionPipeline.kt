package com.gokorei.kotlinmcp.mutation

import com.gokorei.kotlinmcp.execution.CompileResult
import com.gokorei.kotlinmcp.execution.DefaultFastSnippetRunner
import com.gokorei.kotlinmcp.execution.FastSnippetRunner
import com.gokorei.kotlinmcp.execution.SnippetCompiler
import com.gokorei.kotlinmcp.models.KotlinMcpResult

/**
 * Pipeline for executing AST mutation testing in-memory.
 * Compiles and runs baseline code and mutant variations in-process via [FastSnippetRunner].
 */
interface MutationExecutionPipeline : AutoCloseable {
    fun run(
        code: String,
        testCode: String? = null,
        timeoutPerMutantMs: Long = 2000L,
        includeExtremeOperators: Boolean = false,
        maxOrder: Int = 1
    ): MutationReport
}

class DefaultMutationExecutionPipeline(
    private val generator: AstMutantGenerator = AstMutantGenerator(),
    private val runner: FastSnippetRunner = DefaultFastSnippetRunner()
) : MutationExecutionPipeline {

    override fun run(
        code: String,
        testCode: String?,
        timeoutPerMutantMs: Long,
        includeExtremeOperators: Boolean,
        maxOrder: Int
    ): MutationReport {
        val trimmedCode = code.trim()
        val trimmedTest = testCode?.trim().orEmpty()
        val baselineCombined = if (trimmedTest.isNotBlank()) "$trimmedCode\n\n$trimmedTest" else trimmedCode

        // 1. Verify baseline code & tests pass before mutating
        val baselineCompile = SnippetCompiler.compile(baselineCombined)
        if (baselineCompile is CompileResult.Failed) {
            val dummyMutant = AstMutant("baseline", MutationOperator.RETURN_VALUE, 1, 1, "", "", "", "Baseline Compilation")
            return MutationReport(
                score = 0.0,
                totalMutants = 0,
                killedCount = 0,
                survivedCount = 0,
                compilationErrorCount = 1,
                timeoutCount = 0,
                results = listOf(MutantResult(dummyMutant, MutantStatus.COMPILATION_ERROR, "Baseline code failed compilation: ${baselineCompile.message}"))
            )
        }

        val compiledBaseline = baselineCompile as CompileResult.Compiled
        val baselineErrors = compiledBaseline.diagnostics.filter { it.severity.equals("ERROR", ignoreCase = true) }
        if (baselineErrors.isNotEmpty()) {
            SnippetCompiler.cleanup(compiledBaseline)
            val dummyMutant = AstMutant("baseline", MutationOperator.RETURN_VALUE, 1, 1, "", "", "", "Baseline Compilation")
            return MutationReport(
                score = 0.0,
                totalMutants = 0,
                killedCount = 0,
                survivedCount = 0,
                compilationErrorCount = 1,
                timeoutCount = 0,
                results = listOf(MutantResult(dummyMutant, MutantStatus.COMPILATION_ERROR, "Baseline code failed compilation with errors:\n" + baselineErrors.joinToString("\n") { "  - [Line ${it.line ?: 0}:${it.column ?: 0}] ${it.message}" }))
            )
        }

        val baselineResult = runner.run(compiledBaseline.outDir, timeoutMillis = maxOf(15000L, timeoutPerMutantMs * 2))
        SnippetCompiler.cleanup(compiledBaseline)

        if (baselineResult.isError) {
            val err = baselineResult as KotlinMcpResult.Error
            val dummyMutant = AstMutant("baseline", MutationOperator.RETURN_VALUE, 1, 1, "", "", "", "Baseline Test")
            return MutationReport(
                score = 0.0,
                totalMutants = 0,
                killedCount = 0,
                survivedCount = 0,
                compilationErrorCount = 0,
                timeoutCount = 0,
                results = listOf(MutantResult(dummyMutant, MutantStatus.KILLED, "Baseline test failed before mutation: ${err.message}"))
            )
        }

        // 2. Generate AST mutants from target source code
        val mutants = generator.generate(
            code = trimmedCode,
            includeExtremeOperators = includeExtremeOperators,
            maxOrder = maxOrder
        )
        if (mutants.isEmpty()) {
            return MutationReport(
                score = 100.0,
                totalMutants = 0,
                killedCount = 0,
                survivedCount = 0,
                compilationErrorCount = 0,
                timeoutCount = 0,
                results = emptyList(),
                order = maxOrder
            )
        }

        // 3. Execute mutants in-process
        val results = mutableListOf<MutantResult>()

        for (mutant in mutants) {
            val mutantSourceCombined = if (trimmedTest.isNotBlank()) {
                "${mutant.mutatedSource}\n\n$trimmedTest"
            } else {
                mutant.mutatedSource
            }

            val startNanos = System.nanoTime()
            val compiledMutant = SnippetCompiler.compile(mutantSourceCombined)

            if (compiledMutant is CompileResult.Failed) {
                val durMs = (System.nanoTime() - startNanos) / 1_000_000
                results.add(
                    MutantResult(
                        mutant = mutant,
                        status = MutantStatus.COMPILATION_ERROR,
                        details = compiledMutant.message,
                        durationMs = durMs
                    )
                )
                continue
            }

            val compiled = compiledMutant as CompileResult.Compiled
            val mutantErrors = compiled.diagnostics.filter { it.severity.equals("ERROR", ignoreCase = true) }
            if (mutantErrors.isNotEmpty()) {
                val durMs = (System.nanoTime() - startNanos) / 1_000_000
                SnippetCompiler.cleanup(compiled)
                results.add(
                    MutantResult(
                        mutant = mutant,
                        status = MutantStatus.COMPILATION_ERROR,
                        details = "Compilation errors: " + mutantErrors.joinToString("; ") { it.message },
                        durationMs = durMs
                    )
                )
                continue
            }

            val runResult = runner.run(compiled.outDir, timeoutMillis = timeoutPerMutantMs)
            val durMs = (System.nanoTime() - startNanos) / 1_000_000
            SnippetCompiler.cleanup(compiled)

            when (runResult) {
                is KotlinMcpResult.Error -> {
                    if (runResult.code == "EXECUTION_TIMEOUT") {
                        results.add(MutantResult(mutant, MutantStatus.TIMEOUT, runResult.message, durMs))
                    } else {
                        // Test assertion failed or runtime exception caught the mutant -> KILLED
                        results.add(MutantResult(mutant, MutantStatus.KILLED, runResult.message, durMs))
                    }
                }
                is KotlinMcpResult.Success -> {
                    // Test passed despite mutation -> SURVIVED
                    results.add(
                        MutantResult(
                            mutant = mutant,
                            status = MutantStatus.SURVIVED,
                            details = "Test passed exit 0 despite mutation at line ${mutant.line}: ${mutant.description}",
                            durationMs = durMs
                        )
                    )
                }
            }
        }

        val killedCount = results.count { it.status == MutantStatus.KILLED }
        val survivedCount = results.count { it.status == MutantStatus.SURVIVED }
        val timeoutCount = results.count { it.status == MutantStatus.TIMEOUT }
        val compilationErrorCount = results.count { it.status == MutantStatus.COMPILATION_ERROR }

        val totalEffective = killedCount + survivedCount + timeoutCount
        val score = if (totalEffective > 0) {
            ((killedCount + timeoutCount).toDouble() / totalEffective.toDouble()) * 100.0
        } else {
            100.0
        }

        return MutationReport(
            score = (score * 10.0).toInt() / 10.0,
            totalMutants = mutants.size,
            killedCount = killedCount,
            survivedCount = survivedCount,
            compilationErrorCount = compilationErrorCount,
            timeoutCount = timeoutCount,
            results = results,
            order = maxOrder
        )
    }

    override fun close() {
        runner.close()
    }
}
