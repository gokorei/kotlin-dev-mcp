package com.gokorei.kotlinmcp.refactoring

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Strategy component for inspecting AST snippets and suggesting modern `kotlinx-datetime` primitives
 * (`Instant`, `Clock.System.now()`, `LocalDate`) over legacy `java.util.Date`, `Calendar`, and `SimpleDateFormat`.
 */
class DatetimeMigrationSuggestor {

    fun suggestDatetimeMigration(code: String): KotlinMcpResult {
        return suggestKotlinxDatetime(code)
    }

    fun suggestKotlinxDatetime(code: String): KotlinMcpResult {
        val suggestions = mutableListOf<String>()
        val psi = K2SnippetFrontend.parsePsi(code)

        if (psi != null) {
            psi.accept(object : KtTreeVisitorVoid() {
                override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                    val name = expression.getReferencedName()
                    if (name == "Date" || name == "java.util.Date") {
                        suggestions.add("`java.util.Date` → `kotlinx.datetime.Instant` (timestamp) or `LocalDate` (civil date)")
                    } else if (name == "Calendar" || name == "java.util.Calendar") {
                        suggestions.add("`java.util.Calendar` → `kotlinx.datetime.LocalDateTime` / `TimeZone`")
                    } else if (name == "SimpleDateFormat") {
                        suggestions.add("`SimpleDateFormat` → `kotlinx.datetime.LocalDate.parse()` / `format()`")
                    }
                    super.visitSimpleNameExpression(expression)
                }

                override fun visitUserType(type: KtUserType) {
                    val text = type.text
                    if (text == "Date" || text == "java.util.Date") {
                        suggestions.add("`java.util.Date` → `kotlinx.datetime.Instant` (timestamp) or `LocalDate` (civil date)")
                    } else if (text == "Calendar" || text == "java.util.Calendar") {
                        suggestions.add("`java.util.Calendar` → `kotlinx.datetime.LocalDateTime` / `TimeZone`")
                    } else if (text.contains("SimpleDateFormat")) {
                        suggestions.add("`SimpleDateFormat` → `kotlinx.datetime.LocalDate.parse()` / `format()`")
                    }
                    super.visitUserType(type)
                }

                override fun visitCallExpression(expression: KtCallExpression) {
                    val text = expression.text
                    if (text.contains("System.currentTimeMillis()")) {
                        suggestions.add("`System.currentTimeMillis()` → `Clock.System.now().toEpochMilliseconds()`")
                    }
                    super.visitCallExpression(expression)
                }
            })
        }

        val content = buildString {
            appendLine("# `kotlinx-datetime` Migration Advisories")
            appendLine()
            if (suggestions.isEmpty()) {
                appendLine("No legacy Date/Calendar APIs detected in snippet.")
            } else {
                appendLine("Recommended modern `kotlinx.datetime` replacements:")
                suggestions.distinct().forEach { appendLine(" - $it") }
                appendLine()
                appendLine("Imports required:")
                appendLine("```kotlin")
                appendLine("import kotlinx.datetime.*")
                appendLine("```")
            }
        }

        return KotlinMcpResult.Success(
            content = content,
            metadata = mapOf("advisoriesCount" to suggestions.distinct().size.toString())
        )
    }
}
