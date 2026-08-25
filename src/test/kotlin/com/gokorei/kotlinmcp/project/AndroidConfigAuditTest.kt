package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AndroidConfigAuditTest {

    private lateinit var projectService: ProjectService

    @BeforeEach
    fun setUp() {
        projectService = DefaultProjectService()
    }

    @Test
    fun `android_config flags deprecated kotlinCompilerExtensionVersion with Kotlin 2x`() {
        val buildScript = """
            plugins {
                kotlin("android") version "2.1.0"
                id("com.android.application") version "8.5.2"
            }
            android {
                compileSdk = 35
                composeOptions {
                    kotlinCompilerExtensionVersion = "1.5.14"
                }
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.AUDIT_ANDROID_CONFIG,
            buildScriptContent = buildScript
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("kotlinCompilerExtensionVersion"), "expected deprecated flag warning in: ${success.content}")
        assertTrue(success.content.contains("org.jetbrains.kotlin.plugin.compose"))
    }

    @Test
    fun `android_config checks compileSdk and minSdk in android block`() {
        val buildScript = """
            plugins {
                id("com.android.application")
            }
            android {
                namespace = "com.example.app"
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.AUDIT_ANDROID_CONFIG,
            buildScriptContent = buildScript
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("compileSdk"), "expected missing compileSdk warning: ${success.content}")
    }

    @Test
    fun `android_config succeeds on valid Kotlin 2x Compose AGP setup`() {
        val buildScript = """
            plugins {
                kotlin("android") version "2.1.0"
                kotlin("plugin.compose") version "2.1.0"
                id("com.android.application") version "8.5.2"
            }
            android {
                namespace = "com.example.app"
                compileSdk = 35
                defaultConfig {
                    minSdk = 26
                    targetSdk = 35
                }
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.AUDIT_ANDROID_CONFIG,
            buildScriptContent = buildScript
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertFalse(success.content.contains("⚠️"), "valid Android 2.x config should have no warnings: ${success.content}")
    }
}
