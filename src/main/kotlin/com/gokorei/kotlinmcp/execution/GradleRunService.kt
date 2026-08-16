package com.gokorei.kotlinmcp.execution

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executes a Gradle task against a real project on disk and returns the result.
 * This closes the compile/test loop for an actual project instead of handing it
 * back to the caller's shell: the analog of `kotlin_run_snippet`, but for builds.
 */
interface GradleRunService {
    fun execute(projectPath: String, task: String, timeoutMillis: Long): KotlinMcpResult
}

class DefaultGradleRunService : GradleRunService {

    override fun execute(projectPath: String, task: String, timeoutMillis: Long): KotlinMcpResult {
        val root = File(projectPath)
        if (!root.exists() || !root.isDirectory) {
            return KotlinMcpResult.Error(
                message = "Project path does not exist or is not a directory: '$projectPath'",
                code = "PROJECT_NOT_FOUND"
            )
        }
        val trimmedTask = task.trim()
        if (trimmedTask.isEmpty()) {
            return KotlinMcpResult.Error(
                message = "Empty Gradle task — provide a task such as 'build', 'test', or 'compileKotlin'.",
                code = "EMPTY_TASK"
            )
        }

        val tokens = splitTaskTokens(trimmedTask)
        var prev: String? = null
        for (token in tokens) {
            if (!isAllowedToken(token, prev)) {
                return KotlinMcpResult.Error(
                    message = "Task spec contains a rejected argument '$token'. Only task names (e.g. 'build', ':app:compileKotlin') and '--tests 'quoted'' filters are allowed; Gradle flags such as --init-script, -I, -p, -D, -P, --build-file are not accepted.",
                    code = "VALIDATION_ERROR",
                    details = mapOf("task" to trimmedTask, "rejectedToken" to token)
                )
            }
            prev = token
        }

        val launcher = resolveLauncher(root)
            ?: return KotlinMcpResult.Error(
                message = "No Gradle launcher found: no `gradlew` in '$projectPath' and no `gradle` on PATH.",
                code = "GRADLE_NOT_FOUND"
            )

        val command = listOf(launcher, "--no-daemon") + tokens
        return try {
            val process = ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
                .start()

            val output = ByteArrayOutputStream()
            val readerThread = Thread {
                output.write(process.inputStream.readBytes())
            }.apply {
                isDaemon = true
                start()
            }

            val startNanos = System.nanoTime()
            val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000

            if (!completed) {
                val pid = runCatching { process.pid() }.getOrNull()
                val isWindows = System.getProperty("os.name").lowercase().contains("win")

                process.toHandle().descendants().forEach { runCatching { it.destroyForcibly() } }
                process.destroyForcibly()

                if (pid != null && pid > 0) {
                    if (isWindows) {
                        runCatching { ProcessBuilder("taskkill", "/F", "/T", "/PID", pid.toString()).start().waitFor() }
                    } else {
                        runCatching { ProcessBuilder("pkill", "-9", "-P", pid.toString()).start().waitFor() }
                        runCatching { ProcessBuilder("kill", "-9", "-$pid").start().waitFor() }
                    }
                }
                readerThread.join(1000)
                KotlinMcpResult.Error(
                    message = "Gradle task '$trimmedTask' timed out after ${timeoutMillis}ms; process tree destroyed.",
                    code = "EXECUTION_TIMEOUT",
                    details = mapOf("timeoutMillis" to timeoutMillis.toString(), "task" to trimmedTask)
                )
            } else {
                readerThread.join(2000)
                val text = String(output.toByteArray(), Charsets.UTF_8)
                val exit = process.exitValue()
                if (exit == 0) {
                    KotlinMcpResult.Success(
                        content = "✅ Gradle task '$trimmedTask' succeeded (exit 0).\n\n${tail(text)}".trim(),
                        metadata = mapOf(
                            "task" to trimmedTask,
                            "exitCode" to "0",
                            "durationMs" to durationMs.toString(),
                            "projectPath" to root.absolutePath
                        )
                    )
                } else {
                    KotlinMcpResult.Error(
                        message = "Gradle task '$trimmedTask' failed (exit $exit):\n${extractDiagnostics(text)}",
                        code = "GRADLE_BUILD_FAILED",
                        details = mapOf(
                            "exitCode" to exit.toString(),
                            "durationMs" to durationMs.toString(),
                            "task" to trimmedTask
                        ),
                        requireAnotherCall = true
                    )
                }
            }
        } catch (e: Exception) {
            KotlinMcpResult.Error(
                message = "Failed to launch Gradle: ${e.message}",
                code = "LAUNCH_ERROR"
            )
        }
    }

    private fun splitTaskTokens(spec: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in spec) {
            when {
                quote != null -> {
                    if (c == quote) quote = null else current.append(c)
                }
                c == '\'' || c == '"' -> quote = c
                c.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun isAllowedToken(token: String, prev: String?): Boolean {
        if (token == "--tests") return true
        if (prev == "--tests") {
            return Regex("""^[a-zA-Z0-9_.*]+$""").matches(token)
        }
        return Regex("""^:?[a-zA-Z][a-zA-Z0-9_\-:]*(?::[a-zA-Z0-9_\-]+)*$""").matches(token)
    }

    private fun resolveLauncher(root: File): String? {
        val wrapper = File(root, "gradlew")
        if (wrapper.exists() && wrapper.canExecute()) return wrapper.absolutePath
        val wrapperBat = File(root, "gradlew.bat")
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows && wrapperBat.exists()) return wrapperBat.absolutePath
        return findOnPath("gradle")
    }

    private fun findOnPath(executable: String): String? {
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        for (dir in pathDirs) {
            val candidate = File(dir, executable)
            if (candidate.exists() && candidate.canExecute()) return candidate.absolutePath
        }
        return null
    }

    private fun extractDiagnostics(text: String): String {
        val lines = text.lines().filterNot { line ->
            val l = line.trim()
            l.contains("honour the JVM settings", ignoreCase = true) ||
                l.contains("Daemon will be stopped", ignoreCase = true) ||
                l.contains("SKIPPED") || l.contains("NO-SOURCE")
        }
        val interesting = lines.filter { line ->
            val l = line.trim()
            l.startsWith("e:") || (l.startsWith("> Task") && l.contains("FAILED")) ||
                l.contains("error:", ignoreCase = true) ||
                l.contains("FAILED", ignoreCase = true) ||
                l.contains("What went wrong", ignoreCase = true) ||
                l.contains("Compilation error", ignoreCase = true)
        }
        val picked = if (interesting.isNotEmpty()) interesting else lines
        return tail(picked.joinToString("\n"))
    }

    private fun tail(text: String, maxLines: Int = 40): String {
        val lines = text.lines()
        return if (lines.size <= maxLines) text else lines.takeLast(maxLines).joinToString("\n")
    }
}
