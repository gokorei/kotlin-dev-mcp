package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.FrameworkFeature
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile
import java.io.File

/**
 * Strategy component for detecting active framework features and environment profiles.
 */
class EnvironmentProfileDetector {

    fun detectEnvironmentProfile(buildScriptContent: String, projectPath: String?): KotlinMcpResult {
        val profile = detectProfile(buildScriptContent, projectPath, GradleProjectInspector())
        val output = buildString {
            appendLine("# Project Environment Profile")
            appendLine("- Multiplatform (KMP): ${if (profile.isKmp) "Yes" else "No"}")
            appendLine("- Active Frameworks (${profile.activeFrameworks.size}): ${profile.activeFrameworks.joinToString(", ") { it.name }}")
        }
        return KotlinMcpResult.Success(content = output)
    }

    fun detectProfile(buildScriptContent: String, projectPath: String?, gradleProjectInspector: GradleProjectInspector): ProjectEnvironmentProfile {
        val allContent = if (projectPath != null) {
            val file = File(projectPath, "build.gradle.kts")
            if (file.exists()) file.readText() + "\n" + buildScriptContent else buildScriptContent
        } else {
            buildScriptContent
        }

        val active = mutableSetOf<FrameworkFeature>()
        val text = allContent.lowercase()

        if (text.contains("ktor") || text.contains("io.ktor")) active.add(FrameworkFeature.KTOR)
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
        if (text.contains("com.android.") || text.contains("android {") || text.contains("kotlin(\"android\")") || text.contains("androidtarget")) active.add(FrameworkFeature.ANDROID)

        val isKmp = text.contains("multiplatform")

        return ProjectEnvironmentProfile(activeFrameworks = active, isKmp = isKmp)
    }
}
