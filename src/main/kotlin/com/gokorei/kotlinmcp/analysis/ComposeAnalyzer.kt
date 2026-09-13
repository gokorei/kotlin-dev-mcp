package com.gokorei.kotlinmcp.analysis

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.shared.SourceUtils
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtUserType
import java.io.File

/**
 * Strategy component for Jetpack Compose AST inspection, recomposition stability advisories, and state key checks.
 */
public class ComposeAnalyzer {

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

    public fun analyzeWorkspace(workspacePath: String): KotlinMcpResult {
        val root = File(workspacePath)
        if (!root.exists() || !root.isDirectory) {
            return KotlinMcpResult.Success(
                content = "# Jetpack Compose Analysis Findings\nNo obvious Compose anti-patterns detected.",
                metadata = mapOf("findingsCount" to "0"),
            )
        }

        val allFindings = root.walkTopDown()
            .maxDepth(MAX_WORKSPACE_SEARCH_DEPTH)
            .onEnter { it.name !in ignoredDirectoryNames }
            .filter { isAnalyzableFile(it) }
            .mapNotNull { analyzeComposeFile(it, root) }
            .toList()

        val content = if (allFindings.isNotEmpty()) {
            "# Jetpack Compose Workspace Analysis Findings\n\n" + allFindings.joinToString("\n\n")
        } else {
            "# Jetpack Compose Analysis Findings\nNo obvious Compose anti-patterns detected across workspace."
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("findingsCount" to allFindings.size.toString()),
        )
    }

    private fun isAnalyzableFile(file: File): Boolean =
        file.isFile && file.extension == "kt" &&
            !file.invariantSeparatorsPath.contains("/build/") &&
            !file.invariantSeparatorsPath.contains("/generated/")

    private fun analyzeComposeFile(file: File, root: File): String? {
        val text = runCatching { file.readText() }.getOrNull().orEmpty()
        val isComposeSource = text.contains("@Composable") || text.contains("LazyColumn") || text.contains("LazyRow")
        if (!isComposeSource) {
            return null
        }
        val fileResult = analyzeCompose(text)
        return if (fileResult is KotlinMcpResult.Success && fileResult.metadata["findingsCount"] != "0") {
            val relPath = file.relativeTo(root).invariantSeparatorsPath
            val body = fileResult.content.removePrefix("# Jetpack Compose Analysis Findings\n")
            "### `$relPath`\n$body"
        } else {
            null
        }
    }

    public fun analyzeCompose(code: String): KotlinMcpResult {
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
                inspectLazyLayout(expression, lineOf, findings)
                super.visitCallExpression(expression)
            }

            override fun visitBinaryExpression(expression: org.jetbrains.kotlin.psi.KtBinaryExpression) {
                if (expression.operationToken == org.jetbrains.kotlin.lexer.KtTokens.EQ) {
                    val leftText = expression.left?.text.orEmpty()
                    if (leftText.endsWith(".systemUiVisibility") || leftText.endsWith(".statusBarColor") || leftText.endsWith(".navigationBarColor")) {
                        val line = lineOf(expression.textRange.startOffset)
                        findings.add("⚠️ Property assignment `$leftText` at line $line manipulates legacy system UI visibility/insets. In modern Android (Android 15+ / SDK 35), call `enableEdgeToEdge()` in Activity.onCreate and use Compose window insets modifiers (`Modifier.safeDrawingPadding()`, `Modifier.imePadding()`) instead.")
                    }
                }
                super.visitBinaryExpression(expression)
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
        val isPreview = function.annotationEntries.any { it.shortName?.asString() == "Preview" }
        val explicitReturnType = function.typeReference?.text?.trim()
        val returnsUnit = explicitReturnType == null || explicitReturnType == "Unit"
        var hasModifierParam = false

        function.valueParameters.forEach { param ->
            val typeRef = param.typeReference
            val typeText = typeRef?.text?.trim().orEmpty()
            val paramName = param.name ?: param.text.substringBefore(":")
            val typeElement = typeRef?.typeElement
            val isFunctionType = typeElement is KtFunctionType || typeText.startsWith("(")
            val userType = typeElement as? KtUserType
            val typeName = userType?.referencedName ?: typeText.substringAfterLast('.').removeSuffix("?")
            val isContainerType = typeName in containerTypeNames || (userType?.typeArgumentList != null && typeName in containerTypeNames)
            val isModifierType = typeName == "Modifier" || typeText == "androidx.compose.ui.Modifier" || typeText.endsWith(".Modifier")

            if (isModifierType) {
                hasModifierParam = true
                val defaultExprText = param.defaultValue?.text?.trim()
                val isDirectModifier = defaultExprText == "Modifier" || defaultExprText == "androidx.compose.ui.Modifier"
                if (!isDirectModifier) {
                    findings.add("⚠️ Parameter `$paramName` in `@Composable $fnName` does not declare a default value `= Modifier`. Provide `= Modifier` to allow callers to omit modifier argument.")
                }
            }

            val isAnnotatedStable = param.annotationEntries.any {
                val name = it.shortName?.asString()
                name == "Stable" || name == "Immutable"
            } || typeName in stableDeclaredTypes || typeText in stableDeclaredTypes

            if (typeText.isNotEmpty() && typeText !in stablePrimitiveTypes &&
                !isFunctionType && !isContainerType && !isAnnotatedStable && !isModifierType
            ) {
                findings.add("⚠️ `@Composable $fnName` takes parameter `$paramName` of type `$typeText`, which is not a known stable type. Annotate the type with `@Stable`/`@Immutable` or derive it from stable state to avoid recomposition waste.")
            }
        }

        if (returnsUnit && !isPreview && !hasModifierParam && fnName != "anonymous" && !fnName.startsWith("Preview")) {
            findings.add("⚠️ `@Composable $fnName` does not declare a `modifier: Modifier = Modifier` parameter. Custom UI composables should accept a `modifier` parameter as the first optional parameter to allow callers to customize layout attributes.")
        }
    }

    private fun inspectComposableCall(expression: KtCallExpression, lineOf: (Int) -> Int, findings: MutableList<String>) {
        val callee = expression.calleeExpression?.text.orEmpty()
        val line = lineOf(expression.textRange.startOffset)

        if (callee == "collectAsState") {
            findings.add("⚠️ `collectAsState()` called at line $line. In Android Compose applications, use `collectAsStateWithLifecycle()` from `androidx.lifecycle.compose` instead to stop Flow emissions when the app is in the background and prevent battery drain.")
        }

        if (callee in setOf("setSystemUiVisibility", "setStatusBarColor", "setNavigationBarColor", "setDecorFitsSystemWindows")) {
            findings.add("⚠️ Call `$callee` at line $line manipulates legacy system UI visibility/insets. In modern Android (Android 15+ / SDK 35), call `enableEdgeToEdge()` in Activity.onCreate and use Compose window insets modifiers (`Modifier.safeDrawingPadding()`, `Modifier.imePadding()`) instead.")
        }

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

    private fun inspectLazyLayout(expression: KtCallExpression, lineOf: (Int) -> Int, findings: MutableList<String>) {
        val callee = expression.calleeExpression?.text.orEmpty()
        if (callee !in lazyLayoutNames) return
        LazyLayoutInspector(callee, lineOf, findings).inspect(expression)
    }

    internal companion object {
        internal val lazyLayoutNames =
            setOf(
                "LazyColumn",
                "LazyRow",
                "LazyVerticalGrid",
                "LazyHorizontalGrid",
                "LazyVerticalStaggeredGrid",
                "LazyHorizontalStaggeredGrid",
            )

        private const val MAX_WORKSPACE_SEARCH_DEPTH = 25

        private val ignoredDirectoryNames =
            setOf(
                ".gradle",
                ".git",
                "out",
                "node_modules",
                ".idea",
                "build",
            )

        internal val assignmentTokens =
            setOf(
                org.jetbrains.kotlin.lexer.KtTokens.EQ,
                org.jetbrains.kotlin.lexer.KtTokens.PLUSEQ,
                org.jetbrains.kotlin.lexer.KtTokens.MINUSEQ,
                org.jetbrains.kotlin.lexer.KtTokens.MULTEQ,
                org.jetbrains.kotlin.lexer.KtTokens.DIVEQ,
                org.jetbrains.kotlin.lexer.KtTokens.PERCEQ,
            )

        internal fun isConstantLiteral(expr: KtExpression?): Boolean {
            val unwrapped = (expr as? KtBlockExpression)?.statements?.lastOrNull() ?: expr
            return when (unwrapped) {
                is KtConstantExpression -> true
                is KtStringTemplateExpression -> !unwrapped.hasInterpolation()
                else -> false
            }
        }
    }
}

private class LazyLayoutInspector(
    private val callee: String,
    private val lineOf: (Int) -> Int,
    private val findings: MutableList<String>,
) : KtTreeVisitorVoid() {

    private val declaredStaticKeys = mutableMapOf<String, Int>()
    private var mutatedIdentifiers: Set<String> = emptySet()
    private var reportedMissingKeyInContainer = false

    fun inspect(expression: KtCallExpression) {
        val lambda = expression.lambdaArguments.firstOrNull()?.getLambdaExpression()
            ?: expression.valueArguments.firstOrNull()?.getArgumentExpression() as? KtLambdaExpression
            ?: return

        mutatedIdentifiers = extractMutatedIdentifiers(lambda.bodyExpression)
        lambda.bodyExpression?.accept(this)
    }

    override fun visitCallExpression(call: KtCallExpression) {
        val innerCallee = call.calleeExpression?.text.orEmpty()
        if (innerCallee in ComposeAnalyzer.lazyLayoutNames) {
            return
        }

        val callLine = lineOf(call.textRange.startOffset)
        if (innerCallee == "item") {
            inspectItem(call, callLine)
        } else if (innerCallee == "items" || innerCallee == "itemsIndexed") {
            inspectItems(call, innerCallee, callLine)
        }

        super.visitCallExpression(call)
    }

    private fun inspectItem(call: KtCallExpression, callLine: Int) {
        val keyArg = call.valueArguments
            .firstOrNull { it.getArgumentName()?.asName?.asString() == "key" }
            ?.getArgumentExpression() ?: return

        val mutatedInKey = findReferencedIdentifiers(keyArg).intersect(mutatedIdentifiers)
        if (mutatedInKey.isNotEmpty()) {
            val mutatedId = mutatedInKey.first()
            val msg = "⚠️ Key for `item` at line $callLine in `$callee` depends on `$mutatedId`, " +
                "which is mutated inside the layout. A computed key derived from mutable state will " +
                "collide across items and throw `IllegalArgumentException: Key was already used`."
            findings.add(msg)
        } else if (ComposeAnalyzer.isConstantLiteral(keyArg)) {
            val keyText = keyArg.text.trim()
            val prevLine = declaredStaticKeys[keyText]
            if (prevLine != null) {
                val msg = "⚠️ Duplicate key `$keyText` detected across `item` calls in " +
                    "`$callee` at line $callLine (previously declared at line $prevLine). " +
                    "In Jetpack Compose Lazy layouts, item keys must be unique or a runtime " +
                    "`IllegalArgumentException` will be thrown."
                findings.add(msg)
            } else {
                declaredStaticKeys[keyText] = callLine
            }
        }
    }

    private fun inspectItems(call: KtCallExpression, innerCallee: String, callLine: Int) {
        val keyArg = call.valueArguments
            .firstOrNull { it.getArgumentName()?.asName?.asString() == "key" }
            ?.getArgumentExpression()
        if (keyArg == null) {
            if (!reportedMissingKeyInContainer) {
                reportedMissingKeyInContainer = true
                val msg = "ℹ️ Call to `$innerCallee` at line $callLine in `$callee` does not " +
                    "specify a `key` parameter. Without an explicit key, item position is used as " +
                    "the key, which can cause state loss, recomposition churn, and UI glitches " +
                    "when items are reordered or removed."
                findings.add(msg)
            }
            return
        }

        val mutatedInKey = findReferencedIdentifiers(keyArg).intersect(mutatedIdentifiers)
        if (mutatedInKey.isNotEmpty()) {
            val mutatedId = mutatedInKey.first()
            val msg = "⚠️ Key for `$innerCallee` at line $callLine in `$callee` depends on " +
                "`$mutatedId`, which is mutated inside the layout. A computed key derived from " +
                "mutable state will collide across items and throw " +
                "`IllegalArgumentException: Key was already used`."
            findings.add(msg)
        } else {
            val lambdaExpr = keyArg as? KtLambdaExpression
            val returnExpr = lambdaExpr?.bodyExpression?.statements?.lastOrNull()
                ?: lambdaExpr?.bodyExpression
            if (returnExpr != null && ComposeAnalyzer.isConstantLiteral(returnExpr)) {
                val literalText = returnExpr.text.trim()
                val msg = "⚠️ Key lambda for `$innerCallee` at line $callLine in `$callee` " +
                    "returns a constant literal (`$literalText`). Every item in the collection " +
                    "will receive the same key, causing a runtime " +
                    "`IllegalArgumentException: Key was already used` when the list contains " +
                    "more than one item."
                findings.add(msg)
            }
        }
    }

    private fun extractMutatedIdentifiers(lambdaBody: KtExpression?): Set<String> {
        val mutated = mutableSetOf<String>()
        lambdaBody?.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(call: KtCallExpression) {
                val innerCallee = call.calleeExpression?.text.orEmpty()
                if (innerCallee in ComposeAnalyzer.lazyLayoutNames) {
                    return
                }
                super.visitCallExpression(call)
            }

            override fun visitUnaryExpression(expression: org.jetbrains.kotlin.psi.KtUnaryExpression) {
                val op = expression.operationToken
                if (op == org.jetbrains.kotlin.lexer.KtTokens.PLUSPLUS ||
                    op == org.jetbrains.kotlin.lexer.KtTokens.MINUSMINUS
                ) {
                    extractIdentifier(expression.baseExpression)?.let { mutated.add(it) }
                }
                super.visitUnaryExpression(expression)
            }

            override fun visitBinaryExpression(expression: org.jetbrains.kotlin.psi.KtBinaryExpression) {
                val op = expression.operationToken
                if (op in ComposeAnalyzer.assignmentTokens) {
                    extractIdentifier(expression.left)?.let { mutated.add(it) }
                }
                super.visitBinaryExpression(expression)
            }

            override fun visitProperty(property: KtProperty) {
                if (property.isVar) {
                    property.name?.let { mutated.add(it) }
                }
                super.visitProperty(property)
            }
        })
        return mutated
    }

    private fun extractIdentifier(expr: KtExpression?): String? =
        when (expr) {
            is org.jetbrains.kotlin.psi.KtSimpleNameExpression -> expr.getReferencedName()
            is org.jetbrains.kotlin.psi.KtDotQualifiedExpression ->
                (expr.selectorExpression as? org.jetbrains.kotlin.psi.KtSimpleNameExpression)?.getReferencedName()
            else -> null
        }

    private fun findReferencedIdentifiers(expr: KtExpression): Set<String> {
        val names = mutableSetOf<String>()
        expr.accept(object : KtTreeVisitorVoid() {
            override fun visitSimpleNameExpression(expression: org.jetbrains.kotlin.psi.KtSimpleNameExpression) {
                names.add(expression.getReferencedName())
                super.visitSimpleNameExpression(expression)
            }
        })
        return names
    }
}
