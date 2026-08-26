package com.gokorei.kotlinmcp.analysis

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias

/**
 * Strategy component for inspecting Kotlin code snippet symbols, top-level/nested classes, properties, and functions.
 */
class SymbolInspector {

    fun inspectSymbol(code: String): KotlinMcpResult {
        val psi = K2SnippetFrontend.parsePsi(code)
        val builder = StringBuilder("# Code Symbol Analysis\n")
        var anyElements = false

        fun memberProps(decl: KtClassOrObject): List<String> =
            decl.declarations.filterIsInstance<KtProperty>()
                .mapNotNull { it.name }
                .distinct()

        fun memberFuns(decl: KtClassOrObject): List<String> =
            decl.declarations.filterIsInstance<KtNamedFunction>()
                .mapNotNull { it.name }
                .distinct()

        fun kindOf(decl: KtClassOrObject): String = when {
            decl is KtObjectDeclaration && decl.isCompanion() -> "Companion object"
            decl is KtObjectDeclaration -> "Object"
            decl is KtClass && decl.isInterface() -> "Interface"
            decl is KtClass && decl.isEnum() -> "Enum"
            decl is KtClass && decl.isAnnotation() -> "Annotation"
            decl is KtClass && decl.isSealed() -> "Sealed class"
            else -> "Class"
        }

        fun renderClass(decl: KtClassOrObject, nested: Boolean) {
            anyElements = true
            val kind = kindOf(decl)
            val label = if (nested) "Nested ${kind.lowercase()}" else kind
            builder.appendLine("- $label: ${decl.name}")
            val headerProps = decl.primaryConstructorParameters
                .filter { it.hasValOrVar() }
                .mapNotNull { it.name }
            val allProps = (headerProps + memberProps(decl)).distinct()
            if (allProps.isNotEmpty()) builder.appendLine("- Properties: ${allProps.joinToString(", ")}")
            val secondaryCtorCount = decl.secondaryConstructors.size
            if (secondaryCtorCount > 0) builder.appendLine("- Secondary constructors: $secondaryCtorCount")
            val funs = memberFuns(decl)
            if (funs.isNotEmpty()) builder.appendLine("- Functions: ${funs.joinToString(", ")}")
            val constants = (decl as? KtClass)?.body
                ?.enumEntries?.mapNotNull { it.name }.orEmpty()
            if (constants.isNotEmpty()) builder.appendLine("- Enum constants: ${constants.joinToString(", ")}")
            val nestedClasses = decl.declarations.filterIsInstance<KtClassOrObject>()
            if (nestedClasses.isNotEmpty()) {
                builder.appendLine("- Nested classes: ${nestedClasses.joinToString(", ") { it.name.orEmpty() }}")
                nestedClasses.forEach { renderClass(it, nested = true) }
            }

            // Check for Context / Activity / View memory leaks in ViewModel or Singleton classes
            val superTypeNames = (decl as? KtClass)?.superTypeListEntries?.mapNotNull { it.typeReference?.text?.trim() }.orEmpty()
            val knownViewModelBases = setOf("ViewModel", "androidx.lifecycle.ViewModel")
            val knownAndroidViewModelBases = setOf("AndroidViewModel", "androidx.lifecycle.AndroidViewModel")
            val isViewModel = superTypeNames.any { it in knownViewModelBases || it.endsWith(".ViewModel") }
            val isAndroidViewModel = superTypeNames.any { it in knownAndroidViewModelBases || it.endsWith(".AndroidViewModel") }
            val isSingleton = decl.annotationEntries.any {
                val shortName = it.shortName?.asString()
                shortName == "Singleton" || shortName == "ActivityRetainedScoped"
            }

            if (isViewModel || isAndroidViewModel || isSingleton) {
                fun isLeakyType(typeRef: org.jetbrains.kotlin.psi.KtTypeReference?): Boolean {
                    if (typeRef == null) return false
                    val rawType = typeRef.text.trim().removeSuffix("?")
                    val userType = typeRef.typeElement as? org.jetbrains.kotlin.psi.KtUserType
                    val baseName = userType?.referencedName ?: rawType.substringAfterLast('.')
                    return baseName in setOf("Context", "Activity", "View") ||
                        baseName.endsWith("Activity") ||
                        baseName.endsWith("View") ||
                        rawType in setOf("android.content.Context", "android.app.Activity", "android.view.View")
                }

                val leakyParams = decl.primaryConstructorParameters.filter { param ->
                    val hasAppContext = param.annotationEntries.any { it.shortName?.asString() == "ApplicationContext" }
                    !hasAppContext && isLeakyType(param.typeReference)
                }
                val leakyProps = decl.declarations.filterIsInstance<KtProperty>().filter { prop ->
                    val hasAppContext = prop.annotationEntries.any { it.shortName?.asString() == "ApplicationContext" }
                    !hasAppContext && isLeakyType(prop.typeReference)
                }

                (leakyParams.map { it.name to it.typeReference?.text } + leakyProps.map { it.name to it.typeReference?.text }).forEach { (name, type) ->
                    builder.appendLine("⚠️ Memory leak risk: Class `${decl.name}` retains reference to `$type` in property/parameter `$name`. Holding `Activity`, `View`, or UI `Context` references inside a ViewModel or Singleton leaks the Activity across configuration changes. Use `@ApplicationContext`, `AndroidViewModel(application)`, or pass callbacks/state instead.")
                }
            }
        }

        if (psi != null) {
            val declarations = psi.declarations + psi.script?.declarations.orEmpty()
            val topLevelClasses = declarations.filterIsInstance<KtClassOrObject>()
            val topLevelFuns = declarations.filterIsInstance<KtNamedFunction>()
            val topLevelProps = declarations.filterIsInstance<KtProperty>()
            val typeAliases = declarations.filterIsInstance<KtTypeAlias>()

            topLevelClasses.forEach { renderClass(it, nested = false) }

            if (topLevelProps.isNotEmpty()) {
                anyElements = true
                builder.appendLine("- Top-level properties: ${topLevelProps.mapNotNull { it.name }.joinToString(", ")}")
            }
            if (topLevelFuns.isNotEmpty()) {
                anyElements = true
                builder.appendLine("- Top-level functions: ${topLevelFuns.mapNotNull { it.name }.joinToString(", ")}")
            }
            if (typeAliases.isNotEmpty()) {
                anyElements = true
                builder.appendLine("- Type aliases: ${typeAliases.mapNotNull { it.name }.joinToString(", ")}")
            }
            if (!anyElements) {
                builder.appendLine("- Top-level elements analyzed")
            }
            builder.appendLine("- Line count: ${code.lines().size}")
        } else {
            builder.appendLine("- Unable to parse snippet as Kotlin PSI.")
        }

        return KotlinMcpResult.Success(
            content = builder.toString().trim(),
            metadata = mapOf("lineCount" to code.lines().size.toString())
        )
    }
}
