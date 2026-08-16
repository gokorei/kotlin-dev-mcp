# Developer & Contribution Guidelines for `kotlin-mcp`

Thank you for developing and maintaining `kotlin-mcp`! This document outlines local development workflows, architectural principles, and coding standards.

---

## Local Development Workflow

### Build & Compilation
The project uses Gradle with a Kotlin JVM toolchain of 25 (auto-provisioned via the
foojay resolver if a JDK 25 is not already installed). A Gradle wrapper
(`./gradlew`) is committed and can be used anywhere a `gradle` command appears
below.

To build the executable application distribution:
```bash
gradle installDist --no-daemon
```

### Running Tests
All functionality must be covered by functional unit or integration tests before shipping:
```bash
gradle test --no-daemon
```

To run tests with Gradle deprecation checks:
```bash
gradle test --warning-mode all --no-daemon
```

---

## Architecture Guidelines

1. **Explicit Interfaces & Errors-as-Values**:
   - Keep service contracts defined in explicit Kotlin interfaces (`DocService`, `DiagnosticService`, `CodeAnalysisService`, etc.).
   - Return structured domain results via [`KotlinMcpResult`](src/main/kotlin/com/gokorei/kotlinmcp/models/KotlinMcpResult.kt) (`Success` / `Error`). Do not throw unhandled exceptions across the MCP transport boundary.

2. **Action-Multiplexed Tools**:
   - The surface is consolidated into 11 tools (5 read-only, 6 edit/mutating) in [`ToolRegistrar`](src/main/kotlin/com/gokorei/kotlinmcp/server/ToolRegistrar.kt), each dispatching an `action` (or `domain` / `target`) discriminator to a `when`-branch that calls the corresponding `KotlinMcpServer` method. New operations should extend an existing tool's action list rather than adding a 12th tool, keeping the prompt footprint small.
   - Annotate genuinely read-only tools with `ToolAnnotations(readOnlyHint = true)`; tools with any mutating action (rename, format, refactor, register, run) must set `readOnlyHint = false`.

3. **Stdio Safety**:
   - Stdio owns stdout for JSON-RPC message frames. Ensure all logging goes to `stderr` via `kotlin-logging-jvm` and `slf4j-simple`.
   - Never print directly to `System.out` or `println()` in server modules.

4. **Functional Programming & Idiomatic Kotlin**:
   - Write immutable, pure functions wherever possible.
   - Leverage Kotlin language capabilities (sealed classes, pattern matching, extension functions, scope functions) to keep control flow minimal and predictable.

5. **Unified K2 Architecture**:
   - Both static AST analysis (`K2SnippetFrontend`, `WorkspaceSemanticIndexer`) and snippet compilation (`SnippetCompiler`) are unified on modern **Kotlin 2.x (K2)** APIs.
   - Native K2 PSI environments are shared safely and disposed during application shutdown.

6. **AST Traversal Over Regex for Code Analysis**:
   - **NEVER** use regular expressions (`Regex`), multiline pattern matching, or string splitting to analyze or traverse Kotlin source code.
   - **ALWAYS** use the embedded K2 PSI parser (`K2SnippetFrontend.parsePsi(text)` and `KtTreeVisitorVoid`). Regex is brittle against multiline declarations, comments, string literals, and class inheritance (`Table`, `IntIdTable`, `LongIdTable`).

