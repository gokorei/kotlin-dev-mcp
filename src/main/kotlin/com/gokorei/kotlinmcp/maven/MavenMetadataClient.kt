package com.gokorei.kotlinmcp.maven

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import io.github.oshai.kotlinlogging.KotlinLogging
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory

interface MavenMetadataClient {
    fun resolveVersions(coordinate: MavenCoordinate, customRepoUrl: String? = null): KotlinMcpResult
    fun getLatestVersion(coordinate: MavenCoordinate, customRepoUrl: String? = null): KotlinMcpResult
}

class DefaultMavenMetadataClient(
    val defaultRepositories: List<String> = listOf(
        "https://repo1.maven.org/maven2",
        "https://dl.google.com/dl/android/maven2"
    ),
    val cacheDir: File = File(System.getProperty("user.home"), ".cache/kotlin-mcp/metadata"),
    val cacheTtlMillis: Long = 24 * 60 * 60 * 1000L,
    val isOffline: Boolean? = null
) : MavenMetadataClient {

    private val logger = KotlinLogging.logger {}
    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(4000))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private fun checkOffline(): Boolean {
        if (isOffline != null) return isOffline
        val envOffline = System.getenv("KOTLIN_MCP_OFFLINE")?.equals("true", ignoreCase = true) == true
        val propOffline = System.getProperty("kmcp.offline")?.equals("true", ignoreCase = true) == true
        val disableAudits = System.getProperty("kmcp.disable_network_audits")?.equals("true", ignoreCase = true) == true
        return envOffline || propOffline || disableAudits
    }

    private sealed class FetchResult {
        data class Success(val metadata: VersionMetadata, val source: String) : FetchResult()
        data class Error(val code: String, val message: String) : FetchResult()
    }

    override fun resolveVersions(coordinate: MavenCoordinate, customRepoUrl: String?): KotlinMcpResult {
        val result = fetchOrReadMetadata(coordinate, customRepoUrl)
        val (metadata, source) = when (result) {
            is FetchResult.Success -> result.metadata to result.source
            is FetchResult.Error -> return KotlinMcpResult.Error(code = result.code, message = result.message)
            null -> {
                return if (checkOffline()) {
                    KotlinMcpResult.Error(
                        code = "OFFLINE_UNAVAILABLE",
                        message = "Offline mode active and no cached metadata available for '${coordinate.toIdentifier()}'."
                    )
                } else {
                    KotlinMcpResult.Error(
                        code = "NOT_FOUND",
                        message = "No Maven metadata found for '${coordinate.toIdentifier()}' across checked repositories."
                    )
                }
            }
        }

        val output = buildString {
            appendLine("# Maven Version Metadata for `${metadata.group}:${metadata.artifact}`")
            appendLine("- Repository: `${metadata.repositoryUrl}`")
            if (!metadata.latestRelease.isNullOrBlank()) {
                appendLine("- Latest Release: `${metadata.latestRelease}`")
            }
            if (!metadata.latestVersion.isNullOrBlank() && metadata.latestVersion != metadata.latestRelease) {
                appendLine("- Latest Version: `${metadata.latestVersion}`")
            }
            appendLine("- Available Versions (${metadata.versions.size}):")
            metadata.versions.forEach { appendLine("  - `$it`") }
        }

        return KotlinMcpResult.Success(
            content = output,
            metadata = mapOf(
                "group" to metadata.group,
                "artifact" to metadata.artifact,
                "latestRelease" to (metadata.latestRelease ?: ""),
                "latestVersion" to (metadata.latestVersion ?: ""),
                "versionCount" to metadata.versions.size.toString(),
                "repositoryUrl" to metadata.repositoryUrl,
                "source" to source
            )
        )
    }

    override fun getLatestVersion(coordinate: MavenCoordinate, customRepoUrl: String?): KotlinMcpResult {
        val result = fetchOrReadMetadata(coordinate, customRepoUrl)
        val (metadata, source) = when (result) {
            is FetchResult.Success -> result.metadata to result.source
            is FetchResult.Error -> return KotlinMcpResult.Error(code = result.code, message = result.message)
            null -> {
                return if (checkOffline()) {
                    KotlinMcpResult.Error(
                        code = "OFFLINE_UNAVAILABLE",
                        message = "Offline mode active and no cached metadata available for '${coordinate.toIdentifier()}'."
                    )
                } else {
                    KotlinMcpResult.Error(
                        code = "NOT_FOUND",
                        message = "No Maven metadata found for '${coordinate.toIdentifier()}' across checked repositories."
                    )
                }
            }
        }

        val chosenVersion = metadata.latestRelease ?: metadata.latestVersion ?: metadata.versions.firstOrNull()
            ?: return KotlinMcpResult.Error(
                code = "NO_VERSIONS",
                message = "No versions found in metadata for '${coordinate.toIdentifier()}'."
            )

        val output = buildString {
            appendLine("# Latest Version for `${metadata.group}:${metadata.artifact}`")
            appendLine("- Coordinate: `${metadata.group}:${metadata.artifact}:$chosenVersion`")
            if (!metadata.latestRelease.isNullOrBlank()) {
                appendLine("- Latest Release: `${metadata.latestRelease}`")
            }
            if (!metadata.latestVersion.isNullOrBlank() && metadata.latestVersion != metadata.latestRelease) {
                appendLine("- Latest Pre-release/Snapshot: `${metadata.latestVersion}`")
            }
            appendLine("- Repository: `${metadata.repositoryUrl}`")
        }

        return KotlinMcpResult.Success(
            content = output,
            metadata = mapOf(
                "group" to metadata.group,
                "artifact" to metadata.artifact,
                "version" to chosenVersion,
                "latestRelease" to (metadata.latestRelease ?: ""),
                "latestVersion" to (metadata.latestVersion ?: ""),
                "repositoryUrl" to metadata.repositoryUrl,
                "source" to source
            )
        )
    }

    private fun getCacheFile(coordinate: MavenCoordinate): File {
        val groupRel = coordinate.group.replace('.', File.separatorChar)
        val dir = File(cacheDir, groupRel + File.separatorChar + coordinate.artifact)
        return File(dir, "maven-metadata.xml")
    }

    private fun fetchOrReadMetadata(
        coordinate: MavenCoordinate,
        customRepoUrl: String?
    ): FetchResult? {
        val cacheFile = getCacheFile(coordinate)
        val offline = checkOffline()

        // 1. Try cache if valid or offline
        if (cacheFile.exists()) {
            val isFresh = (System.currentTimeMillis() - cacheFile.lastModified()) < cacheTtlMillis
            if (offline || isFresh) {
                val text = runCatching { cacheFile.readText() }.getOrNull()
                if (!text.isNullOrBlank()) {
                    val parsed = parseMetadataXml(text, coordinate.group, coordinate.artifact, "local-cache")
                    if (parsed != null) {
                        return FetchResult.Success(parsed, if (offline) "local-cache (offline)" else "local-cache")
                    }
                }
            }
        }

        if (offline) {
            return null
        }

        var lastError: FetchResult.Error? = null

        // 2. Query remote repositories
        val repos = if (!customRepoUrl.isNullOrBlank()) listOf(customRepoUrl) else defaultRepositories

        for (repo in repos) {
            val cleanBase = repo.trimEnd('/')
            val groupPath = coordinate.group.replace('.', '/')
            val metadataUrl = "$cleanBase/$groupPath/${coordinate.artifact}/maven-metadata.xml"

            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI(metadataUrl))
                    .timeout(Duration.ofMillis(6000))
                    .header("User-Agent", "kotlin-mcp/1.2.0")
                    .GET()
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) {
                    val body = response.body()
                    val parsed = parseMetadataXml(body, coordinate.group, coordinate.artifact, repo)
                    if (parsed != null) {
                        // Persist to cache
                        runCatching {
                            cacheFile.parentFile.mkdirs()
                            cacheFile.writeText(body)
                        }.onFailure { e ->
                            logger.warn(e) { "Failed to write maven metadata cache to ${cacheFile.path}" }
                        }
                        return FetchResult.Success(parsed, repo)
                    } else {
                        lastError = FetchResult.Error(
                            code = "INVALID_METADATA",
                            message = "Malformed or unparseable maven-metadata.xml received from $metadataUrl."
                        )
                    }
                } else if (response.statusCode() != 404) {
                    logger.debug { "HTTP ${response.statusCode()} from $metadataUrl" }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch metadata from $metadataUrl: ${e.message}" }
            }
        }

        // 3. Fallback to stale cache if available
        if (cacheFile.exists()) {
            val text = runCatching { cacheFile.readText() }.getOrNull()
            if (!text.isNullOrBlank()) {
                val parsed = parseMetadataXml(text, coordinate.group, coordinate.artifact, "stale-cache")
                if (parsed != null) return FetchResult.Success(parsed, "stale-cache")
            }
        }

        return lastError
    }

    fun parseMetadataXml(
        xmlContent: String,
        group: String,
        artifact: String,
        repoUrl: String
    ): VersionMetadata? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            // Secure XML configuration
            factory.isNamespaceAware = false
            factory.isValidating = false
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)

            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xmlContent.toByteArray(Charsets.UTF_8)))
            doc.documentElement.normalize()

            val root = doc.documentElement
            if (root.nodeName != "metadata") return null

            var latestRelease: String? = null
            var latestVersion: String? = null
            val versionsList = mutableListOf<String>()

            val versioningNodes = root.getElementsByTagName("versioning")
            if (versioningNodes.length > 0) {
                val versioningElem = versioningNodes.item(0) as? Element
                if (versioningElem != null) {
                    latestRelease = getDirectChildText(versioningElem, "release")
                    latestVersion = getDirectChildText(versioningElem, "latest")

                    val versionsElements = versioningElem.getElementsByTagName("versions")
                    if (versionsElements.length > 0) {
                        val versionsParent = versionsElements.item(0) as? Element
                        if (versionsParent != null) {
                            val vList = versionsParent.getElementsByTagName("version")
                            for (i in 0 until vList.length) {
                                val vNode = vList.item(i)
                                val text = vNode.textContent?.trim()
                                if (!text.isNullOrBlank()) {
                                    versionsList.add(text)
                                }
                            }
                        }
                    }
                }
            }

            val sortedVersions = versionsList.distinct().sortedWith { a, b ->
                mavenVersionCompare(b, a) // descending order
            }

            VersionMetadata(
                group = group,
                artifact = artifact,
                latestRelease = latestRelease ?: sortedVersions.firstOrNull { !isPreRelease(it) } ?: sortedVersions.firstOrNull(),
                latestVersion = latestVersion ?: sortedVersions.firstOrNull(),
                versions = sortedVersions,
                repositoryUrl = repoUrl
            )
        } catch (e: Exception) {
            logger.warn(e) { "Error parsing maven-metadata.xml for $group:$artifact: ${e.message}" }
            null
        }
    }

    private fun getDirectChildText(parent: Element, tagName: String): String? {
        val list = parent.getElementsByTagName(tagName)
        if (list.length > 0) {
            return list.item(0).textContent?.trim()?.takeIf { it.isNotBlank() }
        }
        return null
    }

    companion object {
        fun isPreRelease(v: String): Boolean {
            val lower = v.lowercase()
            return lower.contains("alpha") || lower.contains("beta") || lower.contains("rc") ||
                lower.contains("m") || lower.contains("dev") || lower.contains("snapshot")
        }

        fun mavenVersionCompare(a: String, b: String): Int {
            fun tokens(v: String): List<Any> {
                val out = mutableListOf<Any>()
                val sb = StringBuilder()
                var lastWasDigit: Boolean? = null
                for (c in v) {
                    val isDigit = c.isDigit()
                    when {
                        c == '.' || c == '-' || c == '_' -> {
                            if (sb.isNotEmpty()) {
                                out.add(if (sb[0].isDigit()) sb.toString().toIntOrNull() ?: sb.toString() else sb.toString())
                                sb.setLength(0)
                            }
                            lastWasDigit = null
                        }
                        lastWasDigit != null && isDigit != lastWasDigit -> {
                            if (sb.isNotEmpty()) {
                                out.add(if (sb[0].isDigit()) sb.toString().toIntOrNull() ?: sb.toString() else sb.toString())
                                sb.setLength(0)
                            }
                            sb.append(c)
                            lastWasDigit = isDigit
                        }
                        else -> {
                            sb.append(c)
                            lastWasDigit = isDigit
                        }
                    }
                }
                if (sb.isNotEmpty()) {
                    out.add(if (sb[0].isDigit()) sb.toString().toIntOrNull() ?: sb.toString() else sb.toString())
                }
                return out
            }

            val at = tokens(a)
            val bt = tokens(b)
            fun qualifierRank(t: Any?): Int = when (t.toString().lowercase()) {
                "final", "release", "ga", "sp" -> 4
                "rc", "m" -> 3
                "beta", "b" -> 2
                "alpha", "a" -> 1
                "dev" -> 1
                "snapshot" -> 0
                else -> 2
            }

            val n = maxOf(at.size, bt.size)
            for (i in 0 until n) {
                val xo = at.getOrNull(i)
                val yo = bt.getOrNull(i)
                val cmp = when {
                    xo == null && yo == null -> 0
                    xo is Int && yo == null -> xo.compareTo(0)
                    yo is Int && xo == null -> 0.compareTo(yo)
                    xo is String && yo == null -> qualifierRank(xo).compareTo(4)
                    yo is String && xo == null -> 4.compareTo(qualifierRank(yo))
                    xo is Int && yo is Int -> xo.compareTo(yo)
                    xo is Int -> 1
                    yo is Int -> -1
                    else -> qualifierRank(xo).compareTo(qualifierRank(yo))
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}
