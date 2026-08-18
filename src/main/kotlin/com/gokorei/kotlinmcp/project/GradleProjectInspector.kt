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
