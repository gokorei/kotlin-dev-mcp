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

    private fun stripTrailingComment(line: String): String {
        var inSingleQuote = false
        var inDoubleQuote = false
        var escape = false
        for (i in line.indices) {
            val c = line[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
            } else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
            } else if (c == '#' && !inSingleQuote && !inDoubleQuote) {
                return line.substring(0, i).trim()
            }
        }
        return line.trim()
    }

    override fun parseCatalog(projectPath: String): VersionCatalogModel {
        val file = getTomlFile(projectPath)
        if (!file.exists()) return VersionCatalogModel()

        val lines = runCatching { file.readLines() }.getOrNull() ?: return VersionCatalogModel()
        val versions = mutableMapOf<String, String>()
        val libraries = mutableMapOf<String, CatalogLibraryEntry>()
        val plugins = mutableMapOf<String, String>()
        var currentSection = ""

        lines.forEach { rawLine ->
            val trimmed = stripTrailingComment(rawLine)
            if (trimmed.isBlank()) return@forEach

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

        val lines = runCatching { file.readLines().toMutableList() }.getOrElse { e ->
            return KotlinMcpResult.Error(
                code = "IO_ERROR",
                message = "Failed to read version catalog from ${file.path}: ${e.message}"
            )
        }

        var currentSection = ""
        var updated = false

        for (i in lines.indices) {
            val raw = lines[i]
            val trimmed = stripTrailingComment(raw)
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.removeSurrounding("[", "]").trim().lowercase()
                continue
            }

            if (currentSection == "versions" && "=" in trimmed) {
                val key = trimmed.substringBefore("=").trim()
                if (key == versionRef) {
                    val comment = if ("#" in raw) " #" + raw.substringAfter("#") else ""
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

        runCatching { file.writeText(lines.joinToString("\n") + "\n") }.onFailure { e ->
            return KotlinMcpResult.Error(
                code = "IO_ERROR",
                message = "Failed to write version catalog to ${file.path}: ${e.message}"
            )
        }

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

        val lines = runCatching { file.readLines().toMutableList() }.getOrElse { e ->
            return KotlinMcpResult.Error(
                code = "IO_ERROR",
                message = "Failed to read version catalog from ${file.path}: ${e.message}"
            )
        }

        var currentSection = ""
        var updated = false

        for (i in lines.indices) {
            val raw = lines[i]
            val trimmed = stripTrailingComment(raw)
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.removeSurrounding("[", "]").trim().lowercase()
                continue
            }

            if (currentSection == "libraries" && "=" in trimmed) {
                val key = trimmed.substringBefore("=").trim()
                if (key == alias || key == alias.replace(".", "-")) {
                    val rhs = trimmed.substringAfter("=").trim()
                    if (rhs.startsWith("{") && "version" in rhs) {
                        val comment = if ("#" in raw) " #" + raw.substringAfter("#") else ""
                        val replaced = rhs.replace(Regex("""version\s*=\s*"[^"]+""""), "version = \"$newVersion\"")
                        lines[i] = "$key = $replaced$comment"
                        updated = true
                        break
                    }
                }
            }
        }

        if (updated) {
            runCatching { file.writeText(lines.joinToString("\n") + "\n") }.onFailure { e ->
                return KotlinMcpResult.Error(
                    code = "IO_ERROR",
                    message = "Failed to write version catalog to ${file.path}: ${e.message}"
                )
            }
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
        val existingCatalog = if (file.exists()) parseCatalog(projectPath) else VersionCatalogModel()

        if (existingCatalog.libraries.containsKey(alias) ||
            existingCatalog.libraries.containsKey(alias.replace('.', '-')) ||
            existingCatalog.libraries.containsKey(alias.replace('-', '.'))) {
            return KotlinMcpResult.Error(
                code = "LIBRARY_ALREADY_EXISTS",
                message = "Library alias '$alias' is already declared in ${file.path}."
            )
        }

        if (!file.exists()) {
            file.parentFile?.mkdirs()
            runCatching { file.writeText("[versions]\n\n[libraries]\n") }.onFailure { e ->
                return KotlinMcpResult.Error(
                    code = "IO_ERROR",
                    message = "Failed to initialize version catalog file at ${file.path}: ${e.message}"
                )
            }
        }

        val lines = runCatching { file.readLines().toMutableList() }.getOrElse { e ->
            return KotlinMcpResult.Error(
                code = "IO_ERROR",
                message = "Failed to read version catalog from ${file.path}: ${e.message}"
            )
        }

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

        runCatching { file.writeText(lines.joinToString("\n") + "\n") }.onFailure { e ->
            return KotlinMcpResult.Error(
                code = "IO_ERROR",
                message = "Failed to write version catalog to ${file.path}: ${e.message}"
            )
        }

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
        val skipped = mutableListOf<String>()

        catalog.libraries.values.forEach { lib ->
            val coord = lib.resolvedCoordinate
            if (coord == null) {
                skipped.add("`${lib.alias}`: could not parse module coordinates")
                return@forEach
            }
            val currentVersion = lib.version ?: (lib.versionRef?.let { catalog.versions[it] }) ?: "unknown"

            val latestResult = metadataClient.getLatestVersion(coord)
            if (latestResult is KotlinMcpResult.Success) {
                val rawRelease = latestResult.metadata["latestRelease"]?.takeIf { it.isNotBlank() }
                val rawVersion = latestResult.metadata["version"]?.takeIf { it.isNotBlank() }
                val latestRelease = rawRelease ?: rawVersion ?: currentVersion
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
            } else {
                skipped.add("`${lib.alias}` (${coord.toIdentifier()}): lookup failed")
            }
        }

        val outdated = candidates.filter { it.isOutdated }
        val upToDate = candidates.filter { !it.isOutdated }

        val content = buildString {
            appendLine("# Version Catalog Update Check (${file.name})")
            appendLine("Scanned ${candidates.size + skipped.size} library declaration(s).")
            appendLine()

            if (outdated.isNotEmpty()) {
                appendLine("## ⬆️ Updates Available (${outdated.size})")
                outdated.forEach {
                    appendLine("- **`${it.alias}`** (`${it.coordinate}`): `${it.currentVersion}` → **`${it.latestRelease}`**")
                }
                appendLine()
            } else {
                appendLine("## ✅ All Checked Libraries Up-to-Date")
                appendLine("All ${candidates.size} checked libraries are on their latest release.")
                appendLine()
            }

            if (upToDate.isNotEmpty()) {
                appendLine("## Current / Up-to-Date (${upToDate.size})")
                upToDate.forEach {
                    appendLine(" - `${it.alias}` (`${it.coordinate}`: `${it.currentVersion}`)")
                }
                appendLine()
            }

            if (skipped.isNotEmpty()) {
                appendLine("## ⚠️ Skipped / Unresolved (${skipped.size})")
                skipped.forEach {
                    appendLine(" - $it")
                }
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf(
                "totalChecked" to candidates.size.toString(),
                "outdatedCount" to outdated.size.toString(),
                "skippedCount" to skipped.size.toString()
            )
        )
    }
}
