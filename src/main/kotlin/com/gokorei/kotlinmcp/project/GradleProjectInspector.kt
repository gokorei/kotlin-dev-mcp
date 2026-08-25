package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import java.io.File

/**
 * Strategy component for inspecting Gradle build configuration, target platforms, plugins, and dependencies.
 */
class GradleProjectInspector {

    fun inspectGradleProject(buildScriptContent: String, projectPath: String?): KotlinMcpResult {
        val rootDir = projectPath?.let { File(it) }
        val rootBuildScript = rootDir?.let { dir ->
            File(dir, "build.gradle.kts").takeIf { it.exists() }
                ?: File(dir, "build.gradle").takeIf { it.exists() }
        }
        val effectiveContent = if (buildScriptContent.isBlank() && rootBuildScript != null) {
            rootBuildScript.readText()
        } else buildScriptContent

        val settingsFile = rootDir?.let { dir ->
            File(dir, "settings.gradle.kts").takeIf { it.exists() }
                ?: File(dir, "settings.gradle").takeIf { it.exists() }
        }
        val settingsSubprojects = settingsFile?.let { extractSubprojects(it.readText()) }.orEmpty()

        val allScripts = mutableListOf<Pair<String, String>>()
        if (effectiveContent.isNotBlank()) {
            allScripts.add("root" to effectiveContent)
        }
        if (rootDir != null && settingsSubprojects.isNotEmpty()) {
            settingsSubprojects.forEach { sub ->
                val relPath = sub.removePrefix(":").replace(":", "/")
                val subDir = File(rootDir, relPath)
                val subScript = File(subDir, "build.gradle.kts").takeIf { it.exists() }
                    ?: File(subDir, "build.gradle").takeIf { it.exists() }
                if (subScript != null) {
                    allScripts.add(sub to subScript.readText())
                }
            }
        }

        val allPlugins = allScripts.flatMap { extractPlugins(it.second) }.distinct()
        val allTargets = allScripts.flatMap { extractKmpTargets(it.second) }.distinct()

        val output = buildString {
            appendLine("# Gradle Project Inspection")
            appendLine()
            appendLine("## Detected Plugins (${allPlugins.size})")
            if (allPlugins.isNotEmpty()) {
                allPlugins.forEach { appendLine(" - `$it`") }
            } else {
                appendLine(" - (none detected directly)")
            }
            appendLine()
            appendLine("## KMP Targets (${allTargets.size})")
            if (allTargets.isNotEmpty()) {
                allTargets.forEach { appendLine(" - `$it`") }
                appendLine()
                appendLine("## Recommended Guidelines")
                appendLine("- [Multiplatform Web Storage (Room 3.0 & DataStore)](kotlin://guidelines/kmp-storage.md)")
            } else {
                appendLine(" - JVM / Single-platform")
            }
            if (settingsSubprojects.isNotEmpty()) {
                appendLine()
                appendLine("## Settings Subprojects (${settingsSubprojects.size})")
                settingsSubprojects.forEach { appendLine(" - `$it`") }
            }
        }

        return KotlinMcpResult.Success(
            content = output,
            metadata = mapOf(
                "pluginCount" to allPlugins.size.toString(),
                "targetCount" to allTargets.size.toString(),
                "subprojectCount" to settingsSubprojects.size.toString()
            )
        )
    }

    fun listKmpTargets(buildScriptContent: String): KotlinMcpResult {
        val targets = extractKmpTargets(buildScriptContent)
        val content = buildString {
            appendLine("# KMP Targets (${targets.size})")
            if (targets.isNotEmpty()) {
                targets.forEach { appendLine("- `$it`") }
                appendLine()
                appendLine("## Recommended Guidelines")
                appendLine("- [Multiplatform Web Storage (Room 3.0 & DataStore)](kotlin://guidelines/kmp-storage.md)")
            } else {
                appendLine("- Single-platform (JVM / Android)")
            }
        }
        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("targetCount" to targets.size.toString())
        )
    }

    fun analyzeDependencies(buildScriptContent: String): KotlinMcpResult {
        val deps = mutableListOf<String>()

        // Kotlin DSL: configuration("group:artifact:version")
        Regex("""(implementation|api|testImplementation|compileOnly)\s*\(\s*["']([^"']+)["']\s*\)""")
            .findAll(buildScriptContent)
            .forEach { deps.add("${it.groupValues[1]}: ${it.groupValues[2]}") }

        // Groovy DSL: configuration 'group:artifact:version' or "group:artifact:version"
        Regex("""(implementation|api|testImplementation|compileOnly)\s+["']([^"']+)["']""")
            .findAll(buildScriptContent)
            .forEach { deps.add("${it.groupValues[1]}: ${it.groupValues[2]}") }

        // Version catalog / project references: configuration(libs.foo.bar) or configuration(project(":core"))
        Regex("""(implementation|api|testImplementation|compileOnly)\s*\(\s*([a-zA-Z0-9._]+|project\([^)]+\))\s*\)""")
            .findAll(buildScriptContent)
            .forEach {
                val ref = it.groupValues[2]
                if (!ref.startsWith("\"") && !ref.startsWith("'")) {
                    deps.add("${it.groupValues[1]}: $ref")
                }
            }

        val content = buildString {
            appendLine("# Project Dependencies (${deps.distinct().size})")
            if (deps.isNotEmpty()) {
                deps.distinct().forEach { appendLine("- `$it`") }
            } else {
                appendLine("- (no dependencies extracted from script snippet)")
            }
        }
        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("dependencyCount" to deps.distinct().size.toString())
        )
    }

    fun diagnoseBuild(buildScriptContent: String, settingsContent: String, gradlePropertiesContent: String): KotlinMcpResult {
        val issues = mutableListOf<String>()
        if (buildScriptContent.contains("jcenter()")) {
            issues.add("JCenter repository is sunset; migrate to mavenCentral().")
        }
        val content = buildString {
            appendLine("# Gradle Build Diagnostic")
            if (issues.isNotEmpty()) {
                issues.forEach { appendLine("- ⚠️ $it") }
            } else {
                appendLine("✅ No obvious Gradle script issues detected.")
            }
        }
        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("issueCount" to issues.size.toString())
        )
    }

    /**
     * Statically audits an Android Gradle build script (Kotlin DSL or Groovy) for Kotlin 2.x and AGP alignment,
     * including deprecation of `kotlinCompilerExtensionVersion` and missing SDK declarations.
     *
     * @param buildScriptContent The raw content of build.gradle.kts or build.gradle
     * @return [KotlinMcpResult] containing audit findings and issue counts
     */
    fun auditAndroidConfig(buildScriptContent: String): KotlinMcpResult {
        val issues = mutableListOf<String>()
        val psi = com.gokorei.kotlinmcp.lsp.K2SnippetFrontend.parsePsi(buildScriptContent)

        var isAndroidProject = false
        var hasCompileSdk = false
        var hasMinSdk = false
        var hasDeprecatedComposeCompiler = false

        if (psi != null) {
            psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: org.jetbrains.kotlin.psi.KtCallExpression) {
                    val callee = expression.calleeExpression?.text.orEmpty()
                    val args = expression.valueArguments.mapNotNull { it.getArgumentExpression()?.text?.trim('"', '\'') }

                    when (callee) {
                        "android", "androidTarget" -> isAndroidProject = true
                        "id", "plugin" -> {
                            if (args.any { it.startsWith("com.android.") || it == "android" }) isAndroidProject = true
                        }
                        "kotlin" -> {
                            if (args.any { it == "android" }) isAndroidProject = true
                        }
                        "compileSdk" -> hasCompileSdk = true
                        "minSdk" -> hasMinSdk = true
                    }
                    super.visitCallExpression(expression)
                }

                override fun visitBinaryExpression(expression: org.jetbrains.kotlin.psi.KtBinaryExpression) {
                    val leftText = expression.left?.text.orEmpty()
                    if (leftText == "compileSdk" || leftText.endsWith(".compileSdk")) hasCompileSdk = true
                    if (leftText == "minSdk" || leftText.endsWith(".minSdk")) hasMinSdk = true
                    if (leftText == "kotlinCompilerExtensionVersion" || leftText.endsWith(".kotlinCompilerExtensionVersion")) {
                        hasDeprecatedComposeCompiler = true
                    }
                    super.visitBinaryExpression(expression)
                }

                override fun visitSimpleNameExpression(expression: org.jetbrains.kotlin.psi.KtSimpleNameExpression) {
                    if (expression.getReferencedName() == "kotlinCompilerExtensionVersion") {
                        hasDeprecatedComposeCompiler = true
                    }
                    super.visitSimpleNameExpression(expression)
                }
            })
        } else {
            // Groovy fallback with stripped comments
            val noComments = buildScriptContent
                .replace(Regex("""//.*"""), "")
                .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            val text = noComments.lowercase()

            if (text.contains("com.android.") || text.contains("android {") || text.contains("androidtarget")) {
                isAndroidProject = true
            }
            if (text.contains("compilesdk")) hasCompileSdk = true
            if (text.contains("minsdk")) hasMinSdk = true
            if (text.contains("kotlincompilerextensionversion")) hasDeprecatedComposeCompiler = true
        }

        if (hasDeprecatedComposeCompiler) {
            issues.add("`composeOptions { kotlinCompilerExtensionVersion = ... }` is deprecated with Kotlin 2.0+. In Kotlin 2.x, Compose compiler is configured via the Compose compiler plugin (`kotlin(\"plugin.compose\")` / `id(\"org.jetbrains.kotlin.plugin.compose\")`). Remove `kotlinCompilerExtensionVersion`.")
        }

        if (isAndroidProject) {
            if (!hasCompileSdk) {
                issues.add("`android { ... }` block does not specify `compileSdk`. Explicitly declare `compileSdk = 35` (or target API level).")
            }
            if (!hasMinSdk) {
                issues.add("`minSdk` is not explicitly declared in `defaultConfig { ... }`. Specify `minSdk = 26` (or minimum supported API level).")
            }
        }

        val content = buildString {
            appendLine("# Android & AGP Build Configuration Audit")
            if (issues.isNotEmpty()) {
                issues.forEach { appendLine("- ⚠️ $it") }
            } else {
                appendLine("✅ Android build script configuration is modern and aligned with Kotlin 2.x / AGP guidelines.")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("issueCount" to issues.size.toString())
        )
    }

    private fun extractPlugins(content: String): List<String> {
        val plugins = mutableListOf<String>()
        // Kotlin DSL: id("...") / id('...')
        Regex("""id\s*\(\s*["']([^"']+)["']\s*\)""").findAll(content).forEach { plugins.add(it.groupValues[1]) }
        // Groovy DSL: id '...' / id "..."
        Regex("""id\s+["']([^"']+)["']""").findAll(content).forEach { plugins.add(it.groupValues[1]) }
        // Kotlin DSL helper: kotlin("...")
        Regex("""kotlin\s*\(\s*["']([^"']+)["']\s*\)""").findAll(content).forEach { plugins.add("kotlin-${it.groupValues[1]}") }
        // Groovy DSL apply plugin: '...'
        Regex("""apply\s+plugin:\s*["']([^"']+)["']""").findAll(content).forEach { plugins.add(it.groupValues[1]) }
        return plugins.distinct()
    }

    private fun extractKmpTargets(content: String): List<String> {
        val targets = mutableListOf<String>()
        val knownTargets = listOf("jvm", "androidTarget", "iosX64", "iosArm64", "iosSimulatorArm64", "js", "wasmJs", "linuxX64", "macosX64", "macosArm64")
        knownTargets.forEach { t ->
            if (content.contains("$t(") || content.contains("$t ")) targets.add(t)
        }
        return targets.distinct()
    }

    private fun extractSubprojects(settingsContent: String): List<String> {
        val projects = mutableListOf<String>()
        Regex("""include\s*\(\s*["']([^"']+)["']\s*\)""").findAll(settingsContent).forEach { projects.add(it.groupValues[1]) }
        Regex("""include\s+["']([^"']+)["']""").findAll(settingsContent).forEach { projects.add(it.groupValues[1]) }
        return projects.distinct()
    }
}
