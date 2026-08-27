package com.gokorei.kotlinmcp.maven

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress

class MavenMetadataClientTest {

    @TempDir
    lateinit var tempDir: File

    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    private val sampleKtorMetadataXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <metadata>
          <groupId>io.ktor</groupId>
          <artifactId>ktor-client-core</artifactId>
          <versioning>
            <latest>3.0.3</latest>
            <release>3.0.3</release>
            <versions>
              <version>2.0.0</version>
              <version>2.3.12</version>
              <version>3.0.0</version>
              <version>3.0.3</version>
            </versions>
            <lastUpdated>20241201120000</lastUpdated>
          </versioning>
        </metadata>
    """.trimIndent()

    private val sampleGoogleMavenMetadataXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <metadata>
          <groupId>androidx.compose.ui</groupId>
          <artifactId>ui</artifactId>
          <versioning>
            <release>1.7.5</release>
            <latest>1.8.0-alpha06</latest>
            <versions>
              <version>1.5.0</version>
              <version>1.6.0</version>
              <version>1.7.0</version>
              <version>1.7.5</version>
              <version>1.8.0-alpha06</version>
            </versions>
          </versioning>
        </metadata>
    """.trimIndent()

    @Test
    fun `parses standard maven-metadata xml into VersionMetadata`() {
        val client = DefaultMavenMetadataClient(cacheDir = tempDir)
        val metadata = client.parseMetadataXml(
            xmlContent = sampleKtorMetadataXml,
            group = "io.ktor",
            artifact = "ktor-client-core",
            repoUrl = "https://repo1.maven.org/maven2"
        )

        assertNotNull(metadata)
        assertEquals("io.ktor", metadata?.group)
        assertEquals("ktor-client-core", metadata?.artifact)
        assertEquals("3.0.3", metadata?.latestRelease)
        assertEquals("3.0.3", metadata?.latestVersion)
        assertEquals(listOf("3.0.3", "3.0.0", "2.3.12", "2.0.0"), metadata?.versions)
        assertEquals("https://repo1.maven.org/maven2", metadata?.repositoryUrl)
    }

    @Test
    fun `resolves versions over HTTP from mock repository`() {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/io/ktor/ktor-client-core/maven-metadata.xml") { exchange ->
                val bytes = sampleKtorMetadataXml.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        val port = server!!.address.port
        val repoUrl = "http://localhost:$port"

        val client = DefaultMavenMetadataClient(
            defaultRepositories = listOf(repoUrl),
            cacheDir = tempDir,
            isOffline = false
        )

        val coord = MavenCoordinate("io.ktor", "ktor-client-core")
        val result = client.resolveVersions(coord)

        assertTrue(result is KotlinMcpResult.Success, "Expected success but got $result")
        val content = (result as KotlinMcpResult.Success).content
        assertTrue(content.contains("3.0.3"), "Expected 3.0.3 in content: $content")
        assertTrue(content.contains("2.3.12"), "Expected 2.3.12 in content: $content")
        assertEquals("3.0.3", result.metadata["latestRelease"])
        assertEquals("4", result.metadata["versionCount"])
    }

    @Test
    fun `fails over from primary repo to secondary repo on 404`() {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/primary/androidx/compose/ui/ui/maven-metadata.xml") { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.responseBody.close()
            }
            createContext("/secondary/androidx/compose/ui/ui/maven-metadata.xml") { exchange ->
                val bytes = sampleGoogleMavenMetadataXml.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        val port = server!!.address.port
        val primaryUrl = "http://localhost:$port/primary"
        val secondaryUrl = "http://localhost:$port/secondary"

        val client = DefaultMavenMetadataClient(
            defaultRepositories = listOf(primaryUrl, secondaryUrl),
            cacheDir = tempDir,
            isOffline = false
        )

        val coord = MavenCoordinate("androidx.compose.ui", "ui")
        val result = client.getLatestVersion(coord)

        assertTrue(result is KotlinMcpResult.Success, "Expected success on secondary repo: $result")
        val success = result as KotlinMcpResult.Success
        assertEquals("1.7.5", success.metadata["latestRelease"])
        assertEquals("1.8.0-alpha06", success.metadata["latestVersion"])
        assertEquals(secondaryUrl, success.metadata["repositoryUrl"])
    }

    @Test
    fun `serves cached metadata when offline mode is active`() {
        val cacheFile = File(tempDir, "io/ktor/ktor-client-core/maven-metadata.xml")
        cacheFile.parentFile.mkdirs()
        cacheFile.writeText(sampleKtorMetadataXml)

        val client = DefaultMavenMetadataClient(
            defaultRepositories = listOf("http://invalid-host-unreachable.example.com"),
            cacheDir = tempDir,
            isOffline = true
        )

        val coord = MavenCoordinate("io.ktor", "ktor-client-core")
        val result = client.resolveVersions(coord)

        assertTrue(result is KotlinMcpResult.Success, "Expected cache hit in offline mode: $result")
        val success = result as KotlinMcpResult.Success
        assertTrue(success.content.contains("3.0.3"))
        assertEquals("local-cache (offline)", success.metadata["source"])
    }

    @Test
    fun `returns OFFLINE_UNAVAILABLE when offline and cache is missing`() {
        val client = DefaultMavenMetadataClient(
            defaultRepositories = listOf("http://invalid-host-unreachable.example.com"),
            cacheDir = tempDir,
            isOffline = true
        )

        val coord = MavenCoordinate("nonexistent.group", "artifact")
        val result = client.resolveVersions(coord)

        assertTrue(result is KotlinMcpResult.Error)
        val error = result as KotlinMcpResult.Error
        assertEquals("OFFLINE_UNAVAILABLE", error.code)
    }

    @Test
    fun `returns NOT_FOUND when artifact does not exist in any repository`() {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/nonexistent/artifact/maven-metadata.xml") { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.responseBody.close()
            }
            start()
        }

        val port = server!!.address.port
        val repoUrl = "http://localhost:$port"

        val client = DefaultMavenMetadataClient(
            defaultRepositories = listOf(repoUrl),
            cacheDir = tempDir,
            isOffline = false
        )

        val coord = MavenCoordinate("nonexistent", "artifact")
        val result = client.resolveVersions(coord)

        assertTrue(result is KotlinMcpResult.Error)
        val error = result as KotlinMcpResult.Error
        assertEquals("NOT_FOUND", error.code)
    }

    @Test
    fun `returns INVALID_METADATA on malformed XML`() {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/bad/pkg/maven-metadata.xml") { exchange ->
                val bytes = "<metadata><unclosed>".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        val port = server!!.address.port
        val repoUrl = "http://localhost:$port"

        val client = DefaultMavenMetadataClient(
            defaultRepositories = listOf(repoUrl),
            cacheDir = tempDir,
            isOffline = false
        )

        val coord = MavenCoordinate("bad", "pkg")
        val result = client.resolveVersions(coord)

        assertTrue(result is KotlinMcpResult.Error)
        val error = result as KotlinMcpResult.Error
        assertEquals("INVALID_METADATA", error.code)
    }

    @Test
    fun `MavenCoordinate parses standard coordinate strings safely`() {
        val coord1 = MavenCoordinate.parse("io.ktor:ktor-client-core")
        assertNotNull(coord1)
        assertEquals("io.ktor", coord1?.group)
        assertEquals("ktor-client-core", coord1?.artifact)
        assertNull(coord1?.version)

        val coord2 = MavenCoordinate.parse("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        assertNotNull(coord2)
        assertEquals("org.jetbrains.kotlinx", coord2?.group)
        assertEquals("kotlinx-coroutines-core", coord2?.artifact)
        assertEquals("1.9.0", coord2?.version)

        val coord3 = MavenCoordinate.parse("  \"libs.ktor.client\"  ")
        assertNull(coord3)
    }
}
