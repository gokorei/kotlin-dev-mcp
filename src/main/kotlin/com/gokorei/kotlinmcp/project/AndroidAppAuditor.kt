package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.models.KotlinMcpResult
import org.jetbrains.kotlin.psi.*
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

enum class AndroidAuditCategory {
    COMPOSE_PERFORMANCE,
    RUNTIME_PERMISSIONS,
    R8_MINIFICATION;

    companion object {
        fun fromString(value: String): AndroidAuditCategory? = when (value.trim().lowercase()) {
            "compose_performance", "compose", "performance" -> COMPOSE_PERFORMANCE
            "runtime_permissions", "permissions", "permission" -> RUNTIME_PERMISSIONS
            "r8_minification", "r8", "minification", "proguard" -> R8_MINIFICATION
            else -> entries.find { it.name.equals(value.trim(), ignoreCase = true) }
        }
    }
}

/**
 * Core Android application audit engine and targeted category dispatcher.
 */
class AndroidAppAuditor {

    fun audit(
        code: String,
        projectPath: String? = null,
        categories: List<AndroidAuditCategory> = emptyList()
    ): KotlinMcpResult {
        val targetCategories = if (categories.isEmpty()) AndroidAuditCategory.entries else categories.distinct()
        val findingsByCategory = mutableMapOf<AndroidAuditCategory, MutableList<String>>()

        for (category in targetCategories) {
            val list = mutableListOf<String>()
            when (category) {
                AndroidAuditCategory.COMPOSE_PERFORMANCE -> auditComposePerformance(code, projectPath, list)
                AndroidAuditCategory.RUNTIME_PERMISSIONS -> auditRuntimePermissions(code, projectPath, list)
                AndroidAuditCategory.R8_MINIFICATION -> auditR8Minification(code, projectPath, list)
            }
            if (list.isNotEmpty()) {
                findingsByCategory[category] = list
            }
        }

        val totalFindings = findingsByCategory.values.sumOf { it.size }
        val content = buildString {
            appendLine("# Android App Audit (${targetCategories.joinToString { it.name }})")
            appendLine("Found $totalFindings potential issue(s) across ${targetCategories.size} checked category/categories.")
            appendLine()

            if (findingsByCategory.isEmpty()) {
                appendLine("✅ No obvious Android anti-patterns or performance issues detected across the specified categories.")
            } else {
                for ((cat, findings) in findingsByCategory) {
                    appendLine("## 🔍 ${cat.name} (${findings.size})")
                    findings.forEach { appendLine("- $it") }
                    appendLine()
                }
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf(
                "totalFindings" to totalFindings.toString(),
                "checkedCategories" to targetCategories.joinToString(",") { it.name }
            )
        )
    }

    private fun auditComposePerformance(code: String, projectPath: String?, findings: MutableList<String>) {
        val effectiveCode = if (code.isNotBlank()) code else readWorkspaceKotlinFiles(projectPath)
        if (effectiveCode.isBlank()) return

        val psi = K2SnippetFrontend.parsePsi(effectiveCode) ?: return
        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                super.visitNamedFunction(function)
                val annotations = function.annotationEntries
                val isComposable = annotations.any {
                    val name = it.shortName?.asString()
                    name == "Composable"
                }
                if (!isComposable) return

                val fnName = function.name ?: "anonymous"

                // 1. Check for unstable collection parameter types
                val valueParams = function.valueParameters
                for (param in valueParams) {
                    val typeRef = param.typeReference?.text?.trim() ?: continue
                    val isCollection = typeRef.startsWith("List<") || typeRef.startsWith("Set<") ||
                        typeRef.startsWith("Map<") || typeRef.startsWith("Collection<") ||
                        typeRef.startsWith("kotlin.collections.List<") || typeRef.startsWith("kotlin.collections.Set<") ||
                        typeRef.startsWith("kotlin.collections.Map<")
                    if (isCollection) {
                        findings.add("⚠️ `@Composable fun $fnName`: Parameter `${param.name}: $typeRef` uses an unstable standard collection type. Use `kotlinx.collections.immutable.ImmutableList` / `ImmutableSet` / `ImmutableMap` or wrap in a `@Immutable` data class to prevent unnecessary recompositions.")
                    }
                }

                // 2. Check for items(...) calls missing key parameter in Lazy list DSL
                function.accept(object : KtTreeVisitorVoid() {
                    override fun visitCallExpression(expression: KtCallExpression) {
                        super.visitCallExpression(expression)
                        val callee = expression.calleeExpression?.text
                        if (callee == "items" || callee == "itemsIndexed") {
                            val args = expression.valueArguments
                            val hasKeyArg = args.any { arg ->
                                arg.getArgumentName()?.asName?.asString() == "key" ||
                                    arg.text.contains("key =")
                            }
                            if (!hasKeyArg) {
                                findings.add("⚠️ `@Composable fun $fnName`: `$callee(...)` invocation lacks explicit `key = { ... }` parameter. Provide a unique stable key (e.g. `key = { it.id }`) to optimize LazyColumn/LazyRow diffing and item skipping.")
                            }
                        }

                        // 3. Check for flow.collectAsState() vs collectAsStateWithLifecycle()
                        if (callee == "collectAsState") {
                            findings.add("⚠️ `@Composable fun $fnName`: Found `collectAsState()`. In Android apps, use `collectAsStateWithLifecycle()` from `androidx.lifecycle.compose` to prevent background flow collection and battery drain.")
                        }
                    }
                })
            }
        })
    }

    private fun auditRuntimePermissions(code: String, projectPath: String?, findings: MutableList<String>) {
        val manifestXml = resolveManifestXml(code, projectPath)
        if (manifestXml.isNotBlank()) {
            try {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(ByteArrayInputStream(manifestXml.toByteArray(Charsets.UTF_8)))
                doc.documentElement.normalize()

                val permissionNodes = doc.getElementsByTagName("uses-permission")
                val declaredPermissions = mutableSetOf<String>()
                for (i in 0 until permissionNodes.length) {
                    val node = permissionNodes.item(i) as? org.w3c.dom.Element ?: continue
                    val name = node.getAttribute("android:name").ifEmpty { node.getAttributeNS("http://schemas.android.com/apk/res/android", "name") }
                    if (name.isNotBlank()) declaredPermissions.add(name)
                }

                val dangerousPermissions = listOf(
                    "android.permission.CAMERA" to "Camera",
                    "android.permission.RECORD_AUDIO" to "Microphone",
                    "android.permission.ACCESS_FINE_LOCATION" to "Fine Location",
                    "android.permission.ACCESS_COARSE_LOCATION" to "Coarse Location",
                    "android.permission.READ_MEDIA_IMAGES" to "Photo/Media Access",
                    "android.permission.POST_NOTIFICATIONS" to "Push Notifications (Android 13+)"
                )

                for ((perm, label) in dangerousPermissions) {
                    if (declaredPermissions.contains(perm)) {
                        if (perm == "android.permission.READ_MEDIA_IMAGES") {
                            findings.add("ℹ️ Manifest declares `$perm` ($label). Advisory: For single/multiple image selection on Android 13+, prefer modern PhotoPicker (`ActivityResultContracts.PickVisualMedia`) which requires zero runtime permissions.")
                        } else {
                            findings.add("⚠️ Manifest declares dangerous permission `$perm` ($label). Ensure modern runtime request flow (`rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` or `registerForActivityResult`) is implemented with rationale dialogs before accessing.")
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun auditR8Minification(code: String, projectPath: String?, findings: MutableList<String>) {
        val buildContent = if (code.contains("isMinifyEnabled") || code.contains("minifyEnabled")) {
            code
        } else {
            resolveBuildScriptContent(projectPath)
        }

        val hasMinifyEnabled = buildContent.contains("isMinifyEnabled = true") ||
            buildContent.contains("minifyEnabled true") ||
            buildContent.contains("isMinifyEnabled.set(true)")

        if (hasMinifyEnabled) {
            if (projectPath != null) {
                val dir = File(projectPath)
                val targetDir = if (dir.isFile) dir.parentFile else dir
                val proguardFile = File(targetDir, "proguard-rules.pro")
                val appProguardFile = File(targetDir, "app/proguard-rules.pro")
                if (!proguardFile.exists() && !appProguardFile.exists()) {
                    findings.add("⚠️ R8 Minification is enabled (`isMinifyEnabled = true`), but `proguard-rules.pro` was not found in project root or app module directory. Ensure keep rules are maintained for `@Serializable` DTOs, reflection, and JNI entry points.")
                }
            } else {
                findings.add("ℹ️ R8 Minification is enabled in build script. Verify keep rules or `@Keep` annotations are maintained for reflection, serialization, and dynamic class lookups.")
            }
        }
    }

    private fun readWorkspaceKotlinFiles(projectPath: String?): String {
        if (projectPath == null) return ""
        val dir = File(projectPath)
        if (!dir.exists()) return ""
        if (dir.isFile && (dir.name.endsWith(".kt") || dir.name.endsWith(".kts"))) return dir.readText()

        val sb = StringBuilder()
        dir.walkTopDown().maxDepth(5).filter { it.isFile && it.name.endsWith(".kt") }.take(20).forEach {
            sb.appendLine(runCatching { it.readText() }.getOrNull().orEmpty())
        }
        return sb.toString()
    }

    private fun resolveManifestXml(code: String, projectPath: String?): String {
        if (code.trim().startsWith("<") || code.contains("<manifest")) return code
        if (projectPath != null) {
            val candidatePaths = listOf(
                "src/main/AndroidManifest.xml",
                "app/src/main/AndroidManifest.xml",
                "androidApp/src/main/AndroidManifest.xml",
                "composeApp/src/androidMain/AndroidManifest.xml"
            )
            for (rel in candidatePaths) {
                val file = File(projectPath, rel)
                if (file.exists() && file.isFile) return file.readText()
            }
        }
        return ""
    }

    private fun resolveBuildScriptContent(projectPath: String?): String {
        if (projectPath == null) return ""
        val candidatePaths = listOf(
            "build.gradle.kts",
            "app/build.gradle.kts",
            "androidApp/build.gradle.kts",
            "composeApp/build.gradle.kts"
        )
        for (rel in candidatePaths) {
            val file = File(projectPath, rel)
            if (file.exists() && file.isFile) return file.readText()
        }
        return ""
    }
}
