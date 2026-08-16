package com.gokorei.kotlinmcp.execution

import java.io.File

interface JavaResolver {
    fun resolve(javaPath: String?): File?
    fun validateJvmArgs(jvmArgs: List<String>): List<String>
}

class DefaultJavaResolver : JavaResolver {

    override fun resolve(javaPath: String?): File? {
        if (!javaPath.isNullOrBlank()) {
            val file = File(javaPath)
            return if (file.exists() && file.canExecute()) file else null
        }

        val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME")
        if (!javaHome.isNullOrBlank()) {
            val candidate = File(javaHome, "bin/java")
            if (candidate.exists() && candidate.canExecute()) return candidate
            val candidateExe = File(javaHome, "bin/java.exe")
            if (candidateExe.exists() && candidateExe.canExecute()) return candidateExe
        }

        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        for (dir in pathDirs) {
            val candidate = File(dir, "java")
            if (candidate.exists() && candidate.canExecute()) return candidate
            val candidateExe = File(dir, "java.exe")
            if (candidateExe.exists() && candidateExe.canExecute()) return candidateExe
        }

        return null
    }

    override fun validateJvmArgs(jvmArgs: List<String>): List<String> {
        val forbiddenPrefixes = listOf(
            "-javaagent:", "-agentlib:", "-agentpath:", "-Xbootclasspath",
            "--add-opens", "--add-exports", "--add-reads", "--patch-module",
            "--allow-attach-self", "-Djdk.attach.allowAttachSelf"
        )
        val violations = mutableListOf<String>()
        for (arg in jvmArgs) {
            val trimmed = arg.trim()
            if (forbiddenPrefixes.any { trimmed.startsWith(it, ignoreCase = true) || trimmed.contains("allow-attach-self", ignoreCase = true) }) {
                violations.add(trimmed)
            }
        }
        return violations
    }
}
