# Project Rules & Guidance for AI Coding Agents

## 1. Static AST Traversal Over Regular Expressions
- **NEVER** use regex (`Regex`), multiline text matching, or string substring searching when parsing, analyzing, or traversing Kotlin source code in this codebase.
- **ALWAYS** use the embedded K2 PSI parser via `K2SnippetFrontend.parsePsi(text)` and `KtTreeVisitorVoid` (or `KtElement` visitors).
- **Rationale**: Regex is brittle against multiline formatting, comments, string literals, default parameter values, and class inheritance (`Table`, `IntIdTable`, `LongIdTable`).

## 2. Explicit Interfaces & Errors-as-Values
- Keep service contracts defined in explicit Kotlin interfaces.
- Return structured domain results via `KotlinMcpResult` (`Success` / `Error`). Do not throw unhandled exceptions across the MCP transport boundary.

## 3. Stdio Safety
- Stdio owns stdout for JSON-RPC message frames. Ensure all logging goes to `stderr` via `kotlin-logging-jvm` and `slf4j-simple`.
- Never print directly to `System.out` or `println()` in server modules.

## 4. Test-Driven Development & Verification
- Define functional behavior in unit/integration tests before writing implementation code.
- Run tests (`./gradlew test --no-daemon`) to verify correctness after every completed piece of functionality.
