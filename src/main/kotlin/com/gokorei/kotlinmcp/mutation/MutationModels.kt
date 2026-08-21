package com.gokorei.kotlinmcp.mutation

import kotlinx.serialization.Serializable

/**
 * Standard mutation operators supported by the K2 PSI mutation testing engine.
 */
@Serializable
enum class MutationOperator(val description: String) {
    RELATIONAL_BOUNDARY("Relational boundary condition mutation (< <-> <=, > <-> >=, == <-> !=)"),
    BOOLEAN_INVERSION("Boolean literal and condition negation (true <-> false, condition <-> !condition)"),
    ARITHMETIC_OPERATOR("Arithmetic operator replacement (+ <-> -, * <-> /, % <-> *)"),
    RETURN_VALUE("Return value replacement (null, 0, false, empty)"),
    VOID_CALL_REMOVAL("Omission of standalone function call expression statements")
}

/**
 * Represents a single syntactically valid code mutant produced by AST manipulation.
 */
@Serializable
data class AstMutant(
    val id: String,
    val operator: MutationOperator,
    val line: Int,
    val column: Int,
    val originalSnippet: String,
    val mutatedSnippet: String,
    val mutatedSource: String,
    val description: String
)

/**
 * Outcome of executing test suites against a specific mutant.
 */
@Serializable
enum class MutantStatus {
    /** The test failed or threw an exception on the mutated code (desired outcome). */
    KILLED,

    /** The test passed unchanged on the mutated code (indicates weak or missing assertions). */
    SURVIVED,

    /** The mutated code failed to compile (syntax or type checking error; excluded from score). */
    COMPILATION_ERROR,

    /** Execution of the mutant exceeded the specified timeout limit. */
    TIMEOUT
}

/**
 * Execution result for an individual mutant.
 */
@Serializable
data class MutantResult(
    val mutant: AstMutant,
    val status: MutantStatus,
    val details: String? = null,
    val durationMs: Long = 0L
)

/**
 * Comprehensive mutation test score and breakdown.
 */
@Serializable
data class MutationReport(
    val score: Double,
    val totalMutants: Int,
    val killedCount: Int,
    val survivedCount: Int,
    val compilationErrorCount: Int,
    val timeoutCount: Int,
    val results: List<MutantResult>
) {
    val effectiveMutants: Int
        get() = totalMutants - compilationErrorCount

    val isStrong: Boolean
        get() = score >= 80.0
}
