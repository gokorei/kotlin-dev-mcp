package com.gokorei.kotlinmcp.server

import com.gokorei.kotlinmcp.doc.ParamDocSpec
import com.gokorei.kotlinmcp.doc.ToolDocSpec
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Registers the consolidated Kotlin developer tools on the MCP [Server].
 *
 * Consolidates the tool surface into 11 tools (5 read-only, 6 edit/mutating) using progressive discovery
 * action parameters to drastically reduce LLM prompt token consumption.
 */
object ToolRegistrar {

    fun registerReadOnlyTools(server: Server, kotlinServer: KotlinMcpServer) {
        collectReadOnlyTools(kotlinServer) { name, builder ->
            ToolBuilder(name, kotlinServer).apply(builder).registerOn(server)
        }
    }

    fun registerEditTools(server: Server, kotlinServer: KotlinMcpServer) {
        collectEditTools(kotlinServer) { name, builder ->
            ToolBuilder(name, kotlinServer).apply(builder).registerOn(server)
        }
    }

    fun buildToolDocSpecs(kotlinServer: KotlinMcpServer = KotlinMcpServer()): List<ToolDocSpec> {
        val list = mutableListOf<ToolDocSpec>()
        collectReadOnlyTools(kotlinServer) { name, builder ->
            list.add(ToolBuilder(name, kotlinServer).apply(builder).toToolDocSpec())
        }
        collectEditTools(kotlinServer) { name, builder ->
            list.add(ToolBuilder(name, kotlinServer).apply(builder).toToolDocSpec())
        }
        return list
    }

    private fun collectReadOnlyTools(
        kotlinServer: KotlinMcpServer,
        register: (name: String, builder: ToolBuilder.() -> Unit) -> Unit
    ) {
        // 1. kotlin_docs_read
        register("kotlin_docs_read") {
            description = "READ-ONLY. Search and inspect Kotlin stdlib and framework documentation."
            readOnly = true
            actions("search", "lookup", "explain")
            param("action", "Operation: 'search' (default), 'lookup', 'explain'")
            param("query", "Search query or target symbol/feature name for search/lookup/explain operations")
            param("preset", "Optional response projection for lookup: 'compact' (signature only) or 'full' (default)")
            param("classpath", "Optional array of jar/dir paths for library-aware docs", type = "array", itemsType = "string")
            handleSimple { k, a ->
                dispatchAction(
                    action = a["action"],
                    defaultAction = "search",
                    args = a,
                    handlers = mapOf(
                        "search" to { args -> k.docsSearch(args["query"].orEmpty()) },
                        "lookup" to { args -> k.docsLookupSymbol(args["query"].orEmpty(), args["preset"]) },
                        "explain" to { args -> k.docsExplainFeature(args["query"].orEmpty()) }
                    )
                )
            }
        }

        // 2. kotlin_code_analyze
        register("kotlin_code_analyze") {
            description = "READ-ONLY. AST static analysis for Kotlin code snippets."
            readOnly = true
            actions("inspect", "nullability", "coroutines", "compose", "file_context")
            param("action", "Analysis action: 'inspect' (default, declared elements), 'nullability' (unsafe null handling), 'coroutines' (scope safety & blocking calls), 'compose' (Compose anti-patterns), 'file_context' (cross-file dependencies of a target file)")
            param("code", "Kotlin source code snippet to analyze, or absolute path of a .kt file for file_context")
            param("workspacePath", "Optional workspace root directory (required for file_context)")
            handleSimple { k, a ->
                val code = a["code"].orEmpty()
                dispatchAction(
                    action = a["action"],
                    defaultAction = "inspect",
                    args = a,
                    handlers = mapOf(
                        "inspect" to { k.codeInspectSymbol(code) },
                        "nullability" to { k.codeAnalyzeNullability(code) },
                        "coroutines" to { k.codeExplainCoroutines(code) },
                        "compose" to { k.codeAnalyzeCompose(code) },
                        "file_context" to { k.codeFileContext(code, a["workspacePath"]) }
                    )
                )
            }
        }

        // 3. kotlin_text_lsp_read
        register("kotlin_text_lsp_read") {
            description = "READ-ONLY. AST text services: find definitions, references, completions, search workspace, or trace call/type hierarchies."
            readOnly = true
            actions("definition", "references", "completion", "workspace_search", "workspace_references", "type_hierarchy", "call_hierarchy")
            param("action", "LSP action: 'definition' (default), 'references', 'completion', 'workspace_search' (fuzzy symbol search), 'workspace_references' (exact reference locations), 'type_hierarchy' (super/subtypes), 'call_hierarchy' (incoming/outgoing calls)")
            param("code", "Kotlin source code snippet context")
            param("symbol", "Target symbol name (or prefix for completion, or query for workspace_search)")
            param("workspacePath", "Optional root directory path of workspace (required for workspace_search/workspace_references/hierarchies)")
            handleSimple { k, a ->
                val code = a["code"].orEmpty()
                val symbol = a["symbol"].orEmpty()
                val ws = a["workspacePath"]
                dispatchAction(
                    action = a["action"],
                    defaultAction = "definition",
                    args = a,
                    handlers = mapOf(
                        "definition" to { k.lspFindDefinition(code, symbol) },
                        "references" to { k.lspFindReferences(code, symbol, ws) },
                        "completion" to { k.lspGetCompletions(code, symbol) },
                        "workspace_search" to { k.lspWorkspaceSearch(symbol, ws) },
                        "workspace_references" to { k.lspWorkspaceReferences(symbol, ws) },
                        "type_hierarchy" to { k.lspTypeHierarchy(code, symbol, ws) },
                        "call_hierarchy" to { k.lspCallHierarchy(code, symbol, ws) }
                    )
                )
            }
        }

        // 4. kotlin_project_inspect
        register("kotlin_project_inspect") {
            description = "READ-ONLY. Gradle build script and project layout inspection."
            readOnly = true
            actions("structure", "kmp_targets", "dependencies", "schema_digest", "diagnose_build", "layout_inventory", "vulnerabilities", "package_api", "coverage_report")
            param("action", "Inspection action: 'structure' (default, plugins & source sets), 'kmp_targets', 'dependencies', 'schema_digest' (API/DB schema digest from SQL DDL, Exposed tables, @Serializable DTOs, OpenAPI), 'diagnose_build' (pre-build check), 'layout_inventory' (disk layout listing), 'vulnerabilities' (security advisory audit), 'package_api' (public API surface of a package), 'coverage_report' (JaCoCo test coverage summary)")
            param("buildScriptContent", "Content of build.gradle.kts")
            param("projectPath", "Path to Gradle project root directory (aliases: workspacePath, path)")
            param("packageName", "Target package for package_api (e.g. com.example.app)")
            param("settingsContent", "Optional settings.gradle.kts content for diagnose_build")
            param("gradlePropertiesContent", "Optional gradle.properties content for diagnose_build")
            param("connectTimeoutMs", "Optional connect timeout in milliseconds for OSV vulnerability check (default: 4000)")
            param("readTimeoutMs", "Optional read timeout in milliseconds for OSV vulnerability check (default: 6000)")
            param("maxRetries", "Optional max retry attempts for OSV vulnerability query batch (default: 3)")
            handleSimple { k, a ->
                val projectPath = a["projectPath"] ?: a["workspacePath"] ?: a["path"]
                val script = a["buildScriptContent"].orEmpty()
                dispatchAction(
                    action = a["action"],
                    defaultAction = "structure",
                    args = a,
                    handlers = mapOf(
                        "structure" to { k.projectInspectStructure(script, projectPath) },
                        "kmp_targets" to { k.projectListKmpTargets(script) },
                        "dependencies" to { k.projectAnalyzeDependencies(script, projectPath) },
                        "schema_digest" to { k.projectSchemaDigest(projectPath) },
                        "diagnose_build" to { k.projectDiagnoseBuild(script, a["settingsContent"], a["gradlePropertiesContent"]) },
                        "layout_inventory" to { k.runProjectLayout(projectPath) },
                        "vulnerabilities" to {
                            val connectTimeout = a["connectTimeoutMs"]?.toIntOrNull() ?: 4000
                            val readTimeout = a["readTimeoutMs"]?.toIntOrNull() ?: 6000
                            val retries = a["maxRetries"]?.toIntOrNull() ?: 3
                            k.projectCheckVulnerabilities(script, projectPath, connectTimeout, readTimeout, retries)
                        },
                        "package_api" to { k.projectPackageApi(projectPath, a["packageName"]) },
                        "coverage_report" to { k.projectCoverageReport(projectPath) }
                    )
                )
            }
        }

        // 5. kotlin_check_snippet
        register("kotlin_check_snippet") {
            description = "READ-ONLY. Compile a Kotlin snippet with the embedded K2 compiler and report real syntax/type errors with line:column."
            readOnly = true
            param("code", "Kotlin code snippet to compile-check")
            param("classpath", "Optional array of jar/dir paths added to compile classpath", type = "array", itemsType = "string")
            required("code")
            handleSimple { k, a ->
                val code = a["code"].orEmpty()
                val cp = a["classpath"]?.split(",", ";")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                k.checkSnippet(code, cp)
            }
        }
    }

    private fun collectEditTools(
        kotlinServer: KotlinMcpServer,
        register: (name: String, builder: ToolBuilder.() -> Unit) -> Unit
    ) {
        // 1. kotlin_docs_edit
        register("kotlin_docs_edit") {
            description = "MUTATING. Register custom documentation entries dynamically at runtime and disk persistence."
            readOnly = false
            actions("register_symbol", "register_feature", "register_namespace")
            param("action", "Operation: 'register_symbol' (default), 'register_feature', 'register_namespace'")
            param("name", "Target name/prefix for register operations")
            param("content", "Markdown documentation content for register operations")
            required("name")
            handleSimple { k, a ->
                val name = a["name"].orEmpty()
                val content = a["content"].orEmpty()
                dispatchAction(
                    action = a["action"],
                    defaultAction = "register_symbol",
                    args = a,
                    handlers = mapOf(
                        "register_symbol" to { k.docsRegisterSymbol(name, content) },
                        "register_feature" to { k.docsRegisterFeature(name, content) },
                        "register_namespace" to { k.docsRegisterNamespace(name, content) }
                    )
                )
            }
        }

        // 2. kotlin_text_lsp_edit
        register("kotlin_text_lsp_edit") {
            description = "MUTATING. AST-based symbol renaming across snippet and workspace files in place."
            readOnly = false
            actions("rename")
            param("action", "LSP action: 'rename' (default)")
            param("code", "Kotlin source code snippet context")
            param("oldName", "Current symbol name for rename")
            param("newName", "New symbol name for rename")
            param("workspacePath", "Optional root directory path of workspace")
            required("oldName", "newName")
            handleSimple { k, a ->
                val code = a["code"].orEmpty()
                val oldName = a["oldName"].orEmpty()
                val newName = a["newName"].orEmpty()
                dispatchAction(
                    action = a["action"],
                    defaultAction = "rename",
                    args = a,
                    handlers = mapOf(
                        "rename" to { k.lspRenameSymbol(code, oldName, newName, a["workspacePath"]) }
                    )
                )
            }
        }

        // 3. kotlin_refactor
        register("kotlin_refactor") {
            description = "MUTATING. Code refactorings and compiler-diagnostic quick-fixes that produce new code."
            readOnly = false
            actions("suggest_idioms", "java_to_kotlin", "functional", "quick_fix", "rxjava")
            param("action", "Refactoring action: 'suggest_idioms' (default), 'java_to_kotlin', 'functional' (collection loops), 'quick_fix' (diagnostic diff), 'rxjava' (RxJava to coroutines)")
            param("code", "Source code snippet")
            param("diagnostic", "Diagnostic message for quick_fix")
            required("code")
            handleSimple { k, a ->
                val code = a["code"].orEmpty()
                dispatchAction(
                    action = a["action"],
                    defaultAction = "suggest_idioms",
                    args = a,
                    handlers = mapOf(
                        "java_to_kotlin" to { k.refactorJavaToKotlin(code) },
                        "functional" to { k.refactorImperativeToFunctional(code) },
                        "suggest_idioms" to { k.refactorSuggestIdioms(code) },
                        "quick_fix" to { k.refactorGenerateQuickFix(code, a["diagnostic"].orEmpty()) },
                        "rxjava" to { k.refactorRxJavaToCoroutines(code) }
                    )
                )
            }
        }

        // 4. kotlin_library_analyze
        register("kotlin_library_analyze") {
            description = "MUTATING. Library anti-pattern checks, modernization suggestions, and code-transforming refactors (e.g. Arrow)."
            readOnly = false
            actions("ktor", "serialization", "tests", "route_map", "arrow", "datetime")
            param("action", "Primary library analysis action: 'ktor' (default), 'serialization', 'tests', 'route_map', 'arrow', 'datetime'")
            param("domain", "Deprecated backward-compatible alias for 'action'. Domain alias ('ktor', 'serialization', 'tests', 'arrow', 'datetime')")
            param("code", "Kotlin code snippet to analyze")
            param("dataSources", "Optional schema-diff links for serialization analysis")
            param("legacy", "Optional 'true' for Arrow 1.x monad mode in arrow refactoring")
            required("code")
            handleSimple { k, a ->
                val code = a["code"].orEmpty()
                val targetAction = a["action"] ?: a["domain"]
                dispatchAction(
                    action = targetAction,
                    defaultAction = "ktor",
                    args = a,
                    handlers = mapOf(
                        "ktor" to { k.analyzeKtor(code) },
                        "serialization" to {
                            val links = a["dataSources"]?.split(",", ";", "\n")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                            k.analyzeSerialization(code, links)
                        },
                        "tests" to { k.analyzeTests(code) },
                        "route_map" to { k.routeMap(code) },
                        "arrow" to { k.refactorToArrow(code, a["legacy"]) },
                        "datetime" to { k.suggestKotlinxDatetime(code) }
                    )
                )
            }
        }

        // 5. kotlin_lint
        register("kotlin_lint") {
            description = "MUTATING. Detekt and KtLint static analysis, baseline management, and code formatting."
            readOnly = false
            actions("lint", "detekt", "format", "format_ktlint", "baseline_read", "baseline_dump")
            param("action", "Lint action: 'lint' (default, alias: 'detekt'), 'format' (alias: 'format_ktlint'), 'baseline_read', 'baseline_dump'")
            param("code", "Kotlin source code snippet to lint or format")
            param("workspacePath", "Optional root directory path of workspace")
            handleSimple { k, a ->
                val code = a["code"].orEmpty()
                val ws = a["workspacePath"].orEmpty()
                dispatchAction(
                    action = a["action"],
                    defaultAction = "lint",
                    args = a,
                    handlers = mapOf(
                        "lint" to { k.runDetekt(code, workspacePath = a["workspacePath"]) },
                        "detekt" to { k.runDetekt(code, workspacePath = a["workspacePath"]) },
                        "format" to { k.formatKtlint(code, true) },
                        "format_ktlint" to { k.formatKtlint(code, true) },
                        "baseline_read" to { k.baselineRead(ws) },
                        "baseline_dump" to { k.baselineDump(ws) }
                    )
                )
            }
        }

        // 6. kotlin_run
        register("kotlin_run") {
            description = "MUTATING. Compile and execute standalone Kotlin snippets, Gradle tasks, or test report parsers in an isolated host JVM process."
            readOnly = false
            actions("snippet", "gradle_task", "test_report")
            param("action", "Execution action: 'snippet' (default), 'gradle_task', 'test_report'")
            param("code", "Kotlin source code snippet containing a main() entry point or top-level expressions")
            param("taskName", "Gradle task name to execute for action='gradle_task' (e.g. 'test', 'check')")
            param("workspacePath", "Optional root directory path of project/workspace")
            param("jvmArgs", "Optional string array of JVM arguments (allow-listed: -D, -Xms, -Xmx, --add-opens)")
            param("classpath", "Optional array of jar/dir paths added to execution classpath", type = "array", itemsType = "string")
            param("timeoutSeconds", "Execution timeout in seconds (default: 10)")
            handleSimple { k, a ->
                val code = a["code"].orEmpty()
                val task = a["taskName"] ?: a["task"] ?: "test"
                val ws = a["workspacePath"] ?: a["projectPath"] ?: "."
                val timeoutSec = a["timeoutSeconds"]?.toLongOrNull() ?: 10L
                val cp = a["classpath"]?.split(",", ";")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                val jvmArgs = a["jvmArgs"]?.split(" ")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

                dispatchAction(
                    action = a["action"],
                    defaultAction = "snippet",
                    args = a,
                    handlers = mapOf(
                        "snippet" to { k.runSnippet(code, timeoutSec * 1000L, cp, "host_jvm", jvmArgs, null) },
                        "gradle_task" to { k.gradleRun(ws, task, timeoutSec * 1000L) },
                        "test_report" to { k.runTestReport(ws) }
                    )
                )
            }
        }
    }

    fun parseClasspathElement(element: JsonElement?): List<String> {
        if (element == null) return emptyList()
        return when (element) {
            is kotlinx.serialization.json.JsonArray -> parseStringList(element)
            is JsonPrimitive -> {
                val content = element.content.trim()
                if (content.startsWith("[") && content.endsWith("]")) {
                    val parsedArray = runCatching {
                        kotlinx.serialization.json.Json.parseToJsonElement(content) as? kotlinx.serialization.json.JsonArray
                    }.getOrNull()
                    if (parsedArray != null) return parseStringList(parsedArray)
                }
                content.split(File.pathSeparator, ",", ";", "\n").map { it.trim() }.filter { it.isNotBlank() }
            }
            else -> emptyList()
        }
    }

    fun parseStringList(element: kotlinx.serialization.json.JsonArray): List<String> {
        return element.mapNotNull {
            (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotBlank() }
        }
    }

    data class ParamSpec(
        val name: String,
        val description: String,
        val type: String = "string",
        val itemsType: String? = null,
        val required: Boolean = false
    )

    private fun registerTool(
        server: Server,
        kotlinServer: KotlinMcpServer,
        name: String,
        builder: ToolBuilder.() -> Unit
    ) {
        ToolBuilder(name, kotlinServer).apply(builder).registerOn(server)
    }

    fun toCallToolResult(result: KotlinMcpResult): CallToolResult {
        return CallToolResult(
            content = listOf(TextContent(text = result.toFormattedText())),
            isError = result.isError
        )
    }

    @DslMarker
    annotation class ToolDslMarker

    @ToolDslMarker
    class ToolBuilder(
        val name: String,
        val kotlinServer: KotlinMcpServer
    ) {
        var description: String = ""
        var readOnly: Boolean = true
        var actions: List<String> = emptyList()
        val params = mutableListOf<ParamSpec>()
        private var requiredKeys: List<String>? = null
        private var handler: ((Map<String, JsonElement>) -> KotlinMcpResult)? = null

        fun actions(vararg actionNames: String) {
            actions = actionNames.toList()
        }

        fun param(name: String, description: String, type: String = "string", itemsType: String? = null, required: Boolean = false) {
            params.add(ParamSpec(name, description, type, itemsType, required))
        }

        fun required(vararg names: String) {
            requiredKeys = names.toList()
        }

        fun handle(block: (KotlinMcpServer, Map<String, JsonElement>) -> KotlinMcpResult) {
            handler = { args -> block(kotlinServer, args) }
        }

        fun handleSimple(block: (KotlinMcpServer, Map<String, String>) -> KotlinMcpResult) {
            handler = { rawArgs ->
                val strArgs = rawArgs.mapValues { (_, value) ->
                    when (value) {
                        is JsonPrimitive -> value.content
                        is kotlinx.serialization.json.JsonArray -> parseStringList(value).joinToString(",")
                        else -> value.toString()
                    }
                }
                val normalized = normalizeArgs(strArgs)
                block(kotlinServer, normalized)
            }
        }

        fun toToolDocSpec(): ToolDocSpec {
            val finalRequired = requiredKeys ?: params.filter { it.required }.map { it.name }
            val cleanDescription = description
                .removePrefix("READ-ONLY. ")
                .removePrefix("MUTATING. ")
            return ToolDocSpec(
                name = name,
                description = cleanDescription,
                readOnly = readOnly,
                actions = actions,
                params = params.map { p ->
                    ParamDocSpec(
                        name = p.name,
                        description = p.description,
                        type = p.type,
                        itemsType = p.itemsType,
                        required = p.required || finalRequired.contains(p.name)
                    )
                },
                requiredParams = finalRequired
            )
        }

        internal fun registerOn(server: Server) {
            val finalRequired = requiredKeys ?: params.filter { it.required }.map { it.name }
            val finalHandler = requireNotNull(handler) { "Handler must be specified for tool '$name'" }

            val schema = ToolSchema(
                properties = buildJsonObject {
                    params.forEach { param ->
                        putJsonObject(param.name) {
                            put("type", param.type)
                            if (param.type == "array" && param.itemsType != null) {
                                putJsonObject("items") {
                                    put("type", param.itemsType)
                                }
                            }
                            put("description", param.description)
                        }
                    }
                },
                required = finalRequired
            )

            server.addTool(
                tool = Tool(
                    name = name,
                    description = description,
                    inputSchema = schema,
                    annotations = ToolAnnotations(readOnlyHint = readOnly)
                )
            ) { request ->
                val args = request.arguments.orEmpty()
                toCallToolResult(finalHandler(args))
            }
        }
    }

    fun normalizeArgs(args: Map<String, String>): Map<String, String> {
        val result = args.toMutableMap()
        val snippetAlias = args["snippet"] ?: args["code"]
        if (snippetAlias != null) {
            result["code"] = snippetAlias
        }
        val projectAlias = args["projectPath"] ?: args["workspacePath"] ?: args["path"]
        if (projectAlias != null) {
            result["projectPath"] = projectAlias
            result["path"] = projectAlias
        }
        return result
    }

    fun formatDomainDescription(profile: com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile = com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile.NONE): String {
        val base = "Domain alias ('ktor', 'serialization', 'tests', 'arrow', 'datetime')"
        return if (profile.activeFrameworks.isNotEmpty()) {
            val detected = profile.activeFrameworks.joinToString(", ") { it.name.lowercase() }
            "$base (detected in project: $detected)"
        } else {
            base
        }
    }

    fun dispatchAction(
        action: String?,
        handlers: Map<String, (Map<String, String>) -> KotlinMcpResult>,
        args: Map<String, String> = emptyMap(),
        defaultAction: String = ""
    ): KotlinMcpResult {
        val key = action.orEmpty().ifBlank { defaultAction }
        val handler = handlers[key] ?: return KotlinMcpResult.Error(
            code = "INVALID_ACTION",
            message = "Unknown action '$key'. Supported actions: ${handlers.keys.joinToString(", ")}"
        )
        return handler(args)
    }
}
