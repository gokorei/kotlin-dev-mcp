package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile
import com.gokorei.kotlinmcp.shared.CommandService
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.lsp.WorkspaceSemanticIndexer
import java.io.File



enum class ProjectAction {
    INSPECT_STRUCTURE,
    LIST_KMP_TARGETS,
    ANALYZE_DEPENDENCIES,
    SCHEMA_DIGEST,
    DIAGNOSE_BUILD,
    CHECK_VULNERABILITIES,
    PACKAGE_API,
    COVERAGE_REPORT,
    INSPECT_GRADLE_PROJECT,
    ANALYZE_PROJECT_LAYERING,
    AUDIT_VULNERABILITIES,
    EXPORT_PACKAGE_API,
    REPORT_COVERAGE,
    DETECT_ENVIRONMENT_PROFILE
}

/**
 * Service interface for inspecting Kotlin project layouts, build scripts, KMP targets, and dependency trees.
 */
interface ProjectService : CommandService<ProjectAction> {
    fun execute(action: ProjectAction, buildScriptContent: String, projectPath: String? = null, packageName: String? = null): KotlinMcpResult
    override fun execute(action: ProjectAction, code: String): KotlinMcpResult = execute(action, buildScriptContent = code)

    /** Lightweight pre-build diagnosis of a Gradle project from its build scripts / properties. */
    fun diagnoseBuild(buildScriptContent: String, settingsContent: String = "", gradlePropertiesContent: String = ""): KotlinMcpResult

    /** Audits resolved dependencies against known CVEs and security advisories. */
    fun checkVulnerabilities(
        buildScriptContent: String,
        projectPath: String? = null,
        connectTimeoutMs: Int = 4000,
        readTimeoutMs: Int = 6000,
        maxRetries: Int = 3
    ): KotlinMcpResult

    /** Dumps the public API surface (classes, functions, properties, visibility) of a package. */
    fun packageApi(projectPath: String?, packageName: String? = null): KotlinMcpResult

    /** Detects active frameworks and environment features from build scripts or disk project layout. */
    fun detectProfile(buildScriptContent: String, projectPath: String? = null): ProjectEnvironmentProfile

    /** Parses JaCoCo coverage reports under build/reports/jacoco/. */
    fun coverageReport(projectPath: String? = null): KotlinMcpResult
}

class DefaultProjectService(
    private val indexer: WorkspaceSemanticIndexer = WorkspaceSemanticIndexer(),
    private val schemaScanner: SchemaScanner = SchemaScanner()
) : ProjectService {

    override fun execute(action: ProjectAction, buildScriptContent: String, projectPath: String?, packageName: String?): KotlinMcpResult {
        return when (action) {
            ProjectAction.INSPECT_STRUCTURE, ProjectAction.INSPECT_GRADLE_PROJECT, ProjectAction.ANALYZE_PROJECT_LAYERING -> inspectStructure(buildScriptContent, projectPath)
            ProjectAction.LIST_KMP_TARGETS -> listKmpTargets(buildScriptContent)
            ProjectAction.ANALYZE_DEPENDENCIES -> analyzeDependencies(buildScriptContent, projectPath)
            ProjectAction.SCHEMA_DIGEST -> schemaScanner.scanSchemas(projectPath)
            ProjectAction.DIAGNOSE_BUILD -> diagnoseBuild(buildScriptContent)
            ProjectAction.CHECK_VULNERABILITIES, ProjectAction.AUDIT_VULNERABILITIES -> checkVulnerabilities(buildScriptContent, projectPath)
            ProjectAction.PACKAGE_API, ProjectAction.EXPORT_PACKAGE_API -> packageApi(projectPath, packageName)
            ProjectAction.COVERAGE_REPORT, ProjectAction.REPORT_COVERAGE -> coverageReport(projectPath)
            ProjectAction.DETECT_ENVIRONMENT_PROFILE -> KotlinMcpResult.Success(detectProfile(buildScriptContent, projectPath).toString())
        }
    }


    private fun detectSubprojects(projectPath: String?): List<String> {
        if (projectPath.isNullOrBlank()) return emptyList()
        val settingsFile = File(projectPath, "settings.gradle.kts")
        if (settingsFile.exists()) {
            val text = runCatching { settingsFile.readText() }.getOrNull().orEmpty()
            val subprojects = mutableListOf<String>()
            val psi = K2SnippetFrontend.parsePsi(text)
            if (psi != null) {
                psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                    override fun visitCallExpression(expression: org.jetbrains.kotlin.psi.KtCallExpression) {
                        val calleeText = expression.calleeExpression?.text
                        if (calleeText == "include") {
                            expression.valueArguments.forEach { arg ->
                                val expr = arg.getArgumentExpression()
                                val raw = expr?.text?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
                                if (!raw.isNullOrBlank()) {
                                    subprojects.add(raw)
                                }
                            }
                        }
                        super.visitCallExpression(expression)
                    }
                })
            }
            if (subprojects.isEmpty()) {
                val found = Regex("""include\s*\(([^)]+)\)""").findAll(text).flatMap { match ->
                    Regex("""["']([^"']+)["']""").findAll(match.groupValues[1]).map { it.groupValues[1] }
                }.toList()
                subprojects.addAll(found)
            }
            return subprojects.distinct()
        }
        return emptyList()
    }

    private fun inspectStructure(content: String, projectPath: String?): KotlinMcpResult {
        val plugins = detectPlugins(content)
        val pluginsBlock = if (plugins.isNotEmpty()) plugins.joinToString(", ") else "None detected"
        val sourceSets = detectSourceSets(content, projectPath)
        val layering = inspectLayering(content, projectPath)
        val subprojects = detectSubprojects(projectPath)
        val subprojectsLine = if (subprojects.isNotEmpty()) {
            "\n- Subprojects: ${subprojects.joinToString(", ") { "`$it`" }}"
        } else ""

        val output = """
            # Gradle Project Structure Analysis
            - Plugins: $pluginsBlock
            - Build Script Type: Kotlin DSL (`build.gradle.kts`)$subprojectsLine
            - Detected Source Sets: ${sourceSets.joinToString(", ") { "`$it`" }}

            ${layering}
        """.trimIndent()

        val metadataMap = mutableMapOf("pluginsCount" to plugins.size.toString())
        if (subprojects.isNotEmpty()) {
            metadataMap["subprojectsCount"] = subprojects.size.toString()
        }

        return KotlinMcpResult.Success(
            content = output,
            metadata = metadataMap
        )
    }

    /**
     * Questioning-driven, heuristic architectural inspection (DTGWHRF3). Rather than
     * rigid deterministic rules, detects the project's package topology when a
     * [projectPath] is available and surfaces soft advisories plus targeted
     * clarification questions when the layering is ambiguous.
     */
    private fun inspectLayering(content: String, projectPath: String?): String {
        val layers = listOf("ui", "presentation", "domain", "data", "repository", "infrastructure", "core", "common")
        val detected = mutableMapOf<String, MutableList<String>>()
        var packageCount = 0

        val root = projectPath?.let { java.io.File(it) }
        val sourceRoots = mutableListOf<File>()
        if (root != null && root.isDirectory) {
            root.walkTopDown().maxDepth(5).onEnter { dir ->
                val name = dir.name
                name != "build" && name != ".gradle" && name != ".git" && name != "out" && name != "node_modules"
            }.forEach { file ->
                if (file.isDirectory) {
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    if (rel == "src/main/kotlin" || rel == "src/main/java" ||
                        rel.endsWith("/src/main/kotlin") || rel.endsWith("/src/main/java") ||
                        rel.endsWith("/src/commonMain/kotlin")) {
                        sourceRoots.add(file)
                    }
                }
            }
        }

        sourceRoots.forEach { base ->
            base.walkTopDown().maxDepth(6).forEach { file ->
                if (file.isFile && (file.extension == "kt" || file.extension == "java")) {
                    packageCount++
                    val relative = file.relativeTo(base).invariantSeparatorsPath
                    layers.forEach { layer ->
                        if (relative.startsWith("$layer/") || relative.contains("/$layer/")) {
                            detected.getOrPut(layer) { mutableListOf() }.add(relative)
                        }
                    }
                }
            }
        }

        if (packageCount == 0 && detected.isEmpty()) {
            return """
                ## Architectural Layering (heuristic)

                No Kotlin source files found on disk under the given project path, so the
                package topology could not be verified.

                Clarification questions for the user:
                1. Does this project split UI/presentation from domain and data layers, or is it a single-module monolith?
                2. Do domain modules depend on infrastructure directly, or through injected interfaces?
                3. Are DTOs kept in the data layer and explicitly mapped to domain models at the boundary?
            """.trimIndent()
        }

        val summary = if (detected.isNotEmpty()) {
            detected.entries.joinToString("\n") { (layer, files) ->
                " - `$layer` layer: ${files.size} file(s) (e.g. ${files.take(3).joinToString(", ")})"
            }
        } else {
            " - No classic ui/domain/data layer packages found under `src/main/kotlin`."
        }

        val advisories = mutableListOf<String>()
        if (detected.containsKey("ui") && detected.containsKey("data") && !detected.containsKey("domain")) {
            advisories.add("UI and data packages are both present but no `domain` layer was found. Consider an explicit domain model so the UI does not depend on data shapes.")
        }
        if (detected.containsKey("data") && detected.containsKey("domain")) {
            advisories.add("Domain and data layers detected. Prefer injecting repository/use-case interfaces into the domain/presentation layers rather than depending on concrete data implementations.")
        }
        if (detected.keys.any { it == "repository" || it == "infrastructure" } && detected.containsKey("domain")) {
            advisories.add("Consider defining repository interfaces in the domain layer and keeping concrete implementations in the data/infrastructure layer.")
        }

        return buildString {
            appendLine("## Architectural Layering (heuristic)")
            appendLine()
            appendLine("Detected package topology (from disk):")
            appendLine(summary)
            if (advisories.isNotEmpty()) {
                appendLine()
                appendLine("Soft advisory suggestions:")
                advisories.forEach { appendLine(" - ${it}") }
            }
            appendLine()
            appendLine("Clarification questions for the user:")
            appendLine(" 1. Are boundaries enforced by module/dependency rules, or by convention?")
            appendLine(" 2. How are DTOs mapped to domain models — explicit mappers at the boundary, or direct leakage?")
            appendLine(" 3. Which layer owns threading/coroutine dispatch — is it injected and testable?")
        }
    }

    /**
     * Lightweight Gradle/AGP pre-build diagnostics (EXGKPB2H): scans build scripts and
     * properties for dependency version-mismatch risks, missing repositories, and
     * plugin conflicts — before running a full build.
     */
    override fun diagnoseBuild(buildScriptContent: String, settingsContent: String, gradlePropertiesContent: String): KotlinMcpResult {
        val findings = mutableListOf<String>()
        val all = buildScriptContent + "\n" + settingsContent + "\n" + gradlePropertiesContent

        // 1. Plugin conflicts: duplicate plugin ids in the plugins block.
        // `kotlin("jvm")` and `id("org.jetbrains.kotlin.jvm")` are the same plugin, so aliases are
        // normalised to their canonical id before counting duplicates.
        val pluginIds = Regex("""(?:kotlin\("|id\(")([^"]+)""").findAll(all)
            .map { canonicalPluginId(it.groupValues[1]) }
            .toList()
        pluginIds.groupingBy { it }.eachCount().filter { it.value > 1 }.forEach { (id, count) ->
            findings.add("🔴 Plugin conflict: `$id` is declared $count time(s).")
        }

        // 2. AGP vs Kotlin compatibility hint.
        val agp = Regex("""id\("com\.android\.(?:application|library)"\)\s*version\s*"([^"]+)""" ).find(all)?.groupValues?.get(1)
        val kotlin = Regex("""kotlin\("(?:jvm|android|multiplatform)"\)\s*version\s*"([^"]+)""" ).find(all)?.groupValues?.get(1)
        if (agp != null && kotlin != null) {
            val agpMajor = agp.substringBefore('.').toIntOrNull()
            val kotlinMajor = kotlin.substringBefore('.').toIntOrNull()
            if (agpMajor != null && kotlinMajor != null && agpMajor > 0 && kotlinMajor >= 2 && agpMajor <= 7) {
                findings.add("⚠️ AGP `$agp` with Kotlin `$kotlin` — verify against the official AGP↔Kotlin compatibility matrix (AGP 8.x requires recent Kotlin; AGP 7.x supports Kotlin 1.6–1.8).")
            } else {
                findings.add("ℹ️ AGP `$agp` with Kotlin `$kotlin` — no immediate mismatch flagged, but confirm the version pair on the compatibility matrix.")
            }
        }

        // 3. Missing repository declarations.
        val hasRepos = Regex("""\brepositories\s*\{""").containsMatchIn(buildScriptContent) ||
            Regex("""dependencyResolutionManagement\s*\{""").containsMatchIn(settingsContent) ||
            Regex("""pluginManagement\s*\{""").containsMatchIn(settingsContent)
        if (!hasRepos && all.isNotBlank()) {
            findings.add("🟡 Missing repository declaration: no `repositories { }`, `dependencyResolutionManagement`, or `pluginManagement` found. Add `mavenCentral()` (and `google()` for Android) or resolution will fail.")
        }

        // 4. Hardcoded versions without a version catalog / BOM.
        val hardcoded = Regex("""(?:\w+|libs\.\w+)?["(]([a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+):(\d+[a-zA-Z0-9_.-]*)[")]""").findAll(all).map { "${it.groupValues[1]}:${it.groupValues[2]}" }.toList()
        val usesCatalog = Regex("""\blibs\.[A-Za-z0-9_.]+""").containsMatchIn(all)
        val usesBom = Regex("""platform\s*\(|bom""", RegexOption.IGNORE_CASE).containsMatchIn(all)
        if (hardcoded.size >= 3 && !usesCatalog && !usesBom) {
            findings.add("🟡 ${hardcoded.size} dependencies hardcode versions inline (e.g. `${hardcoded.first()}`). Consider a version catalog (`libs.versions.toml`) or a BOM to keep versions consistent.")
        }

        // 5. Conflicting kotlin stdlib / serialization versions.
        val stdlibVersions = Regex("""kotlin-stdlib[":]*(\d[0-9a-zA-Z._-]*)""").findAll(all).map { it.groupValues[1] }.distinct().toList()
        if (stdlibVersions.size > 1) {
            findings.add("🔴 Conflicting `kotlin-stdlib` versions detected: ${stdlibVersions.joinToString(", ")}. Align them (or rely on the Kotlin Gradle Plugin's default).")
        }

        val content = if (findings.isNotEmpty()) {
            "# Gradle Pre-Build Diagnostics\n" + findings.joinToString("\n") +
                "\n\nRun `./gradlew help --no-daemon` or the build to confirm; these are heuristics, not a substitute for the build's own error reporting."
        } else {
            "# Gradle Pre-Build Diagnostics\nNo obvious pre-build issues detected. Versions, repositories, and plugin declarations look consistent."
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("findingsCount" to findings.size.toString())
        )
    }

    private fun canonicalPluginId(raw: String): String = when (raw) {
        "jvm" -> "org.jetbrains.kotlin.jvm"
        "android" -> "org.jetbrains.kotlin.android"
        "multiplatform" -> "org.jetbrains.kotlin.multiplatform"
        "js" -> "org.jetbrains.kotlin.js"
        "native" -> "org.jetbrains.kotlin.native"
        else -> raw
    }

    private fun detectPlugins(content: String): List<String> {
        val plugins = mutableListOf<String>()
        val psi = K2SnippetFrontend.parsePsi(content)
        if (psi != null) {
            psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: org.jetbrains.kotlin.psi.KtCallExpression) {
                    val callee = expression.calleeExpression?.text.orEmpty()
                    val arg = expression.valueArguments.firstOrNull()?.getArgumentExpression()?.text.orEmpty().removeSurrounding("\"")
                    if (callee == "kotlin" && arg.isNotBlank()) {
                        plugins.add("kotlin($arg)")
                    } else if (callee == "id" && arg.isNotBlank()) {
                        plugins.add("id($arg)")
                    }
                    super.visitCallExpression(expression)
                }

                override fun visitSimpleNameExpression(expression: org.jetbrains.kotlin.psi.KtSimpleNameExpression) {
                    val name = expression.getReferencedName()
                    if (name in setOf("application", "java-library", "java")) {
                        plugins.add(name)
                    }
                    super.visitSimpleNameExpression(expression)
                }
            })
        } else {
            Regex("""kotlin\("([^"]+)"\)""").findAll(content).forEach { plugins.add("kotlin(${it.groupValues[1]})") }
            Regex("""\bid\("([^"]+)"\)""").findAll(content).forEach { plugins.add("id(${it.groupValues[1]})") }
            val barePlugins = setOf("application", "java-library", "java")
            content.lines().map { it.trim() }.filter { it in barePlugins }.distinct().forEach { plugins.add(it) }
        }
        return plugins.distinct()
    }

    private fun detectSourceSets(content: String, projectPath: String?): List<String> {
        if (projectPath != null) {
            val root = java.io.File(projectPath)
            if (root.isDirectory) {
                val found = mutableListOf<String>()
                root.walkTopDown().maxDepth(5).onEnter { dir ->
                    val name = dir.name
                    name != "build" && name != ".gradle" && name != ".git" && name != "out" && name != "node_modules"
                }.forEach { file ->
                    if (file.isDirectory) {
                        val rel = file.relativeTo(root).invariantSeparatorsPath
                        if (rel == "src/main/kotlin" || rel == "src/main/java" ||
                            rel.endsWith("/src/main/kotlin") || rel.endsWith("/src/main/java") ||
                            rel.endsWith("/src/commonMain/kotlin") || rel.endsWith("/src/test/kotlin") ||
                            rel.endsWith("/src/androidTest/java") || rel.endsWith("/src/androidTest/kotlin")) {
                            found.add(rel)
                        }
                    }
                }
                if (found.isNotEmpty()) return found.distinct()
            }
        }
        val targets = detectTargets(content)
        return if (targets.isNotEmpty()) {
            listOf("commonMain", "commonTest") + targets.flatMap { listOf("${it}Main", "${it}Test") }
        } else {
            listOf("src/main/kotlin", "src/test/kotlin")
        }
    }

    private fun detectTargets(content: String): List<String> {
        val possibleTargets = listOf(
            "jvm", "androidTarget", "android", "iosX64", "iosArm64", "iosSimulatorArm64",
            "js", "wasmJs", "macosX64", "macosArm64", "linuxX64", "mingwX64"
        )
        return possibleTargets.filter { target ->
            content.contains("$target(") || content.contains("$target {") || content.lines().any { it.trim() == target }
        }
    }

    private fun listKmpTargets(content: String): KotlinMcpResult {
        val detectedTargets = detectTargets(content)

        val output = if (detectedTargets.isNotEmpty()) {
            "# Kotlin Multiplatform (KMP) Targets\nFound ${detectedTargets.size} target(s):\n" +
                detectedTargets.joinToString("\n") { " - `$it`" } +
                "\n\nSource sets structure:\n - `commonMain` / `commonTest`\n" +
                detectedTargets.joinToString("\n") { " - `${it}Main` / `${it}Test`" }
        } else {
            "# Kotlin Project Analysis\nStandard single-target JVM project configuration."
        }

        return KotlinMcpResult.Success(
            content = output,
            metadata = mapOf("targetCount" to detectedTargets.size.toString())
        )
    }

    private fun parseVersionCatalog(projectPath: String?): Map<String, String> {
        if (projectPath.isNullOrBlank()) return emptyMap()
        val tomlFile = File(projectPath, "gradle/libs.versions.toml")
        if (!tomlFile.exists()) return emptyMap()
        val text = runCatching { tomlFile.readText() }.getOrNull().orEmpty()

        val versions = mutableMapOf<String, String>()
        val libraries = mutableMapOf<String, String>()

        var currentSection = ""
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.isBlank()) continue
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length - 1).trim()
                continue
            }

            if (currentSection == "versions") {
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val valStr = parts[1].trim().trim('"').trim('\'')
                    versions[key] = valStr
                }
            } else if (currentSection == "libraries") {
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) {
                    val rawAlias = parts[0].trim()
                    val canonicalAlias = rawAlias.replace("-", ".")
                    val rhs = parts[1].trim()

                    if (rhs.startsWith("{") && rhs.endsWith("}")) {
                        val body = rhs.substring(1, rhs.length - 1)
                        val kvMap = mutableMapOf<String, String>()
                        body.split(",").forEach { kv ->
                            val kvParts = kv.split("=", limit = 2)
                            if (kvParts.size == 2) {
                                val k = kvParts[0].trim()
                                val v = kvParts[1].trim().trim('"').trim('\'')
                                kvMap[k] = v
                            }
                        }
                        val group = kvMap["group"]
                        val name = kvMap["name"]
                        val module = kvMap["module"]
                        val versionRef = kvMap["version.ref"]
                        val version = kvMap["version"] ?: versionRef?.let { versions[it] } ?: ""

                        val coord = when {
                            module != null -> if (version.isNotBlank()) "$module:$version" else module
                            group != null && name != null -> if (version.isNotBlank()) "$group:$name:$version" else "$group:$name"
                            else -> null
                        }
                        if (coord != null) {
                            libraries["libs.$canonicalAlias"] = coord
                            libraries["libs.$rawAlias"] = coord
                        }
                    } else if (rhs.startsWith("\"") || rhs.startsWith("'")) {
                        val coord = rhs.trim('"').trim('\'')
                        libraries["libs.$canonicalAlias"] = coord
                        libraries["libs.$rawAlias"] = coord
                    }
                }
            }
        }
        return libraries
    }

    private fun analyzeDependencies(content: String, projectPath: String? = null): KotlinMcpResult {
        val effectiveContent = if (content.isBlank() && !projectPath.isNullOrBlank()) {
            val bg = File(projectPath, "build.gradle.kts")
            if (bg.exists()) runCatching { bg.readText() }.getOrNull().orEmpty() else content
        } else content
        val entries = mutableListOf<String>()
        val knownConfigs = setOf("implementation", "api", "testImplementation", "runtimeOnly", "compileOnly", "testRuntimeOnly", "androidTestImplementation")
        val catalogMap = parseVersionCatalog(projectPath)
        val psi = K2SnippetFrontend.parsePsi(effectiveContent)

        val configs = "implementation|api|testImplementation|runtimeOnly|compileOnly|testRuntimeOnly|androidTestImplementation"
        // Groovy DSL: configuration 'group:artifact:version' or "group:artifact:version"
        Regex("""($configs)\s+["']([^"']+)["']""").findAll(content).forEach {
            entries.add("- `${it.groupValues[2]}` (${it.groupValues[1]})")
        }

        if (psi != null) {
            psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: org.jetbrains.kotlin.psi.KtCallExpression) {
                    val callee = expression.calleeExpression?.text.orEmpty()
                    if (callee in knownConfigs) {
                        val argExpr = expression.valueArguments.firstOrNull()?.getArgumentExpression()
                        if (argExpr != null) {
                            val argText = argExpr.text.trim()
                            val formatted = when {
                                argText.startsWith("project(") -> {
                                    val inner = argText.removePrefix("project(").removeSuffix(")").trim().removeSurrounding("\"").removePrefix(":")
                                    "- `project(\":$inner\")` ($callee, module)"
                                }
                                argText.startsWith("libs.") -> {
                                    val mapped = catalogMap[argText] ?: catalogMap[argText.replace("-", ".")]
                                    if (mapped != null) {
                                        "- `$argText` → `$mapped` ($callee, version catalog)"
                                    } else {
                                        "- `$argText` ($callee, version catalog)"
                                    }
                                }
                                else -> "- `${argText.removeSurrounding("\"").removeSurrounding("'")}` ($callee)"
                            }
                            entries.add(formatted)
                        }
                    }
                    super.visitCallExpression(expression)
                }
            })
        } else {
            val configs = "implementation|api|testImplementation|runtimeOnly|compileOnly|testRuntimeOnly|androidTestImplementation"
            Regex("""($configs)\s*\(\s*"([^"]+)"\s*\)""").findAll(effectiveContent).forEach {
                entries.add("- `${it.groupValues[2]}` (${it.groupValues[1]})")
            }
            Regex("""($configs)\s*\(\s*(libs\.[A-Za-z0-9_.]+)\s*\)""").findAll(effectiveContent).forEach {
                val alias = it.groupValues[2]
                val mapped = catalogMap[alias] ?: catalogMap[alias.replace("-", ".")]
                if (mapped != null) {
                    entries.add("- `$alias` → `$mapped` (${it.groupValues[1]}, version catalog)")
                } else {
                    entries.add("- `$alias` (${it.groupValues[1]}, version catalog)")
                }
            }
            Regex("""($configs)\s*\(\s*project\(\s*":?([^"]+)"\s*\)\s*\)""").findAll(effectiveContent).forEach {
                entries.add("- `project(\":${it.groupValues[2]}\")` (${it.groupValues[1]}, module)")
            }
        }

        val output = if (entries.isNotEmpty()) {
            "# Declared Dependencies (${entries.size})\n" + entries.distinct().joinToString("\n")
        } else {
            "# Declared Dependencies\nNo explicit dependencies found in analyzed snippet."
        }

        val metadataMap = mutableMapOf("dependencyCount" to entries.size.toString())
        if (catalogMap.isNotEmpty()) {
            metadataMap["catalogEntriesCount"] = catalogMap.size.toString()
        }

        return KotlinMcpResult.Success(
            content = output,
            metadata = metadataMap
        )
    }

    override fun checkVulnerabilities(
        buildScriptContent: String,
        projectPath: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        maxRetries: Int
    ): KotlinMcpResult {
        val scriptToAnalyze = if (projectPath != null) {
            val file = java.io.File(projectPath, "build.gradle.kts")
            if (file.exists()) file.readText() + "\n" + buildScriptContent else buildScriptContent
        } else {
            buildScriptContent
        }

        val libsToml = if (projectPath != null) {
            val f = java.io.File(projectPath, "gradle/libs.versions.toml")
            if (f.exists()) f.readText() else ""
        } else {
            ""
        }

        val lockfileContent = if (projectPath != null) {
            val root = java.io.File(projectPath)
            val lockfiles = root.listFiles { _, name -> name.endsWith(".lockfile") }?.toList().orEmpty() +
                java.io.File(root, "gradle/gradle.lockfile").takeIf { it.exists() }?.let { listOf(it) }.orEmpty()
            lockfiles.distinct().joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        } else ""

        val scriptWithLockfile = if (lockfileContent.isNotBlank()) "$scriptToAnalyze\n$lockfileContent" else scriptToAnalyze

        val parsedDeps = extractDependencyCoordinates(scriptWithLockfile, libsToml)

        // No coordinates parseable → this is not a fake clean report. Tell the
        // caller the tool cannot scan, so they don't mistake absence-of-data for
        // absence-of-vulnerabilities.
        if (parsedDeps.isEmpty()) {
            return KotlinMcpResult.Error(
                message = "No dependency coordinates could be parsed from the provided build script. The vulnerability scan requires resolvable Maven coordinates (e.g. implementation(\"g:a:v\") or a version catalog). If this is a real project, pass projectPath so gradle/libs.versions.toml and transitive resolution can be consulted.",
                code = "TOOL_UNAVAILABLE",
                details = mapOf("parsedCoordinateCount" to "0")
            )
        }

        // 1) Primary path: query the OSV.dev vulnerability database for each
        //    coordinate. Short timeout; failures fall back to the offline baseline.
        var findings = queryOsvDatabase(parsedDeps, connectTimeoutMs, readTimeoutMs, maxRetries)
        var source = "osv.dev"
        if (findings == null) {
            // 2) Offline fallback: a small, clearly-labelled embedded baseline so
            //    the tool still surfaces known-critical CVEs without network.
            findings = parsedDeps.mapNotNull { dep ->
                OfflineVulnerabilityBaseline.check(dep.group, dep.name, dep.version)?.let { advisory ->
                    VulnerabilityFinding(dep, advisory)
                }
            }
            source = "local-baseline (offline fallback)"
        }

        val cleanDeps = parsedDeps.filter { dep -> findings.none { it.dependency == dep } }

        val output = buildString {
            appendLine("# Dependency Vulnerability Audit Report")
            appendLine("Scanned ${parsedDeps.size} dependency coordinate(s). (source: $source)")
            appendLine()

            if (findings.isNotEmpty()) {
                appendLine("## 🚨 Flagged Security Advisories (${findings.size})")
                findings.forEach { f ->
                    appendLine("- **`${f.dependency.coordinate}`**")
                    appendLine("  - **Advisory ID**: ${f.advisory.id}")
                    appendLine("  - **Severity**: ${f.advisory.severity}")
                    appendLine("  - **Summary**: ${f.advisory.summary}")
                    appendLine("  - **Fixed Version**: ${f.advisory.fixedVersion}")
                    appendLine()
                }
            } else {
                appendLine("## ✅ No Known Vulnerabilities Detected")
                appendLine("All ${parsedDeps.size} analyzed dependencies match current secure version baselines.")
                appendLine()
            }

            if (cleanDeps.isNotEmpty()) {
                appendLine("## Scanned Clean Dependencies (${cleanDeps.size})")
                cleanDeps.forEach { appendLine(" - `${it.coordinate}`") }
            }
        }

        return KotlinMcpResult.Success(
            content = output,
            metadata = mapOf(
                "scannedCoordinateCount" to parsedDeps.size.toString(),
                "advisoryCount" to findings.size.toString(),
                "source" to source
            )
        )
    }

    private data class DependencyCoordinate(val group: String, val name: String, val version: String) {
        val coordinate: String get() = "$group:$name:$version"
    }

    private data class VulnerabilityAdvisory(
        val id: String,
        val severity: String,
        val summary: String,
        val fixedVersion: String
    )

    private data class VulnerabilityFinding(
        val dependency: DependencyCoordinate,
        val advisory: VulnerabilityAdvisory
    )

    /**
     * Query the OSV.dev vulnerability database for all Maven coordinates in one
     * batch. Retries up to maxRetries with exponential backoff on transient network
     * errors or timeouts. Returns null when retries are exhausted.
     */
    private fun queryOsvDatabase(
        deps: List<DependencyCoordinate>,
        connectTimeoutMs: Int = 4000,
        readTimeoutMs: Int = 6000,
        maxRetries: Int = 3
    ): List<VulnerabilityFinding>? {
        val queries = deps.map { dep ->
            buildString {
                append("{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"${dep.group}:${dep.name}\"},\"version\":\"${dep.version}\"}")
            }
        }.joinToString(",")
        val body = "{\"queries\":[$queries]}"

        val effectiveRetries = maxRetries.coerceAtLeast(1)
        var attempt = 0
        var backoffMs = 100L

        while (attempt < effectiveRetries) {
            val res = try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val url = java.net.URI("https://api.osv.dev/v1/querybatch").toURL()
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.connectTimeout = connectTimeoutMs
                        conn.readTimeout = readTimeoutMs
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        val status = conn.responseCode
                        if (status !in 200..299) {
                            conn.inputStream?.close()
                            return@withContext null
                        }
                        val response = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        conn.disconnect()

                        parseOsvBatchResponse(response, deps)
                    }
                }
            } catch (e: Throwable) {
                null
            }

            if (res != null) return res

            attempt++
            if (attempt < effectiveRetries) {
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                backoffMs = (backoffMs * 2).coerceAtMost(2000L)
            }
        }
        return null
    }

    /**
     * Minimal OSV querybatch JSON parser. For each query result we extract the
     * first advisory's ID, severity, summary, and a fixed version. Uses the
     * kotlinx.serialization JsonElement tree so no fragile regex is needed.
     */
    private fun parseOsvBatchResponse(response: String, deps: List<DependencyCoordinate>): List<VulnerabilityFinding> {
        val findings = mutableListOf<VulnerabilityFinding>()
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(response) as kotlinx.serialization.json.JsonObject
        } catch (e: Exception) {
            return emptyList()
        }
        val results = root["results"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        results.forEachIndexed { i, resultEl ->
            val dep = deps.getOrNull(i) ?: return@forEachIndexed
            val resultObj = resultEl as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
            val vulns = resultObj["vulns"] as? kotlinx.serialization.json.JsonArray ?: return@forEachIndexed
            val first = vulns.firstOrNull() as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
            val id = (first["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEachIndexed
            val summary = (first["summary"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: id
            val fixed = (first["affected"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { el -> (el as? kotlinx.serialization.json.JsonObject) }
                ?.flatMap { obj ->
                    val ranges = obj["ranges"] as? kotlinx.serialization.json.JsonArray ?: emptyList<kotlinx.serialization.json.JsonElement>()
                    ranges.mapNotNull { r ->
                        (r as? kotlinx.serialization.json.JsonObject)?.get("events") as? kotlinx.serialization.json.JsonArray
                    }.flatMap { events ->
                        events.mapNotNull { e ->
                            ((e as? kotlinx.serialization.json.JsonObject)?.get("fixed") as? kotlinx.serialization.json.JsonPrimitive)?.content
                        }
                    }
                }?.firstOrNull()
                ?: "unknown"
            val severity = (first["severity"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { s -> (s as? kotlinx.serialization.json.JsonObject)?.get("score") as? kotlinx.serialization.json.JsonPrimitive }
                ?.firstOrNull()?.content?.let { score ->
                    when {
                        score.toDoubleOrNull()?.let { it >= 9.0 } == true -> "CRITICAL"
                        score.toDoubleOrNull()?.let { it >= 7.0 } == true -> "HIGH"
                        score.toDoubleOrNull()?.let { it >= 4.0 } == true -> "MEDIUM"
                        else -> "LOW"
                    }
                } ?: "UNKNOWN"
            findings.add(
                VulnerabilityFinding(
                    dep,
                    VulnerabilityAdvisory(id = id, severity = severity, summary = summary, fixedVersion = fixed)
                )
            )
        }
        return findings.distinctBy { it.dependency.coordinate to it.advisory.id }
    }

    /**
     * Small offline baseline of known-critical CVEs. This is deliberately a
     * fallback only (used when OSV.dev is unreachable); it is NOT a complete
     * database and the report labels its source accordingly.
     */
    private object OfflineVulnerabilityBaseline {
        private data class Rule(val group: String, val name: String, val vulnerableBelow: String, val advisory: VulnerabilityAdvisory)

        private val rules = listOf(
            Rule("org.apache.commons", "commons-compress", "1.26.0", VulnerabilityAdvisory("CVE-2024-26308", "HIGH", "Out-of-memory denial of service vulnerability in archive decompression.", "1.26.0")),
            Rule("com.fasterxml.jackson.core", "jackson-databind", "2.15.0", VulnerabilityAdvisory("CVE-2023-35116", "HIGH", "Denial of service through deep nesting during JSON parsing.", "2.15.0")),
            Rule("org.apache.logging.log4j", "log4j-core", "2.17.1", VulnerabilityAdvisory("CVE-2021-44228", "CRITICAL", "Log4Shell remote code execution vulnerability via JNDI lookup.", "2.17.1")),
            Rule("io.ktor", "ktor-server-core", "2.3.12", VulnerabilityAdvisory("CVE-2024-34080", "MEDIUM", "Path traversal / header injection risk in Ktor HTTP server routes.", "2.3.12")),
            Rule("io.netty", "netty-all", "4.1.108.Final", VulnerabilityAdvisory("CVE-2024-29025", "MEDIUM", "HTTP request smuggling vulnerability in Netty codec.", "4.1.108.Final")),
            Rule("io.netty", "netty-codec-http", "4.1.108.Final", VulnerabilityAdvisory("CVE-2024-29025", "MEDIUM", "HTTP request smuggling vulnerability in Netty codec.", "4.1.108.Final")),
            Rule("org.springframework.boot", "spring-boot", "3.2.4", VulnerabilityAdvisory("CVE-2024-22259", "HIGH", "URL parsing vulnerability in Spring Framework.", "3.2.4")),
            Rule("com.squareup.okhttp3", "okhttp", "4.12.0", VulnerabilityAdvisory("CVE-2023-3635", "MEDIUM", "GzipSource Denial of Service vulnerability in OkHttp.", "4.12.0")),
            Rule("org.yaml", "snakeyaml", "2.2", VulnerabilityAdvisory("CVE-2022-1471", "CRITICAL", "Unsafe deserialization remote code execution vulnerability.", "2.2"))
        )

        fun check(group: String, name: String, version: String): VulnerabilityAdvisory? {
            return rules.firstOrNull { it.group == group && it.name == name && mavenVersionCompare(version, it.vulnerableBelow) < 0 }?.advisory
        }
    }

    /**
     * Maven-aware version comparison. Preserves qualifiers (Final, Release, SP1,
     * RC, etc.) instead of dropping non-numeric tokens: `4.1.108.Final` compares
     * equal to `4.1.108`, and `1.26.0` < `1.26.1`.
     */
    private fun isVersionLessThan(current: String, target: String): Boolean {
        return mavenVersionCompare(current, target) < 0
    }

    /** Parse dependency coordinates from a Gradle build script, supporting:
     * - `implementation("g:a:v")` and `implementation 'g:a:v'`
     * - named-arg notation `implementation(group = "g", name = "a", version = "v")`
     * - version-catalog references `implementation(libs.foo.bar)` resolved
     *   against the supplied libs.versions.toml
     * - BOM platform imports `implementation(platform("g:a:v"))`
     * - plugins block `id("g") version "v"` / `kotlin("jvm") version "v"`
     */
    private fun extractDependencyCoordinates(content: String, libsToml: String = ""): List<DependencyCoordinate> {
        val result = mutableListOf<DependencyCoordinate>()
        val configs = "(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testApi|testRuntimeOnly|annotationProcessor|kapt|ksp|kover|detekt|detektTooling|ktlintTooling)"

        // 1. `implementation("g:a:v")`
        Regex("""$configs\s*\(\s*["']([^"']+):([^"']+):([^"']+)["']\s*\)""").findAll(content).forEach { m ->
            result.add(DependencyCoordinate(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
        }

        // 1b. Gradle lockfile entries: `group:name:version=classpath`
        Regex("""^([a-zA-Z0-9._-]+):([a-zA-Z0-9._-]+):([a-zA-Z0-9._-]+(?:-[a-zA-Z0-9._-]+)?)=(.*)""", RegexOption.MULTILINE)
            .findAll(content).forEach { m ->
                result.add(DependencyCoordinate(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
            }

        // 2. Named-arg notation: implementation(group = "g", name = "a", version = "v")
        Regex("""$configs\s*\(\s*group\s*=\s*["']([^"']+)["']\s*,\s*name\s*=\s*["']([^"']+)["']\s*,\s*version\s*=\s*["']([^"']+)["']""")
            .findAll(content).forEach { m ->
                result.add(DependencyCoordinate(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
            }

        // 3. Version-catalog references, resolved against gradle/libs.versions.toml.
        Regex("""$configs\s*\(\s*libs\.([a-zA-Z0-9_.]+)\s*\)""").findAll(content).forEach { m ->
            resolveCatalogCoordinate(m.groupValues[1], libsToml)?.let { result.add(it) }
        }

        // 4. BOM/platform imports.
        Regex("""$configs\s*\(\s*platform\s*\(\s*["']([^"']+):([^"']+):([^"']+)["']\s*\)\s*\)""").findAll(content).forEach { m ->
            result.add(DependencyCoordinate(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
        }

        // 5. plugins block: id("g:a") version "v" or kotlin("jvm") version "v".
        Regex("""\bid\s*\(\s*["']([^"']+)["']\s*\)\s*version\s+["']([^"']+)["']""").findAll(content).forEach { m ->
            val id = m.groupValues[1]
            // `id("g:a")` → group:name; if only a name is given, prefix the standard plugin group.
            val g = if (":" in id) id.substringBefore(":") else "plugin"
            val n = if (":" in id) id.substringAfter(":") else id
            result.add(DependencyCoordinate(g, n, m.groupValues[2]))
        }
        Regex("""\bkotlin\s*\(\s*["']([^"']+)["']\s*\)\s*version\s+["']([^"']+)["']""").findAll(content).forEach { m ->
            result.add(DependencyCoordinate("org.jetbrains.kotlin", "kotlin-gradle-plugin-${m.groupValues[1]}", m.groupValues[2]))
        }

        return result.distinct().toList()
    }

    /** Resolve a `libs.xxx.yyy` catalog reference against a version catalog TOML. */
    private fun resolveCatalogCoordinate(reference: String, libsToml: String): DependencyCoordinate? {
        if (libsToml.isBlank()) return null
        // `libs.kotlinx.coroutines.core` → library key `kotlinx-coroutines-core`
        // (all segments dashed); the library entry carries a `module` and a
        // `version.ref` pointing into [versions].
        val libKey = reference.split(".").joinToString("-")
        // The library line is the one that defines a module mapping — skip the
        // identically-named version line under [versions].
        val libLine = libsToml.lineSequence()
            .firstOrNull { it.trimStart().startsWith("$libKey =") && it.contains("module") }
            ?: return null
        val module = Regex("""module\s*=\s*["']([^"']+:[^"']+)["']""").find(libLine)?.groupValues?.get(1)
            ?: return null
        val versionRef = Regex("""version\.ref\s*=\s*["']([^"']+)["']""").find(libLine)?.groupValues?.get(1)
            ?: return null
        val version = libsToml.lineSequence()
            .firstOrNull { it.trimStart().startsWith("$versionRef =") }
            ?.substringAfter("=")?.trim()?.trim('"', '\'')
            ?: return null
        return DependencyCoordinate(module.substringBefore(":"), module.substringAfter(":"), version)
    }

    /**
     * Package API dumper (C0SCWQZV): scans Kotlin sources under [projectPath],
     * filters to [packageName] (null = all), and emits a structured public API
     * surface — classes/interfaces/functions/properties with visibility and
     * signatures. Semantic mode resolves inferred return types via BindingContext.
     */
    override fun packageApi(projectPath: String?, packageName: String?): KotlinMcpResult {
        if (projectPath.isNullOrBlank()) {
            return KotlinMcpResult.Error(
                message = "projectPath is required for package_api.",
                code = "INVALID_ARGUMENTS"
            )
        }
        val root = File(projectPath)
        if (!root.isDirectory) {
            return KotlinMcpResult.Error(
                message = "projectPath must be a readable directory for package_api.",
                code = "INVALID_ARGUMENTS"
            )
        }
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val (elements, _) = indexer.publicApiOf(files, root.invariantSeparatorsPath, packageName)
        if (elements.isEmpty()) {
            return KotlinMcpResult.Error(
                message = "No public declarations found for package '${packageName ?: "(any)"}'.",
                code = "NOT_FOUND"
            )
        }

        val content = buildString {
            appendLine("# Public API Surface — ${packageName ?: "all packages"} (${elements.size} declarations)")
            appendLine()
            elements.groupBy { it.file }.forEach { (file, list) ->
                appendLine("## `$file`")
                list.forEach { el ->
                    val doc = el.docSummary?.let { " — $it" }.orEmpty()
                    appendLine("- `${el.visibility} ${el.signature}`$doc")
                }
                appendLine()
            }
            appendLine("> Mode: semantic (inferred return types resolved)")
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf(
                "packageName" to (packageName ?: ""),
                "declarationCount" to elements.size.toString(),
                "fileCount" to elements.map { it.file }.distinct().size.toString()
            )
        )
    }

    override fun detectProfile(buildScriptContent: String, projectPath: String?): com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile {
        val allContent = if (projectPath != null) {
            val file = java.io.File(projectPath, "build.gradle.kts")
            if (file.exists()) file.readText() + "\n" + buildScriptContent else buildScriptContent
        } else {
            buildScriptContent
        }

        val active = mutableSetOf<com.gokorei.kotlinmcp.models.FrameworkFeature>()
        val text = allContent.lowercase()

        if (text.contains("ktor") || text.contains("io.ktor")) active.add(com.gokorei.kotlinmcp.models.FrameworkFeature.KTOR)
        if (text.contains("spring") || text.contains("org.springframework")) active.add(com.gokorei.kotlinmcp.models.FrameworkFeature.SPRING)
        if (text.contains("compose") || text.contains("androidx.compose")) active.add(com.gokorei.kotlinmcp.models.FrameworkFeature.COMPOSE)
        if (text.contains("arrow-core") || text.contains("io.arrow-kt")) active.add(com.gokorei.kotlinmcp.models.FrameworkFeature.ARROW)
        if (text.contains("serialization") || text.contains("kotlinx-serialization")) active.add(com.gokorei.kotlinmcp.models.FrameworkFeature.SERIALIZATION)
        if (text.contains("mockk") || text.contains("io.mockk")) active.add(com.gokorei.kotlinmcp.models.FrameworkFeature.MOCKK)
        if (text.contains("coroutines") || text.contains("kotlinx-coroutines")) active.add(com.gokorei.kotlinmcp.models.FrameworkFeature.COROUTINES)
        if (text.contains("turbine") || text.contains("app.cash.turbine")) active.add(com.gokorei.kotlinmcp.models.FrameworkFeature.TURBINE)
        if (text.contains("datetime") || text.contains("kotlinx-datetime")) active.add(com.gokorei.kotlinmcp.models.FrameworkFeature.DATETIME)
        if (text.contains("exposed") || text.contains("org.jetbrains.exposed")) active.add(com.gokorei.kotlinmcp.models.FrameworkFeature.EXPOSED)
        if (text.contains("room") || text.contains("androidx.room")) active.add(com.gokorei.kotlinmcp.models.FrameworkFeature.ROOM)

        val isKmp = text.contains("multiplatform") || detectTargets(allContent).size > 1

        return com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile(activeFrameworks = active, isKmp = isKmp)
    }

    override fun coverageReport(projectPath: String?): KotlinMcpResult {
        val root = if (projectPath != null) File(projectPath) else File(".")
        val jacocoDir = File(root, "build/reports/jacoco/test")
        if (!jacocoDir.exists() || !jacocoDir.isDirectory) {
            return KotlinMcpResult.Error(
                message = "No JaCoCo coverage directory found at ${jacocoDir.path}. Run `./gradlew jacocoTestReport` first.",
                code = "NOT_FOUND"
            )
        }

        val xmlReport = File(jacocoDir, "jacocoTestReport.xml")
        val content = if (xmlReport.exists()) {
            val text = xmlReport.readText()
            val lineCov = Regex("""<counter type="LINE"\s+missed="(\d+)"\s+covered="(\d+)"/>""").find(text)
            val branchCov = Regex("""<counter type="BRANCH"\s+missed="(\d+)"\s+covered="(\d+)"/>""").find(text)

            buildString {
                appendLine("# JaCoCo Code Coverage Report")
                if (lineCov != null) {
                    val missed = lineCov.groupValues[1].toInt()
                    val covered = lineCov.groupValues[2].toInt()
                    val total = missed + covered
                    val pct = if (total > 0) (covered * 100) / total else 0
                    appendLine("- Line Coverage: $pct% ($covered / $total lines)")
                }
                if (branchCov != null) {
                    val missed = branchCov.groupValues[1].toInt()
                    val covered = branchCov.groupValues[2].toInt()
                    val total = missed + covered
                    val pct = if (total > 0) (covered * 100) / total else 0
                    appendLine("- Branch Coverage: $pct% ($covered / $total branches)")
                }
            }
        } else {
            "# JaCoCo Code Coverage Report\nHTML report directory exists at `${jacocoDir.path}`."
        }

        return KotlinMcpResult.Success(content = content)
    }
}

/** Maven-aware version comparison shared by the vulnerability baseline and helpers. */
private fun mavenVersionCompare(a: String, b: String): Int {
    fun tokens(v: String): List<Any> {
        val out = mutableListOf<Any>()
        val sb = StringBuilder()
        var lastWasDigit: Boolean? = null
        for (c in v) {
            val isDigit = c.isDigit()
            when {
                c == '.' || c == '-' || c == '_' -> {
                    if (sb.isNotEmpty()) { out.add(if (sb[0].isDigit()) sb.toString().toIntOrNull() ?: sb.toString() else sb.toString()); sb.setLength(0) }
                    lastWasDigit = null
                }
                lastWasDigit != null && isDigit != lastWasDigit -> {
                    out.add(if (sb[0].isDigit()) sb.toString().toIntOrNull() ?: sb.toString() else sb.toString()); sb.setLength(0)
                    sb.append(c); lastWasDigit = isDigit
                }
                else -> { sb.append(c); lastWasDigit = isDigit }
            }
        }
        if (sb.isNotEmpty()) out.add(if (sb[0].isDigit()) sb.toString().toIntOrNull() ?: sb.toString() else sb.toString())
        return out
    }

    val at = tokens(a)
    val bt = tokens(b)
    // Qualifier ordering: release/final > rc > beta > alpha > snapshot.
    fun qualifierRank(t: Any): Int = when (t.toString().lowercase()) {
        "final", "release", "ga", "sp" -> 4
        "rc", "m" -> 3
        "beta", "b" -> 2
        "alpha", "a" -> 1
        "snapshot" -> 0
        else -> 2
    }

    val n = maxOf(at.size, bt.size)
    for (i in 0 until n) {
        val x = at.getOrNull(i) ?: 0
        val y = bt.getOrNull(i) ?: 0
        val cmp = when {
            x is Int && y is Int -> x.compareTo(y)
            x is Int -> 1
            y is Int -> -1
            else -> qualifierRank(x).compareTo(qualifierRank(y))
        }
        if (cmp != 0) return cmp
    }
    return 0
}



