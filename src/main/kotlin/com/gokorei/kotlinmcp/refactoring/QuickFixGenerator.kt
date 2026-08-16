package com.gokorei.kotlinmcp.refactoring

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import com.gokorei.kotlinmcp.shared.DiffUtils
import com.gokorei.kotlinmcp.shared.SourceUtils

/**
 * Strategy component for generating diagnostic-driven unified diffs (+/-) and quick-fixes.
 */
class QuickFixGenerator {

    fun generateQuickFix(code: String, diagnostic: String): KotlinMcpResult {
        if (code.isBlank()) {
            return KotlinMcpResult.Error(
                message = "Empty code snippet; nothing to fix.",
                code = "EMPTY_SNIPPET"
            )
        }

        val fixes = mutableListOf<String>()
        val lines = code.lines().toMutableList()

        // 1. Unresolved reference: `Unresolved reference: foo` / `Unresolved reference 'foo'` → suggest an import.
        Regex("""[Uu]nresolved reference[:：'\s]+(\w+)""").findAll(diagnostic).forEach { m ->
            val symbol = m.groupValues[1]
            fixes.add(
                "Unresolved reference `$symbol`: add an import for `$symbol` if it comes from " +
                    "a dependency, or declare it locally. Snippet change: add an import line at the top of the file."
            )
        }

        // 2. Missing import for a qualified-but-unimported name.
        Regex("""(?:missing import|no class found for|unresolved reference)[:：'\s]*([A-Za-z0-9_.]+)""", RegexOption.IGNORE_CASE)
            .findAll(diagnostic).forEach { m ->
                val fqcn = m.groupValues[1].trim()
                if (fqcn.contains('.')) {
                    fixes.add("Missing import for `$fqcn` — add `import $fqcn`.")
                }
            }

        // 3. Non-null assertion advisory: check AST for `!!`.
        val psiForCheck = K2SnippetFrontend.parsePsi(code)
        var hasNonNullAssertion = false
        psiForCheck?.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
            override fun visitPostfixExpression(expression: org.jetbrains.kotlin.psi.KtPostfixExpression) {
                if (expression.operationToken == org.jetbrains.kotlin.lexer.KtTokens.EXCLEXCL) {
                    hasNonNullAssertion = true
                }
                super.visitPostfixExpression(expression)
            }
        })

        if (hasNonNullAssertion) {
            fixes.add("`!!` non-null assertion: replace with safe handling — `x?.let { }` or `x ?: fallback` to avoid NPE.")
        }

        // If no specific fix matched but a snippet-level suggestion is possible, produce a diff anyway.
        val needsImport = fixes.any { it.startsWith("Missing import") || it.startsWith("Unresolved reference") }
        if (needsImport) {
            val unresolved = Regex("""[Uu]nresolved reference[:：'\s]+(\w+)""").find(diagnostic)?.groupValues?.get(1)
            if (unresolved != null && !lines.any { it.startsWith("import ") && it.contains(unresolved, ignoreCase = true) }) {
                val insertIdx = calculateImportInsertIndex(code)
                lines.add(insertIdx.coerceIn(0, lines.size), "import com.example.$unresolved")
            }
        }

        val replaced = lines.joinToString("\n")
        val diff = DiffUtils.generateUnifiedDiff(code, replaced, "Snippet.kt")

        val content = buildString {
            appendLine("# Quick-Fix (Unified Diff)")
            appendLine()
            if (fixes.isEmpty()) {
                appendLine("No resolvable pattern found in the diagnostic. Reported diagnostic:")
                appendLine("```")
                appendLine(diagnostic.ifBlank { "(empty)" })
                appendLine("```")
                appendLine("Re-run kotlin_check_snippet after manually fixing to confirm.")
            } else {
                appendLine("Diagnostic-driven fixes:")
                fixes.distinct().forEach { appendLine(" - $it") }
                appendLine()
                appendLine("Unified diff:")
                appendLine("```diff")
                appendLine(diff)
                appendLine("```")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("fixCount" to fixes.distinct().size.toString())
        )
    }

    private fun calculateImportInsertIndex(code: String): Int {
        val psi = K2SnippetFrontend.parsePsi(code) ?: return 0
        val lines = code.lines()

        var lastDirectiveLine = -1

        val fileAnnoList = psi.fileAnnotationList
        if (fileAnnoList != null && fileAnnoList.textRange.endOffset > 0) {
            val endLine = SourceUtils.lineOf(code, fileAnnoList.textRange.endOffset) - 1
            if (endLine > lastDirectiveLine) lastDirectiveLine = endLine
        }

        val pkgDir = psi.packageDirective
        if (pkgDir != null && pkgDir.packageNames.isNotEmpty()) {
            val pkgLine = SourceUtils.lineOf(code, pkgDir.textRange.endOffset) - 1
            if (pkgLine > lastDirectiveLine) lastDirectiveLine = pkgLine
        }

        val importList = psi.importList
        if (importList != null && importList.imports.isNotEmpty()) {
            val lastImportLine = SourceUtils.lineOf(code, importList.textRange.endOffset) - 1
            if (lastImportLine > lastDirectiveLine) lastDirectiveLine = lastImportLine
        }

        return if (lastDirectiveLine >= 0 && lastDirectiveLine < lines.size) {
            lastDirectiveLine + 1
        } else {
            0
        }
    }
}
