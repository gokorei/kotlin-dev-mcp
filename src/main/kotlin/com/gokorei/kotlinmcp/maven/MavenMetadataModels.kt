package com.gokorei.kotlinmcp.maven

import kotlinx.serialization.Serializable

@Serializable
data class MavenCoordinate(
    val group: String,
    val artifact: String,
    val version: String? = null
) {
    fun toIdentifier(): String = if (version != null) "$group:$artifact:$version" else "$group:$artifact"

    companion object {
        fun parse(coordinateStr: String): MavenCoordinate? {
            val trimmed = coordinateStr.trim().removeSurrounding("\"", "'")
            val parts = trimmed.split(":")
            return when (parts.size) {
                2 -> {
                    val g = parts[0].trim()
                    val a = parts[1].trim()
                    if (g.isNotBlank() && a.isNotBlank()) MavenCoordinate(group = g, artifact = a) else null
                }
                3 -> {
                    val g = parts[0].trim()
                    val a = parts[1].trim()
                    val v = parts[2].trim()
                    if (g.isNotBlank() && a.isNotBlank() && v.isNotBlank()) MavenCoordinate(group = g, artifact = a, version = v) else null
                }
                else -> null
            }
        }
    }
}

@Serializable
data class VersionMetadata(
    val group: String,
    val artifact: String,
    val latestRelease: String?,
    val latestVersion: String?,
    val versions: List<String>,
    val repositoryUrl: String
)
