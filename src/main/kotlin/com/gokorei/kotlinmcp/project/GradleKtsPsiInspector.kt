package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Shared K2 PSI AST visitor and extraction utilities for Gradle Kotlin DSL (`.gradle.kts`) scripts.
 * Adheres strictly to Rule #1 (never use regex to parse Kotlin code).
 */
object GradleKtsPsiInspector {

    data class ParsedPlugin(
        val id: String,
        val version: String? = null
    )

    data class ParsedDependency(
        val configuration: String,
        val coordinate: String,
        val isProject: Boolean = false,
        val isCatalog: Boolean = false,
        val isPlatform: Boolean = false
    )

    /**
     * Parses the Kotlin script PSI, or returns `null` if K2 snippet frontend could not parse it.
     */
    fun parse(code: String): KtFile? = K2SnippetFrontend.parsePsi(code)

    /**
     * Extracts plugin declarations (id and optional version) from Gradle script AST.
     */
    fun extractPlugins(code: String): List<ParsedPlugin> {
        val psi = parse(code) ?: return emptyList()
        val plugins = mutableListOf<ParsedPlugin>()

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression?.text.orEmpty()
                if (callee == "kotlin" || callee == "id") {
                    val arg = expression.valueArguments.firstOrNull()?.getArgumentExpression()
                    val idVal = extractLiteralString(arg)
                    if (!idVal.isNullOrBlank()) {
                        val fullId = if (callee == "kotlin") "kotlin-$idVal" else idVal
                        var version: String? = null

                        var current: KtExpression = expression
                        while (current.parent is KtBinaryExpression) {
                            val parentBinary = current.parent as KtBinaryExpression
                            if (parentBinary.operationReference.text == "version") {
                                version = extractLiteralString(parentBinary.right)
                                break
                            }
                            current = parentBinary
                        }
                        plugins.add(ParsedPlugin(fullId, version))
                    }
                }
                super.visitCallExpression(expression)
            }

            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                val name = expression.getReferencedName()
                if (name in setOf("application", "java-library", "java")) {
                    plugins.add(ParsedPlugin(name))
                }
                super.visitSimpleNameExpression(expression)
            }
        })
        return plugins
    }

    /**
     * Extracts subprojects from `include(...)` calls in `settings.gradle.kts`.
     */
    fun extractSubprojects(code: String): List<String> {
        val psi = parse(code) ?: return emptyList()
        val subprojects = mutableListOf<String>()

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression?.text
                if (callee == "include") {
                    expression.valueArguments.forEach { arg ->
                        val str = extractLiteralString(arg.getArgumentExpression())
                        if (!str.isNullOrBlank()) {
                            subprojects.add(str)
                        }
                    }
                }
                super.visitCallExpression(expression)
            }
        })
        return subprojects.distinct()
    }

    /**
     * Extracts dependencies from `dependencies { ... }` blocks in `build.gradle.kts`.
     */
    fun extractDependencies(code: String, knownConfigurations: Set<String>): List<ParsedDependency> {
        val psi = parse(code) ?: return emptyList()
        val deps = mutableListOf<ParsedDependency>()

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression?.text.orEmpty()
                if (callee in knownConfigurations) {
                    val firstArg = expression.valueArguments.firstOrNull()?.getArgumentExpression()
                    if (firstArg != null) {
                        when {
                            // Named arguments: implementation(group = "...", name = "...", version = "...")
                            expression.valueArguments.any { it.isNamed() } -> {
                                var group: String? = null
                                var name: String? = null
                                var version: String? = null
                                expression.valueArguments.forEach { arg ->
                                    val paramName = arg.getArgumentName()?.asName?.asString()
                                    val v = extractLiteralString(arg.getArgumentExpression())
                                    when (paramName) {
                                        "group" -> group = v
                                        "name" -> name = v
                                        "version" -> version = v
                                    }
                                }
                                if (!group.isNullOrBlank() && !name.isNullOrBlank()) {
                                    val coord = if (!version.isNullOrBlank()) "$group:$name:$version" else "$group:$name"
                                    deps.add(ParsedDependency(callee, coord))
                                }
                            }
                            // platform("group:name:version")
                            firstArg is KtCallExpression && firstArg.calleeExpression?.text == "platform" -> {
                                val innerArg = firstArg.valueArguments.firstOrNull()?.getArgumentExpression()
                                val str = extractLiteralString(innerArg)
                                if (!str.isNullOrBlank()) {
                                    deps.add(ParsedDependency(callee, str, isPlatform = true))
                                }
                            }
                            // project(":path")
                            firstArg is KtCallExpression && firstArg.calleeExpression?.text == "project" -> {
                                val innerArg = firstArg.valueArguments.firstOrNull()?.getArgumentExpression()
                                val str = extractLiteralString(innerArg) ?: innerArg?.text?.trim().orEmpty()
                                val clean = str.removePrefix(":").removeSurrounding("\"").removeSurrounding("'")
                                deps.add(ParsedDependency(callee, ":$clean", isProject = true))
                            }
                            // libs.foo.bar
                            firstArg is KtDotQualifiedExpression && firstArg.text.startsWith("libs.") -> {
                                deps.add(ParsedDependency(callee, firstArg.text.trim(), isCatalog = true))
                            }
                            // Standard string literal "group:artifact:version"
                            firstArg is KtStringTemplateExpression -> {
                                val str = extractLiteralString(firstArg)
                                if (!str.isNullOrBlank()) {
                                    deps.add(ParsedDependency(callee, str))
                                }
                            }
                            // Other identifier / call
                            else -> {
                                val raw = firstArg.text.trim().removeSurrounding("\"").removeSurrounding("'")
                                if (raw.isNotBlank()) {
                                    deps.add(ParsedDependency(callee, raw))
                                }
                            }
                        }
                    }
                }
                super.visitCallExpression(expression)
            }
        })
        return deps
    }

    /**
     * Inspects KMP target calls inside `kotlin { ... }` blocks (e.g. `jvm()`, `androidTarget()`, `iosX64()`).
     */
    fun extractKmpTargets(code: String): List<String> {
        val psi = parse(code) ?: return emptyList()
        val targets = mutableListOf<String>()
        val knownTargets = setOf(
            "jvm", "androidTarget", "iosX64", "iosArm64", "iosSimulatorArm64",
            "js", "wasmJs", "linuxX64", "macosX64", "macosArm64", "mingwX64"
        )

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression?.text.orEmpty()
                if (callee in knownTargets) {
                    targets.add(callee)
                }
                super.visitCallExpression(expression)
            }

            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                val name = expression.getReferencedName()
                if (name in knownTargets) {
                    targets.add(name)
                }
                super.visitSimpleNameExpression(expression)
            }
        })
        return targets.distinct()
    }

    /**
     * Checks if repositories / dependency resolution management blocks exist in Gradle script AST.
     */
    fun hasRepositories(code: String): Boolean {
        val psi = parse(code) ?: return false
        var found = false
        val repoCallees = setOf("repositories", "dependencyResolutionManagement", "pluginManagement")
        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression?.text
                if (callee in repoCallees) {
                    found = true
                }
                super.visitCallExpression(expression)
            }
        })
        return found
    }

    /**
     * Extracts clean literal text from string templates without quotes.
     */
    fun extractLiteralString(expression: KtExpression?): String? {
        if (expression == null) return null
        if (expression is KtStringTemplateExpression) {
            val entries = expression.entries
            if (entries.isEmpty()) return ""
            return entries.joinToString("") { it.text }
        }
        return expression.text?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
    }
}
