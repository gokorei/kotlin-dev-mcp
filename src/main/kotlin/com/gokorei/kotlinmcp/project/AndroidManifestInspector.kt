package com.gokorei.kotlinmcp.project

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

    fun inspectManifest(contentOrPath: String, projectPath: String?): KotlinMcpResult {
        val effectiveXml = resolveManifestXml(contentOrPath, projectPath)
        if (effectiveXml.isBlank()) {
            return KotlinMcpResult.Success(
                content = "# Android Manifest Inspection\nNo `AndroidManifest.xml` found or provided.",
                metadata = mapOf("findingsCount" to "0")
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
                            findings.add("⚠️ Manifest requests `FOREGROUND_SERVICE` permission but `<service android:name=\"$name\">` lacks `android:foregroundServiceType=\"...\"`. Android 14+ (SDK 34+) requires explicit foreground service types (e.g. `dataSync`, `mediaPlayback`, `location`).")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            findings.add("⚠️ Failed to parse AndroidManifest.xml: ${e.message}")
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
