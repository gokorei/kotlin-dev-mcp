package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.FrameworkFeature
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EnvironmentProfileDetectorTest {

    private val detector = EnvironmentProfileDetector()

    @Test
    fun `detectProfile identifies Android application and library plugins`() {
        val appScript = """
            plugins {
                id("com.android.application") version "8.5.2"
                kotlin("android")
            }
            android {
                namespace = "com.example.app"
                compileSdk = 35
            }
        """.trimIndent()

        val profile = detector.detectProfile(appScript, null, GradleProjectInspector())
        assertTrue(profile.hasFramework(FrameworkFeature.ANDROID), "Android should be detected for com.android.application")
        assertTrue(profile.isAndroid, "isAndroid helper should be true")
        assertFalse(profile.isKmp, "Single-platform Android is not KMP")
    }

    @Test
    fun `detectProfile identifies Android Kotlin Multiplatform target`() {
        val kmpScript = """
            plugins {
                kotlin("multiplatform") version "2.1.0"
                id("com.android.library") version "8.5.2"
            }
            kotlin {
                androidTarget()
                jvm()
            }
        """.trimIndent()

        val profile = detector.detectProfile(kmpScript, null, GradleProjectInspector())
        assertTrue(profile.hasFramework(FrameworkFeature.ANDROID), "Android should be detected")
        assertTrue(profile.isAndroid)
        assertTrue(profile.isKmp, "KMP should be detected")
    }

    @Test
    fun `detectProfile isolates non-Android pure JVM projects`() {
        val jvmScript = """
            plugins {
                kotlin("jvm") version "2.1.0"
                application
            }
            dependencies {
                implementation("io.ktor:ktor-server-core:3.0.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        """.trimIndent()

        val profile = detector.detectProfile(jvmScript, null, GradleProjectInspector())
        assertFalse(profile.hasFramework(FrameworkFeature.ANDROID), "Android should NOT be detected in pure JVM project")
        assertFalse(profile.isAndroid)
        assertTrue(profile.hasFramework(FrameworkFeature.KTOR))
        assertTrue(profile.hasFramework(FrameworkFeature.COROUTINES))
    }

    @Test
    fun `detectProfile ignores comments and unrelated string literals mentioning android or plugins`() {
        val nonAndroidScript = """
            // This is a comment mentioning com.android.application and android {
            /* multi-line comment mentioning androidTarget() */
            plugins {
                kotlin("jvm") version "2.1.0"
            }
            val note = "com.android.library is not used here"
        """.trimIndent()

        val profile = detector.detectProfile(nonAndroidScript, null, GradleProjectInspector())
        assertFalse(profile.hasFramework(FrameworkFeature.ANDROID), "Comments or string literals must not trigger Android detection")
        assertFalse(profile.isAndroid)
    }

    @Test
    fun `detectProfile correctly identifies Android in Groovy build script`() {
        val groovyScript = """
            apply plugin: 'com.android.application'
            apply plugin: 'kotlin-android'

            android {
                compileSdkVersion 34
            }
        """.trimIndent()

        val profile = detector.detectProfile(groovyScript, null, GradleProjectInspector())
        assertTrue(profile.hasFramework(FrameworkFeature.ANDROID), "Groovy build.gradle with Android plugin should be detected")
        assertTrue(profile.isAndroid)
    }

    @Test
    fun `detectProfile ignores android block mentions in string literals in Groovy build script`() {
        val groovyScript = """
            apply plugin: 'java'
            ext {
                note = "android { compileSdk = 35 }"
            }
        """.trimIndent()

        val profile = detector.detectProfile(groovyScript, null, GradleProjectInspector())
        assertFalse(profile.hasFramework(FrameworkFeature.ANDROID), "android { in Groovy string literal must not trigger Android detection")
        assertFalse(profile.isAndroid)
    }

    @Test
    fun `detectProfile ignores quoted com android values outside plugin declarations in Groovy`() {
        val groovyScript = """
            apply plugin: 'java'
            ext {
                description = 'Reference to com.android.tools for documentation'
                sdkPackage = "com.android.support:support-v4:28.0.0"
            }
        """.trimIndent()

        val profile = detector.detectProfile(groovyScript, null, GradleProjectInspector())
        assertFalse(profile.hasFramework(FrameworkFeature.ANDROID), "Arbitrary com.android.* string in Groovy must not trigger Android detection")
        assertFalse(profile.isAndroid)
    }

    @Test
    fun `detectEnvironmentProfile renders structured markdown summary`() {
        val script = """
            plugins {
                id("com.android.application")
            }
        """.trimIndent()

        val result = detector.detectEnvironmentProfile(script, null)
        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("ANDROID"))
    }
}
