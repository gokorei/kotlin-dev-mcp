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
     * @param scriptPath Optional file path to identify build script dialect (.gradle.kts vs .gradle)
     * @return [KotlinMcpResult] containing audit findings and issue counts
     */
    fun auditAndroidConfig(buildScriptContent: String, scriptPath: String? = null): KotlinMcpResult {
        val issues = mutableListOf<String>()
        val isExplicitKts = scriptPath?.endsWith(".gradle.kts") == true ||
            (scriptPath == null && (buildScriptContent.contains("plugins {") || buildScriptContent.contains("val ") || buildScriptContent.contains("var ")))

        val psi = com.gokorei.kotlinmcp.lsp.K2SnippetFrontend.parsePsi(buildScriptContent)

        var isAndroidProject = false
        var hasCompileSdk = false
        var hasMinSdk = false
        var hasDeprecatedComposeCompiler = false

        var hasParserErrors = false
        if (psi != null) {
            psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                override fun visitElement(element: org.jetbrains.kotlin.com.intellij.psi.PsiElement) {
                    if (element is org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement) {
                        hasParserErrors = true
                    }
                    if (!hasParserErrors) super.visitElement(element)
                }
            })
        }

        if (isExplicitKts && (psi == null || hasParserErrors)) {
            return KotlinMcpResult.Error(
                code = "KOTLIN_SCRIPT_PARSE_ERROR",
                message = "Failed to parse Kotlin DSL build script with K2 PSI."
            )
        }

        if (psi != null && !hasParserErrors) {
            val scopeStack = mutableListOf<String>()
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
                        "compileSdk" -> {
                            if ("android" in scopeStack) hasCompileSdk = true
                        }
                        "minSdk" -> {
                            if ("android" in scopeStack || "defaultConfig" in scopeStack) hasMinSdk = true
                        }
                    }

                    if (callee in setOf("android", "defaultConfig", "composeOptions")) {
                        scopeStack.add(callee)
                        super.visitCallExpression(expression)
                        scopeStack.removeAt(scopeStack.lastIndex)
                    } else {
                        super.visitCallExpression(expression)
                    }
                }

                override fun visitBinaryExpression(expression: org.jetbrains.kotlin.psi.KtBinaryExpression) {
                    if (expression.operationToken == org.jetbrains.kotlin.lexer.KtTokens.EQ) {
                        val leftText = expression.left?.text.orEmpty()
                        if (leftText == "compileSdk" && "android" in scopeStack) hasCompileSdk = true
                        if (leftText.endsWith(".compileSdk")) hasCompileSdk = true

                        if (leftText == "minSdk" && ("android" in scopeStack || "defaultConfig" in scopeStack)) hasMinSdk = true
                        if (leftText.endsWith(".minSdk")) hasMinSdk = true

                        if (leftText == "kotlinCompilerExtensionVersion" && ("composeOptions" in scopeStack || "android" in scopeStack)) {
                            hasDeprecatedComposeCompiler = true
                        }
                        if (leftText.endsWith(".kotlinCompilerExtensionVersion")) {
                            hasDeprecatedComposeCompiler = true
                        }
                    }
                    super.visitBinaryExpression(expression)
                }
            })
        } else {
            // Groovy fallback with stripped comments and string literals
            val noComments = buildScriptContent
                .replace(Regex("""//.*"""), "")
                .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            val noStrings = noComments
                .replace(Regex(""""(?:[^"\\]|\\.)*""""), "")
                .replace(Regex("""'(?:[^'\\]|\\.)*'"""), "")
            val text = noComments.lowercase()
            val textNoStrings = noStrings.lowercase()

            if (text.contains("com.android.") || textNoStrings.contains("android {") || text.contains("androidtarget")) {
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
