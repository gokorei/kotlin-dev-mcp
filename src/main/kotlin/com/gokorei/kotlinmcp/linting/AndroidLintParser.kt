package com.gokorei.kotlinmcp.linting

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Strategy component for parsing Android Lint XML report files (`lint-results.xml`)
 * into structured findings for LLM consumption and verification loops.
 */
class AndroidLintParser {

    fun parseReport(xmlContentOrPath: String, workspacePath: String? = null): KotlinMcpResult {
        val effectiveXml = resolveXml(xmlContentOrPath, workspacePath)
        if (effectiveXml.isBlank()) {
            return KotlinMcpResult.Success(
                content = "# Android Lint Analysis Report\nNo Android Lint report (`lint-results.xml`) found or provided.",
                metadata = mapOf("issuesCount" to "0")
            )
        }

        val issues = mutableListOf<String>()
        var count = 0
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(effectiveXml.toByteArray(Charsets.UTF_8)))
            doc.documentElement.normalize()

            val issueNodes = doc.getElementsByTagName("issue")
            count = issueNodes.length
            for (i in 0 until issueNodes.length) {
                val issueElem = issueNodes.item(i) as? Element ?: continue
                val id = issueElem.getAttribute("id")
                val severity = issueElem.getAttribute("severity").ifEmpty { "Warning" }
                val message = issueElem.getAttribute("message")
                val category = issueElem.getAttribute("category")
                val priority = issueElem.getAttribute("priority")

                val locationElem = issueElem.getElementsByTagName("location").item(0) as? Element
                val file = locationElem?.getAttribute("file").orEmpty()
                val line = locationElem?.getAttribute("line").orEmpty()
                val col = locationElem?.getAttribute("column").orEmpty()

                val locSuffix = if (line.isNotBlank()) ":$line${if (col.isNotBlank()) ":$col" else ""}" else ""
                val shortFile = file.substringAfterLast("/")

                val icon = when (severity.lowercase()) {
                    "error", "fatal" -> "❌"
                    "information", "info" -> "ℹ️"
                    else -> "⚠️"
                }

                issues.add("$icon **[$severity] `$id`** in `$shortFile$locSuffix`\n  - **Category**: $category (Priority: $priority)\n  - **Message**: $message")
            }
        } catch (e: Exception) {
            return KotlinMcpResult.Error(
                message = "Failed to parse Android Lint XML report: ${e.message}",
                code = "XML_PARSE_ERROR"
            )
        }

        val content = if (issues.isNotEmpty()) {
            "# Android Lint Analysis Report (${issues.size} findings)\n\n" + issues.joinToString("\n\n")
        } else {
            "# Android Lint Analysis Report\nNo Android Lint issues found."
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("issuesCount" to count.toString())
        )
    }

    private fun resolveXml(contentOrPath: String, workspacePath: String?): String {
        val trimmed = contentOrPath.trim()
        if (trimmed.startsWith("<") || trimmed.contains("<issues")) {
            return contentOrPath
        }

        if (contentOrPath.isNotBlank()) {
            val file = File(contentOrPath)
            if (file.exists() && file.isFile) return file.readText()
        }

        if (workspacePath != null) {
            val candidatePaths = listOf(
                "build/reports/lint-results.xml",
                "app/build/reports/lint-results.xml",
                "app/build/reports/lint-results-debug.xml",
                "build/reports/lint-results-debug.xml"
            )
            for (rel in candidatePaths) {
                val candidate = File(workspacePath, rel)
                if (candidate.exists() && candidate.isFile) return candidate.readText()
            }
        }

        return ""
    }
}
