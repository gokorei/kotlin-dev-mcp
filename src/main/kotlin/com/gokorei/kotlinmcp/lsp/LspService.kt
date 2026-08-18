package com.gokorei.kotlinmcp.lsp

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.doc.DocService
import com.gokorei.kotlinmcp.doc.DefaultDocService
import com.gokorei.kotlinmcp.doc.DocAction
import org.jetbrains.kotlin.psi.KtReferenceExpression
import java.io.File

enum class LspAction {
    FIND_DEFINITION,
    FIND_REFERENCES,
    GET_COMPLETIONS,
    RENAME_SYMBOL,
    WORKSPACE_SEARCH,
    WORKSPACE_REFERENCES,
    TYPE_HIERARCHY,
    CALL_HIERARCHY
}

/**
 * Service providing text-level Kotlin language intelligence for LLMs:
 * definition resolution, reference searching, code completion, and symbol
 * renaming.
 */
interface LspService {
    fun execute(
        action: LspAction,
        code: String,
        symbol: String? = null,
        newName: String? = null,
        workspacePath: String? = null
    ): KotlinMcpResult
}

class DefaultLspService(
    private val docService: DocService = DefaultDocService(),
    private val semanticEngine: K2SemanticEngine = DefaultK2SemanticEngine()
) : LspService {

    private val indexer = WorkspaceSemanticIndexer()

    override fun execute(
        action: LspAction,
        code: String,
        symbol: String?,
        newName: String?,
        workspacePath: String?
    ): KotlinMcpResult {
        return when (action) {
            LspAction.FIND_DEFINITION -> findDefinition(code, symbol, workspacePath)
            LspAction.FIND_REFERENCES -> findReferences(code, symbol, workspacePath)
            LspAction.GET_COMPLETIONS -> getCompletions(code, symbol)
            LspAction.RENAME_SYMBOL -> renameSymbol(code, symbol, newName, workspacePath)
            LspAction.WORKSPACE_SEARCH -> workspaceSearch(symbol, workspacePath)
            LspAction.WORKSPACE_REFERENCES -> workspaceReferences(symbol, workspacePath)
            LspAction.TYPE_HIERARCHY -> typeHierarchy(code, symbol, workspacePath)
            LspAction.CALL_HIERARCHY -> callHierarchy(code, symbol, workspacePath)
        }
    }


    private fun findDefinition(code: String, symbol: String?, workspacePath: String?): KotlinMcpResult {
        if (symbol.isNullOrBlank()) {
            return KotlinMcpResult.Error(
                message = "Symbol name is required for findDefinition.",
                code = "INVALID_ARGUMENTS"
            )
        }
        val s = symbol.trim()

        val session = runCatching { semanticEngine.session(workspacePath, code) }.getOrNull()
        if (session != null) {
            val ref = findSnippetReference(session.file, s)
            if (ref != null) {
                val resolved = runCatching { semanticEngine.resolveReference(session, ref, workspacePath) }.getOrNull()
                when (resolved?.source) {
                    ResolvedSource.SNIPPET -> return definitionResult(
                        s, "Line ${resolved.line}", resolved.signature, line = resolved.line
                    )
                    ResolvedSource.WORKSPACE -> return definitionResult(
                        s, "${resolved.file}:${resolved.line}", resolved.signature, line = resolved.line, file = resolved.file
                    )
                    else -> Unit // EXTERNAL / UNRESOLVED fall through to the stdlib doc lookup
                }
            }
            val decl = findSnippetDeclaration(session.file, s)
            if (decl != null) {
                return definitionResult(s, "Line ${decl.first}", decl.second, line = decl.first)
            }
        }

        val stdlibMatch = stdlibDocFor(s)
        if (stdlibMatch != null) {
            return KotlinMcpResult.Success(
                content = "# Definition of `$s` (Kotlin Standard Library)\n- Type/Signature: $stdlibMatch",
                metadata = mapOf("symbol" to s, "found" to "true", "source" to "stdlib")
            )
        }
        return KotlinMcpResult.Success(
            content = "Symbol `$s` declaration not found in snippet. It may be an external dependency or imported symbol.",
            metadata = mapOf("symbol" to s, "found" to "false")
        )
    }

    private fun definitionResult(
        symbol: String,
        location: String,
        signature: String?,
        line: Int,
        file: String? = null
    ): KotlinMcpResult {
        val content = buildString {
            appendLine("# Definition of `$symbol`")
            appendLine("- Defined at: $location")
            signature?.let { appendLine("- Declaration: `$it`") }
        }
        val metadata = buildMap {
            put("symbol", symbol)
            put("found", "true")
            put("line", line.toString())
            if (file != null) put("file", file)
        }
        return KotlinMcpResult.Success(content = content.trim(), metadata = metadata)
    }

    /** First reference expression in the snippet matching [symbol], if any. */
    private fun findSnippetReference(file: org.jetbrains.kotlin.psi.KtFile, symbol: String): KtReferenceExpression? {
        var found: KtReferenceExpression? = null
        file.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
            override fun visitSimpleNameExpression(expression: org.jetbrains.kotlin.psi.KtSimpleNameExpression) {
                if (found == null && expression.getReferencedName() == symbol) {
                    found = expression
                }
                super.visitSimpleNameExpression(expression)
            }
        })
        return found
    }

    /** Line + signature of a declaration matching [symbol] in the snippet, if any. */
    private fun findSnippetDeclaration(file: org.jetbrains.kotlin.psi.KtFile, symbol: String): Pair<Int, String>? {
        val text = file.text
        var result: Pair<Int, String>? = null
        file.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
            override fun visitNamedDeclaration(declaration: org.jetbrains.kotlin.psi.KtNamedDeclaration) {
                if (result == null && declaration is org.jetbrains.kotlin.psi.KtPrimaryConstructor) {
                    super.visitNamedDeclaration(declaration)
                    return
                }
                if (result == null && declaration.name == symbol) {
                    result = com.gokorei.kotlinmcp.shared.SourceUtils.lineOf(text, declaration.textRange.startOffset) to signatureOf(declaration)
                }
                super.visitNamedDeclaration(declaration)
            }
        })
        return result
    }

    private fun signatureOf(decl: org.jetbrains.kotlin.psi.KtNamedDeclaration): String {
        val text = decl.text.take(140).replace(Regex("\\s+"), " ").trim()
        return text
    }

    private fun findReferences(code: String, symbol: String?, workspacePath: String?): KotlinMcpResult {
        if (symbol.isNullOrBlank()) {
            return KotlinMcpResult.Error(
                message = "Symbol name is required for findReferences.",
                code = "INVALID_ARGUMENTS"
            )
        }
        val s = symbol.trim()
        val references = mutableListOf<String>()

        val session = runCatching { semanticEngine.session(workspacePath, code) }.getOrNull()
        if (session != null) {
            val rows = runCatching { semanticEngine.referencesForSymbol(session, s, workspacePath) }.getOrNull().orEmpty()
            val ordered = rows.sortedWith(compareBy(
                { if (it.file == "Snippet.kt") 0 else 1 },
                { it.file },
                { it.line },
                { it.column }
            ))
            ordered.forEach { ref ->
                val fqn = ref.fqn?.let { " → $it" }.orEmpty()
                if (ref.file == "Snippet.kt") {
                    references.add("Snippet: Line ${ref.line}: `${ref.snippet}`")
                } else {
                    references.add("${ref.file}: Line ${ref.line}: `${ref.snippet}`$fqn")
                }
            }
        }

        if (references.isEmpty() && session == null) {
            val text = code
            fun lineOf(offset: Int): Int = text.substring(0, minOf(offset, text.length)).count { '\n' == it } + 1

            val psi = K2SnippetFrontend.parsePsi(code)
            if (psi != null) {
                val offsets = mutableSetOf<Int>()
                psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                    override fun visitSimpleNameExpression(expression: org.jetbrains.kotlin.psi.KtSimpleNameExpression) {
                        if (expression.getReferencedName() == s) offsets.add(expression.textRange.startOffset)
                        super.visitSimpleNameExpression(expression)
                    }
                })
                psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                    override fun visitNamedDeclaration(declaration: org.jetbrains.kotlin.psi.KtNamedDeclaration) {
                        if (declaration.name == s) {
                            declaration.nameIdentifier?.textRange?.let { offsets.add(it.startOffset) }
                        }
                        super.visitNamedDeclaration(declaration)
                    }
                })
                offsets.sorted().forEach { offset ->
                    val snippetLine = lineOf(offset)
                    val lineText = text.lines().getOrNull(snippetLine - 1)?.trim().orEmpty()
                    references.add("Snippet: Line $snippetLine: `$lineText`")
                }
            } else {
                code.lines().forEachIndexed { index, line ->
                    if (Regex("""\b${Regex.escape(s)}\b""").containsMatchIn(line)) {
                        references.add("Snippet: Line ${index + 1}: `${line.trim()}`")
                    }
                }
            }
        }

        val content = if (references.isNotEmpty()) {
            "# Symbol References for `$s` (${references.size} occurrences found)\n\n" + references.distinct().joinToString("\n")
        } else {
            "No occurrences or references of symbol `$s` were found."
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("symbol" to s, "referenceCount" to references.distinct().size.toString())
        )
    }

    private fun getCompletions(code: String, symbol: String?): KotlinMcpResult {
        val prefix = (symbol ?: "").trim()

        val session = runCatching { semanticEngine.session(null, code) }.getOrNull()
        val candidates = session?.let { runCatching { semanticEngine.completionCandidates(it, prefix) }.getOrNull() }
        val semantic = ((candidates?.members).orEmpty() + (candidates?.scope).orEmpty()).distinct().sortedBy { it.lowercase() }

        val commonCompletions = listOf(
            "map { it }", "filter { it }", "mapNotNull { it }", "flatMap { it }",
            "takeIf { it }", "runCatching { }", "let { it }", "apply { }", "also { it }",
            "withContext(Dispatchers.IO) { }", "coroutineScope { }", "buildList { }", "buildMap { }"
        )
        val curated = if (prefix.isNotBlank() && !prefix.contains(".")) {
            commonCompletions.filter { it.startsWith(prefix, ignoreCase = true) || it.contains(prefix, ignoreCase = true) }
        } else {
            commonCompletions
        }

        val content = if (semantic.isNotEmpty() || curated.isNotEmpty()) {
            buildString {
                appendLine("# Code Completions for `${prefix.ifEmpty { "<all>" }}`")
                if (semantic.isNotEmpty()) {
                    appendLine()
                    appendLine("## Semantic candidates")
                    semantic.forEach { appendLine(" - `$it`") }
                }
                if (curated.isNotEmpty()) {
                    appendLine()
                    appendLine("## Idiom suggestions (curated)")
                    curated.forEach { appendLine(" - `$it`") }
                }
            }.trim()
        } else {
            "No matching completions found for prefix `$prefix`."
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("prefix" to prefix, "completionCount" to (semantic.size + curated.size).toString())
        )
    }

    private fun renameSymbol(code: String, oldName: String?, newName: String?, workspacePath: String?, maxFiles: Int = 500): KotlinMcpResult {
        if (oldName.isNullOrBlank() || newName.isNullOrBlank()) {
            return KotlinMcpResult.Error(
                message = "Both oldName and newName parameters are required for renameSymbol.",
                code = "INVALID_ARGUMENTS"
            )
        }
        val old = oldName.trim()
        val new = newName.trim()

        val (refactoredCode, snippetReplacementCount) = renameAstNodes(code, old, new)

        val workspaceChanges = mutableListOf<String>()
        var isTruncated = false
        var totalMatchingFiles = 0

        if (!workspacePath.isNullOrBlank()) {
            val root = File(workspacePath)
            if (root.exists() && root.isDirectory) {
                val index = indexer.index(workspacePath)
                val codePsi = K2SnippetFrontend.parsePsi(code)
                val codePackage = codePsi?.packageFqName?.asString().orEmpty()
                val codeImports = codePsi?.importDirectives?.mapNotNull { it.importedFqName?.asString() }?.toSet().orEmpty()

                val allTargetDecls = index.declarations.filter { it.name == old }
                val scopedTargetDecls = if (codePackage.isNotEmpty()) {
                    allTargetDecls.filter { decl ->
                        val declFqn = decl.fqn.orEmpty()
                        declFqn.startsWith("$codePackage.") || declFqn == codePackage || codeImports.contains(declFqn) || codeImports.any { imp -> declFqn.startsWith("$imp.") }
                    }.ifEmpty { allTargetDecls }
                } else {
                    allTargetDecls
                }
                val targetFqns = scopedTargetDecls.mapNotNull { it.fqn }.toSet()

                val allFiles = root.walkTopDown()
                    .onEnter { dir ->
                        val name = dir.name
                        name != "build" && name != ".gradle" && name != ".git" && name != "out" && name != "node_modules"
                    }
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
                    .toList()

                totalMatchingFiles = allFiles.size
                if (totalMatchingFiles > maxFiles) {
                    isTruncated = true
                }

                allFiles.take(maxFiles).forEach { file ->
                    try {
                        val original = file.readText()
                        val (updated, count) = renameAstNodes(original, old, new, targetFqns)
                        if (count > 0 && updated != original) {
                            file.writeText(updated)
                            val relative = file.relativeTo(root).path
                            workspaceChanges.add("$relative: $count replacements")
                        }
                    } catch (e: Exception) {
                        val relative = runCatching { file.relativeTo(root).path }.getOrDefault(file.name)
                        workspaceChanges.add("$relative: FAILED (${e.message})")
                    }
                }
            }
        }

        val content = buildString {
            if (isTruncated) {
                appendLine("⚠ Workspace scan truncated: examined $maxFiles of $totalMatchingFiles Kotlin files.")
                appendLine()
            }
            appendLine("# Symbol Rename: `$old` -> `$new`")
            appendLine("- Replacements in snippet: $snippetReplacementCount")
            if (workspaceChanges.isNotEmpty()) {
                appendLine("- Workspace files updated:")
                workspaceChanges.forEach { appendLine("  - $it") }
            } else if (!workspacePath.isNullOrBlank()) {
                appendLine("- No workspace files matched (or workspacePath not a readable directory).")
            }
            appendLine()
            appendLine("## Refactored Snippet")
            appendLine("```kotlin")
            appendLine(refactoredCode)
            appendLine("```")
        }

        val metadataMap = mutableMapOf(
            "oldName" to old,
            "newName" to new,
            "replacementCount" to snippetReplacementCount.toString(),
            "workspaceFileCount" to workspaceChanges.size.toString()
        )
        if (isTruncated) {
            metadataMap["truncated"] = "true"
            metadataMap["totalFiles"] = totalMatchingFiles.toString()
            metadataMap["maxFiles"] = maxFiles.toString()
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = metadataMap
        )
    }

    private fun renameAstNodes(
        code: String,
        oldName: String,
        newName: String,
        targetFqns: Set<String> = emptySet()
    ): Pair<String, Int> {
        val psi = K2SnippetFrontend.parsePsi(code) ?: return code to 0

        val filePackage = psi.packageFqName.asString()
        val fileImports = psi.importDirectives.mapNotNull { it.importedFqName?.asString() }.toSet()
        val matchesTargetFqn = targetFqns.isEmpty() || targetFqns.any { fqn ->
            if (filePackage.isEmpty()) {
                !fqn.contains(".") || fqn == oldName
            } else {
                fqn.startsWith("$filePackage.") || fqn == filePackage || fileImports.contains(fqn) || fileImports.any { imp -> fqn.startsWith("$imp.") }
            }
        }

        val rangesToReplace = mutableListOf<org.jetbrains.kotlin.com.intellij.openapi.util.TextRange>()
        var fileDeclaresTarget = false

        psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
            override fun visitNamedDeclaration(declaration: org.jetbrains.kotlin.psi.KtNamedDeclaration) {
                if (declaration !is org.jetbrains.kotlin.psi.KtPrimaryConstructor && declaration.name == oldName) {
                    val fqn = declaration.fqName?.asString() ?: declaration.name
                    if (targetFqns.isEmpty() || (fqn != null && fqn in targetFqns)) {
                        declaration.nameIdentifier?.textRange?.let { rangesToReplace.add(it) }
                        fileDeclaresTarget = true
                    }
                }
                super.visitNamedDeclaration(declaration)
            }

            override fun visitSimpleNameExpression(expression: org.jetbrains.kotlin.psi.KtSimpleNameExpression) {
                if (expression.getReferencedName() == oldName) {
                    if (matchesTargetFqn || fileDeclaresTarget) {
                        rangesToReplace.add(expression.textRange)
                    }
                }
                super.visitSimpleNameExpression(expression)
            }
        })

        if (rangesToReplace.isEmpty()) {
            return code to 0
        }

        val offsetShift = psi.text.indexOf(code).let { if (it >= 0) it else 0 }

        val sortedRanges = rangesToReplace.distinctBy { it.startOffset to it.endOffset }
            .sortedByDescending { it.startOffset }

        val sb = StringBuilder(code)
        for (range in sortedRanges) {
            val start = range.startOffset - offsetShift
            val end = range.endOffset - offsetShift
            if (start in 0..sb.length && end in start..sb.length) {
                sb.replace(start, end, newName)
            }
        }

        return sb.toString() to sortedRanges.size
    }

    private fun workspaceSearch(symbol: String?, workspacePath: String?): KotlinMcpResult {
        if (workspacePath.isNullOrBlank()) {
            return KotlinMcpResult.Error(
                message = "workspacePath is required for workspace_search.",
                code = "INVALID_ARGUMENTS"
            )
        }
        val query = symbol?.trim().orEmpty()
        if (query.isEmpty()) {
            return KotlinMcpResult.Error(
                message = "Symbol name is required for workspace_search.",
                code = "INVALID_ARGUMENTS"
            )
        }
        val index = indexer.index(workspacePath)
        val matches = fuzzyMatches(index.declarations, query)
        val content = if (matches.isNotEmpty()) {
            "# Workspace Symbol Search for `$query` (${matches.size} matches)\n\n" +
                matches.joinToString("\n") { (occ, score) ->
                    val fqn = occ.fqn?.let { " ($it)" } ?: ""
                    "- `$score` ${occ.name}$fqn — ${occ.file}:${occ.line}"
                }
        } else {
            "No symbols matching `$query` were found in the workspace."
        }
        val metadata = buildMap {
            put("symbol", query)
            put("matchCount", matches.size.toString())
            put("fileCount", index.fileCount.toString())
            if (index.truncated) {
                put("truncated", "true")
                put("maxFiles", index.maxFiles?.toString() ?: "")
                put("totalKtFiles", index.totalKtFiles.toString())
            }
        }
        return KotlinMcpResult.Success(
            content = indexStatusPrefix(index) + content,
            metadata = metadata
        )
    }

    private fun workspaceReferences(symbol: String?, workspacePath: String?): KotlinMcpResult {
        if (workspacePath.isNullOrBlank()) {
            return KotlinMcpResult.Error(
                message = "workspacePath is required for workspace_references.",
                code = "INVALID_ARGUMENTS"
            )
        }
        val target = symbol?.trim().orEmpty()
        if (target.isEmpty()) {
            return KotlinMcpResult.Error(
                message = "Symbol name is required for workspace_references.",
                code = "INVALID_ARGUMENTS"
            )
        }
        val session = runCatching { semanticEngine.session(workspacePath, "") }.getOrNull()
        val refs = session?.let {
            runCatching { semanticEngine.referencesForSymbol(it, target, workspacePath) }.getOrNull().orEmpty()
        }.orEmpty().sortedWith(compareBy({ it.file }, { it.line }, { it.column }))

        val content = if (refs.isNotEmpty()) {
            val lines = refs.joinToString("\n") { ref ->
                val fqn = ref.fqn?.let { " → $it" }.orEmpty()
                "- ${ref.file}:${ref.line}:${ref.column} [${ref.kind}]$fqn `${ref.snippet}`"
            }
            "# Symbol References for `$target` (${refs.size} occurrences)\n\n$lines"
        } else {
            "No occurrences or references of symbol `$target` were found in the workspace."
        }
        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("symbol" to target, "referenceCount" to refs.size.toString())
        )
    }

    private fun indexStatusPrefix(index: WorkspaceIndex): String = buildString {
        if (index.truncated) {
            appendLine("⚠ Workspace truncated: indexed ${index.fileCount} of ${index.totalKtFiles} .kt files (maxFiles=${index.maxFiles}). Results may be incomplete — pass a higher maxFiles or split the workspace.")
        }
        if (isNotEmpty()) appendLine()
    }

    private fun fuzzyMatches(declarations: List<KtSymbolOccurrence>, query: String): List<Pair<KtSymbolOccurrence, Int>> {
        val q = query.lowercase()
        fun scoreOf(name: String): Int {
            val n = name.lowercase()
            return when {
                n == q -> 100
                n.contains(q) -> 90 - (n.length - q.length).coerceAtLeast(0)
                isSubsequence(q, n) -> 60 - (n.length - q.length).coerceAtLeast(0)
                else -> 0
            }
        }
        return declarations
            .map { d -> d to scoreOf(d.name) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(50)
    }

    private fun isSubsequence(query: String, name: String): Boolean {
        var qi = 0
        for (c in name) {
            if (qi < query.length && c == query[qi]) qi++
        }
        return qi == query.length
    }

    private fun stdlibDocFor(symbol: String): String? {
        val result = docService.execute(DocAction.LOOKUP_SYMBOL, symbol)
        return if (result is KotlinMcpResult.Success) result.content else null
    }

    private fun typeHierarchy(code: String, symbol: String?, workspacePath: String?): KotlinMcpResult {
        val target = symbol?.trim().orEmpty()
        if (target.isEmpty()) {
            return KotlinMcpResult.Error(
                message = "Symbol name is required for type_hierarchy.",
                code = "INVALID_ARGUMENTS"
            )
        }

        val res = indexer.typeHierarchyOf(code, target, workspacePath)

        val content = buildString {
            appendLine("# Type Hierarchy for `$target`")
            appendLine("## Supertypes / Base Interfaces")
            if (res.supertypes.isNotEmpty()) res.supertypes.forEach { appendLine("- `$it`") } else appendLine("- (none)")
            appendLine()
            appendLine("## Subtypes & Implementations")
            if (res.subtypes.isNotEmpty()) res.subtypes.forEach { appendLine("- `${it.name}`") } else appendLine("- (none)")
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("symbol" to target, "supertypeCount" to res.supertypes.size.toString(), "subtypeCount" to res.subtypes.size.toString())
        )
    }

    private fun callHierarchy(code: String, symbol: String?, workspacePath: String?): KotlinMcpResult {
        val target = symbol?.trim().orEmpty()
        if (target.isEmpty()) {
            return KotlinMcpResult.Error(
                message = "Symbol name is required for call_hierarchy.",
                code = "INVALID_ARGUMENTS"
            )
        }

        val res = indexer.callHierarchyOf(code, target, workspacePath)

        val content = buildString {
            appendLine("# Call Hierarchy for `$target`")
            appendLine("## Incoming Calls & Usage Sites")
            if (res.callers.isNotEmpty()) {
                res.callers.forEach { call ->
                    val callerStr = if (call.callerName != null) {
                        "${call.callerName} (Line ${call.line}: `${call.snippet}`)"
                    } else {
                        "Line ${call.line}: `${call.snippet}`"
                    }
                    appendLine("- $callerStr")
                }
            } else {
                appendLine("- (none found in snippet)")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("symbol" to target, "callerCount" to res.callers.size.toString())
        )
    }

}
