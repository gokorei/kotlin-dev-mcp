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

class DefaultFrameworkDetector(
    private val profileDetector: com.gokorei.kotlinmcp.project.EnvironmentProfileDetector = com.gokorei.kotlinmcp.project.EnvironmentProfileDetector()
) : FrameworkDetector {

    override fun detectFromBuildScript(scriptContent: String): ProjectEnvironmentProfile {
        return profileDetector.detectProfile(scriptContent, null)
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
