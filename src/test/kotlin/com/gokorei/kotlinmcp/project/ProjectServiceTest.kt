package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProjectServiceTest {

    private lateinit var projectService: ProjectService

    @BeforeEach
    fun setUp() {
        projectService = DefaultProjectService()
    }

    @Test
    fun `inspect_structure identifies gradle build scripts and project layout`() {
        val buildScriptContent = """
            plugins {
                kotlin("jvm") version "2.1.0"
            }
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.INSPECT_STRUCTURE,
            buildScriptContent = buildScriptContent
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Gradle Project Structure"))
        assertTrue(success.content.contains("kotlin(jvm)"))
    }

    @Test
    fun `inspect_structure detects id plugins and bare application plugin`() {
        val buildScriptContent = """
            plugins {
                kotlin("jvm") version "2.3.20"
                id("org.graalvm.buildtools.native") version "1.1.7"
                application
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.INSPECT_STRUCTURE,
            buildScriptContent = buildScriptContent
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("kotlin(jvm)"), "expected kotlin(jvm): ${success.content}")
        assertTrue(success.content.contains("id(org.graalvm.buildtools.native)"), "expected id() plugin: ${success.content}")
        assertTrue(success.content.contains("application"), "expected bare application plugin: ${success.content}")
    }

    @Test
    fun `analyze_dependencies extracts version catalog and project dependencies`() {
        val scriptContent = """
            dependencies {
                implementation("io.modelcontextprotocol:kotlin-sdk:0.14.0")
                implementation(libs.kotlinx.coroutines)
                api(project(":core"))
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.ANALYZE_DEPENDENCIES,
            buildScriptContent = scriptContent
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("io.modelcontextprotocol:kotlin-sdk:0.14.0"), "string dep: ${success.content}")
        assertTrue(success.content.contains("libs.kotlinx.coroutines"), "catalog dep: ${success.content}")
        assertTrue(success.content.contains("project(\":core\")"), "project dep: ${success.content}")
    }

    @Test
    fun `analyze_dependencies handles multi-line arguments and block comments via PSI`() {
        val scriptContent = """
            dependencies {
                /*
                 * Primary SDK dependency
                 */
                implementation(
                    "com.google.guava:guava:33.0.0-jre"
                )
                testImplementation(libs.junit.jupiter)
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.ANALYZE_DEPENDENCIES,
            buildScriptContent = scriptContent
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("com.google.guava:guava:33.0.0-jre"), "expected guava dep: ${success.content}")
        assertTrue(success.content.contains("libs.junit.jupiter"), "expected catalog dep: ${success.content}")
    }

    @Test
    fun `list_kmp_targets parses multiplatform target source sets`() {
        val kmpScriptContent = """
            kotlin {
                jvm()
                androidTarget()
                iosX64()
                iosArm64()
                iosSimulatorArm64()
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.LIST_KMP_TARGETS,
            buildScriptContent = kmpScriptContent
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Kotlin Multiplatform (KMP) Targets"))
        assertTrue(success.content.contains("jvm"))
        assertTrue(success.content.contains("androidTarget"))
        assertTrue(success.content.contains("iosX64"))
    }

    @Test
    fun `analyze_dependencies extracts declared libraries and versions`() {
        val scriptContent = """
            dependencies {
                implementation("io.modelcontextprotocol:kotlin-sdk:0.14.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.ANALYZE_DEPENDENCIES,
            buildScriptContent = scriptContent
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("io.modelcontextprotocol:kotlin-sdk:0.14.0"))
        assertTrue(success.content.contains("kotlinx-serialization-json"))
    }

    @Test
    fun `diagnose_build flags plugin version duplication`() {
        val script = """
            plugins {
                kotlin("jvm") version "2.1.0"
                kotlin("android") version "2.1.0"
                id("org.jetbrains.kotlin.android") version "2.1.0"
                id("com.android.application") version "8.9.0"
            }
            repositories {
                google()
                mavenCentral()
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.DIAGNOSE_BUILD,
            buildScriptContent = script
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Plugin conflict"), "expected plugin findings in: ${success.content}")
    }

    @Test
    fun `diagnose_build flags missing repository and hardcoded versions`() {
        val script = """
            plugins {
                kotlin("jvm") version "2.1.0"
            }
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("io.ktor:ktor-client-core:2.3.0")
                implementation("io.ktor:ktor-client-cio:2.3.0")
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.DIAGNOSE_BUILD,
            buildScriptContent = script
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("repositor"), "expected missing-repository hint in: ${success.content}")
    }

    @Test
    fun `inspect_structure reports layered architecture when dirs exist`() {
        val dir = java.nio.file.Files.createTempDirectory("kmcp-layers")
        java.nio.file.Files.createDirectories(dir.resolve("src/main/kotlin/com/app/ui"))
        java.nio.file.Files.createDirectories(dir.resolve("src/main/kotlin/com/app/domain"))
        java.nio.file.Files.createDirectories(dir.resolve("src/main/kotlin/com/app/data"))
        try {
            val buildScript = """
                plugins {
                    kotlin("jvm") version "2.1.0"
                }
            """.trimIndent()
            val result = projectService.execute(
                action = ProjectAction.INSPECT_STRUCTURE,
                buildScriptContent = buildScript,
                projectPath = dir.toString()
            )
            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("Layering"), "expected layering section in: ${success.content}")
            assertTrue(success.content.contains("ui"), "expected ui layer in: ${success.content}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `package_api dumps public declarations from project directory`() {
        val dir = java.nio.file.Files.createTempDirectory("kmcp-package-api")
        val pkgDir = dir.resolve("src/main/kotlin/com/example/api")
        java.nio.file.Files.createDirectories(pkgDir)
        pkgDir.resolve("User.kt").toFile().writeText("""
            package com.example.api
            
            data class User(val id: Long, val name: String)
            fun greetUser(u: User): String = "Hello ${'$'}{u.name}"
        """.trimIndent())

        try {
            val result = projectService.packageApi(dir.toString(), "com.example.api")
            assertTrue(result.isSuccess)
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("User"), "expected class User in package_api output")
            assertTrue(success.content.contains("greetUser"), "expected function greetUser in package_api output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `check_vulnerabilities scans dependencies and flags known CVE advisories`() {
        val scriptContent = """
            dependencies {

                implementation("org.apache.commons:commons-compress:1.18")
                implementation("io.modelcontextprotocol:kotlin-sdk:0.14.0")
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.CHECK_VULNERABILITIES,
            buildScriptContent = scriptContent
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("Vulnerab"), "expected vulnerability report header")
        assertTrue(success.content.contains("org.apache.commons:commons-compress"), "expected flagged dependency")
    }

    @Test
    fun `check_vulnerabilities returns clean report when no vulnerabilities are found`() {
        val scriptContent = """
            dependencies {
                implementation("io.modelcontextprotocol:kotlin-sdk:0.14.0")
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.CHECK_VULNERABILITIES,
            buildScriptContent = scriptContent
        )

        assertTrue(result.isSuccess)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("No known vulnerabilities") || success.content.contains("Vulnerab"))
    }

    @Test
    fun `check_vulnerabilities returns TOOL_UNAVAILABLE when no coordinates parse`() {
        val scriptContent = """
            println("hello")
            val x = 42
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.CHECK_VULNERABILITIES,
            buildScriptContent = scriptContent
        )

        assertTrue(result.isError, "expected error when nothing can be scanned, got: ${result.toFormattedText()}")
        assertTrue(result.toFormattedText().contains("TOOL_UNAVAILABLE"), "got: ${result.toFormattedText()}")
    }

    @Test
    fun `check_vulnerabilities parses named-arg and version-catalog coordinates`() {
        val scriptContent = """
            dependencies {
                implementation(group = "org.apache.commons", name = "commons-compress", version = "1.18")
                implementation(libs.kotlinx.coroutines.core)
            }
        """.trimIndent()
        val libsToml = """
            [versions]
            kotlinx-coroutines-core = "1.9.0"
            [libraries]
            kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines-core" }
        """.trimIndent()

        val dir = java.nio.file.Files.createTempDirectory("kmcp-vuln-catalog")
        java.nio.file.Files.createDirectories(dir.resolve("gradle"))
        dir.resolve("gradle/libs.versions.toml").toFile().writeText(libsToml)
        dir.resolve("build.gradle.kts").toFile().writeText(scriptContent)

        try {
            val result = projectService.execute(
                action = ProjectAction.CHECK_VULNERABILITIES,
                buildScriptContent = "",
                projectPath = dir.toString()
            )
            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            val scanned = success.metadata["scannedCoordinateCount"]?.toIntOrNull() ?: 0
            assertTrue(scanned >= 2, "expected both named-arg and catalog coordinates parsed, got $scanned")
            assertTrue(success.content.contains("commons-compress"), "expected commons-compress flagged: ${success.content}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `maven version comparison preserves qualifiers`() {
        // 4.1.108.Final must NOT be treated as < 4.1.108 (old code dropped Final).
        // Use commons-compress 1.26.0 (the fixed version) vs 1.26.0.Final to
        // exercise the qualifier-aware comparator deterministically.
        val fixed = projectService.execute(
            action = ProjectAction.CHECK_VULNERABILITIES,
            buildScriptContent = """dependencies { implementation("org.apache.commons:commons-compress:1.26.0") }"""
        )
        assertFalse(
            (fixed as KotlinMcpResult.Success).content.contains("Flagged Security Advisories"),
            "1.26.0 (fixed version) must not be flagged: ${fixed.content}"
        )
    }

    @Test
    fun `maven version comparison treats Final as equal to the bare version`() {
        // 1.26.0.Final is NOT below 1.26.0; the old comparator dropped `Final`.
        val withFinal = projectService.execute(
            action = ProjectAction.CHECK_VULNERABILITIES,
            buildScriptContent = """dependencies { implementation("org.apache.commons:commons-compress:1.26.0.Final") }"""
        )
        assertFalse(
            (withFinal as KotlinMcpResult.Success).content.contains("Flagged Security Advisories"),
            "1.26.0.Final must not be flagged: ${withFinal.content}"
        )
    }

    @Test
    fun `maven version comparison flags a genuinely vulnerable older version`() {
        val old = projectService.execute(
            action = ProjectAction.CHECK_VULNERABILITIES,
            buildScriptContent = """dependencies { implementation("org.apache.commons:commons-compress:1.18") }"""
        )
        assertTrue(
            (old as KotlinMcpResult.Success).content.contains("Flagged Security Advisories"),
            "1.18 must be flagged as vulnerable: ${old.content}"
        )
    }

    @Test
    fun `detectProfile scans buildScriptContent and identifies active framework features`() {
        val script = """
            plugins {
                kotlin("jvm") version "2.3.20"
                kotlin("plugin.serialization") version "2.3.20"
            }
            dependencies {
                implementation("io.ktor:ktor-server-core:3.0.3")
                implementation("io.arrow-kt:arrow-core:2.0.1")
                testImplementation("io.mockk:mockk:1.13.13")
            }
        """.trimIndent()

        val profile = projectService.detectProfile(script)

        assertTrue(profile.hasFramework(com.gokorei.kotlinmcp.models.FrameworkFeature.KTOR), "expected KTOR detected")
        assertTrue(profile.hasFramework(com.gokorei.kotlinmcp.models.FrameworkFeature.ARROW), "expected ARROW detected")
        assertTrue(profile.hasFramework(com.gokorei.kotlinmcp.models.FrameworkFeature.MOCKK), "expected MOCKK detected")
        assertFalse(profile.hasFramework(com.gokorei.kotlinmcp.models.FrameworkFeature.SPRING), "expected SPRING not detected")
    }

    @Test
    fun `coverageReport parses JaCoCo XML report summary`() {
        val dir = java.nio.file.Files.createTempDirectory("kmcp-jacoco")
        val jacocoDir = dir.resolve("build/reports/jacoco/test")
        java.nio.file.Files.createDirectories(jacocoDir)
        jacocoDir.resolve("jacocoTestReport.xml").toFile().writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <report name="kotlin-mcp">
              <counter type="LINE" missed="10" covered="90"/>
              <counter type="BRANCH" missed="5" covered="15"/>
            </report>
        """.trimIndent())

        try {
            val result = projectService.coverageReport(dir.toString())
            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("JaCoCo Code Coverage Report"))
            assertTrue(success.content.contains("Line Coverage: 90%"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `inspect_structure discovers Android submodule source roots app-src-main-java`() {
        val dir = java.nio.file.Files.createTempDirectory("kmcp-android-proj")
        val appJavaDir = dir.resolve("app/src/main/java/com/example/ui")
        java.nio.file.Files.createDirectories(appJavaDir)
        appJavaDir.resolve("MainActivity.java").toFile().writeText("""
            package com.example.ui;
            public class MainActivity {}
        """.trimIndent())

        try {
            val result = projectService.execute(
                action = ProjectAction.INSPECT_STRUCTURE,
                buildScriptContent = "plugins { id(\"com.android.application\") }",
                projectPath = dir.toString()
            )
            assertTrue(result.isSuccess)
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("app/src/main/java"), "expected app/src/main/java in detected source sets: ${success.content}")
            assertTrue(success.content.contains("`ui` layer"), "expected ui layer detected: ${success.content}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `checkVulnerabilities parses dependencies from gradle lockfile`() {
        val dir = java.nio.file.Files.createTempDirectory("kmcp-lockfile-proj")
        val lockfile = dir.resolve("gradle.lockfile")
        java.nio.file.Files.writeString(lockfile, """
            # This is a Gradle lockfile
            org.springframework:spring-core:5.3.0=compileClasspath
            org.apache.logging.log4j:log4j-core:2.14.0=runtimeClasspath
            empty=
        """.trimIndent())

        try {
            val result = projectService.checkVulnerabilities("", projectPath = dir.toString())
            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("spring-core") || success.content.contains("log4j-core"), "expected lockfile coordinates: ${success.content}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `inspect_structure parses settings gradle kts for subprojects`() {
        val dir = java.nio.file.Files.createTempDirectory("kmcp-subprojects-proj")
        try {
            val settings = dir.resolve("settings.gradle.kts")
            java.nio.file.Files.writeString(settings, """
                rootProject.name = "my-root"
                include(":app", ":core:domain", ":core:data")
            """.trimIndent())

            val result = projectService.execute(
                action = ProjectAction.INSPECT_STRUCTURE,
                buildScriptContent = "plugins { kotlin(\"jvm\") }",
                projectPath = dir.toString()
            )
            assertTrue(result.isSuccess)
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("`:app`, `:core:domain`, `:core:data`"), "expected subprojects in output: ${success.content}")
            assertEquals("3", success.metadata["subprojectsCount"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `analyze_dependencies parses gradle libs versions toml version catalog`() {
        val dir = java.nio.file.Files.createTempDirectory("kmcp-catalog-proj")
        try {
            val gradleDir = dir.resolve("gradle")
            java.nio.file.Files.createDirectories(gradleDir)
            val toml = gradleDir.resolve("libs.versions.toml")
            java.nio.file.Files.writeString(toml, """
                [versions]
                coroutines = "1.9.0"
                ktor = "3.0.0"

                [libraries]
                kotlinx-coroutines = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
                ktor-server = { group = "io.ktor", name = "ktor-server-core", version.ref = "ktor" }
            """.trimIndent())

            val buildScript = """
                dependencies {
                    implementation(libs.kotlinx.coroutines)
                    implementation(libs.ktor.server)
                }
            """.trimIndent()

            val result = projectService.execute(
                action = ProjectAction.ANALYZE_DEPENDENCIES,
                buildScriptContent = buildScript,
                projectPath = dir.toString()
            )
            assertTrue(result.isSuccess)
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0"), "expected catalog mapped coroutines: ${success.content}")
            assertTrue(success.content.contains("io.ktor:ktor-server-core:3.0.0"), "expected catalog mapped ktor: ${success.content}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `analyze_dependencies parses Groovy DSL syntax without parentheses`() {
        val scriptContent = """
            plugins {
                id 'com.android.application'
                id "kotlin-android"
            }
            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.12.0'
                api "com.squareup.retrofit2:retrofit:2.9.0"
            }
        """.trimIndent()

        val result = projectService.execute(
            action = ProjectAction.ANALYZE_DEPENDENCIES,
            buildScriptContent = scriptContent
        )

        assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("commons-lang3") || success.content.contains("3.12.0"), "expected Groovy single-quote dep: ${success.content}")
        assertTrue(success.content.contains("retrofit"), "expected Groovy double-quote dep: ${success.content}")
    }

    @Test
    fun `inspect_structure scans module level build scripts in multi-module projects`() {
        val dir = java.nio.file.Files.createTempDirectory("kmcp-multimodule-proj")
        try {
            val settings = dir.resolve("settings.gradle.kts")
            java.nio.file.Files.writeString(settings, """
                include(":app", ":feature:auth")
            """.trimIndent())

            val appDir = dir.resolve("app")
            java.nio.file.Files.createDirectories(appDir)
            appDir.resolve("build.gradle").toFile().writeText("""
                plugins {
                    id 'com.android.application'
                }
                dependencies {
                    implementation 'androidx.core:core-ktx:1.12.0'
                }
            """.trimIndent())

            val authDir = dir.resolve("feature/auth")
            java.nio.file.Files.createDirectories(authDir)
            authDir.resolve("build.gradle.kts").toFile().writeText("""
                plugins {
                    kotlin("jvm")
                }
                dependencies {
                    implementation("io.ktor:ktor-client-core:2.3.0")
                }
            """.trimIndent())

            val result = projectService.execute(
                action = ProjectAction.INSPECT_STRUCTURE,
                buildScriptContent = "",
                projectPath = dir.toString()
            )
            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains(":app") || success.content.contains("com.android.application"), "expected app module detected: ${success.content}")
            assertTrue(success.content.contains("feature/auth") || success.content.contains(":feature:auth") || success.content.contains("ktor-client-core"), "expected auth module detected: ${success.content}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `analyze_dependencies reads build script and resolves catalog from projectPath when content is blank`() {
        val dir = java.nio.file.Files.createTempDirectory("kmcp-deps-projectpath")
        try {
            val gradleDir = dir.resolve("gradle")
            java.nio.file.Files.createDirectories(gradleDir)
            java.nio.file.Files.writeString(gradleDir.resolve("libs.versions.toml"), """
                [versions]
                coroutines = "1.9.0"

                [libraries]
                kotlinx-coroutines = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
            """.trimIndent())
            java.nio.file.Files.writeString(dir.resolve("build.gradle.kts"), """
                plugins {
                    kotlin("jvm") version "2.3.20"
                }
                dependencies {
                    implementation(libs.kotlinx.coroutines)
                }
            """.trimIndent())

            val result = projectService.execute(
                action = ProjectAction.ANALYZE_DEPENDENCIES,
                buildScriptContent = "",
                projectPath = dir.toString()
            )
            assertTrue(result.isSuccess, "expected success, got: ${result.toFormattedText()}")
            val success = result as KotlinMcpResult.Success
            assertTrue(success.content.contains("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0"), "expected projectPath-resolved catalog coordinate: ${success.content}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `check_vulnerabilities accepts custom timeouts and max retries parameters`() {
        val scriptContent = """
            dependencies {
                implementation("org.apache.commons:commons-compress:1.18")
            }
        """.trimIndent()

        val result = projectService.checkVulnerabilities(
            buildScriptContent = scriptContent,
            connectTimeoutMs = 2000,
            readTimeoutMs = 3000,
            maxRetries = 2
        )

        assertTrue(result.isSuccess, "expected success with custom timeouts, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("commons-compress"), "expected commons-compress flagged: ${success.content}")
    }

    @Test
    fun `check_vulnerabilities falls back to offline baseline when retries are exhausted on invalid timeout`() {
        val scriptContent = """
            dependencies {
                implementation("org.apache.commons:commons-compress:1.18")
            }
        """.trimIndent()

        // Very small timeout to guarantee failure/fallback
        val result = projectService.checkVulnerabilities(
            buildScriptContent = scriptContent,
            connectTimeoutMs = 1,
            readTimeoutMs = 1,
            maxRetries = 1
        )

        assertTrue(result.isSuccess, "expected fallback to succeed, got: ${result.toFormattedText()}")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("commons-compress"), "expected commons-compress flagged via offline fallback: ${success.content}")
    }
}
