package com.gokorei.kotlinmcp.server

import com.gokorei.kotlinmcp.models.FrameworkFeature
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrameworkDetectorTest {

    private val detector: FrameworkDetector = DefaultFrameworkDetector()

    @Test
    fun `detectFromBuildScript identifies ktor and serialization dependencies`() {
        val script = """
            dependencies {
                implementation("io.ktor:ktor-server-core:3.0.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        """.trimIndent()

        val profile = detector.detectFromBuildScript(script)
        assertTrue(profile.hasFramework(FrameworkFeature.KTOR), "expected KTOR detected")
        assertTrue(profile.hasFramework(FrameworkFeature.SERIALIZATION), "expected SERIALIZATION detected")
        assertFalse(profile.hasFramework(FrameworkFeature.SPRING), "SPRING should not be detected")
    }

    @Test
    fun `detectFromBuildScript identifies arrow, datetime, and mockk dependencies`() {
        val script = """
            dependencies {
                implementation("io.arrow-kt:arrow-core:2.0.1")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
                testImplementation("io.mockk:mockk:1.13.13")
            }
        """.trimIndent()

        val profile = detector.detectFromBuildScript(script)
        assertTrue(profile.hasFramework(FrameworkFeature.ARROW), "expected ARROW detected")
        assertTrue(profile.hasFramework(FrameworkFeature.DATETIME), "expected DATETIME detected")
        assertTrue(profile.hasFramework(FrameworkFeature.MOCKK), "expected MOCKK detected")
    }

    @Test
    fun `detectFromBuildScript handles empty or blank build scripts gracefully`() {
        val profile = detector.detectFromBuildScript("")
        assertTrue(profile.activeFrameworks.isEmpty())
    }
}
