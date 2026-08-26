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
    DETECT_ENVIRONMENT_PROFILE,
    INSPECT_ANDROID_MANIFEST,
    AUDIT_ANDROID_CONFIG
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

    /** Statically inspects AndroidManifest.xml for exported tags and permissions compliance. */
    fun inspectAndroidManifest(contentOrPath: String, projectPath: String? = null): KotlinMcpResult =
        AndroidManifestInspector().inspectManifest(contentOrPath, projectPath)

    /** Statically audits Gradle build scripts for AGP and Kotlin 2.x Compose compiler alignment. */
    fun auditAndroidConfig(buildScriptContent: String, projectPath: String? = null): KotlinMcpResult =
        GradleProjectInspector().auditAndroidConfig(buildScriptContent, projectPath)
}

class DefaultProjectService(
    private val indexer: WorkspaceSemanticIndexer = WorkspaceSemanticIndexer(),
    private val schemaScanner: SchemaScanner = SchemaScanner(),
    private val vulnerabilityAuditor: VulnerabilityAuditor = VulnerabilityAuditor(),
    private val androidManifestInspector: AndroidManifestInspector = AndroidManifestInspector()
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
            ProjectAction.INSPECT_ANDROID_MANIFEST -> androidManifestInspector.inspectManifest(buildScriptContent, projectPath)
            ProjectAction.AUDIT_ANDROID_CONFIG -> {
                val scriptPath = if (projectPath != null) {
                    val file = File(projectPath)
                    if (file.isFile) {
                        file.absolutePath
                    } else {
                        val kts = File(file, "build.gradle.kts")
                        if (kts.exists()) kts.absolutePath else File(file, "build.gradle").takeIf { it.exists() }?.absolutePath
                    }
                } else null
                GradleProjectInspector().auditAndroidConfig(buildScriptContent, scriptPath)
            }
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
        val detectedTargets = detectTargets(content)
        val isKmpPlugin = plugins.any {
            it == "kotlin(multiplatform)" ||
            it == "id(org.jetbrains.kotlin.multiplatform)" ||
            it == "org.jetbrains.kotlin.multiplatform"
        }
        val isKmp = isKmpPlugin || detectedTargets.isNotEmpty()
        val kmpGuideline = if (isKmp) {
            "\n\n## Recommended Guidelines\n- [Multiplatform Web Storage (Room 3.0 & DataStore)](kotlin://guidelines/kmp-storage.md)"
        } else ""

        val output = """
            # Gradle Project Structure Analysis
            - Plugins: $pluginsBlock
            - Build Script Type: Kotlin DSL (`build.gradle.kts`)$subprojectsLine
            - Detected Source Sets: ${sourceSets.joinToString(", ") { "`$it`" }}

            ${layering}$kmpGuideline
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
            "jvm", "androidTarget", "iosX64", "iosArm64", "iosSimulatorArm64",
            "js", "wasmJs", "macosX64", "macosArm64", "linuxX64", "mingwX64"
        )
        return possibleTargets.filter { target ->
            content.contains("$target(") || content.contains("$target {") || content.lines().any { it.trim() == target }
        }
    }

    private fun listKmpTargets(content: String): KotlinMcpResult {
        val detectedTargets = detectTargets(content)
        val isKmpPlugin = content.contains("kotlin(\"multiplatform\")") ||
            content.contains("org.jetbrains.kotlin.multiplatform") ||
            content.contains("kotlin(\"kmp\")")
        val contentOut = buildString {
            if (!isKmpPlugin && detectedTargets.isEmpty()) {
                appendLine("Standard single-target JVM project configuration.")
            } else {
                appendLine("# Kotlin Multiplatform (KMP) Targets (${detectedTargets.size})")
                if (detectedTargets.isNotEmpty()) {
                    detectedTargets.forEach { appendLine(" - `$it`") }
                    appendLine()
                    appendLine("## Recommended Guidelines")
                    appendLine("- [Multiplatform Web Storage (Room 3.0 & DataStore)](kotlin://guidelines/kmp-storage.md)")
                } else {
                    appendLine(" - (No specific platform targets declared yet; configure targets under `kotlin { ... }`)")
                }
            }
        }
        return KotlinMcpResult.Success(
            content = contentOut,
            metadata = mapOf("targetCount" to detectedTargets.size.toString())
        )
    }

    /**
     * Parses gradle/libs.versions.toml into a map of alias -> coordinate string.
     * Supports `libs.ktor.server` as well as dashed keys.
     */
    private fun parseVersionCatalog(projectPath: String?): Map<String, String> {
        if (projectPath.isNullOrBlank()) return emptyMap()
        val toml = File(projectPath, "gradle/libs.versions.toml")
        if (!toml.exists()) return emptyMap()

        val versions = mutableMapOf<String, String>()
        val libraries = mutableMapOf<String, String>()
        var currentSection = ""

        toml.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.isBlank()) return@forEach

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.removeSurrounding("[", "]").trim().lowercase()
                return@forEach
            }

            if (currentSection == "versions" && "=" in trimmed) {
                val key = trimmed.substringBefore("=").trim()
                val value = trimmed.substringAfter("=").trim().trim('"').trim('\'')
                versions[key] = value
            } else if (currentSection == "libraries" && "=" in trimmed) {
                val rawAlias = trimmed.substringBefore("=").trim()
                val canonicalAlias = rawAlias.replace('-', '.')
                val rhs = trimmed.substringAfter("=").trim()

                if (rhs.startsWith("{") && rhs.endsWith("}")) {
                    val body = rhs.removeSurrounding("{", "}").trim()
                    val kvMap = mutableMapOf<String, String>()
                    val parts = body.split(",")
                    parts.forEach { part ->
                        if ("=" in part) {
                            val k = part.substringBefore("=").trim()
                            val v = part.substringAfter("=").trim().trim('"').trim('\'')
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
        // Groovy DSL fallback on caller-supplied content: configuration 'group:artifact:version' or "group:artifact:version"
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
        return vulnerabilityAuditor.checkVulnerabilities(buildScriptContent, projectPath)
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
    fun qualifierRank(t: Any?): Int = when (t.toString().lowercase()) {
        "final", "release", "ga", "sp" -> 4
        "rc", "m" -> 3
        "beta", "b" -> 2
        "alpha", "a" -> 1
        "snapshot" -> 0
        else -> 2
    }

    // A missing trailing token is treated as the numeric 0 (so `1.26.0` equals
    // `1.26`) and as a release-neutral qualifier (so `1.26.0.Final` equals
    // `1.26.0`, and pre-release qualifiers like `1.0-rc1` sort below `1.0`).
    val n = maxOf(at.size, bt.size)
    for (i in 0 until n) {
        val xo = at.getOrNull(i)
        val yo = bt.getOrNull(i)
        val cmp = when {
            xo == null && yo == null -> 0
            xo is Int && yo == null -> xo.compareTo(0)
            yo is Int && xo == null -> 0.compareTo(yo)
            xo is String && yo == null -> qualifierRank(xo).compareTo(4)
            yo is String && xo == null -> 4.compareTo(qualifierRank(yo))
            xo is Int && yo is Int -> xo.compareTo(yo)
            xo is Int -> 1
            yo is Int -> -1
            else -> qualifierRank(xo).compareTo(qualifierRank(yo))
        }
        if (cmp != 0) return cmp
    }
    return 0
}



