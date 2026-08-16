package com.gokorei.kotlinmcp.project

import com.gokorei.kotlinmcp.models.KotlinMcpResult
import java.io.File

/**
 * Strategy component that scans a project directory and produces a compact markdown
 * digest of its API/DB schemas so an LLM can generate code against real shapes.
 *
 * Supported sources (best-effort textual scanning, sized for LLM context, not
 * exhaustive schema fidelity):
 *  - SQL DDL: `*.sql` files with `CREATE TABLE` statements (table + column names/types).
 *  - Exposed tables: Kotlin `object X : Table(...)` / `class X : Table(...)` bodies.
 *  - `@Serializable` data classes (DTO field names/types).
 *  - OpenAPI specs: `openapi.{yaml,yml,json}` (paths + HTTP operations).
 */
class SchemaScanner {

    fun scanSchemas(projectPath: String?): KotlinMcpResult {
        if (projectPath.isNullOrBlank()) {
            return KotlinMcpResult.Error(
                message = "schema_digest requires a projectPath (directory) to scan. Get it from the repo's working directory.",
                code = "MISSING_PROJECT_PATH"
            )
        }
        val root = File(projectPath)
        if (!root.isDirectory) {
            return KotlinMcpResult.Error(
                message = "Project path '$projectPath' is not a readable directory.",
                code = "INVALID_PATH"
            )
        }

        val sqlTables = scanSqlDdl(root)
        val exposedTables = scanExposedTables(root)
        val dtoClasses = scanSerializableDtos(root)
        val openApiPaths = scanOpenApi(root)

        val totalTables = sqlTables.size + exposedTables.size
        val output = buildString {
            appendLine("# API / DB Schema Digest")
            appendLine("Scanned `$projectPath` — ${sqlTables.size} SQL table(s), ${exposedTables.size} Exposed table(s), ${dtoClasses.size} @Serializable DTO(s), ${openApiPaths.size} OpenAPI spec(s).")
            appendLine()

            if (totalTables == 0 && dtoClasses.isEmpty() && openApiPaths.isEmpty()) {
                appendLine("No schema definitions detected in the scanned sources (SQL DDL, Exposed tables, @Serializable DTOs, OpenAPI specs).")
                appendLine()
                return@buildString
            }

            if (sqlTables.isNotEmpty()) {
                appendLine("## SQL DDL Tables (${sqlTables.size})")
                sqlTables.forEach { (file, table, columns) ->
                    appendLine("### `${table}` — $file")
                    columns.forEach { appendLine("  - `$it`") }
                    appendLine()
                }
            }

            if (exposedTables.isNotEmpty()) {
                appendLine("## Exposed Tables (${exposedTables.size})")
                exposedTables.forEach { (file, table, columns) ->
                    appendLine("### `$table` — $file")
                    columns.forEach { appendLine("  - `$it`") }
                    appendLine()
                }
            }

            if (dtoClasses.isNotEmpty()) {
                appendLine("## @Serializable DTOs (${dtoClasses.size})")
                dtoClasses.forEach { (file, name, fields) ->
                    appendLine("### `$name` — $file")
                    fields.forEach { appendLine("  - `$it`") }
                    appendLine()
                }
            }

            if (openApiPaths.isNotEmpty()) {
                appendLine("## OpenAPI Paths (${openApiPaths.size})")
                openApiPaths.forEach { (file, operations) ->
                    appendLine("### `$file`")
                    operations.forEach { appendLine("  - `$it`") }
                    appendLine()
                }
            }
        }

        return KotlinMcpResult.Success(
            content = output,
            metadata = mapOf(
                "sqlTableCount" to sqlTables.size.toString(),
                "exposedTableCount" to exposedTables.size.toString(),
                "dtoCount" to dtoClasses.size.toString(),
                "openApiSpecCount" to openApiPaths.size.toString()
            )
        )
    }

    // ---- SQL DDL ----

    private fun scanSqlDdl(root: File): List<Entry> {
        val results = mutableListOf<Entry>()
        walkFiles(root, setOf("sql")).forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            val createRegex = Regex("""(?i)CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([`"\w]+)\s*\(""")
            createRegex.findAll(text).forEach { match ->
                val tableName = match.groupValues[1].trim('`', '"')
                val body = extractBalancedBlock(text, match.range.last)
                val columns = sqlColumns(body)
                if (columns.isNotEmpty()) {
                    results.add(Entry(relativeTo(root, file), tableName, columns.take(MAX_ENTRIES)))
                }
            }
        }
        return results
    }

    private fun sqlColumns(body: String): List<String> {
        val cols = mutableListOf<String>()
        body.lines().forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.startsWith("/*") || trimmed.startsWith("*")) return@forEach
            if (CONSTRAINT_PREFIXES.any { trimmed.uppercase().startsWith(it) }) return@forEach
            if (trimmed.startsWith("(")) return@forEach
            val cleaned = trimmed.trimEnd().removeSuffix(",").trim()
            if (cleaned.isNotEmpty()) cols.add(cleaned)
        }
        if (cols.isEmpty() && body.contains(",")) {
            body.split(",").forEach { part ->
                val cleaned = part.trim().trimEnd().trim()
                if (cleaned.isNotEmpty() && !cleaned.startsWith("--") && !cleaned.startsWith("/*")) cols.add(cleaned)
            }
        }
        return cols.distinct()
    }

    // ---- Exposed tables ----

    private fun scanExposedTables(root: File): List<Entry> {
        val results = mutableListOf<Entry>()
        walkFiles(root, setOf("kt")).forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            val psi = com.gokorei.kotlinmcp.lsp.K2SnippetFrontend.parsePsi(text) ?: return@forEach

            psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                override fun visitClassOrObject(classOrObject: org.jetbrains.kotlin.psi.KtClassOrObject) {
                    val superEntries = classOrObject.superTypeListEntries
                    val isExposedTable = superEntries.any { entry ->
                        val typeText = entry.typeAsUserType?.referencedName ?: entry.typeReference?.text ?: entry.text
                        typeText.endsWith("Table") || typeText.contains("IdTable") || typeText == "Table"
                    }

                    if (isExposedTable) {
                        val columns = mutableListOf<String>()
                        classOrObject.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtProperty>().forEach { prop ->
                            val propName = prop.name ?: return@forEach
                            val initText = prop.initializer?.text ?: return@forEach
                            columns.add("$propName = $initText")
                        }
                        val tableName = classOrObject.name ?: "AnonymousTable"
                        if (columns.isNotEmpty()) {
                            results.add(Entry(relativeTo(root, file), tableName, columns.take(MAX_ENTRIES)))
                        }
                    }
                    super.visitClassOrObject(classOrObject)
                }
            })
        }
        return results
    }

    // ---- @Serializable DTOs ----

    private fun scanSerializableDtos(root: File): List<Entry> {
        val results = mutableListOf<Entry>()
        walkFiles(root, setOf("kt")).forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            val psi = com.gokorei.kotlinmcp.lsp.K2SnippetFrontend.parsePsi(text) ?: return@forEach

            psi.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                override fun visitClassOrObject(classOrObject: org.jetbrains.kotlin.psi.KtClassOrObject) {
                    val isSerializable = classOrObject.annotationEntries.any { ann ->
                        val name = ann.shortName?.asString() ?: ann.text
                        name == "Serializable" || name.endsWith("Serializable")
                    }

                    if (isSerializable) {
                        val fields = mutableListOf<String>()
                        classOrObject.primaryConstructorParameters.forEach { param ->
                            val pName = param.name ?: return@forEach
                            val pType = param.typeReference?.text ?: return@forEach
                            fields.add("$pName: $pType")
                        }
                        classOrObject.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtProperty>().forEach { prop ->
                            val propName = prop.name ?: return@forEach
                            val propType = prop.typeReference?.text ?: return@forEach
                            if (fields.none { it.startsWith("$propName:") }) {
                                fields.add("$propName: $propType")
                            }
                        }

                        val className = classOrObject.name ?: "AnonymousDto"
                        if (fields.isNotEmpty()) {
                            results.add(Entry(relativeTo(root, file), className, fields.take(MAX_ENTRIES)))
                        }
                    }
                    super.visitClassOrObject(classOrObject)
                }
            })
        }
        return results
    }

    // ---- OpenAPI ----

    private fun scanOpenApi(root: File): List<PathEntry> {
        val results = mutableListOf<PathEntry>()
        val candidates = mutableListOf<File>()
        walkFiles(root, emptySet()).forEach { file ->
            if (file.name.lowercase() in OPENAPI_FILENAMES) candidates.add(file)
        }
        candidates.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            val ops = if (file.extension.lowercase() == "json") extractJsonPaths(text) else extractYamlPaths(text)
            if (ops.isNotEmpty()) {
                results.add(PathEntry(relativeTo(root, file), ops.take(MAX_ENTRIES)))
            }
        }
        return results
    }

    private fun extractYamlPaths(text: String): List<String> {
        val ops = mutableListOf<String>()
        text.lines().forEachIndexed { index, raw ->
            val trimmed = raw.trim()
            if (!trimmed.startsWith("/") || !trimmed.endsWith(":")) return@forEachIndexed
            val path = trimmed.removeSuffix(":").trim()
            val baseIndent = raw.takeWhile { it == ' ' }.length
            var i = index + 1
            while (i < text.lines().size) {
                val line = text.lines()[i]
                if (line.isBlank() || line.trim().startsWith("#")) { i++; continue }
                val indent = line.takeWhile { it == ' ' }.length
                if (indent <= baseIndent) break
                val op = HTTP_OPERATIONS.firstOrNull { line.trim().lowercase().startsWith("$it:") }
                if (op != null) { ops.add("$op $path") }
                i++
            }
        }
        return ops.distinct()
    }

    private fun extractJsonPaths(text: String): List<String> {
        val ops = mutableListOf<String>()
        val pathRegex = Regex("""\s*"(/[^"\s{}]+)"\s*:\s*\{""")
        pathRegex.findAll(text).forEach { match ->
            val window = text.substring(match.range.last, minOf(text.length, match.range.last + 2000))
            val foundOps = HTTP_OPERATIONS.filter { op -> Regex("""\s*"${Regex.escape(op)}"\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(window) }
            foundOps.forEach { op -> ops.add("$op ${match.groupValues[1]}") }
        }
        return ops.distinct()
    }

    // ---- shared helpers ----

    private fun extractBalancedBlock(text: String, openIndex: Int, openChar: Char = '(', closeChar: Char = ')'): String {
        var depth = 0
        var i = openIndex
        while (i < text.length) {
            when (text[i]) {
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return if (i > openIndex + 1) text.substring(openIndex + 1, i) else ""
                }
            }
            i++
        }
        return ""
    }

    private fun walkFiles(root: File, extensions: Set<String>): List<File> {
        val out = mutableListOf<File>()
        fun rec(dir: File) {
            dir.listFiles()?.forEach { f ->
                when {
                    f.isDirectory -> if (f.name !in EXCLUDED_DIRS) rec(f)
                    extensions.isEmpty() || f.extension.lowercase() in extensions -> out.add(f)
                }
            }
        }
        rec(root)
        return out
    }

    private fun relativeTo(root: File, file: File): String =
        runCatching { file.toRelativeString(root) }.getOrDefault(file.name)

    private data class Entry(val file: String, val name: String, val details: List<String>)
    private data class PathEntry(val file: String, val operations: List<String>)

    companion object {
        private const val MAX_ENTRIES = 60
        private val EXCLUDED_DIRS = setOf("build", ".gradle", ".git", "out", ".idea")
        private val CONSTRAINT_PREFIXES = listOf("PRIMARY", "FOREIGN", "CONSTRAINT", "UNIQUE", "CHECK", "KEY", "INDEX")
        private val OPENAPI_FILENAMES = setOf("openapi.yaml", "openapi.yml", "openapi.json")
        private val HTTP_OPERATIONS = listOf("get", "post", "put", "delete", "patch", "head", "options")
    }
}