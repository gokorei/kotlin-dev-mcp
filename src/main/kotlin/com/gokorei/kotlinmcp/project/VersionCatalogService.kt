package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.maven.DefaultMavenMetadataClient
import com.gokorei.kotlinmcp.maven.MavenCoordinate
import com.gokorei.kotlinmcp.maven.MavenMetadataClient
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

data class CatalogLibraryEntry(
    val alias: String,
    val group: String? = null,
    val name: String? = null,
    val module: String? = null,
    val version: String? = null,
    val versionRef: String? = null
) {
    val resolvedCoordinate: MavenCoordinate?
        get() {
            return when {
                module != null -> MavenCoordinate.parse(module)
                group != null && name != null -> MavenCoordinate(group, name)
                else -> null
            }
        }
}

data class VersionCatalogModel(
    val versions: Map<String, String> = emptyMap(),
    val libraries: Map<String, CatalogLibraryEntry> = emptyMap(),
    val plugins: Map<String, String> = emptyMap()
)

interface VersionCatalogService {
    fun parseCatalog(projectPath: String): VersionCatalogModel
    fun updateVersion(projectPath: String, versionRef: String, newVersion: String): KotlinMcpResult
    fun updateLibraryVersion(projectPath: String, alias: String, newVersion: String): KotlinMcpResult
    fun addLibrary(projectPath: String, alias: String, module: String, version: String? = null, versionRef: String? = null): KotlinMcpResult
    fun checkCatalogUpdates(projectPath: String): KotlinMcpResult
}

class DefaultVersionCatalogService(
    private val metadataClient: MavenMetadataClient = DefaultMavenMetadataClient()
) : VersionCatalogService {

    private val logger = KotlinLogging.logger {}

    private fun getTomlFile(projectPath: String): File {
        val root = File(projectPath)
        return if (root.name == "libs.versions.toml" && root.isFile) {
            root
        } else {
            File(root, "gradle/libs.versions.toml")
        }
    }

    override fun parseCatalog(projectPath: String): VersionCatalogModel {
        val file = getTomlFile(projectPath)
        if (!file.exists()) return VersionCatalogModel()

        val versions = mutableMapOf<String, String>()
        val libraries = mutableMapOf<String, CatalogLibraryEntry>()
        val plugins = mutableMapOf<String, String>()
        var currentSection = ""

        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.isBlank()) return@forEach

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.removeSurrounding("[", "]").trim().lowercase()
                return@forEach
            }

            if (currentSection == "versions" && "=" in trimmed) {
                val key = trimmed.substringBefore("=").trim()
                val value = trimmed.substringAfter("=").trim().trim('"', '\'')
                versions[key] = value
            } else if (currentSection == "libraries" && "=" in trimmed) {
                val rawAlias = trimmed.substringBefore("=").trim()
                val rhs = trimmed.substringAfter("=").trim()

                if (rhs.startsWith("{") && rhs.endsWith("}")) {
                    val body = rhs.removeSurrounding("{", "}").trim()
                    val kvMap = mutableMapOf<String, String>()
                    body.split(",").forEach { part ->
                        if ("=" in part) {
                            val k = part.substringBefore("=").trim()
                            val v = part.substringAfter("=").trim().trim('"', '\'')
                            kvMap[k] = v
                        }
                    }
                    val group = kvMap["group"]
                    val name = kvMap["name"]
                    val module = kvMap["module"]
                    val versionRef = kvMap["version.ref"]
                    val version = kvMap["version"] ?: versionRef?.let { versions[it] }

                    libraries[rawAlias] = CatalogLibraryEntry(
                        alias = rawAlias,
                        group = group,
                        name = name,
                        module = module,
                        version = version,
                        versionRef = versionRef
                    )
                } else if (rhs.startsWith("\"") || rhs.startsWith("'")) {
                    val coord = rhs.trim('"', '\'')
                    libraries[rawAlias] = CatalogLibraryEntry(
                        alias = rawAlias,
                        module = coord,
                        version = coord.substringAfterLast(":", "")
                    )
                }
            } else if (currentSection == "plugins" && "=" in trimmed) {
                val rawAlias = trimmed.substringBefore("=").trim()
                plugins[rawAlias] = trimmed.substringAfter("=").trim()
            }
        }

        return VersionCatalogModel(versions, libraries, plugins)
    }

    override fun updateVersion(projectPath: String, versionRef: String, newVersion: String): KotlinMcpResult {
        val file = getTomlFile(projectPath)
        if (!file.exists()) {
            return KotlinMcpResult.Error(
                code = "FILE_NOT_FOUND",
                message = "Version catalog not found at ${file.path}"
            )
        }

        val lines = file.readLines().toMutableList()
        var currentSection = ""
        var updated = false

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.removeSurrounding("[", "]").trim().lowercase()
                continue
            }

            if (currentSection == "versions" && "=" in trimmed) {
                val key = trimmed.substringBefore("=").trim()
                if (key == versionRef) {
                    val comment = if ("#" in lines[i]) " #" + lines[i].substringAfter("#") else ""
                    lines[i] = "$versionRef = \"$newVersion\"$comment"
                    updated = true
                    break
                }
            }
        }

        if (!updated) {
            return KotlinMcpResult.Error(
                code = "VERSION_NOT_FOUND",
                message = "Version reference '$versionRef' not found in [versions] section of ${file.path}."
            )
        }

        file.writeText(lines.joinToString("\n") + "\n")
        return KotlinMcpResult.Success(
            content = "Updated version reference `$versionRef` to `$newVersion` in `${file.path}`.",
            metadata = mapOf("versionRef" to versionRef, "newVersion" to newVersion)
        )
    }

    override fun updateLibraryVersion(projectPath: String, alias: String, newVersion: String): KotlinMcpResult {
        val file = getTomlFile(projectPath)
        if (!file.exists()) {
            return KotlinMcpResult.Error(
                code = "FILE_NOT_FOUND",
                message = "Version catalog not found at ${file.path}"
            )
        }

        val catalog = parseCatalog(projectPath)
        val entry = catalog.libraries[alias] ?: catalog.libraries[alias.replace(".", "-")]
            ?: return KotlinMcpResult.Error(
                code = "LIBRARY_NOT_FOUND",
                message = "Library '$alias' not found in [libraries] section of ${file.path}."
            )

        if (entry.versionRef != null) {
            return updateVersion(projectPath, entry.versionRef, newVersion)
        }

        val lines = file.readLines().toMutableList()
        var currentSection = ""
        var updated = false

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.removeSurrounding("[", "]").trim().lowercase()
                continue
            }

            if (currentSection == "libraries" && "=" in trimmed) {
                val key = trimmed.substringBefore("=").trim()
                if (key == alias || key == alias.replace(".", "-")) {
                    val rhs = trimmed.substringAfter("=").trim()
                    if (rhs.startsWith("{") && "version" in rhs) {
                        val replaced = rhs.replace(Regex("""version\s*=\s*"[^"]+""""), "version = \"$newVersion\"")
                        lines[i] = "$key = $replaced"
                        updated = true
                        break
                    }
                }
            }
        }

        if (updated) {
            file.writeText(lines.joinToString("\n") + "\n")
            return KotlinMcpResult.Success(
                content = "Updated library `$alias` inline version to `$newVersion` in `${file.path}`.",
                metadata = mapOf("alias" to alias, "newVersion" to newVersion)
            )
        }

        return KotlinMcpResult.Error(
            code = "UPDATE_FAILED",
            message = "Could not update inline version for library '$alias'."
        )
    }

    override fun addLibrary(
        projectPath: String,
        alias: String,
        module: String,
        version: String?,
        versionRef: String?
    ): KotlinMcpResult {
        val file = getTomlFile(projectPath)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText("[versions]\n\n[libraries]\n")
        }

        val lines = file.readLines().toMutableList()
        var libIndex = -1

        for (i in lines.indices) {
            if (lines[i].trim() == "[libraries]") {
                libIndex = i
                break
            }
        }

        val versionClause = when {
            versionRef != null -> "version.ref = \"$versionRef\""
            version != null -> "version = \"$version\""
            else -> ""
        }

        val entryLine = if (versionClause.isNotBlank()) {
            "$alias = { module = \"$module\", $versionClause }"
        } else {
            "$alias = { module = \"$module\" }"
        }

        if (libIndex != -1) {
            lines.add(libIndex + 1, entryLine)
        } else {
            lines.add("")
            lines.add("[libraries]")
            lines.add(entryLine)
        }

        file.writeText(lines.joinToString("\n") + "\n")
        return KotlinMcpResult.Success(
            content = "Added library `$alias` (`$module`) to `${file.path}`.",
            metadata = mapOf("alias" to alias, "module" to module)
        )
    }

    override fun checkCatalogUpdates(projectPath: String): KotlinMcpResult {
        val file = getTomlFile(projectPath)
        if (!file.exists()) {
            return KotlinMcpResult.Error(
                code = "FILE_NOT_FOUND",
                message = "Version catalog not found at ${file.path}"
            )
        }

        val catalog = parseCatalog(projectPath)
        if (catalog.libraries.isEmpty()) {
            return KotlinMcpResult.Success(
                content = "# Version Catalog Dependency Audit\nNo libraries found in `${file.path}`."
            )
        }

        data class UpdateCandidate(
            val alias: String,
            val coordinate: String,
            val currentVersion: String,
            val latestRelease: String,
            val isOutdated: Boolean
        )

        val candidates = mutableListOf<UpdateCandidate>()

        catalog.libraries.values.forEach { lib ->
            val coord = lib.resolvedCoordinate ?: return@forEach
            val currentVersion = lib.version ?: (lib.versionRef?.let { catalog.versions[it] }) ?: "unknown"

            val latestResult = metadataClient.getLatestVersion(coord)
            if (latestResult is KotlinMcpResult.Success) {
                val latestRelease = latestResult.metadata["latestRelease"] ?: latestResult.metadata["version"] ?: currentVersion
                val isOutdated = currentVersion != "unknown" && latestRelease != currentVersion &&
                    DefaultMavenMetadataClient.mavenVersionCompare(currentVersion, latestRelease) < 0

                candidates.add(
                    UpdateCandidate(
                        alias = lib.alias,
                        coordinate = "${coord.group}:${coord.artifact}",
                        currentVersion = currentVersion,
                        latestRelease = latestRelease,
                        isOutdated = isOutdated
                    )
                )
            }
        }

        val outdated = candidates.filter { it.isOutdated }
        val upToDate = candidates.filter { !it.isOutdated }

        val content = buildString {
            appendLine("# Version Catalog Update Check (${file.name})")
            appendLine("Scanned ${candidates.size} library declarations.")
            appendLine()

            if (outdated.isNotEmpty()) {
                appendLine("## ⬆️ Updates Available (${outdated.size})")
                outdated.forEach {
                    appendLine("- **`${it.alias}`** (`${it.coordinate}`): `${it.currentVersion}` → **`${it.latestRelease}`**")
                }
                appendLine()
            } else {
                appendLine("## ✅ All Libraries Up-to-Date")
                appendLine("All ${candidates.size} checked libraries are on their latest release.")
                appendLine()
            }

            if (upToDate.isNotEmpty()) {
                appendLine("## Current / Up-to-Date (${upToDate.size})")
                upToDate.forEach {
                    appendLine(" - `${it.alias}` (`${it.coordinate}`: `${it.currentVersion}`)")
                }
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf(
                "totalChecked" to candidates.size.toString(),
                "outdatedCount" to outdated.size.toString()
            )
        )
    }
}
