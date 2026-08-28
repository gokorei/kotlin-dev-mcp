package com.gokorei.kotlinmcp.semantic

import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.semantic.analyzers.*

/**
 * Service interface for compiler-backed deep semantic analysis and K2 compiler session management.
 */
interface SemanticService {
    fun checkWhenExhaustiveness(code: String, classpath: List<String> = emptyList()): KotlinMcpResult
    fun checkValueClass(code: String, classpath: List<String> = emptyList()): KotlinMcpResult
    fun checkInlineReified(code: String, classpath: List<String> = emptyList()): KotlinMcpResult
    fun checkContracts(code: String, classpath: List<String> = emptyList()): KotlinMcpResult
    fun checkExpectActual(code: String, classpath: List<String> = emptyList()): KotlinMcpResult
    fun checkExperimentalOptIn(code: String, classpath: List<String> = emptyList()): KotlinMcpResult
    fun checkDeprecated(code: String, classpath: List<String> = emptyList()): KotlinMcpResult
    fun acquireSession(code: String, classpath: List<String> = emptyList()): K2AnalysisSession?
}

/**
 * Default implementation of [SemanticService] leveraging [KtSessionProvider] and specialized semantic analyzers.
 */
class DefaultSemanticService(
    private val sessionProvider: KtSessionProvider = KtSessionProvider()
) : SemanticService {

    private val whenAnalyzer = WhenExhaustivenessAnalyzer()
    private val valueClassAnalyzer = ValueClassAnalyzer()
    private val inlineReifiedAnalyzer = InlineReifiedAnalyzer()
    private val contractsAnalyzer = ContractsAnalyzer()
    private val expectActualAnalyzer = ExpectActualAnalyzer()
    private val optInAndDeprecationAnalyzer = OptInAndDeprecationAnalyzer()

    override fun acquireSession(code: String, classpath: List<String>): K2AnalysisSession? {
        return sessionProvider.acquireSession(code, classpath)
    }

    private fun withSession(code: String, classpath: List<String>, block: (K2AnalysisSession) -> KotlinMcpResult): KotlinMcpResult {
        val session = acquireSession(code, classpath) ?: run {
            val fallbackFile = K2SnippetFrontend.parsePsi(code)
            if (fallbackFile != null) K2AnalysisSession(fallbackFile) else null
        }

        return if (session != null) {
            block(session)
        } else {
            KotlinMcpResult.Error(
                code = "PSI_PARSE_ERROR",
                message = "Failed to parse Kotlin source code for semantic analysis."
            )
        }
    }

    override fun checkWhenExhaustiveness(code: String, classpath: List<String>): KotlinMcpResult {
        return withSession(code, classpath) { session -> whenAnalyzer.analyze(session) }
    }

    override fun checkValueClass(code: String, classpath: List<String>): KotlinMcpResult {
        return withSession(code, classpath) { session -> valueClassAnalyzer.analyze(session) }
    }

    override fun checkInlineReified(code: String, classpath: List<String>): KotlinMcpResult {
        return withSession(code, classpath) { session -> inlineReifiedAnalyzer.analyze(session) }
    }

    override fun checkContracts(code: String, classpath: List<String>): KotlinMcpResult {
        return withSession(code, classpath) { session -> contractsAnalyzer.analyze(session) }
    }

    override fun checkExpectActual(code: String, classpath: List<String>): KotlinMcpResult {
        return withSession(code, classpath) { session -> expectActualAnalyzer.analyze(session) }
    }

    override fun checkExperimentalOptIn(code: String, classpath: List<String>): KotlinMcpResult {
        return withSession(code, classpath) { session -> optInAndDeprecationAnalyzer.analyzeOptIn(session) }
    }

    override fun checkDeprecated(code: String, classpath: List<String>): KotlinMcpResult {
        return withSession(code, classpath) { session -> optInAndDeprecationAnalyzer.analyzeDeprecated(session) }
    }
}
