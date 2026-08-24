package com.gokorei.kotlinmcp.server

/**
 * LLM-facing guidance exposed through MCP prompts and resources,
 * matching the architecture of atlassian-mcp-tools.
 */
object LlmGuidance {

    const val LLM_GUIDE_RESOURCE_URI = "kotlin://server/usage-guide.md"
    const val LLM_GUIDE_RESOURCE_NAME = "kotlin-server-usage-guide"
    const val LLM_GUIDE_PROMPT_NAME = "kotlin_mcp_quickstart"

    fun buildLlmUsageGuide(goal: String? = null, includeExamples: Boolean = true): String = buildString {
        appendLine("# Kotlin MCP LLM Usage Guide")
        appendLine()
        appendLine("Use this guide when selecting Kotlin tools, choosing response presets,")
        appendLine("or avoiding token-heavy calls.")
        appendLine()

        if (!goal.isNullOrBlank()) {
            appendLine("## Current Goal")
            appendLine()
            appendLine("- Prioritize the guidance below for: $goal")
            appendLine()
        }

        appendLine("## Tool Action & Parameter Matrix")
        appendLine()
        appendLine("| Tool | Action | Required Parameters | Purpose | Token Cost |")
        appendLine("| :--- | :--- | :--- | :--- | :--- |")
        appendLine("| `kotlin_check_snippet` | `check`\\|`mutate` | `code` (required), `testCode` (for `mutate`) | Fast in-memory syntax/type check or in-memory AST mutation testing | Low (<200) |")
        appendLine("| `kotlin_docs_read` | `search`\\|`lookup`\\|`explain` | `query` (optional: `preset=\"compact\"`) | Stdlib & language documentation lookup | Low-Med (Use `preset=\"compact\"`) |")
        appendLine("| `kotlin_code_analyze` | `file_context`\\|`nullability`\\|`coroutines`\\|`symbol_declarations`\\|`ast_dump` | `code` (optional: `filePath`) | Single-file PSI AST analysis | Low-Med |")
        appendLine("| `kotlin_text_lsp_read` | `definition`\\|`references`\\|`type_hierarchy`\\|`call_hierarchy`\\|`workspace_search` | `symbol` + `workspacePath` | Cross-file semantic LSP navigation | Med |")
        appendLine("| `kotlin_project_inspect` | `structure`\\|`kmp_targets`\\|`dependencies`\\|`diagnose_build`\\|`package_api` | `workspacePath` (optional: `preset=\"compact\"`) | Gradle build & module inspection | Med |")
        appendLine("| `kotlin_library_analyze` | `inspect_jar`\\|`resolve_types`\\|`decompile_class` | `jarPath` or `className` | Compiled dependency API analysis | Med |")
        appendLine("| `kotlin_refactor` | `functional`\\|`java_to_kotlin`\\|`suggest_idioms`\\|`quick_fix`\\|`rxjava` | `code` | Mutating AST refactoring transformations | Med |")
        appendLine("| `kotlin_lint` | `lint_detekt`\\|`lint_ktlint`\\|`format_ktlint`\\|`baseline_dump` | `workspacePath` or `code` | Mutating code style & static analysis | Med-High |")
        appendLine("| `kotlin_run` | `snippet`\\|`gradle_task` | `code` or `taskName` | Subprocess code/task execution | High |")
        appendLine()

        appendLine("## Strict Execution Pipelines (State Machines)")
        appendLine()
        appendLine("### 1. Code Refactoring & Modification Flow")
        appendLine("1. **Analyze PSI Context**: Run `kotlin_code_analyze(action=\"file_context\", code=...)` to inspect AST structures.")
        appendLine("2. **Validate Proposed Edit**: Run `kotlin_check_snippet(code=proposedCode)` to confirm syntax/type validity.")
        appendLine("3. **Execute Mutation**: Call `kotlin_refactor(action=...)` or `kotlin_text_lsp_edit(action=\"rename\", ...)`.")
        appendLine("4. **Re-Verify AST Integrity**: Re-run `kotlin_check_snippet(code=updatedCode)`.")
        appendLine()
        appendLine("### 2. Mutation Testing & Property-Based Testing (PBT) Verification Flow")
        appendLine("1. **Evaluate Test Strength**: Run `kotlin_check_snippet(action=\"mutate\", code=implementationCode, testCode=unitTestCode)` to execute in-memory K2 PSI mutation testing.")
        appendLine("2. **Inspect Survived Mutants**: Check diffs reported under `⚠️ Survived Mutants` to identify missing boundary, relational, or conditional assertions.")
        appendLine("3. **Synthesize Invariant Assertions or Property-Based Tests (PBT)**:")
        appendLine("   - When boundary or arithmetic mutants survive (`<` vs `<=`, `+` vs `-`), synthesize property-based invariants (e.g., using Kotest Property `checkAll` / `forAll`, `Arb` generators, or parameterized edge-case tables).")
        appendLine("   - Verify algebraic invariants (idempotence, roundtripping, monotonicity, boundary extrema) across generated inputs rather than single happy-path inputs.")
        appendLine("4. **Re-Run In-Memory Mutation Test**: Confirm the mutation score reaches 100% and all mutants are killed.")
        appendLine("5. **Pluggable Mutator Extension**: If domain-specific mutations are required, implement `AstMutator` and register it with `MutatorRegistry.register(customMutator)`.")
        appendLine()

        appendLine("## Token Budgeting & Response Presets")
        appendLine()
        appendLine("- **MANDATORY `preset=\"compact\"`**: Always supply `preset=\"compact\"` on `kotlin_docs_read` and `kotlin_project_inspect(action=\"package_api\")` during discovery. This drops full KDocs and returns single-line signatures, saving ~90% tokens (reducing ~4,500 tokens to ~350 tokens per symbol).")
        appendLine("- **Fast Dry-Run Validation**: Run `kotlin_check_snippet` before `kotlin_run(action=\"snippet\")` to catch syntax/type errors instantly without spawning heavy JVM subprocesses.")
        appendLine("- **Context Reduction**: Use `kotlin_code_analyze(action=\"file_context\")` instead of transmitting large raw file content blobs.")
        appendLine()

        appendLine("## Write Safety & Execution Guarantees")
        appendLine()
        appendLine("- **Read-Only First**: Always execute discovery read actions before mutating write actions (`kotlin_text_lsp_edit`, `kotlin_docs_edit`).")
        appendLine("- **Mutating Operations**: Treat `kotlin_text_lsp_edit(action=\"rename\")`, `kotlin_refactor`, and `kotlin_lint(action=\"format_ktlint\")` as mutating file modifications.")
        appendLine("- **AST Guarantee**: All tools utilize in-memory PSI AST parsing. Synthetic wrappers ensure snippet statement blocks parse as valid AST nodes.")
        appendLine()

        appendLine("## Explicit Anti-Patterns (DO NOT DO THIS)")
        appendLine()
        appendLine("1. **Regex Code Renaming**: DO NOT use string regex or text replacements to rename Kotlin symbols across files. ALWAYS use `kotlin_text_lsp_edit(action=\"rename\", symbol=..., newName=..., workspacePath=...)` to ensure AST node ranges are targeted and comments/KDoc are preserved.")
        appendLine("2. **Shell Execution for Syntax Checking**: DO NOT invoke raw shell commands or Gradle build tasks to check snippet syntax. ALWAYS use `kotlin_check_snippet(code=...)` for sub-millisecond in-memory K2 compiler type checking.")
        appendLine("3. **Regex Build Script Parsing**: DO NOT rely on manual regex when analyzing Gradle Kotlin DSL build scripts. ALWAYS use `kotlin_project_inspect(action=\"structure\"|\"dependencies\"|\"diagnose_build\", workspacePath=...)`.")
        appendLine("4. **Unvalidated Refactorings**: DO NOT apply large code refactorings without dry-running `kotlin_check_snippet` first.")
        appendLine("5. **Unmocked Live Network Calls**: DO NOT make unmocked live HTTP requests (`openConnection()`, `HttpURLConnection`, external REST endpoints) inside unit tests or build resource tasks. ALWAYS mock HTTP requests using `MockWebServer` (`mockwebserver3`), Ktor `MockEngine`, or `WireMock` to prevent test hangs and flakiness.")
        appendLine("6. **Non-Daemon Subprocess Output Threads**: DO NOT create non-daemon background threads for reading process output streams. ALWAYS configure `Thread { ... }.apply { isDaemon = true }.start()` to prevent blocked stream readers from hanging JVM termination.")
        appendLine()

        if (includeExamples) {
            appendLine("## Quick Examples")
            appendLine()
            appendLine("```kotlin")
            appendLine("// Fast in-memory syntax & type check")
            appendLine("kotlin_check_snippet(code = \"fun main() { val x: Int = 42 }\")")
            appendLine()
            appendLine("// Find definition of a workspace symbol")
            appendLine("kotlin_text_lsp_read(action = \"definition\", symbol = \"parseData\", workspacePath = \"/path/to/project\")")
            appendLine()
            appendLine("// AST nullability analysis")
            appendLine("kotlin_code_analyze(action = \"nullability\", code = snippet)")
            appendLine()
            appendLine("// Convert imperative loop to idiomatic functional pipeline")
            appendLine("kotlin_refactor(action = \"functional\", code = imperativeLoop)")
            appendLine("```")
            appendLine()
        }

        appendLine("## Decision Shortcuts")
        appendLine()
        appendLine("- **Validate snippet code**: use `kotlin_check_snippet`.")
        appendLine("- **Locate unknown symbol**: use `kotlin_text_lsp_read(action=\"definition\", symbol=..., workspacePath=...)`.")
        appendLine("- **Refactor imperative loops**: use `kotlin_refactor(action=\"functional\")`.")
        appendLine("- **Convert legacy Java to Kotlin**: use `kotlin_refactor(action=\"java_to_kotlin\")`.")
        appendLine("- **Diagnose Gradle build failures**: use `kotlin_project_inspect(action=\"diagnose_build\", workspacePath=...)`.")
        appendLine("- **Inspect library jar API**: use `kotlin_library_analyze(action=\"inspect_jar\", jarPath=\"...\")`.")
        appendLine()

        appendLine("## Efficiency Defaults")
        appendLine()
        appendLine("- Always specify `action` parameter explicitly on progressive discovery tools.")
        appendLine("- Pass `workspacePath` when analyzing cross-file dependencies or workspace symbols.")
        appendLine("- Use `kotlin_check_snippet` for fast syntax and type checking before executing `./gradlew` build tasks.")
        appendLine("- Avoid passing massive string blobs when analyzing single files; use `kotlin_code_analyze(action=\"file_context\")`.")
        appendLine()

        appendLine("## Write Safety")
        appendLine()
        appendLine("- Treat `kotlin_text_lsp_edit(action=\"rename\")` as a mutating operation that rewrites workspace files in place.")
        appendLine("- Treat `kotlin_refactor` (`java_to_kotlin`, `functional`, `suggest_idioms`, `quick_fix`, `rxjava`) as mutating code generators.")
        appendLine("- Treat `kotlin_lint(action=\"format_ktlint\"|\"baseline_dump\")` as mutating workspace operations.")
        appendLine("- Treat `kotlin_run` (`snippet`, `gradle_task`) as process execution operations.")
        appendLine()

        appendLine("## Client Gotchas")
        appendLine()
        appendLine("- All tool responses return formatted output, so read the compact Markdown structure directly.")
        appendLine("- In-memory PSI AST parsing parses actual Kotlin syntax nodes; string matchers inside comments/KDoc are never matched.")
        appendLine("- When `workspacePath` is supplied, symbol rename performs AST offset replacements from right to left to ensure token index validity.")
    }
}
