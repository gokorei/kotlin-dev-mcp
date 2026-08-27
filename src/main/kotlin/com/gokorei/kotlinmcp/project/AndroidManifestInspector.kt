package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Strategy component for statically inspecting AndroidManifest.xml for exported tags,
 * foreground service types, and permissions compliance.
 */
class AndroidManifestInspector {

    /**
     * Statically inspects an AndroidManifest.xml file or snippet for security and configuration hygiene,
     * including explicit `android:exported` on components with intent-filters and foreground service types.
     *
     * @param contentOrPath XML string content or path to AndroidManifest.xml
     * @param projectPath Optional root path of the project workspace
     * @return [KotlinMcpResult] containing formatted inspection findings or structured error
     */
    fun inspectManifest(contentOrPath: String, projectPath: String?): KotlinMcpResult {
        val effectiveXml: String
        try {
            effectiveXml = resolveManifestXml(contentOrPath, projectPath)
            if (effectiveXml.isBlank()) {
                return KotlinMcpResult.Error(
                    code = "FILE_NOT_FOUND",
                    message = "No `AndroidManifest.xml` found or provided. Specify an XML snippet or a path to AndroidManifest.xml."
                )
            }
        } catch (e: Exception) {
            return KotlinMcpResult.Error(
                code = "FILE_NOT_FOUND",
                message = "Failed to resolve AndroidManifest.xml: ${e.message}"
            )
        }

        val findings = mutableListOf<String>()
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(effectiveXml.toByteArray(Charsets.UTF_8)))
            doc.documentElement.normalize()

            val permissions = mutableSetOf<String>()
            val permissionNodes = doc.getElementsByTagName("uses-permission")
            for (i in 0 until permissionNodes.length) {
                val node = permissionNodes.item(i) as? Element ?: continue
                val name = node.getAttribute("android:name").ifEmpty { node.getAttributeNS("http://schemas.android.com/apk/res/android", "name") }
                if (name.isNotBlank()) permissions.add(name)
            }

            val hasForegroundServicePermission = permissions.any { it.contains("FOREGROUND_SERVICE") }

            val componentTagNames = listOf("activity", "service", "receiver", "activity-alias")
            for (tagName in componentTagNames) {
                val nodes = doc.getElementsByTagName(tagName)
                for (i in 0 until nodes.length) {
                    val elem = nodes.item(i) as? Element ?: continue
                    val name = elem.getAttribute("android:name").ifEmpty {
                        elem.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
                    }.ifEmpty { "Unnamed $tagName" }

                    val hasIntentFilter = hasChildElement(elem, "intent-filter")
                    val exportedAttr = elem.getAttribute("android:exported").ifEmpty {
                        elem.getAttributeNS("http://schemas.android.com/apk/res/android", "exported")
                    }

                    if (hasIntentFilter && exportedAttr.isBlank()) {
                        findings.add("⚠️ Component `$name` (`<$tagName>`) declares `<intent-filter>` but lacks `android:exported=\"true|false\"`. Android 12+ (SDK 31+) requires explicit `android:exported` on all components with intent filters.")
                    }

                    if (tagName == "service" && hasForegroundServicePermission) {
                        val fgType = elem.getAttribute("android:foregroundServiceType").ifEmpty {
                            elem.getAttributeNS("http://schemas.android.com/apk/res/android", "foregroundServiceType")
                        }
                        if (fgType.isBlank()) {
                            findings.add("ℹ️ Advisory: Manifest declares `FOREGROUND_SERVICE` permission. If `<service android:name=\"$name\">` is started via `startForeground()`, ensure it declares `android:foregroundServiceType=\"...\"` (e.g. `dataSync`, `mediaPlayback`, `location`) as required by Android 14+ (SDK 34+).")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return KotlinMcpResult.Error(
                code = "XML_PARSE_ERROR",
                message = "Failed to parse AndroidManifest.xml: ${e.message}"
            )
        }

        val content = if (findings.isNotEmpty()) {
            "# Android Manifest Inspection Findings\n" + findings.distinct().joinToString("\n\n")
        } else {
            "# Android Manifest Inspection Findings\nNo obvious Android Manifest anti-patterns or security violations detected."
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("findingsCount" to findings.distinct().size.toString())
        )
    }

    /**
     * Resolves effective Android runtime target metadata (applicationId, namespace, launcher activity,
     * and synthesized ADB CLI launch/logcat commands) from AndroidManifest.xml and Gradle build scripts.
     *
     * @param contentOrPath XML content or path to AndroidManifest.xml
     * @param projectPath Optional root directory path of the project workspace
     * @param buildScriptContent Optional build.gradle.kts content for extracting namespace/applicationId
     * @return [KotlinMcpResult] containing formatted target markdown and structured metadata
     */
    fun resolveRuntimeTarget(
        contentOrPath: String,
        projectPath: String?,
        buildScriptContent: String?
    ): KotlinMcpResult {
        val effectiveXml: String
        try {
            effectiveXml = resolveManifestXml(contentOrPath, projectPath)
            if (effectiveXml.isBlank()) {
                return KotlinMcpResult.Error(
                    code = "FILE_NOT_FOUND",
                    message = "No `AndroidManifest.xml` found or provided. Specify an XML snippet or a path to AndroidManifest.xml."
                )
            }
        } catch (e: Exception) {
            return KotlinMcpResult.Error(
                code = "FILE_NOT_FOUND",
                message = "Failed to resolve AndroidManifest.xml: ${e.message}"
            )
        }

        val effectiveBuildScript = resolveBuildScriptContent(buildScriptContent, projectPath)
        val buildScriptInfo = extractBuildScriptNamespaceAndAppId(effectiveBuildScript)

        var manifestPackage: String? = null
        var rawLauncherActivity: String? = null

        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(effectiveXml.toByteArray(Charsets.UTF_8)))
            doc.documentElement.normalize()

            manifestPackage = doc.documentElement.getAttribute("package").takeIf { it.isNotBlank() }

            val activityNodes = doc.getElementsByTagName("activity")
            val aliasNodes = doc.getElementsByTagName("activity-alias")

            val candidateNodes = mutableListOf<Element>()
            for (i in 0 until activityNodes.length) {
                (activityNodes.item(i) as? Element)?.let { candidateNodes.add(it) }
            }
            for (i in 0 until aliasNodes.length) {
                (aliasNodes.item(i) as? Element)?.let { candidateNodes.add(it) }
            }

            for (elem in candidateNodes) {
                if (isLauncherComponent(elem)) {
                    val name = elem.getAttribute("android:name").ifEmpty {
                        elem.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
                    }
                    if (name.isNotBlank()) {
                        rawLauncherActivity = name
                        break
                    }
                }
            }
        } catch (e: Exception) {
            return KotlinMcpResult.Error(
                code = "XML_PARSE_ERROR",
                message = "Failed to parse AndroidManifest.xml: ${e.message}"
            )
        }

        val namespace = buildScriptInfo.namespace ?: manifestPackage ?: "unknown"
        val applicationId = buildScriptInfo.applicationId ?: manifestPackage ?: namespace

        val launcherActivity = rawLauncherActivity?.let { raw ->
            when {
                raw.startsWith(".") -> "$namespace$raw"
                !raw.contains(".") -> "$namespace.$raw"
                else -> raw
            }
        }

        val launchCommand = if (launcherActivity != null && applicationId != "unknown") {
            "adb shell am start -n $applicationId/$launcherActivity"
        } else null

        val logcatPidCommand = if (applicationId != "unknown") {
            "adb logcat --pid=\$(adb shell pidof -s $applicationId)"
        } else "adb logcat"

        val logcatTagCommand = if (applicationId != "unknown") {
            "adb logcat -s AndroidRuntime:E $applicationId:*"
        } else "adb logcat -s AndroidRuntime:E"

        val content = buildString {
            appendLine("# Android Runtime Target Resolution")
            appendLine("- **Application ID:** `$applicationId`")
            appendLine("- **Namespace:** `$namespace`")
            if (launcherActivity != null) {
                appendLine("- **Launcher Activity:** `$launcherActivity`")
            } else {
                appendLine("- **Launcher Activity:** *(None identified)*")
            }
            appendLine()
            appendLine("## 🚀 CLI Commands")
            if (launchCommand != null) {
                appendLine("```bash")
                appendLine("# Launch app on connected device / emulator")
                appendLine(launchCommand)
                appendLine("```")
                appendLine()
            }
            appendLine("```bash")
            appendLine("# Stream process logcat by PID")
            appendLine(logcatPidCommand)
            appendLine()
            appendLine("# Stream error / runtime tags")
            appendLine(logcatTagCommand)
            appendLine("```")
        }

        val metadata = mutableMapOf(
            "applicationId" to applicationId,
            "namespace" to namespace,
            "logcatPidCommand" to logcatPidCommand,
            "logcatTagCommand" to logcatTagCommand
        )
        if (launcherActivity != null) metadata["launcherActivity"] = launcherActivity
        if (launchCommand != null) metadata["launchCommand"] = launchCommand

        return KotlinMcpResult.Success(content = content, metadata = metadata)
    }

    private fun isLauncherComponent(elem: Element): Boolean {
        val children = elem.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == "intent-filter") {
                val filterElem = child as Element
                var hasMainAction = false
                var hasLauncherCategory = false

                val subChildren = filterElem.childNodes
                for (j in 0 until subChildren.length) {
                    val sub = subChildren.item(j)
                    if (sub.nodeType == Node.ELEMENT_NODE) {
                        val subElem = sub as Element
                        val name = subElem.getAttribute("android:name").ifEmpty {
                            subElem.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
                        }
                        if (subElem.nodeName == "action" && name == "android.intent.action.MAIN") {
                            hasMainAction = true
                        }
                        if (subElem.nodeName == "category" && name == "android.intent.category.LAUNCHER") {
                            hasLauncherCategory = true
                        }
                    }
                }
                if (hasMainAction && hasLauncherCategory) return true
            }
        }
        return false
    }

    private data class BuildScriptAndroidInfo(
        val namespace: String?,
        val applicationId: String?
    )

    private fun extractBuildScriptNamespaceAndAppId(scriptContent: String): BuildScriptAndroidInfo {
        if (scriptContent.isBlank()) return BuildScriptAndroidInfo(null, null)

        var namespace: String? = null
        var applicationId: String? = null

        // Parse via K2 PSI AST to strictly comply with Rule 1
        val psi = K2SnippetFrontend.parsePsi(scriptContent) ?: return BuildScriptAndroidInfo(null, null)
        psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
            override fun visitBinaryExpression(expression: org.jetbrains.kotlin.psi.KtBinaryExpression) {
                super.visitBinaryExpression(expression)
                val left = expression.left?.text?.trim()
                val right = expression.right?.text?.trim()?.trim('"', '\'')
                if (left == "namespace" && right != null) {
                    namespace = right
                }
                if (left == "applicationId" && right != null) {
                    applicationId = right
                }
            }

            override fun visitCallExpression(expression: org.jetbrains.kotlin.psi.KtCallExpression) {
                super.visitCallExpression(expression)
                val callee = expression.calleeExpression?.text
                val args = expression.valueArguments
                if (callee == "namespace" && args.isNotEmpty()) {
                    val argText = args[0].getArgumentExpression()?.text?.trim()?.trim('"', '\'')
                    if (argText != null) namespace = argText
                }
                if (callee == "applicationId" && args.isNotEmpty()) {
                    val argText = args[0].getArgumentExpression()?.text?.trim()?.trim('"', '\'')
                    if (argText != null) applicationId = argText
                }
            }
        })

        return BuildScriptAndroidInfo(namespace, applicationId)
    }

    private fun resolveBuildScriptContent(buildScriptContent: String?, projectPath: String?): String {
        if (!buildScriptContent.isNullOrBlank()) return buildScriptContent
        if (projectPath != null) {
            val candidatePaths = listOf(
                "build.gradle.kts",
                "app/build.gradle.kts",
                "androidApp/build.gradle.kts",
                "composeApp/build.gradle.kts"
            )
            for (rel in candidatePaths) {
                val candidate = File(projectPath, rel)
                if (candidate.exists() && candidate.isFile) {
                    val text = runCatching { candidate.readText() }.getOrNull()
                    if (!text.isNullOrBlank()) return text
                }
            }
        }
        return ""
    }

    private fun hasChildElement(parent: Element, tagName: String): Boolean {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == tagName) {
                return true
            }
        }
        return false
    }

    private fun resolveManifestXml(contentOrPath: String, projectPath: String?): String {
        val trimmed = contentOrPath.trim()
        if (trimmed.startsWith("<") || trimmed.contains("<manifest")) {
            return contentOrPath
        }

        if (contentOrPath.isNotBlank()) {
            val file = File(contentOrPath)
            if (file.exists() && file.isFile) return file.readText()
        }

        if (projectPath != null) {
            val candidatePaths = listOf(
                "src/main/AndroidManifest.xml",
                "app/src/main/AndroidManifest.xml",
                "androidApp/src/main/AndroidManifest.xml",
                "composeApp/src/androidMain/AndroidManifest.xml"
            )
            for (rel in candidatePaths) {
                val candidate = File(projectPath, rel)
                if (candidate.exists() && candidate.isFile) return candidate.readText()
            }
        }

        return ""
    }
}
