package com.gokorei.kotlinmcp.lsp

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.shared.CommandService

enum class LibraryAnalysisAction {
    ANALYZE_KTOR,
    ANALYZE_SERIALIZATION,
    ANALYZE_TESTS,
    ROUTE_MAP,
    ANALYZE_ANDROID_DI,
    ANALYZE_WORKMANAGER
}

/**
 * Service interface for library-aware static analysis of Kotlin snippets:
 * Ktor route/plugin usage, kotlinx.serialization correctness, and test-pattern
 * hygiene (runTest, MainDispatcherRule, MockK, Turbine).
 */
interface LibraryAnalysisService : CommandService<LibraryAnalysisAction> {
    fun execute(action: LibraryAnalysisAction, code: String, dataSources: List<String> = emptyList()): KotlinMcpResult
    override fun execute(action: LibraryAnalysisAction, code: String): KotlinMcpResult = execute(action, code, emptyList())
}

/**
 * Heuristic, text-based analysis of third-party library usage alongside K2-backed
 * semantic passes.
 */
class DefaultLibraryAnalysisService(
    private val workManagerAnalyzer: com.gokorei.kotlinmcp.analysis.WorkManagerAnalyzer = com.gokorei.kotlinmcp.analysis.WorkManagerAnalyzer()
) : LibraryAnalysisService {

    override fun execute(action: LibraryAnalysisAction, code: String, dataSources: List<String>): KotlinMcpResult {
        return when (action) {
            LibraryAnalysisAction.ANALYZE_KTOR -> analyzeKtor(code)
            LibraryAnalysisAction.ANALYZE_SERIALIZATION -> analyzeSerialization(code, dataSources)
            LibraryAnalysisAction.ANALYZE_TESTS -> analyzeTests(code)
            LibraryAnalysisAction.ROUTE_MAP -> routeMap(code)
            LibraryAnalysisAction.ANALYZE_ANDROID_DI -> analyzeAndroidDi(code)
            LibraryAnalysisAction.ANALYZE_WORKMANAGER -> workManagerAnalyzer.analyze(code)
        }
    }

    private fun analyzeKtor(code: String): KotlinMcpResult {
        val advisories = mutableListOf<String>()
        val psi = K2SnippetFrontend.parsePsi(code)

        if (psi != null) {
            val routePaths = mutableListOf<String>()
            var hasSerializable = false
            var hasContentNegotiation = false
            var returnsSerializable = false
            var returnsErrorsExplicitly = false
            var hasStatusPages = false
            var usesClientPlugins = false
            var usesServerPluginsOnClient = false
            var clientWithRouting = false

            psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                override fun visitClassOrObject(classOrObject: org.jetbrains.kotlin.psi.KtClassOrObject) {
                    if (classOrObject.annotationEntries.any { it.shortName?.asString() == "Serializable" }) {
                        hasSerializable = true
                    }
                    super.visitClassOrObject(classOrObject)
                }

                override fun visitCallExpression(expression: org.jetbrains.kotlin.psi.KtCallExpression) {
                    val callee = expression.calleeExpression?.text.orEmpty()

                    if (callee == "route") {
                        val pathArg = expression.valueArguments.firstOrNull()?.getArgumentExpression()?.text?.removeSurrounding("\"").orEmpty()
                        if (pathArg.isNotBlank()) routePaths.add(pathArg)
                    }

                    if (callee == "install") {
                        val firstArg = expression.valueArguments.firstOrNull()?.getArgumentExpression()
                        val argName = (firstArg as? org.jetbrains.kotlin.psi.KtNameReferenceExpression)?.getReferencedName()
                            ?: (firstArg as? org.jetbrains.kotlin.psi.KtDotQualifiedExpression)?.selectorExpression?.text
                            ?: firstArg?.text.orEmpty()
                        if (argName == "ContentNegotiation") {
                            hasContentNegotiation = true
                        }
                        if (argName == "StatusPages") {
                            hasStatusPages = true
                        }
                        val parentDot = expression.parent as? org.jetbrains.kotlin.psi.KtDotQualifiedExpression
                        val receiverText = parentDot?.receiverExpression?.text.orEmpty()
                        if (receiverText.contains("client.plugins") || receiverText.contains("client")) {
                            usesClientPlugins = true
                            if (argName in setOf("Routing", "StatusPages", "CallLogging")) {
                                usesServerPluginsOnClient = true
                            }
                        }
                    }

                    if (callee == "respond" || callee == "respondText" || callee == "respondStatus") {
                        val firstArg = expression.valueArguments.firstOrNull()?.getArgumentExpression()
                        val argText = firstArg?.text.orEmpty()
                        if (argText.startsWith("HttpStatusCode") || argText.startsWith("StatusCode")) {
                            returnsErrorsExplicitly = true
                        }
                        if (hasSerializable || firstArg is org.jetbrains.kotlin.psi.KtCallExpression) {
                            returnsSerializable = true
                        }
                    }

                    if (callee == "routing") {
                        if (usesClientPlugins) clientWithRouting = true
                    }

                    super.visitCallExpression(expression)
                }

                override fun visitDotQualifiedExpression(expression: org.jetbrains.kotlin.psi.KtDotQualifiedExpression) {
                    val selector = expression.selectorExpression?.text.orEmpty()
                    val receiver = expression.receiverExpression.text
                    if (receiver == "client" && selector == "plugins") {
                        usesClientPlugins = true
                    }
                    if (usesClientPlugins && selector in setOf("Routing", "StatusPages", "CallLogging")) {
                        usesServerPluginsOnClient = true
                    }
                    super.visitDotQualifiedExpression(expression)
                }
            })

            val duplicateRoutes = routePaths.groupingBy { it }.eachCount().filterValues { it > 1 }
            duplicateRoutes.forEach { (path, count) ->
                advisories.add("route_collision: route \"$path\" is declared $count times in the same routing block; only the first is reachable.")
            }

            if (hasSerializable && !hasContentNegotiation && returnsSerializable) {
                advisories.add(
                    "missing_content_negotiation: server returns @Serializable types but does not install " +
                        "ContentNegotiation with the JSON serializer. Add `install(ContentNegotiation) { json() }` " +
                        "(import io.ktor.serialization.kotlinx.json.json) so DTOs serialize correctly."
                )
            }

            if (returnsErrorsExplicitly && !hasStatusPages) {
                advisories.add(
                    "missing_status_pages: server maps errors to raw HttpStatusCode responses but does not install " +
                        "StatusPages. Consider `install(StatusPages) { exception<Throwable> { call, cause -> ... } }` to " +
                        "centralize error handling and avoid leaking stack traces."
                )
            }

            if (usesServerPluginsOnClient) {
                advisories.add(
                    "client_plugins_confusion: `client.plugins.install(...)` is being used with a server-only plugin. " +
                        "Routing/StatusPages/CallLogging are server modules (install on `server.plugins`); HttpClient uses " +
                        "`install(ContentNegotiation) { ... }` on the client instance for ContentNegotiation."
                )
            }
            if (clientWithRouting) {
                advisories.add(
                    "client_plugins_confusion: `client.plugins` is referenced alongside `routing { }`. Routing belongs to the " +
                        "server (application.module { routing { ... } }); the HTTP client does not define routes."
                )
            }
        }

        val content = if (advisories.isNotEmpty()) {
            "# Ktor Analysis Advisories\n" + advisories.distinct().joinToString("\n\n")
        } else {
            "# Ktor Analysis Advisories\nNo Ktor anti-patterns detected."
        }
        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("advisoryCount" to advisories.distinct().size.toString())
        )
    }

    private fun analyzeSerialization(code: String, dataSources: List<String>): KotlinMcpResult {
        val advisories = mutableListOf<String>()
        val psi = K2SnippetFrontend.parsePsi(code)

        if (psi != null) {
            val serialNames = mutableListOf<String>()

            psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                override fun visitClassOrObject(classOrObject: org.jetbrains.kotlin.psi.KtClassOrObject) {
                    val isSerializable = classOrObject.annotationEntries.any { it.shortName?.asString() == "Serializable" }
                    val className = classOrObject.name.orEmpty()

                    if (isSerializable && className.isNotBlank()) {
                        val ctor = classOrObject.primaryConstructor
                        val ctorIsPrivateOrProtected = ctor?.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) == true ||
                            ctor?.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PROTECTED_KEYWORD) == true
                        val classIsPrivateOrProtected = classOrObject.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) ||
                            classOrObject.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PROTECTED_KEYWORD)

                        if (ctorIsPrivateOrProtected || classIsPrivateOrProtected) {
                            advisories.add(
                                "hidden_primary_ctor: @Serializable class `$className` has a private/protected primary constructor. " +
                                    "kotlinx.serialization requires the primary constructor to be internal-or-public (or the class to expose " +
                                    "a matching public one) to synthesize a serializer."
                            )
                        }

                        val nonSerializableTypes = setOf("File", "InputStream", "OutputStream", "Socket", "Thread", "Context", "Activity", "View", "Bitmap")
                        classOrObject.primaryConstructorParameters.forEach { param ->
                            val paramName = param.name.orEmpty()
                            val typeText = param.typeReference?.text.orEmpty()
                            val hasDefault = param.hasDefaultValue()
                            val hasSerialName = param.annotationEntries.any { it.shortName?.asString() == "SerialName" }
                            if (paramName.isNotBlank() && !hasDefault && !hasSerialName && !param.hasValOrVar()) {
                                advisories.add(
                                    "evolution_risk: property `$paramName` in `$className` has no default value and no @SerialName. " +
                                        "Adding such a property to an already-published schema is a breaking change for old payloads. " +
                                        "Give it a default or an explicit @SerialName."
                                )
                            }
                            if (nonSerializableTypes.any { typeText == it || typeText.endsWith(".$it") }) {
                                advisories.add(
                                    "non_serializable_type: @Serializable class `$className` contains property `$paramName` of type `$typeText`. " +
                                        "This type cannot be serialized by kotlinx.serialization without a custom KSerializer. Use a serializable DTO or @Transient."
                                )
                            }
                        }
                    }

                    classOrObject.annotationEntries.forEach { ann ->
                        if (ann.shortName?.asString() == "SerialName") {
                            val valueArg = ann.valueArguments.firstOrNull()?.getArgumentExpression()?.text?.removeSurrounding("\"").orEmpty()
                            if (valueArg.isNotBlank()) serialNames.add(valueArg)
                        }
                    }

                    super.visitClassOrObject(classOrObject)
                }


                override fun visitParameter(parameter: org.jetbrains.kotlin.psi.KtParameter) {
                    parameter.annotationEntries.forEach { ann ->
                        if (ann.text.contains("SerialName")) {
                            val valueArg = ann.valueArguments.firstOrNull()?.getArgumentExpression()?.text?.removeSurrounding("\"").orEmpty()
                            if (valueArg.isNotBlank()) serialNames.add(valueArg)
                        }
                    }
                    super.visitParameter(parameter)
                }
            })

            val duplicateSerialNames = serialNames.groupingBy { it }.eachCount().filterValues { it > 1 }
            duplicateSerialNames.forEach { (name, count) ->
                advisories.add(
                    "serial_name_collision: @SerialName \"$name\" is used $count times. In a sealed hierarchy, two subclasses " +
                        "with the same discriminator produce ambiguous polymorphic deserialization. Use distinct @SerialName values."
                )
            }
        }

        dataSources.filter { it.isNotBlank() }.forEach { link ->
            advisories.add(
                "schema_diff_input: a prior schema was supplied via $link. When evolving a published schema, every new property " +
                    "must have a default value (or @SerialName + default) so old serialized payloads still decode."
            )
        }

        val content = if (advisories.isNotEmpty()) {
            "# Serialization Analysis Advisories\n" + advisories.distinct().joinToString("\n\n")
        } else {
            "# Serialization Analysis Advisories\nNo kotlinx.serialization issues detected."
        }
        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("advisoryCount" to advisories.distinct().size.toString())
        )
    }

    private fun analyzeTests(code: String): KotlinMcpResult {
        val advisories = mutableListOf<String>()
        val psi = K2SnippetFrontend.parsePsi(code)

        if (psi != null) {
            var usesMainDispatcher = false
            var hasMainRule = false
            var hasEvery = false
            var hasVerify = false
            var signalsAssertion = false
            var usesTurbine = false
            var hasTurbineAwait = false
            var mocksStatic = false
            var unmocksAll = false

            psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                override fun visitImportDirective(importDirective: org.jetbrains.kotlin.psi.KtImportDirective) {
                    val importPath = importDirective.importPath?.pathStr.orEmpty()
                    if (importPath.contains("app.cash.turbine")) usesTurbine = true
                    super.visitImportDirective(importDirective)
                }

                override fun visitSimpleNameExpression(expression: org.jetbrains.kotlin.psi.KtSimpleNameExpression) {
                    val name = expression.getReferencedName()
                    if (name == "assertThat" || name == "assertEquals" || name == "shouldBe") {
                        signalsAssertion = true
                    }
                    if (name == "Turbine" || name == "turbine") {
                        usesTurbine = true
                    }
                    if (name == "unmockkAll" || name == "unmockkObject" || name == "unmockkStatic") {
                        unmocksAll = true
                    }
                    super.visitSimpleNameExpression(expression)
                }

                override fun visitDotQualifiedExpression(expression: org.jetbrains.kotlin.psi.KtDotQualifiedExpression) {
                    val text = expression.text
                    if (text.contains("Dispatchers.Main") || text.contains("Dispatchers.getMain")) {
                        usesMainDispatcher = true
                    }
                    if (text.contains("Dispatchers.setMain")) {
                        hasMainRule = true
                    }
                    super.visitDotQualifiedExpression(expression)
                }

                override fun visitClassOrObject(classOrObject: org.jetbrains.kotlin.psi.KtClassOrObject) {
                    val text = classOrObject.text
                    if (text.contains("MainDispatcherRule") || classOrObject.declarations.any { decl ->
                            decl.annotationEntries.any { it.text.contains("Rule") }
                        }) {
                        hasMainRule = true
                    }
                    super.visitClassOrObject(classOrObject)
                }

                override fun visitCallExpression(expression: org.jetbrains.kotlin.psi.KtCallExpression) {
                    val callee = expression.calleeExpression?.text.orEmpty()

                    if (callee == "runBlocking") {
                        advisories.add(
                            "runblocking_in_test: `runBlocking { }` in a test blocks the thread and skips virtual-time control. " +
                                "Use `runTest { }` (kotlinx.coroutines.test) so delays advance instantly and `StandardTestDispatcher` " +
                                "virtual time is honored."
                        )
                    }

                    if (callee == "every") hasEvery = true
                    if (callee == "verify" || callee == "confirmVerified") hasVerify = true

                    if (callee == "test") usesTurbine = true
                    if (callee == "awaitItem" || callee == "awaitError" || callee == "awaitComplete") hasTurbineAwait = true

                    if (callee == "mockkObject" || callee == "mockkStatic") mocksStatic = true

                    super.visitCallExpression(expression)
                }
            })

            if (usesMainDispatcher && !hasMainRule) {
                advisories.add(
                    "missing_main_dispatcher_rule: test touches Dispatchers.Main but no MainDispatcherRule (or Dispatchers.setMain) is set. " +
                        "Add a JUnit rule that swaps in a StandardTestDispatcher so Main is deterministic in tests."
                )
            }

            if (hasEvery && !hasVerify && signalsAssertion) {
                advisories.add(
                    "mockk_verify_gap: `every { ... }` stubs behavior but no `verify { ... }` asserts it was called, yet the test " +
                        "makes assertions. Add `verify { mock.method(...) }` (or `confirmVerified(mock)`) to assert the interaction."
                )
            }

            if (usesTurbine && !hasTurbineAwait) {
                advisories.add(
                    "turbine_unconsumed: Turbine `.test { }` block has no awaitItem()/awaitError()/awaitComplete() consumer. " +
                        "Unconsumed emissions are discarded; assert each expected emission explicitly."
                )
            }

            if (mocksStatic && !unmocksAll) {
                advisories.add(
                    "mockk_leak: `mockkObject`/`mockkStatic` alters global state; without `unmockkAll()` (or per-object unmock) in " +
                        "@AfterEach the mock leaks into other tests. Add `@AfterEach fun tearDown() { unmockkAll() }`."
                )
            }
        }

        val content = if (advisories.isNotEmpty()) {
            "# Test-Pattern Analysis Advisories\n" + advisories.distinct().joinToString("\n\n")
        } else {
            "# Test-Pattern Analysis Advisories\nNo test anti-patterns detected."
        }
        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("advisoryCount" to advisories.distinct().size.toString())
        )
    }

    private fun routeMap(code: String): KotlinMcpResult {
        val routes = mutableListOf<String>()
        val psi = K2SnippetFrontend.parsePsi(code)

        if (psi != null) {
            val lineOf = { offset: Int -> com.gokorei.kotlinmcp.shared.SourceUtils.lineOf(code, offset) }
            val httpMethods = setOf("get", "post", "put", "delete", "patch", "head", "options")

            fun extractRoutes(element: org.jetbrains.kotlin.com.intellij.psi.PsiElement, currentPrefix: String) {
                if (element is org.jetbrains.kotlin.psi.KtCallExpression) {
                    val callee = element.calleeExpression?.text?.lowercase().orEmpty()
                    if (callee == "route") {
                        val arg = element.valueArguments.firstOrNull()?.getArgumentExpression()?.text?.removeSurrounding("\"").orEmpty()
                        val newPrefix = (currentPrefix.removeSuffix("/") + "/" + arg.removePrefix("/")).trimEnd('/')
                        element.children.forEach { extractRoutes(it, if (newPrefix.isBlank()) "/" else newPrefix) }
                        return
                    } else if (callee in httpMethods) {
                        val arg = element.valueArguments.firstOrNull()?.getArgumentExpression()?.text?.removeSurrounding("\"").orEmpty()
                        val fullPath = (currentPrefix.removeSuffix("/") + "/" + arg.removePrefix("/")).replace("//", "/")
                        val line = lineOf(element.textRange.startOffset)
                        routes.add("${callee.uppercase()} ${if (fullPath.isBlank()) "/" else fullPath} (Line $line)")
                    }
                }
                element.children.forEach { extractRoutes(it, currentPrefix) }
            }

            extractRoutes(psi, "")
        }

        val content = buildString {
            appendLine("# HTTP Route Map")
            if (routes.isNotEmpty()) {
                routes.distinct().forEach { appendLine("- `$it`") }
            } else {
                appendLine("- (no HTTP routes declared in snippet)")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("routeCount" to routes.distinct().size.toString())
        )
    }

    private fun analyzeAndroidDi(code: String): KotlinMcpResult {
        val advisories = mutableListOf<String>()
        val psi = K2SnippetFrontend.parsePsi(code)

        if (psi != null) {
            psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                override fun visitClassOrObject(classOrObject: org.jetbrains.kotlin.psi.KtClassOrObject) {
                    val className = classOrObject.name ?: "Anonymous"
                    val annotations = classOrObject.annotationEntries.mapNotNull { it.shortName?.asString() }.toSet()
                    val superTypeListEntries = (classOrObject as? org.jetbrains.kotlin.psi.KtClass)?.superTypeListEntries.orEmpty()

                    val isModule = "Module" in annotations
                    if (isModule && "InstallIn" !in annotations && "TestInstallIn" !in annotations) {
                        advisories.add("⚠️ Class `$className` is annotated with `@Module` but lacks `@InstallIn(...)` (or `@TestInstallIn(...)`). Hilt requires an installation annotation (e.g. `@InstallIn(SingletonComponent::class)`) to determine component binding scope.")
                    }

                    val knownViewModelTypes = setOf("ViewModel", "AndroidViewModel", "androidx.lifecycle.ViewModel", "androidx.lifecycle.AndroidViewModel")
                    val isViewModel = superTypeListEntries.any { entry ->
                        val typeRef = entry.typeReference
                        val userType = typeRef?.typeElement as? org.jetbrains.kotlin.psi.KtUserType
                        val typeName = userType?.referencedName ?: typeRef?.text?.trim().orEmpty()
                        typeName in knownViewModelTypes
                    }
                    if (isViewModel) {
                        val hasInjectCtor = classOrObject.primaryConstructor?.annotationEntries?.any { it.shortName?.asString() == "Inject" } == true ||
                            classOrObject.secondaryConstructors.any { it.annotationEntries.any { a -> a.shortName?.asString() == "Inject" } }
                        if (hasInjectCtor && "HiltViewModel" !in annotations) {
                            advisories.add("⚠️ ViewModel `$className` has `@Inject constructor` but lacks `@HiltViewModel`. Add `@HiltViewModel` to allow Hilt to provide this ViewModel in `@AndroidEntryPoint` classes or `hiltViewModel()` calls.")
                        }
                    }

                    val knownAndroidComponentTypes = setOf(
                        "Activity", "ComponentActivity", "AppCompatActivity", "FragmentActivity",
                        "Fragment", "DialogFragment",
                        "Service", "IntentService", "JobService",
                        "BroadcastReceiver"
                    )
                    val isAndroidComponent = superTypeListEntries.any { entry ->
                        val typeRef = entry.typeReference
                        val userType = typeRef?.typeElement as? org.jetbrains.kotlin.psi.KtUserType
                        val typeName = userType?.referencedName ?: typeRef?.text?.trim().orEmpty()
                        typeName in knownAndroidComponentTypes
                    }
                    if (isAndroidComponent) {
                        val hasInjectFields = classOrObject.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtProperty>().any { prop ->
                            prop.annotationEntries.any { it.shortName?.asString() == "Inject" }
                        }
                        if (hasInjectFields && "AndroidEntryPoint" !in annotations) {
                            advisories.add("⚠️ Android component `$className` contains `@Inject` properties but lacks `@AndroidEntryPoint`. Add `@AndroidEntryPoint` to enable field injection.")
                        }
                    }

                    super.visitClassOrObject(classOrObject)
                }
            })
        }

        val content = if (advisories.isNotEmpty()) {
            "# Android Dependency Injection (Hilt/Dagger) Findings\n" + advisories.distinct().joinToString("\n\n")
        } else {
            "# Android Dependency Injection (Hilt/Dagger) Findings\nNo obvious DI annotation wiring issues detected."
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("advisoriesCount" to advisories.distinct().size.toString())
        )
    }
}
