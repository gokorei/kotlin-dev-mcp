package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.maven.DefaultMavenMetadataClient
import com.gokorei.kotlinmcp.maven.MavenCoordinate
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress

class VersionCatalogServiceTest {

    @TempDir
    lateinit var tempDir: File

    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    private val sampleToml = """
        # Version Catalog for Kotlin project
        [versions]
        kotlin = "2.0.0"
        ktor = "2.3.12"
        coroutines = "1.8.0"

        [libraries]
        # Core Ktor client
        ktor-client = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
        ktor-cio = { group = "io.ktor", name = "ktor-client-cio", version = "2.3.12" }
        coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }

        [plugins]
        kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
    """.trimIndent()

    @Test
    fun `parses version catalog toml correctly`() {
        val gradleDir = File(tempDir, "gradle").apply { mkdirs() }
        val tomlFile = File(gradleDir, "libs.versions.toml").apply { writeText(sampleToml) }

        val service = DefaultVersionCatalogService()
        val parsed = service.parseCatalog(tempDir.absolutePath)

        assertNotNull(parsed)
        assertEquals("2.0.0", parsed.versions["kotlin"])
        assertEquals("2.3.12", parsed.versions["ktor"])
        assertEquals("1.8.0", parsed.versions["coroutines"])

        assertEquals(3, parsed.libraries.size)
        val ktorClient = parsed.libraries["ktor-client"]
        assertNotNull(ktorClient)
        assertEquals("io.ktor:ktor-client-core", ktorClient?.module)
        assertEquals("ktor", ktorClient?.versionRef)
    }

    @Test
    fun `updates version in versions table preserving comments and structure`() {
        val gradleDir = File(tempDir, "gradle").apply { mkdirs() }
        val tomlFile = File(gradleDir, "libs.versions.toml").apply { writeText(sampleToml) }

        val service = DefaultVersionCatalogService()
        val result = service.updateVersion(tempDir.absolutePath, "ktor", "3.0.3")

        assertTrue(result is KotlinMcpResult.Success)
        val updatedText = tomlFile.readText()
        assertTrue(updatedText.contains("ktor = \"3.0.3\""), "Expected updated version in toml:\n$updatedText")
        assertTrue(updatedText.contains("# Core Ktor client"), "Comments should be preserved")
        assertTrue(updatedText.contains("kotlin = \"2.0.0\""), "Other versions should be untouched")
    }

    @Test
    fun `adds new library to catalog preserving structure`() {
        val gradleDir = File(tempDir, "gradle").apply { mkdirs() }
        val tomlFile = File(gradleDir, "libs.versions.toml").apply { writeText(sampleToml) }

        val service = DefaultVersionCatalogService()
        val result = service.addLibrary(
            projectPath = tempDir.absolutePath,
            alias = "serialization-json",
            module = "org.jetbrains.kotlinx:kotlinx-serialization-json",
            version = "1.7.3"
        )

        assertTrue(result is KotlinMcpResult.Success)
        val updatedText = tomlFile.readText()
        assertTrue(updatedText.contains("serialization-json = { module = \"org.jetbrains.kotlinx:kotlinx-serialization-json\", version = \"1.7.3\" }") ||
            updatedText.contains("serialization-json = { group = \"org.jetbrains.kotlinx\", name = \"kotlinx-serialization-json\", version = \"1.7.3\" }"),
            "Expected added library in toml:\n$updatedText")
    }

    @Test
    fun `checks catalog for outdated versions against Maven metadata`() {
        val ktorXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>io.ktor</groupId>
              <artifactId>ktor-client-core</artifactId>
              <versioning>
                <release>3.0.3</release>
                <latest>3.0.3</latest>
                <versions>
                  <version>2.3.12</version>
                  <version>3.0.3</version>
                </versions>
              </versioning>
            </metadata>
        """.trimIndent()

        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/io/ktor/ktor-client-core/maven-metadata.xml") { exchange ->
                val bytes = ktorXml.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            createContext("/io/ktor/ktor-client-cio/maven-metadata.xml") { exchange ->
                val bytes = ktorXml.replace("ktor-client-core", "ktor-client-cio").toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            createContext("/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml") { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.responseBody.close()
            }
            start()
        }

        val port = server!!.address.port
        val repoUrl = "http://localhost:$port"

        val metadataClient = DefaultMavenMetadataClient(
            defaultRepositories = listOf(repoUrl),
            cacheDir = tempDir,
            isOffline = false
        )

        val gradleDir = File(tempDir, "gradle").apply { mkdirs() }
        File(gradleDir, "libs.versions.toml").writeText(sampleToml)

        val service = DefaultVersionCatalogService(metadataClient = metadataClient)
        val result = service.checkCatalogUpdates(tempDir.absolutePath)

        assertTrue(result is KotlinMcpResult.Success)
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("ktor-client"))
        assertTrue(success.content.contains("3.0.3"))
        assertTrue(success.content.contains("2.3.12"))
    }
}
