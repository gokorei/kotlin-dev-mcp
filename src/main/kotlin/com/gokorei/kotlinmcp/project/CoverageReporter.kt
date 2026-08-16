package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import java.io.File

/**
 * Strategy component for parsing JaCoCo coverage reports under `build/reports/jacoco/`.
 */
class CoverageReporter {

    fun reportCoverage(buildScriptContent: String, projectPath: String?): KotlinMcpResult {
        return coverageReport(projectPath)
    }

    fun coverageReport(projectPath: String?): KotlinMcpResult {
        val root = if (projectPath != null) File(projectPath) else File(".")
        val jacocoDir = File(root, "build/reports/jacoco/test")
        if (!jacocoDir.exists() || !jacocoDir.isDirectory) {
            return KotlinMcpResult.Error(
                message = "No JaCoCo coverage directory found at ${jacocoDir.path}. Run `./gradlew jacocoTestReport` first.",
                code = "NOT_FOUND"
            )
        }

        val xmlReport = File(jacocoDir, "jacocoTestReport.xml")
        val content = if (xmlReport.exists()) {
            val text = xmlReport.readText()
            val lineCov = Regex("""<counter type="LINE"\s+missed="(\d+)"\s+covered="(\d+)"/>""").find(text)
            val branchCov = Regex("""<counter type="BRANCH"\s+missed="(\d+)"\s+covered="(\d+)"/>""").find(text)

            buildString {
                appendLine("# JaCoCo Code Coverage Report")
                if (lineCov != null) {
                    val missed = lineCov.groupValues[1].toInt()
                    val covered = lineCov.groupValues[2].toInt()
                    val total = missed + covered
                    val pct = if (total > 0) (covered * 100) / total else 0
                    appendLine("- Line Coverage: $pct% ($covered / $total lines)")
                }
                if (branchCov != null) {
                    val missed = branchCov.groupValues[1].toInt()
                    val covered = branchCov.groupValues[2].toInt()
                    val total = missed + covered
                    val pct = if (total > 0) (covered * 100) / total else 0
                    appendLine("- Branch Coverage: $pct% ($covered / $total branches)")
                }
            }
        } else {
            "# JaCoCo Code Coverage Report\nHTML report directory exists at `${jacocoDir.path}`."
        }

        return KotlinMcpResult.Success(content = content)
    }
}
