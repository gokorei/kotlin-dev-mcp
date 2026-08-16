package com.gokorei.kotlinmcp.analysis

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.shared.SourceUtils
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Strategy component for Jetpack Compose AST inspection, recomposition stability advisories, and state key checks.
 */
class ComposeAnalyzer {

    private val stablePrimitiveTypes = setOf(
        "String", "Int", "Long", "Double", "Float", "Boolean", "Char", "Short", "Byte",
        "String?", "Int?", "Long?", "Double?", "Boolean?"
    )

    private val commonStandardExtensions = setOf(
        "forEach", "map", "filter", "flatMap", "let", "run", "also", "apply", "repeat", "with", "use",
        "takeIf", "takeUnless", "firstOrNull", "find", "associate", "associateBy", "fold", "reduce",
        "List", "Map", "Set", "Intent", "File", "Thread", "String", "Pair", "Triple", "Array", "Sequence", "Result", "UUID"
    )

    private val containerTypeNames = setOf("List", "Set", "Map", "Flow", "State", "StateFlow", "MutableStateFlow")

    fun analyzeCompose(code: String): KotlinMcpResult {
        val findings = mutableListOf<String>()
        val psi = K2SnippetFrontend.parsePsi(code)
        if (psi == null) {
            return KotlinMcpResult.Success(
                content = "# Jetpack Compose Analysis Findings\nNo obvious Compose anti-patterns detected.",
                metadata = mapOf("findingsCount" to "0")
            )
        }

        val lineOf = { offset: Int -> SourceUtils.lineOf(code, offset) }

        psi.accept(object : KtTreeVisitorVoid() {
            override fun visitNamedFunction(function: KtNamedFunction) {
                inspectComposableParameters(function, findings)
                super.visitNamedFunction(function)
            }

            override fun visitCallExpression(expression: KtCallExpression) {
                inspectComposableCall(expression, lineOf, findings)
                super.visitCallExpression(expression)
            }
        })

        val content = if (findings.isNotEmpty()) {
            "# Jetpack Compose Analysis Findings\n" + findings.distinct().joinToString("\n\n")
        } else {
            "# Jetpack Compose Analysis Findings\nNo obvious Compose anti-patterns detected."
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("findingsCount" to findings.distinct().size.toString())
        )
    }

    private fun inspectComposableParameters(function: KtNamedFunction, findings: MutableList<String>) {
        val isComposable = function.annotationEntries.any { it.shortName?.asString() == "Composable" }
        if (!isComposable) return

        val rootPsi = function.containingKtFile
        val stableDeclaredTypes = mutableSetOf<String>()
        rootPsi.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtClass>().forEach { ktClass ->
            val className = ktClass.name
            val isValueClass = ktClass.isInline() || ktClass.modifierList?.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.VALUE_KEYWORD) == true ||
                ktClass.annotationEntries.any { it.shortName?.asString() == "JvmInline" }
            val isClassAnnotatedStable = ktClass.annotationEntries.any {
                val name = it.shortName?.asString()
                name == "Stable" || name == "Immutable"
            }
            if (className != null && (isValueClass || isClassAnnotatedStable)) {
                stableDeclaredTypes.add(className)
                stableDeclaredTypes.add("$className?")
            }
        }

        val fnName = function.name ?: "anonymous"
        function.valueParameters.forEach { param ->
            val typeRef = param.typeReference
            val typeText = typeRef?.text?.trim().orEmpty()
            val paramName = param.name ?: param.text.substringBefore(":")
            val typeElement = typeRef?.typeElement
            val isFunctionType = typeElement is KtFunctionType || typeText.startsWith("(")
            val userType = typeElement as? KtUserType
            val typeName = userType?.referencedName.orEmpty()
            val isContainerType = typeName in containerTypeNames || (userType?.typeArgumentList != null && typeName in containerTypeNames)

            val isAnnotatedStable = param.annotationEntries.any {
                val name = it.shortName?.asString()
                name == "Stable" || name == "Immutable"
            } || typeName in stableDeclaredTypes || typeText in stableDeclaredTypes

            if (typeText.isNotEmpty() && typeText !in stablePrimitiveTypes &&
                !isFunctionType && !isContainerType && !isAnnotatedStable
            ) {
                findings.add("⚠️ `@Composable $fnName` takes parameter `$paramName` of type `$typeText`, which is not a known stable type. Annotate the type with `@Stable`/`@Immutable` or derive it from stable state to avoid recomposition waste.")
            }
        }
    }

    private fun inspectComposableCall(expression: KtCallExpression, lineOf: (Int) -> Int, findings: MutableList<String>) {
        val callee = expression.calleeExpression?.text.orEmpty()
        val line = lineOf(expression.textRange.startOffset)

        if (callee == "remember") {
            val valueArgs = expression.valueArguments.filter { it !is KtLambdaArgument }
            if (valueArgs.isEmpty()) {
                val lambda = expression.lambdaArguments.firstOrNull()?.getLambdaExpression()
                    ?: expression.valueArguments.firstOrNull()?.getArgumentExpression() as? KtLambdaExpression
                var declaresVarOrState = false
                lambda?.bodyExpression?.accept(object : KtTreeVisitorVoid() {
                    override fun visitProperty(property: KtProperty) {
                        if (property.isVar) declaresVarOrState = true
                        super.visitProperty(property)
                    }
                    override fun visitCallExpression(call: KtCallExpression) {
                        if (call.calleeExpression?.text == "mutableStateOf") declaresVarOrState = true
                        super.visitCallExpression(call)
                    }
                })
                if (declaresVarOrState) {
                    findings.add("⚠️ `remember { }` (line $line) has no key arguments yet its body declares mutable state. Pass explicit keys (`remember(key) { ... }`) so state recalculation tracks the inputs it depends on.")
                }
            }
        }


        if (callee == "derivedStateOf") {
            var inRemember = false
            var ancestor: PsiElement? = expression.parent
            while (ancestor != null && !inRemember) {
                if (ancestor is KtCallExpression) {
                    val ancestorCallee = ancestor.calleeExpression?.text.orEmpty()
                    if (ancestorCallee == "remember") {
                        inRemember = true
                    }
                }
                ancestor = ancestor.parent
            }
            if (!inRemember) {
                findings.add("Line $line: `derivedStateOf { }` is not wrapped in `remember { }`. It should be `val x by remember { derivedStateOf { ... } }`.")
            }
        }
    }
}
