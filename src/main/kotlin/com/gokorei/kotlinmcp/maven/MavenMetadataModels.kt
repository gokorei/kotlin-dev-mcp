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
            fun isValid(segment: String): Boolean =
                segment.isNotBlank() && !segment.contains('/') && !segment.contains('\\') && !segment.contains("..")

            return when (parts.size) {
                2 -> {
                    val g = parts[0].trim()
                    val a = parts[1].trim()
                    if (isValid(g) && isValid(a)) MavenCoordinate(group = g, artifact = a) else null
                }
                3 -> {
                    val g = parts[0].trim()
                    val a = parts[1].trim()
                    val v = parts[2].trim()
                    if (isValid(g) && isValid(a) && isValid(v)) MavenCoordinate(group = g, artifact = a, version = v) else null
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
