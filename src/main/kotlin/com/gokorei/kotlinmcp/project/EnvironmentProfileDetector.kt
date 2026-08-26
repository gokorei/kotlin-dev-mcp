package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.FrameworkFeature
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile
import java.io.File

/**
 * Strategy component for detecting active framework features and environment profiles.
 */
class EnvironmentProfileDetector {

    /**
     * Detects project environment profile and returns a structured markdown representation for LLMs.
     *
     * @param buildScriptContent The raw content of build.gradle.kts or build.gradle
     * @param projectPath Optional root path of the project workspace
     * @return [KotlinMcpResult] containing formatted profile summary
     */
    fun detectEnvironmentProfile(buildScriptContent: String, projectPath: String?): KotlinMcpResult {
        val profile = detectProfile(buildScriptContent, projectPath, GradleProjectInspector())
        val output = buildString {
            appendLine("# Project Environment Profile")
            appendLine("- Multiplatform (KMP): ${if (profile.isKmp) "Yes" else "No"}")
            appendLine("- Active Frameworks (${profile.activeFrameworks.size}): ${profile.activeFrameworks.joinToString(", ") { it.name }}")
        }
        return KotlinMcpResult.Success(content = output)
    }

    /**
     * Statically analyzes build scripts using K2 PSI AST traversal to extract active framework features.
     *
     * @param buildScriptContent Raw build script content
     * @param projectPath Optional root path of the project workspace
     * @param gradleProjectInspector Optional inspector instance
     * @return [ProjectEnvironmentProfile] resolved framework profile
     */
    fun detectProfile(buildScriptContent: String, projectPath: String?, gradleProjectInspector: GradleProjectInspector = GradleProjectInspector()): ProjectEnvironmentProfile {
        val allContent = if (projectPath != null) {
            val file = File(projectPath, "build.gradle.kts")
            if (file.exists()) file.readText() + "\n" + buildScriptContent else buildScriptContent
        } else {
            buildScriptContent
        }

        if (allContent.isBlank()) return ProjectEnvironmentProfile.NONE

        val psi = com.gokorei.kotlinmcp.lsp.K2SnippetFrontend.parsePsi(allContent)
        val active = mutableSetOf<FrameworkFeature>()
        var isKmp = false

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

        if (psi != null && !hasParserErrors) {
            psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: org.jetbrains.kotlin.psi.KtCallExpression) {
                    val callee = expression.calleeExpression?.text.orEmpty()
                    val args = expression.valueArguments.mapNotNull { it.getArgumentExpression()?.text?.trim('"', '\'') }

                    when (callee) {
                        "android" -> active.add(FrameworkFeature.ANDROID)
                        "androidTarget" -> {
                            active.add(FrameworkFeature.ANDROID)
                            isKmp = true
                        }
                        "id", "plugin" -> {
                            for (arg in args) {
                                if (arg.startsWith("com.android.") || arg == "android") active.add(FrameworkFeature.ANDROID)
                                if (arg.contains("compose")) active.add(FrameworkFeature.COMPOSE)
                                if (arg.contains("serialization")) active.add(FrameworkFeature.SERIALIZATION)
                                if (arg.contains("spring")) active.add(FrameworkFeature.SPRING)
                                if (arg.contains("multiplatform") || arg.contains("kmp")) isKmp = true
                            }
                        }
                        "kotlin" -> {
                            for (arg in args) {
                                if (arg == "android") active.add(FrameworkFeature.ANDROID)
                                if (arg == "multiplatform" || arg == "kmp") isKmp = true
                                if (arg.contains("serialization")) active.add(FrameworkFeature.SERIALIZATION)
                                if (arg.contains("compose")) active.add(FrameworkFeature.COMPOSE)
                                if (arg.contains("spring")) active.add(FrameworkFeature.SPRING)
                            }
                        }
                        "implementation", "api", "testImplementation", "androidTestImplementation", "compileOnly", "runtimeOnly", "ksp", "kapt" -> {
                            for (arg in args) {
                                val lower = arg.lowercase()
                                if (lower.contains("ktor")) active.add(FrameworkFeature.KTOR)
                                if (lower.contains("spring")) active.add(FrameworkFeature.SPRING)
                                if (lower.contains("compose")) active.add(FrameworkFeature.COMPOSE)
                                if (lower.contains("arrow")) active.add(FrameworkFeature.ARROW)
                                if (lower.contains("serialization")) active.add(FrameworkFeature.SERIALIZATION)
                                if (lower.contains("mockk")) active.add(FrameworkFeature.MOCKK)
                                if (lower.contains("coroutines")) active.add(FrameworkFeature.COROUTINES)
                                if (lower.contains("turbine")) active.add(FrameworkFeature.TURBINE)
                                if (lower.contains("datetime")) active.add(FrameworkFeature.DATETIME)
                                if (lower.contains("exposed")) active.add(FrameworkFeature.EXPOSED)
                                if (lower.contains("room")) active.add(FrameworkFeature.ROOM)
                                if (lower.startsWith("androidx.") || lower.contains("com.google.android.")) active.add(FrameworkFeature.ANDROID)
                            }
                        }
                    }
                    super.visitCallExpression(expression)
                }
            })
        } else {
            // Safe fallback for Groovy DSL build.gradle (strip single-line and multi-line comments)
            val noComments = allContent
                .replace(Regex("""//.*"""), "")
                .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            val text = noComments.lowercase()

            if (text.contains("ktor")) active.add(FrameworkFeature.KTOR)
            if (text.contains("spring") || text.contains("org.springframework")) active.add(FrameworkFeature.SPRING)
            if (text.contains("compose") || text.contains("androidx.compose")) active.add(FrameworkFeature.COMPOSE)
            if (text.contains("arrow-core") || text.contains("io.arrow-kt")) active.add(FrameworkFeature.ARROW)
            if (text.contains("serialization") || text.contains("kotlinx-serialization")) active.add(FrameworkFeature.SERIALIZATION)
            if (text.contains("mockk") || text.contains("io.mockk")) active.add(FrameworkFeature.MOCKK)
            if (text.contains("coroutines") || text.contains("kotlinx-coroutines")) active.add(FrameworkFeature.COROUTINES)
            if (text.contains("turbine") || text.contains("app.cash.turbine")) active.add(FrameworkFeature.TURBINE)
            if (text.contains("datetime") || text.contains("kotlinx-datetime")) active.add(FrameworkFeature.DATETIME)
            if (text.contains("exposed") || text.contains("org.jetbrains.exposed")) active.add(FrameworkFeature.EXPOSED)
            if (text.contains("room") || text.contains("androidx.room")) active.add(FrameworkFeature.ROOM)
            if (text.contains("com.android.") || text.contains("apply plugin: 'com.android.") || text.contains("apply plugin: \"com.android.") || text.contains("android {")) active.add(FrameworkFeature.ANDROID)

            isKmp = text.contains("multiplatform")
        }

        return ProjectEnvironmentProfile(activeFrameworks = active, isKmp = isKmp)
    }
}
