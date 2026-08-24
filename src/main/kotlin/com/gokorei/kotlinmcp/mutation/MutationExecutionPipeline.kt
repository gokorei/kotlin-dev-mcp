package com.gokorei.kotlinmcp.mutation

import com.gokorei.kotlinmcp.execution.CompileResult
import com.gokorei.kotlinmcp.execution.DefaultFastSnippetRunner
import com.gokorei.kotlinmcp.execution.FastSnippetRunner
import com.gokorei.kotlinmcp.execution.SnippetCompiler
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.jetbrains.kotlin.psi.KtFile

/**
 * Pipeline for executing AST mutation testing in-memory.
 * Compiles and runs baseline code and mutant variations in-process via [FastSnippetRunner].
 */
interface MutationExecutionPipeline : AutoCloseable {
    fun run(
        code: String,
        testCode: String? = null,
        timeoutPerMutantMs: Long = 2000L
    ): MutationReport
}

class DefaultMutationExecutionPipeline(
    private val generator: AstMutantGenerator = AstMutantGenerator(),
    private val runner: FastSnippetRunner = DefaultFastSnippetRunner()
) : MutationExecutionPipeline {

    override fun run(
        code: String,
        testCode: String?,
        timeoutPerMutantMs: Long
    ): MutationReport {
        val trimmedCode = code.trim()
        val trimmedTest = testCode?.trim().orEmpty()
        val baselineCombined = mergeSourceAndTest(trimmedCode, trimmedTest)

        // 1. Verify baseline code & tests pass before mutating
        val baselineCompile = SnippetCompiler.compile(baselineCombined)
        if (baselineCompile is CompileResult.Failed || (baselineCompile is CompileResult.Compiled && baselineCompile.diagnostics.any { it.severity == "error" })) {
            val msg = if (baselineCompile is CompileResult.Failed) {
                baselineCompile.message
            } else {
                (baselineCompile as CompileResult.Compiled).diagnostics.filter { it.severity == "error" }.joinToString("; ") { it.message }
            }
            if (baselineCompile is CompileResult.Compiled) {
                SnippetCompiler.cleanup(baselineCompile)
            }
            val dummyMutant = AstMutant("baseline", MutationOperator.RETURN_VALUE, 1, 1, "", "", "", "Baseline Compilation")
            return MutationReport(
                score = 0.0,
                totalMutants = 0,
                killedCount = 0,
                survivedCount = 0,
                compilationErrorCount = 0,
                timeoutCount = 0,
                results = listOf(MutantResult(dummyMutant, MutantStatus.BASELINE_ERROR, "Baseline code failed compilation: $msg"))
            )
        }

        val compiledBaseline = baselineCompile as CompileResult.Compiled
        val baselineResult = try {
            runner.run(compiledBaseline.outDir, timeoutMillis = 5000L)
        } finally {
            SnippetCompiler.cleanup(compiledBaseline)
        }

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
                results = listOf(MutantResult(dummyMutant, MutantStatus.BASELINE_ERROR, "Baseline test failed before mutation: ${err.message}"))
            )
        }

        // 2. Generate AST mutants from target source code
        val mutants = generator.generate(trimmedCode)
        if (mutants.isEmpty()) {
            return MutationReport(
                score = 100.0,
                totalMutants = 0,
                killedCount = 0,
                survivedCount = 0,
                compilationErrorCount = 0,
                timeoutCount = 0,
                results = emptyList()
            )
        }

        // 3. Execute mutants in-process
        val results = mutableListOf<MutantResult>()

        for (mutant in mutants) {
            val mutantSourceCombined = mergeSourceAndTest(mutant.mutatedSource, trimmedTest)

            val startNanos = System.nanoTime()
            val compiledMutant = SnippetCompiler.compile(mutantSourceCombined)

            if (compiledMutant is CompileResult.Failed || (compiledMutant is CompileResult.Compiled && compiledMutant.diagnostics.any { it.severity == "error" })) {
                val durMs = (System.nanoTime() - startNanos) / 1_000_000
                val msg = if (compiledMutant is CompileResult.Failed) {
                    compiledMutant.message
                } else {
                    (compiledMutant as CompileResult.Compiled).diagnostics.filter { it.severity == "error" }.joinToString("; ") { it.message }
                }
                if (compiledMutant is CompileResult.Compiled) {
                    SnippetCompiler.cleanup(compiledMutant)
                }
                results.add(
                    MutantResult(
                        mutant = mutant,
                        status = MutantStatus.COMPILATION_ERROR,
                        details = msg,
                        durationMs = durMs
                    )
                )
                continue
            }

            val compiled = compiledMutant as CompileResult.Compiled
            try {
                val runResult = runner.run(compiled.outDir, timeoutMillis = timeoutPerMutantMs)
                val durMs = (System.nanoTime() - startNanos) / 1_000_000

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
            } finally {
                SnippetCompiler.cleanup(compiled)
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
            0.0
        }

        return MutationReport(
            score = (score * 10.0).toInt() / 10.0,
            totalMutants = mutants.size,
            killedCount = killedCount,
            survivedCount = survivedCount,
            compilationErrorCount = compilationErrorCount,
            timeoutCount = timeoutCount,
            results = results
        )
    }

    private fun mergeSourceAndTest(code: String, testCode: String): String {
        if (testCode.isBlank()) return code

        val codeFile = K2SnippetFrontend.parsePsi(code)
        val testFile = K2SnippetFrontend.parsePsi(testCode)

        if (codeFile == null || testFile == null) {
            return "$code\n\n$testCode".trim()
        }

        val codeImports = codeFile.importDirectives.map { it.text }
        val testImports = testFile.importDirectives.map { it.text }
        val allImports = (codeImports + testImports).distinct()

        val selectedPackage = codeFile.packageDirective?.takeIf { it.text.isNotBlank() }?.text
            ?: testFile.packageDirective?.takeIf { it.text.isNotBlank() }?.text

        val codeBody = stripPackageAndImports(code, codeFile)
        val testBody = stripPackageAndImports(testCode, testFile)

        val sb = StringBuilder()
        if (selectedPackage != null) {
            sb.appendLine(selectedPackage)
            sb.appendLine()
        }
        if (allImports.isNotEmpty()) {
            allImports.forEach { sb.appendLine(it) }
            sb.appendLine()
        }
        sb.appendLine(codeBody)
        sb.appendLine()
        sb.appendLine(testBody)
        return sb.toString().trim()
    }

    private fun stripPackageAndImports(source: String, file: KtFile): String {
        val rangesToRemove = mutableListOf<org.jetbrains.kotlin.com.intellij.openapi.util.TextRange>()
        file.packageDirective?.takeIf { it.text.isNotBlank() }?.let { rangesToRemove.add(it.textRange) }
        file.importList?.takeIf { it.text.isNotBlank() }?.let { rangesToRemove.add(it.textRange) }

        if (rangesToRemove.isEmpty()) return source.trim()

        val sortedRanges = rangesToRemove.sortedByDescending { it.startOffset }
        var result = source
        for (range in sortedRanges) {
            val start = range.startOffset.coerceAtLeast(0)
            val end = range.endOffset.coerceAtMost(result.length)
            if (start < end) {
                result = result.substring(0, start) + result.substring(end)
            }
        }
        return result.trim()
    }

    override fun close() {
        runner.close()
    }
}
