package com.gokorei.kotlinmcp.lsp

import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeAlias
import java.io.File

enum class OccurrenceKind { DECLARATION, REFERENCE }

/** A single named occurrence in a workspace file: either a declaration or a usage. */
data class KtSymbolOccurrence(
    val name: String,
    val fqn: String?,
    val file: String,
    val line: Int,
    val column: Int,
    val snippet: String,
    val kind: OccurrenceKind
)

/** Result of crawling a workspace: parsed files, all occurrences, and just the declarations. */
data class WorkspaceIndex(
    val rootPath: String,
    val fileCount: Int,
    val occurrences: List<KtSymbolOccurrence>,
    val declarations: List<KtSymbolOccurrence>,
    val truncated: Boolean = false,
    val maxFiles: Int? = null,
    val totalKtFiles: Int = fileCount
)

/** A single public API declaration extracted for the package_api tool. */
data class KtPublicApiElement(
    val kind: String,
    val name: String,
    val visibility: String,
    val signature: String,
    val docSummary: String?,
    val file: String,
    val line: Int
)

data class KtTypeOccurrence(
    val name: String,
    val file: String,
    val line: Int
)

data class KtTypeHierarchyResult(
    val symbol: String,
    val supertypes: List<String>,
    val subtypes: List<KtTypeOccurrence>
)

data class KtCallOccurrence(
    val callerName: String?,
    val file: String,
    val line: Int,
    val snippet: String
)

data class KtCallHierarchyResult(
    val symbol: String,
    val callers: List<KtCallOccurrence>
)

/**
 * Shared in-process workspace crawler using the K2 PSI AST parser.
 */
class WorkspaceSemanticIndexer(
    val defaultMaxFiles: Int = System.getenv("WORKSPACE_MAX_FILES")?.toIntOrNull() ?: 200,
    val vfsCache: VfsPsiCache = DefaultVfsPsiCache()
) {
    private data class FileCacheEntry(
        val lastModified: Long,
        val occurrences: List<KtSymbolOccurrence>,
        val declarations: List<KtSymbolOccurrence>
    )

    private val fileCache = java.util.concurrent.ConcurrentHashMap<String, FileCacheEntry>()

    fun index(workspacePath: String, maxFiles: Int = defaultMaxFiles): WorkspaceIndex {
        val root = File(workspacePath)
        if (!root.isDirectory) return WorkspaceIndex(workspacePath, 0, emptyList(), emptyList(), truncated = false, maxFiles = maxFiles)
        val allKt = root.walkTopDown().onEnter { dir -> !K2ResolutionUtils.isExcludedWorkspaceDir(dir) }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts" || it.extension == "java") }
            .toList()
        val truncated = allKt.size > maxFiles
        val files = allKt.take(maxFiles)
        val indexed = index(files, root.invariantSeparatorsPath)
        return indexed.copy(
            truncated = truncated,
            maxFiles = maxFiles,
            totalKtFiles = allKt.size
        )
    }

    fun index(files: List<File>, rootPath: String): WorkspaceIndex {
        val parsed = mutableListOf<Pair<File, KtFile>>()
        val javaOccurrences = mutableListOf<KtSymbolOccurrence>()
        val rootFile = File(rootPath)

        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (file.extension == "java") {
                val relPath = runCatching { file.relativeTo(rootFile).invariantSeparatorsPath }.getOrDefault(file.name)
                val project = K2SnippetFrontend.environment.project
                val psiJava = runCatching {
                    org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory.getInstance(project)
                        .createFileFromText(file.name, org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType.INSTANCE, text) as? org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
                }.getOrNull()
                psiJava?.accept(object : org.jetbrains.kotlin.com.intellij.psi.JavaRecursiveElementVisitor() {
                    override fun visitClass(aClass: org.jetbrains.kotlin.com.intellij.psi.PsiClass) {
                        val name = aClass.name
                        if (name != null) {
                            val fqn = aClass.qualifiedName ?: name
                            val line = com.gokorei.kotlinmcp.shared.SourceUtils.lineOf(text, aClass.textOffset)
                            javaOccurrences.add(
                                KtSymbolOccurrence(
                                    name = name,
                                    fqn = fqn,
                                    file = relPath,
                                    line = line,
                                    column = 1,
                                    snippet = aClass.text.take(80).replace("\n", " ").trim(),
                                    kind = OccurrenceKind.DECLARATION
                                )
                            )
                        }
                        super.visitClass(aClass)
                    }
                })
            } else {
                val psi = vfsCache.getOrParse(file) ?: K2SnippetFrontend.parsePsi(text) ?: continue
                parsed += file to psi
            }
        }
        val occurrences = mutableListOf<KtSymbolOccurrence>()
        val declarationsMap = mutableMapOf<String, String>()

        val session = if (parsed.isNotEmpty()) {
            val mainPsi = parsed.first().second
            val extraPsis = parsed.drop(1).map { it.second }
            K2SnippetFrontend.analyzeSession(mainPsi.text, extraPsis)
        } else null
        val bindingContext = session?.bindingContext ?: org.jetbrains.kotlin.resolve.BindingContext.EMPTY

        parsed.forEach { (_, psi) ->
            val pkg = psi.packageFqName.asString()
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitDeclaration(declaration: KtDeclaration) {
                    if (declaration is KtNamedDeclaration && declaration.name != null) {
                        val name = declaration.name!!
                        val descriptor = if (bindingContext != org.jetbrains.kotlin.resolve.BindingContext.EMPTY) {
                            bindingContext.get(org.jetbrains.kotlin.resolve.BindingContext.DECLARATION_TO_DESCRIPTOR, declaration)
                        } else null
                        val fqn = descriptor?.let { org.jetbrains.kotlin.resolve.DescriptorUtils.getFqName(it).asString() }
                            ?: declaration.fqName?.asString()
                            ?: if (pkg.isNotEmpty()) "$pkg.$name" else name
                        declarationsMap[name] = fqn
                        if (pkg.isNotEmpty()) {
                            declarationsMap["$pkg.$name"] = fqn
                        }
                    }
                    super.visitDeclaration(declaration)
                }
            })
        }

        occurrences += javaOccurrences
        parsed.forEach { (file, psi) ->
            val rel = try {
                rootFile.toPath().relativize(file.toPath()).toString()
            } catch (e: Exception) {
                file.name
            }
            occurrences += collectOccurrences(psi, rel, declarationsMap, bindingContext)
        }
        val declarations = occurrences.filter { it.kind == OccurrenceKind.DECLARATION }
        return WorkspaceIndex(rootPath, files.size, occurrences, declarations)
    }

    fun publicApiOf(files: List<File>, rootPath: String, packageName: String?): Pair<List<KtPublicApiElement>, List<KtFile>> {
        val parsed = mutableListOf<Pair<File, KtFile>>()
        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            val psi = K2SnippetFrontend.parsePsi(text) ?: continue
            if (packageName != null && psi.packageFqName.asString() != packageName) continue
            parsed += file to psi
        }
        val rootFile = File(rootPath)
        val elements = mutableListOf<KtPublicApiElement>()
        parsed.forEach { (file, psi) ->
            val rel = try {
                rootFile.toPath().relativize(file.toPath()).toString()
            } catch (e: Exception) {
                file.name
            }
            elements += collectPublicApi(psi, rel)
        }
        return elements to parsed.map { it.second }
    }

    private fun collectOccurrences(
        file: KtFile,
        rel: String,
        workspaceDeclarations: Map<String, String> = emptyMap(),
        bindingContext: org.jetbrains.kotlin.resolve.BindingContext = org.jetbrains.kotlin.resolve.BindingContext.EMPTY
    ): List<KtSymbolOccurrence> {
        val result = mutableListOf<KtSymbolOccurrence>()
        val text = file.text
        val imports = file.importDirectives.mapNotNull { it.importedFqName?.asString() }

        fun lineCol(offset: Int): Pair<Int, Int> {
            var line = 1
            var col = 1
            for (i in 0 until minOf(offset, text.length)) {
                if (text[i] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
            }
            return line to col
        }

        file.accept(object : KtTreeVisitorVoid() {
            override fun visitDeclaration(declaration: KtDeclaration) {
                if (declaration is KtNamedDeclaration && declaration.name != null) {
                    val start = declaration.nameIdentifier?.textRange?.startOffset
                        ?: declaration.textRange.startOffset
                    val (l, c) = lineCol(start)
                    val descriptor = if (bindingContext != org.jetbrains.kotlin.resolve.BindingContext.EMPTY) {
                        bindingContext.get(org.jetbrains.kotlin.resolve.BindingContext.DECLARATION_TO_DESCRIPTOR, declaration)
                    } else null
                    val fqn = descriptor?.let { org.jetbrains.kotlin.resolve.DescriptorUtils.getFqName(it).asString() }
                        ?: declaration.fqName?.asString()
                    result += KtSymbolOccurrence(
                        name = declaration.name!!,
                        fqn = fqn,
                        file = rel,
                        line = l,
                        column = c,
                        snippet = lineSnippet(text, start),
                        kind = OccurrenceKind.DECLARATION
                    )
                }
                super.visitDeclaration(declaration)
            }

            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                val name = expression.getReferencedName()
                if (name.isNotEmpty()) {
                    val start = expression.textRange.startOffset
                    val (l, c) = lineCol(start)
                    val pkg = file.packageFqName.asString()
                    val targetDescriptor = if (bindingContext != org.jetbrains.kotlin.resolve.BindingContext.EMPTY) {
                        bindingContext.get(org.jetbrains.kotlin.resolve.BindingContext.REFERENCE_TARGET, expression)
                    } else null
                    val resolvedFqn = targetDescriptor?.let { org.jetbrains.kotlin.resolve.DescriptorUtils.getFqName(it).asString() }
                    val matchingImport = imports.firstOrNull { it.endsWith(".$name") }
                    val declaredFqn = result.firstOrNull { it.kind == OccurrenceKind.DECLARATION && it.name == name }?.fqn
                    val samePkgFqn = if (pkg.isNotEmpty()) workspaceDeclarations["$pkg.$name"] else null
                    val fqn = resolvedFqn ?: matchingImport ?: declaredFqn ?: samePkgFqn ?: workspaceDeclarations[name] ?: if (pkg.isNotEmpty()) "$pkg.$name" else name
                    result += KtSymbolOccurrence(
                        name = name,
                        fqn = fqn,
                        file = rel,
                        line = l,
                        column = c,
                        snippet = lineSnippet(text, start),
                        kind = OccurrenceKind.REFERENCE
                    )
                }
                super.visitSimpleNameExpression(expression)
            }
        })
        return result
    }

    private fun collectPublicApi(file: KtFile, rel: String): List<KtPublicApiElement> {
        val elements = mutableListOf<KtPublicApiElement>()
        fun lineOf(offset: Int): Int = file.text.substring(0, minOf(offset, file.text.length)).count { it == '\n' } + 1

        for (decl in file.declarations) {
            if (decl !is KtNamedDeclaration || decl.name == null) continue
            val vis = visibilityOf(decl)
            if (vis == "private" || vis == "internal") continue
            val name = decl.name!!
            val doc = docSummaryOf(decl)
            val start = decl.nameIdentifier?.textRange?.startOffset ?: decl.textRange.startOffset
            val line = lineOf(start)

            when (decl) {
                is KtClassOrObject -> {
                    val kind = when {
                        decl is KtObjectDeclaration -> "object"
                        (decl as? KtClass)?.isInterface() == true -> "interface"
                        (decl as? KtClass)?.isEnum() == true -> "enum"
                        (decl as? KtClass)?.isAnnotation() == true -> "annotation"
                        (decl as? KtClass)?.isSealed() == true -> "sealed class"
                        else -> "class"
                    }
                    elements += KtPublicApiElement(kind, name, vis, "$kind $name", doc, rel, line)
                    for (member in decl.declarations) {
                        if (member !is KtNamedDeclaration || member.name == null) continue
                        val mvis = visibilityOf(member)
                        if (mvis == "private" || mvis == "internal") continue
                        val mLine = lineOf(member.nameIdentifier?.textRange?.startOffset ?: member.textRange.startOffset)
                        elements += memberApi(member, rel, mLine)
                    }
                }

                is KtNamedFunction ->
                    elements += KtPublicApiElement("fun", name, vis, signatureOfFunction(decl), doc, rel, line)

                is KtProperty ->
                    elements += KtPublicApiElement(if (decl.isVar) "var" else "val", name, vis, signatureOfProperty(decl), doc, rel, line)

                is KtTypeAlias ->
                    elements += KtPublicApiElement("typealias", name, vis, "typealias $name = ${decl.getTypeReference()?.text}", doc, rel, line)
            }
        }
        return elements
    }

    private fun memberApi(member: KtNamedDeclaration, rel: String, line: Int): KtPublicApiElement {
        val vis = visibilityOf(member)
        return when (member) {
            is KtNamedFunction ->
                KtPublicApiElement("fun", member.name!!, vis, signatureOfFunction(member), null, rel, line)
            is KtProperty ->
                KtPublicApiElement(if (member.isVar) "var" else "val", member.name!!, vis, signatureOfProperty(member), null, rel, line)
            else ->
                KtPublicApiElement("member", member.name!!, vis, member.text.take(60), null, rel, line)
        }
    }

    private fun signatureOfFunction(fn: KtNamedFunction): String {
        val params = fn.valueParameters.joinToString(", ") { p ->
            buildString {
                append(p.name)
                if (p.typeReference != null) append(": ${p.typeReference!!.text}")
            }
        }
        val ret = when {
            fn.typeReference != null -> ": ${fn.typeReference!!.text}"
            fn.initializer != null -> {
                val expr = fn.initializer!!.text.trim()
                when {
                    expr.startsWith("\"") && expr.endsWith("\"") -> ": String"
                    expr.toIntOrNull() != null -> ": Int"
                    expr.toBooleanStrictOrNull() != null -> ": Boolean"
                    expr.toDoubleOrNull() != null -> ": Double"
                    else -> ""
                }
            }
            else -> ""
        }
        return "fun ${fn.name}($params)$ret"
    }

    private fun signatureOfProperty(prop: KtProperty): String {
        val type = when {
            prop.typeReference != null -> prop.typeReference!!.text
            prop.initializer != null -> {
                val expr = prop.initializer!!.text.trim()
                when {
                    expr.startsWith("\"") && expr.endsWith("\"") -> "String"
                    expr.toIntOrNull() != null -> "Int"
                    expr.toBooleanStrictOrNull() != null -> "Boolean"
                    expr.toDoubleOrNull() != null -> "Double"
                    expr.startsWith("listOf(") || expr.startsWith("setOf(") -> expr.takeWhile { it != '(' }
                    else -> ""
                }
            }
            else -> ""
        }
        return "${if (prop.isVar) "var" else "val"} ${prop.name}${if (type.isNotEmpty()) ": $type" else ""}"
    }

    private fun visibilityOf(decl: KtNamedDeclaration): String {
        val modifierText = decl.modifierList?.text.orEmpty()
        return when {
            "private" in modifierText -> "private"
            "internal" in modifierText -> "internal"
            "protected" in modifierText -> "protected"
            else -> "public"
        }
    }

    private fun docSummaryOf(decl: KtNamedDeclaration): String? {
        val kdocText = decl.docComment?.text ?: return null
        return kdocText
            .removePrefix("/**")
            .removeSuffix("*/")
            .trim()
            .lineSequence()
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .take(200)
    }

    private fun lineSnippet(text: String, offset: Int): String {
        val start = text.lastIndexOf('\n', offset - 1) + 1
        val end = text.indexOf('\n', offset).let { if (it == -1) text.length else it }
        return text.substring(start, end).trim()
    }

    fun typeHierarchyOf(code: String, targetSymbol: String, workspacePath: String? = null): KtTypeHierarchyResult {
        val parsedFiles = mutableListOf<Pair<String, KtFile>>()
        if (code.isNotBlank()) {
            K2SnippetFrontend.parsePsi(code)?.let { parsedFiles += "Snippet" to it }
        }
        if (!workspacePath.isNullOrBlank()) {
            val root = File(workspacePath)
            if (root.isDirectory) {
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.take(100).forEach { file ->
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    val psi = K2SnippetFrontend.parsePsi(text) ?: return@forEach
                    val rel = try { root.toPath().relativize(file.toPath()).toString() } catch (e: Exception) { file.name }
                    parsedFiles += rel to psi
                }
            }
        }

        val supertypes = mutableListOf<String>()
        val subtypes = mutableListOf<KtTypeOccurrence>()

        parsedFiles.forEach { (relFile, psi) ->
            val text = psi.text
            fun lineOf(offset: Int): Int = text.substring(0, minOf(offset, text.length)).count { it == '\n' } + 1

            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                    super.visitClassOrObject(classOrObject)
                    val className = classOrObject.name ?: return
                    val supertypeNames = classOrObject.superTypeListEntries.mapNotNull { entry ->
                        (entry.typeReference?.typeElement as? org.jetbrains.kotlin.psi.KtUserType)?.referencedName
                            ?: entry.typeReference?.text?.trim()
                    }

                    if (className == targetSymbol) {
                        supertypes.addAll(supertypeNames)
                    }
                    if (supertypeNames.any { it == targetSymbol }) {
                        val offset = classOrObject.nameIdentifier?.textRange?.startOffset ?: classOrObject.textRange.startOffset
                        subtypes.add(KtTypeOccurrence(className, relFile, lineOf(offset)))
                    }
                }
            })
        }

        return KtTypeHierarchyResult(targetSymbol, supertypes.distinct(), subtypes.distinctBy { Pair(it.name, it.file) })
    }

    fun callHierarchyOf(code: String, targetSymbol: String, workspacePath: String? = null): KtCallHierarchyResult {
        val parsedFiles = mutableListOf<Pair<String, KtFile>>()
        if (code.isNotBlank()) {
            K2SnippetFrontend.parsePsi(code)?.let { parsedFiles += "Snippet" to it }
        }
        if (!workspacePath.isNullOrBlank()) {
            val root = File(workspacePath)
            if (root.isDirectory) {
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.take(100).forEach { file ->
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    val psi = K2SnippetFrontend.parsePsi(text) ?: return@forEach
                    val rel = try { root.toPath().relativize(file.toPath()).toString() } catch (e: Exception) { file.name }
                    parsedFiles += rel to psi
                }
            }
        }

        val callers = mutableListOf<KtCallOccurrence>()

        parsedFiles.forEach { (relFile, psi) ->
            val text = psi.text
            fun lineOf(offset: Int): Int = text.substring(0, minOf(offset, text.length)).count { it == '\n' } + 1

            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: org.jetbrains.kotlin.psi.KtCallExpression) {
                    val callee = expression.calleeExpression?.text
                    val parent = expression.parent as? org.jetbrains.kotlin.psi.KtDotQualifiedExpression
                    val qualified = parent?.receiverExpression?.text?.let { "$it.$callee" }
                    if (callee == targetSymbol || qualified == targetSymbol || qualified?.endsWith(".$targetSymbol") == true) {
                        var enclosing: KtNamedFunction? = null
                        var ancestor = expression.parent
                        while (ancestor != null && enclosing == null) {
                            if (ancestor is KtNamedFunction) enclosing = ancestor
                            ancestor = ancestor.parent
                        }
                        val line = lineOf(expression.textRange.startOffset)
                        val callSnippet = expression.text.take(80)
                        callers.add(KtCallOccurrence(enclosing?.name, relFile, line, callSnippet))
                    }
                    super.visitCallExpression(expression)
                }
            })
        }

        return KtCallHierarchyResult(targetSymbol, callers.distinct())
    }
}
