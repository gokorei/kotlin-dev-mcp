# kotlin-mcp — Wiki Home

`kotlin-mcp` is a [Model Context Protocol](https://modelcontextprotocol.io) server that brings a full Kotlin development toolkit into MCP-capable AI assistants (Claude Code, Cursor, OpenCode, and other MCP hosts). It exposes documentation lookup, snippet diagnostics, static analysis, linting, refactoring, project inspection, and snippet execution as MCP tools.

The server is distributed as a self-contained JVM uber-JAR (`kotlin-mcp-1.1.0-all.jar`) that requires no Gradle daemons or external services. Kotlin type checking and PSI/AST analysis run through the embedded K2 compiler.

---

## Quickstart

Download the uber-JAR from the [latest release](https://github.com/gokorei/kotlin-dev-mcp/releases) and register it as an MCP server in your client's configuration:

```json
{
  "mcpServers": {
    "kotlin-mcp": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/kotlin-mcp-1.1.0-all.jar"]
    }
  }
}
```

Requires a JVM — see the [README](https://github.com/gokorei/kotlin-dev-mcp/blob/main/README.md) for JDK requirements and per-client configuration snippets (Claude Code, Desktop/Antigravity, Codex, OpenCode, Pi, Crush).

---

## Tools

| Tool | Purpose |
| :--- | :--- |
| `kotlin_docs_read` / `kotlin_docs_edit` | Query stdlib docs; register custom documentation entries |
| `kotlin_check_snippet` | Compile Kotlin snippets with the embedded K2 compiler, report line:col diagnostics |
| `kotlin_code_analyze` | AST inspection, nullability, coroutine safety, Compose, cross-file context |
| `kotlin_text_lsp_read` / `kotlin_text_lsp_edit` | Symbol definition/references, workspace search, type/call hierarchy, rename |
| `kotlin_project_inspect` | Gradle structure, KMP targets, dependency audits, API/DB schema digests |
| `kotlin_library_analyze` | Framework-aware checks (Ktor, serialization, Arrow, datetime, tests) |
| `kotlin_lint` | Real detekt static analysis and ktlint formatting backends |
| `kotlin_refactor` | Java-to-Kotlin, functional/loop modernization, RxJava migration, quick fixes |
| `kotlin_run` | Execute `fun main()` snippets, Gradle tasks, and JUnit XML test reports |

The server also publishes bundled stdlib documentation and architecture guidelines as MCP resources (`kotlin://docs/index.md`, `kotlin://guidelines/architecture.md`) and registers the `kotlin-task` and `kotlin-architecture` guidance prompts.

---

## Guides

- [Tool Reference](Tool-Reference) — complete in-code backed API reference for all 11 MCP tools and actions
- [Release Notes](Release-Notes) — new features, bug fixes, and improvements per version
- [Security and Sandboxing](Security-And-Sandboxing) — process isolation model, execution architecture, and containerized deployment for untrusted environments
- [Configuration](Configuration) — system properties, environment variables, offline mode, and runtime tuning

---

## Repository

Source repository: **gokorei/kotlin-dev-mcp**

- [README](https://github.com/gokorei/kotlin-dev-mcp#readme) — full overview and client integration guides
- [Changelog](https://github.com/gokorei/kotlin-dev-mcp/blob/main/CHANGELOG.md)
- [Contributing](https://github.com/gokorei/kotlin-dev-mcp/blob/main/CONTRIBUTING.md)
- [Security policy](https://github.com/gokorei/kotlin-dev-mcp/blob/main/SECURITY.md)