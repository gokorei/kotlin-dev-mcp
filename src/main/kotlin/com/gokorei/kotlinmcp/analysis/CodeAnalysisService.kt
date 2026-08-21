package com.gokorei.kotlinmcp.analysis

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.models.ProjectionFilter
import com.gokorei.kotlinmcp.models.ResponseProjection
import com.gokorei.kotlinmcp.lsp.WorkspaceSemanticIndexer
import com.gokorei.kotlinmcp.shared.CommandService

enum class CodeAnalysisAction {
    INSPECT_SYMBOL,
    ANALYZE_NULLABILITY,
    EXPLAIN_COROUTINES,
    ANALYZE_COMPOSE,
    FILE_CONTEXT
}

/**
 * Service interface for analyzing Kotlin code snippets for nullability, symbol structures, and coroutine safety.
 */
interface CodeAnalysisService : CommandService<CodeAnalysisAction> {
    /** Executes an action that may additionally reference a workspace path and optional response projection. */
    fun execute(
        action: CodeAnalysisAction,
        code: String,
        workspacePath: String? = null,
        projection: ResponseProjection = ResponseProjection()
    ): KotlinMcpResult

    override fun execute(action: CodeAnalysisAction, code: String): KotlinMcpResult =
        execute(action, code, workspacePath = null, projection = ResponseProjection())
}

/**
 * Single-responsibility facade routing [CodeAnalysisAction] operations to dedicated code analysis strategy components.
 */
class DefaultCodeAnalysisService(
    private val indexer: WorkspaceSemanticIndexer = WorkspaceSemanticIndexer(),
    private val fileContextAnalyzer: FileContextAnalyzer = FileContextAnalyzer(),
    private val symbolInspector: SymbolInspector = SymbolInspector(),
    private val nullabilityAnalyzer: NullabilityAnalyzer = NullabilityAnalyzer(),
    private val coroutinesSafetyAnalyzer: CoroutinesSafetyAnalyzer = CoroutinesSafetyAnalyzer(),
    private val composeAnalyzer: ComposeAnalyzer = ComposeAnalyzer()
) : CodeAnalysisService {

    override fun execute(
        action: CodeAnalysisAction,
        code: String,
        workspacePath: String?,
        projection: ResponseProjection
    ): KotlinMcpResult {
        val raw = when (action) {
            CodeAnalysisAction.INSPECT_SYMBOL -> symbolInspector.inspectSymbol(code)
            CodeAnalysisAction.ANALYZE_NULLABILITY -> nullabilityAnalyzer.analyzeNullability(code)
            CodeAnalysisAction.EXPLAIN_COROUTINES -> coroutinesSafetyAnalyzer.explainCoroutines(code)
            CodeAnalysisAction.ANALYZE_COMPOSE -> composeAnalyzer.analyzeCompose(code)
            CodeAnalysisAction.FILE_CONTEXT -> fileContextAnalyzer.fileContext(code, workspacePath, indexer)
        }
        return ProjectionFilter.apply(raw, projection)
    }
}
