package com.gokorei.kotlinmcp.server

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.analysis.*
import com.gokorei.kotlinmcp.doc.*
import com.gokorei.kotlinmcp.execution.*
import com.gokorei.kotlinmcp.linting.*
import com.gokorei.kotlinmcp.lsp.*
import com.gokorei.kotlinmcp.project.*
import com.gokorei.kotlinmcp.refactoring.*
import com.gokorei.kotlinmcp.mutation.*
import com.gokorei.kotlinmcp.models.ResponsePreset
import com.gokorei.kotlinmcp.models.ResponseProjection

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Main MCP Server class orchestrating all Kotlin developer tools.
 *
 * The tool surface is consolidated into 9 action-multiplexed core tools: almost
 * every tool dispatches on an `action` (or `domain`/`target`) discriminator to a
 * when-branch below, rather than being registered atomically one-operation-per-tool.
 * [ToolRegistrar] is the single source of truth for the surface definition — it
 * enumerates the registerTool() calls and maps each action to the methods here.
 *
 * All results are returned errors-as-values ([KotlinMcpResult]) so the transport
 * layer can set the protocol `isError` flag on failures.
 */
class KotlinMcpServer(
    val docService: DocService = DefaultDocService(),
    val semanticEngine: K2SemanticEngine = DefaultK2SemanticEngine(),
    private val codeAnalysisService: CodeAnalysisService = DefaultCodeAnalysisService(),
    private val diagnosticService: DiagnosticService = DefaultDiagnosticService(),
    private val projectService: ProjectService = DefaultProjectService(),
    private val refactoringService: RefactoringService = DefaultRefactoringService(),
    private val runSnippetService: RunSnippetService = DefaultRunSnippetService(),
    private val gradleRunService: GradleRunService = DefaultGradleRunService(),
    private val lspService: LspService? = null,
    private val libraryAnalysisService: LibraryAnalysisService = DefaultLibraryAnalysisService(),
    private val lintService: LintService = DefaultLintService(),
    private val mutationService: MutationService = DefaultMutationService()
) {

    private val logger = KotlinLogging.logger {}
    private val textService: LspService = lspService ?: DefaultLspService(docService, semanticEngine)

    init {
        lintService.prewarm()
    }


    // ---- kotlin_docs ----

    fun docsSearch(query: String, classpath: List<String> = emptyList()): KotlinMcpResult =
        docService.execute(DocAction.SEARCH, query, null, classpath)

    fun docsLookupSymbol(query: String, preset: String? = null, classpath: List<String> = emptyList()): KotlinMcpResult =
        docService.execute(DocAction.LOOKUP_SYMBOL, query, preset, classpath)

    fun docsExplainFeature(query: String): KotlinMcpResult =
        docService.execute(DocAction.EXPLAIN_FEATURE, query)

    fun docsRegisterSymbol(name: String, content: String): KotlinMcpResult {
        docService.registerDynamicSymbol(name, content)
        return KotlinMcpResult.Success(
            content = "Registered symbol documentation for '$name'.",
            metadata = mapOf("kind" to "symbol", "name" to name)
        )
    }

    fun docsRegisterFeature(name: String, content: String): KotlinMcpResult {
        docService.registerDynamicFeature(name, content)
        return KotlinMcpResult.Success(
            content = "Registered language-feature documentation for '$name'.",
            metadata = mapOf("kind" to "feature", "name" to name)
        )
    }

    fun docsRegisterNamespace(name: String, content: String): KotlinMcpResult {
        docService.registerDynamicNamespace(name, content)
        return KotlinMcpResult.Success(
            content = "Registered custom namespace '$name' for documentation lookups.",
            metadata = mapOf("kind" to "namespace", "name" to name)
        )
    }

    // ---- kotlin_code ----

    fun codeInspectSymbol(code: String): KotlinMcpResult =
        codeAnalysisService.execute(CodeAnalysisAction.INSPECT_SYMBOL, code)

    fun codeAnalyzeNullability(code: String): KotlinMcpResult =
        codeAnalysisService.execute(CodeAnalysisAction.ANALYZE_NULLABILITY, code)

    fun codeExplainCoroutines(code: String): KotlinMcpResult =
        codeAnalysisService.execute(CodeAnalysisAction.EXPLAIN_COROUTINES, code)

    fun codeAnalyzeCompose(code: String): KotlinMcpResult =
        codeAnalysisService.execute(CodeAnalysisAction.ANALYZE_COMPOSE, code)

    fun codeFileContext(code: String, workspacePath: String? = null): KotlinMcpResult =
        codeAnalysisService.execute(CodeAnalysisAction.FILE_CONTEXT, code, workspacePath)

    fun codeAnalyzeWorkManager(code: String): KotlinMcpResult =
        codeAnalysisService.execute(CodeAnalysisAction.WORKMANAGER, code)

    // ---- kotlin_lsp ----

    fun lspFindDefinition(code: String, symbol: String?, workspacePath: String? = null): KotlinMcpResult =
        textService.execute(LspAction.FIND_DEFINITION, code, symbol = symbol, workspacePath = workspacePath)

    /** Legacy two-argument overload, retained for binary compatibility. */
    fun lspFindDefinition(code: String, symbol: String?): KotlinMcpResult =
        lspFindDefinition(code, symbol, null)

    fun lspFindReferences(code: String, symbol: String?, workspacePath: String?): KotlinMcpResult =
        textService.execute(LspAction.FIND_REFERENCES, code, symbol = symbol, workspacePath = workspacePath)

    fun lspGetCompletions(code: String, symbol: String?): KotlinMcpResult =
        textService.execute(LspAction.GET_COMPLETIONS, code, symbol = symbol)

    fun lspRenameSymbol(code: String, oldName: String?, newName: String?, workspacePath: String?): KotlinMcpResult =
        textService.execute(LspAction.RENAME_SYMBOL, code, symbol = oldName, newName = newName, workspacePath = workspacePath)

    fun lspWorkspaceSearch(symbol: String?, workspacePath: String?): KotlinMcpResult =
        textService.execute(LspAction.WORKSPACE_SEARCH, "", symbol = symbol, workspacePath = workspacePath)

    fun lspWorkspaceReferences(symbol: String?, workspacePath: String?): KotlinMcpResult =
        textService.execute(LspAction.WORKSPACE_REFERENCES, "", symbol = symbol, workspacePath = workspacePath)

    fun lspTypeHierarchy(code: String, symbol: String?, workspacePath: String?): KotlinMcpResult =
        textService.execute(LspAction.TYPE_HIERARCHY, code, symbol = symbol, workspacePath = workspacePath)

    fun lspCallHierarchy(code: String, symbol: String?, workspacePath: String?): KotlinMcpResult =
        textService.execute(LspAction.CALL_HIERARCHY, code, symbol = symbol, workspacePath = workspacePath)

    fun lspHover(code: String, symbol: String?, workspacePath: String?): KotlinMcpResult =
        textService.execute(LspAction.HOVER, code, symbol = symbol, workspacePath = workspacePath)

    /** Releases cached PSI / analysis state held by the embedded services (safe to call once at shutdown). */
    fun close() {
        runCatching { textService.close() }
            .onFailure { logger.warn(it) { "Failed to close LSP text service during shutdown." } }
        runCatching { semanticEngine.close() }
            .onFailure { logger.warn(it) { "Failed to close K2 semantic engine during shutdown." } }
        runCatching { mutationService.close() }
            .onFailure { logger.warn(it) { "Failed to close mutation service during shutdown." } }
    }


    // ---- kotlin_check_snippet / project layout / mutation ----

    fun checkSnippet(code: String, classpath: List<String> = emptyList(), projectPath: String? = null): KotlinMcpResult =
        diagnosticService.execute(DiagnosticAction.CHECK_SNIPPET, code, projectPath = projectPath, classpath = classpath)

    fun mutationTest(
        code: String,
        testCode: String? = null,
        preset: String? = null
    ): KotlinMcpResult =
        mutationService.mutateAndTest(code, testCode, ResponseProjection(ResponsePreset.fromString(preset)))

    fun runProjectLayout(projectPath: String?): KotlinMcpResult =
        diagnosticService.execute(DiagnosticAction.RUN_PROJECT_LAYOUT, code = "", projectPath = projectPath)

    // ---- kotlin_run ----

    fun runSnippet(
        code: String,
        timeoutMillis: Long = 10_000L,
        classpath: List<String> = emptyList(),
        runner: String = "host_jvm",
        jvmArgs: List<String> = emptyList(),
        javaPath: String? = null,
        projectPath: String? = null
    ): KotlinMcpResult =
        runSnippetService.execute(code, timeoutMillis, classpath, runner, jvmArgs, javaPath, projectPath)



    fun gradleRun(projectPath: String, task: String, timeoutMillis: Long = 120_000L): KotlinMcpResult =
        gradleRunService.execute(projectPath, task, timeoutMillis)

    fun runTestReport(projectPath: String): KotlinMcpResult =
        runSnippetService.parseTestReport(projectPath)


    // ---- kotlin_project ----

    fun projectInspectStructure(buildScriptContent: String, projectPath: String? = null): KotlinMcpResult =
        projectService.execute(ProjectAction.INSPECT_STRUCTURE, buildScriptContent, projectPath)

    fun projectListKmpTargets(buildScriptContent: String): KotlinMcpResult =
        projectService.execute(ProjectAction.LIST_KMP_TARGETS, buildScriptContent)

    fun projectAnalyzeDependencies(buildScriptContent: String, projectPath: String? = null): KotlinMcpResult =
        projectService.execute(ProjectAction.ANALYZE_DEPENDENCIES, buildScriptContent, projectPath)

    fun projectSchemaDigest(projectPath: String?): KotlinMcpResult =
        projectService.execute(ProjectAction.SCHEMA_DIGEST, "", projectPath)

    fun projectDiagnoseBuild(
        buildScriptContent: String,
        settingsContent: String? = null,
        gradlePropertiesContent: String? = null
    ): KotlinMcpResult =
        projectService.diagnoseBuild(buildScriptContent, settingsContent.orEmpty(), gradlePropertiesContent.orEmpty())

    fun projectCheckVulnerabilities(
        buildScriptContent: String,
        projectPath: String? = null,
        connectTimeoutMs: Int = 4000,
        readTimeoutMs: Int = 6000,
        maxRetries: Int = 3
    ): KotlinMcpResult =
        projectService.checkVulnerabilities(buildScriptContent, projectPath, connectTimeoutMs, readTimeoutMs, maxRetries)

    fun projectPackageApi(projectPath: String?, packageName: String? = null): KotlinMcpResult =
        projectService.execute(ProjectAction.PACKAGE_API, buildScriptContent = "", projectPath = projectPath, packageName = packageName)

    fun projectCoverageReport(projectPath: String?): KotlinMcpResult =
        projectService.coverageReport(projectPath)

    /**
     * Statically inspects an AndroidManifest.xml file or snippet for exported attributes and foreground service types.
     */
    fun projectInspectAndroidManifest(contentOrPath: String, projectPath: String? = null): KotlinMcpResult =
        projectService.execute(ProjectAction.INSPECT_ANDROID_MANIFEST, buildScriptContent = contentOrPath, projectPath = projectPath)

    /**
     * Statically audits an Android Gradle build script for Kotlin 2.x and AGP configuration alignment.
     */
    fun projectAuditAndroidConfig(buildScriptContent: String): KotlinMcpResult =
        projectService.execute(ProjectAction.AUDIT_ANDROID_CONFIG, buildScriptContent = buildScriptContent)

    /**
     * Resolves published Maven versions for a given coordinate (e.g. 'io.ktor:ktor-client-core').
     */
    fun projectResolveVersions(coordinate: String, customRepoUrl: String? = null): KotlinMcpResult =
        projectService.resolveVersions(coordinate, customRepoUrl)

    /**
     * Gets the latest stable published version for a given Maven coordinate.
     */
    fun projectGetLatestVersion(coordinate: String, customRepoUrl: String? = null): KotlinMcpResult =
        projectService.getLatestVersion(coordinate, customRepoUrl)

    /**
     * Audits all declared dependencies in gradle/libs.versions.toml for available newer versions.
     */
    fun projectCheckCatalogUpdates(projectPath: String? = null): KotlinMcpResult =
        projectService.checkCatalogUpdates(projectPath)

    /**
     * Resolves effective Android runtime target metadata (applicationId, namespace, launcher activity, and CLI commands).
     */
    fun projectResolveAndroidRuntimeTarget(
        manifestContentOrPath: String,
        projectPath: String? = null,
        buildScriptContent: String? = null
    ): KotlinMcpResult =
        projectService.resolveAndroidRuntimeTarget(manifestContentOrPath, projectPath, buildScriptContent)

    /**
     * Statically audits an Android project/snippet across targeted categories (Compose performance, permissions, R8).
     */
    fun projectAuditAndroidApp(
        codeOrWorkspace: String,
        projectPath: String? = null,
        category: String? = null
    ): KotlinMcpResult {
        val catList = category?.let { com.gokorei.kotlinmcp.project.AndroidAuditCategory.fromString(it) }?.let { listOf(it) } ?: emptyList()
        return projectService.auditAndroidApp(codeOrWorkspace, projectPath, catList)
    }



    // ---- kotlin_refactor ----

    fun refactorJavaToKotlin(code: String): KotlinMcpResult =
        refactoringService.execute(RefactoringAction.JAVA_TO_KOTLIN, code)

    fun refactorImperativeToFunctional(code: String): KotlinMcpResult =
        refactoringService.execute(RefactoringAction.IMPERATIVE_TO_FUNCTIONAL, code)

    fun refactorSuggestIdioms(code: String): KotlinMcpResult =
        refactoringService.execute(RefactoringAction.SUGGEST_IDIOMS, code)

    fun refactorGenerateQuickFix(code: String, diagnostic: String): KotlinMcpResult =
        refactoringService.execute(RefactoringAction.GENERATE_QUICK_FIX, code, diagnostic)

    fun refactorRxJavaToCoroutines(code: String): KotlinMcpResult =
        refactoringService.execute(RefactoringAction.RXJAVA_TO_COROUTINES, code)

    fun refactorToArrow(code: String, legacy: String? = null): KotlinMcpResult =
        refactoringService.execute(RefactoringAction.MIGRATE_ARROW_RAISE, code)

    fun suggestKotlinxDatetime(code: String): KotlinMcpResult =
        refactoringService.execute(RefactoringAction.MIGRATE_DATETIME, code)

    // ---- kotlin_library ----

    fun analyzeKtor(code: String): KotlinMcpResult =
        libraryAnalysisService.execute(LibraryAnalysisAction.ANALYZE_KTOR, code)

    fun analyzeSerialization(code: String, dataSources: List<String>): KotlinMcpResult =
        libraryAnalysisService.execute(LibraryAnalysisAction.ANALYZE_SERIALIZATION, code, dataSources)

    fun analyzeTests(code: String): KotlinMcpResult =
        libraryAnalysisService.execute(LibraryAnalysisAction.ANALYZE_TESTS, code)

    fun routeMap(code: String): KotlinMcpResult =
        libraryAnalysisService.execute(LibraryAnalysisAction.ROUTE_MAP, code)

    /**
     * Statically inspects Kotlin code for Hilt and Dagger Android DI annotation wiring consistency.
     */
    fun analyzeAndroidDi(code: String): KotlinMcpResult =
        libraryAnalysisService.execute(LibraryAnalysisAction.ANALYZE_ANDROID_DI, code)

    /**
     * Statically inspects WorkManager CoroutineWorker and ListenableWorker implementations for Hilt DI, foreground types, and thread safety.
     */
    fun analyzeWorkManager(code: String): KotlinMcpResult =
        libraryAnalysisService.execute(LibraryAnalysisAction.ANALYZE_WORKMANAGER, code)

    // ---- kotlin_lint ----

    fun runDetekt(
        code: String,
        workspacePath: String? = null,
        config: Map<String, Any> = emptyMap(),
        compilerClasspath: List<String> = emptyList()
    ): KotlinMcpResult =
        lintService.runDetekt(code, workspacePath, config, compilerClasspath)

    fun formatKtlint(
        code: String,
        apply: Boolean = true,
        compilerClasspath: List<String> = emptyList()
    ): KotlinMcpResult =
        lintService.formatKtlint(code, apply, compilerClasspath)

    fun baselineRead(workspacePath: String): KotlinMcpResult =
        lintService.baselineRead(workspacePath)

    fun baselineDump(workspacePath: String, findings: List<LintFinding>? = null): KotlinMcpResult =
        lintService.baselineDump(workspacePath, findings)

    /**
     * Parses an Android Lint XML report into structured findings with coordinates.
     */
    fun parseAndroidLint(xmlContentOrPath: String, workspacePath: String? = null): KotlinMcpResult =
        lintService.parseAndroidLintReport(xmlContentOrPath, workspacePath)
}
