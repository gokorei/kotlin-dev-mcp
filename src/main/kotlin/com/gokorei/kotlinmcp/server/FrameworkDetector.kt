package com.gokorei.kotlinmcp.server

import com.gokorei.kotlinmcp.models.FrameworkFeature
import com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile
import java.io.File

/**
 * Service interface for automatically detecting project frameworks and library dependencies
 * from Gradle build scripts and project layout.
 */
interface FrameworkDetector {
    fun detectFromBuildScript(scriptContent: String): ProjectEnvironmentProfile
    fun detectFromProjectDir(projectPath: String): ProjectEnvironmentProfile
}

class DefaultFrameworkDetector : FrameworkDetector {

    override fun detectFromBuildScript(scriptContent: String): ProjectEnvironmentProfile {
        if (scriptContent.isBlank()) return ProjectEnvironmentProfile.NONE
        val active = mutableSetOf<FrameworkFeature>()
        val text = scriptContent.lowercase()

        if (text.contains("ktor")) active.add(FrameworkFeature.KTOR)
        if (text.contains("serialization")) active.add(FrameworkFeature.SERIALIZATION)
        if (text.contains("arrow")) active.add(FrameworkFeature.ARROW)
        if (text.contains("datetime")) active.add(FrameworkFeature.DATETIME)
        if (text.contains("mockk")) active.add(FrameworkFeature.MOCKK)
        if (text.contains("turbine")) active.add(FrameworkFeature.TURBINE)
        if (text.contains("spring")) active.add(FrameworkFeature.SPRING)
        if (text.contains("compose")) active.add(FrameworkFeature.COMPOSE)
        if (text.contains("exposed")) active.add(FrameworkFeature.EXPOSED)
        if (text.contains("room")) active.add(FrameworkFeature.ROOM)
        if (text.contains("coroutines")) active.add(FrameworkFeature.COROUTINES)
        if (text.contains("com.android.") || text.contains("android {") || text.contains("kotlin(\"android\")") || text.contains("androidtarget")) active.add(FrameworkFeature.ANDROID)

        val isKmp = text.contains("kotlin(\"multiplatform\")") || text.contains("kotlin-multiplatform") || text.contains("kotlin(\"kmp\")")

        return ProjectEnvironmentProfile(activeFrameworks = active, isKmp = isKmp)
    }

    override fun detectFromProjectDir(projectPath: String): ProjectEnvironmentProfile {
        val root = File(projectPath)
        if (!root.exists() || !root.isDirectory) return ProjectEnvironmentProfile.NONE

        val buildFiles = listOf(File(root, "build.gradle.kts"), File(root, "build.gradle"))
            .filter { it.isFile }

        if (buildFiles.isEmpty()) return ProjectEnvironmentProfile.NONE

        val combinedScript = buildFiles.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        return detectFromBuildScript(combinedScript)
    }
}
