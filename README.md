# Kotlin Developer MCP Server (`kotlin-mcp`)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF.svg)](https://kotlinlang.org)
[![JDK](https://img.shields.io/badge/JDK-25-orange.svg)](https://openjdk.org)

A Model Context Protocol (MCP) server providing high-performance Kotlin development tools, live code execution, real K2 compiler static diagnostics, and documentation inspection for LLMs and AI coding assistants.

---

## Key Features

- **Embedded K2 Compiler**: In-process static type checking and line/column diagnostics using Kotlin 2.x compiler APIs without external Gradle daemons.
- **11 Multiplexed Tools**: Complete surface covering documentation lookup, code analysis, AST refactoring, dependency analysis, formatting, and live execution.
- **Detekt & KtLint Integration**: In-process static analysis and code formatting running in isolated worker classloaders.
- **Framework Awareness**: Automatically detects project dependencies (Ktor, Spring, Compose, Arrow, Serialization) to customize tool capabilities dynamically.
- **Safe Stdio Transport**: Clean JSON-RPC communication over stdio with isolated subprocess execution.

---

## Available Tools

The server consolidates its surface into **11 tools** (5 read-only, 6 mutating) using action-multiplexed parameters to minimize token usage while maximizing context:

### Read-Only Tools (`readOnly = true`)

| Tool Name | Actions / Targets | Description |
| :--- | :--- | :--- |
| `kotlin_docs_read` | `search`, `lookup`, `explain` | **Documentation**: Query stdlib symbols and language features; search index or lookup signatures. |
| `kotlin_check_snippet` | *(Direct)* | **Diagnostics**: Compile Kotlin snippets in-process using embedded K2 compiler and report line:col errors. |
| `kotlin_code_analyze` | `inspect`, `nullability`, `coroutines`, `compose`, `file_context` | **Code Analysis**: AST inspection, unsafe null handling, coroutines scope safety, Compose anti-patterns, and cross-file dependencies. |
| `kotlin_text_lsp_read` | `definition`, `references`, `completion`, `workspace_search`, `workspace_references`, `type_hierarchy`, `call_hierarchy`, `hover` | **Text / LSP**: Compiler AST text services, symbol definitions, multi-file workspace search/references, type/call hierarchies, and hover signatures/KDoc. |
| `kotlin_project_inspect` | `structure`, `kmp_targets`, `dependencies`, `schema_digest`, `diagnose_build`, `layout_inventory`, `vulnerabilities`, `package_api`, `coverage_report` | **Project**: Gradle build scripts, KMP targets, security vulnerability audits (CVEs), public package API surface dumping, API/DB schema digest (SQL DDL, Exposed tables, @Serializable DTOs, OpenAPI), and JaCoCo coverage reports. |

### Mutating / Edit Tools (`readOnly = false`)

| Tool Name | Actions / Targets / Domains | Description |
| :--- | :--- | :--- |
| `kotlin_docs_edit` | `register_symbol`, `register_feature`, `register_namespace` | **Doc Persistence**: Register custom documentation entries dynamically at runtime and disk persistence. |
| `kotlin_text_lsp_edit` | `rename` | **LSP Refactoring**: AST-based symbol renaming across snippet and workspace files in place. |
| `kotlin_refactor` | `java_to_kotlin`, `functional`, `suggest_idioms`, `quick_fix`, `rxjava` | **Refactoring**: Code transformations, collection loop modernization, quick-fix diff generation, and RxJava conversion. |
| `kotlin_library_analyze` | `ktor`, `serialization`, `tests`, `route_map`, `arrow`, `datetime` | **Library Checks**: Progressive discovery library checks. HTTP route mapping, Ktor plugins, kotlinx.serialization, Arrow refactoring, and test-pattern hygiene. |
| `kotlin_lint` | `detekt`, `format_ktlint`, `baseline_read`, `baseline_dump` | **Lint & Format**: Real detekt static analysis engine and KtLint code formatting. |
| `kotlin_run` | `snippet`, `gradle_task`, `test_report` | **Execution**: Execute `fun main()` snippets in isolated subprocesses, run Gradle tasks, or parse JUnit XML test reports. |

---

### Progressive Discovery Architecture

`kotlin-mcp` automatically detects workspace dependencies (`build.gradle.kts` / `libs.versions.toml`). If a framework (such as Ktor, Spring, Compose, or Arrow) is present in the analyzed codebase, tool descriptions dynamically highlight relevant framework actions—hiding irrelevant options when frameworks are absent.

**Note on architecture (Unified K2):** both code execution (`SnippetCompiler`) and static AST analysis (`K2SnippetFrontend`, `WorkspaceSemanticIndexer`) are unified on modern **K2** compiler APIs. See [CONTRIBUTING.md](CONTRIBUTING.md) §5 for details.


---

## Building and Running Locally

### Prerequisites
- JDK 25 or later
- Gradle 8.5+ (a [Gradle wrapper](gradle/wrapper/gradle-wrapper.properties) is committed — `./gradlew` can be used anywhere a `gradle` command is shown below)

### Build Executable Application
```bash
./gradlew installDist --no-daemon
```
The executable launcher will be created at: `./build/install/kotlin-mcp/bin/kotlin-mcp`

### Build Self-Contained Uber-JAR (Fat JAR)
To package all application classes, service action models, and dependencies into a single runnable Fat JAR:
```bash
./gradlew uberJar --no-daemon
```
The self-contained Fat JAR will be created at: `./build/libs/kotlin-mcp-1.0.0-all.jar`

### Run Tests
```bash
./gradlew test --no-daemon
```

---

## Agent Harness Integration Guide

### Option 1: Self-Contained Uber-JAR (Requires Java 21+)
Build the Fat JAR locally:
```bash
./gradlew uberJar --no-daemon
```

### Tool Loading Modes (Eager vs. Lazy)

By default, we recommend registering `kotlin-mcp` with `"eager": true`.

- **Eager Mode (`"eager": true`, Default Recommended)**: Injects all 11 tool definitions and parameter schemas directly into the LLM system prompt on agent startup. Removes the two-step friction where models read JSON schemas before each call.
- **Lazy Mode (`"eager": false`)**: For environments or users sensitive to initial prompt token context size, omit `"eager": true` or set `"eager": false` to defer tool schema resolution.

### Built-in Agent Skill (`skills/kotlin-mcp/SKILL.md`)

`kotlin-mcp` includes a built-in skill file at [`skills/kotlin-mcp/SKILL.md`](skills/kotlin-mcp/SKILL.md) (also mirrored at [`.agents/skills/kotlin-mcp/SKILL.md`](.agents/skills/kotlin-mcp/SKILL.md)). This skill equips AI coding assistants (such as Antigravity, Claude Code, OpenCode, and Codex) with:
- Tool decision trees and parameter action matrices
- 4-step state-machine refactoring pipelines
- Token-saving response presets (`preset="compact"`)
- Practical workflow code examples

---

#### Claude Code CLI:
```bash
claude mcp add kotlin-mcp java -- -jar /path/to/kotlin-mcp/build/libs/kotlin-mcp-1.0.0-all.jar
```

#### Claude Desktop / Antigravity Configuration:
```json
{
  "mcpServers": {
    "kotlin-mcp": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/kotlin-mcp/build/libs/kotlin-mcp-1.0.0-all.jar"
      ],
      "eager": true
    }
  }
}
```

---

### 2. OpenAI Codex / MCP Clients

Add to your Codex configuration file (`~/.codex/config.json` or project-local `codex.json`):
```json
{
  "mcpServers": {
    "kotlin-mcp": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/kotlin-mcp/build/libs/kotlin-mcp-1.0.0-all.jar"
      ],
      "eager": true,
      "env": {
        "JAVA_HOME": "/path/to/your/jdk-25"
      }
    }
  }
}
```

---

### 3. OpenCode

Add to `.opencode/mcp.json` or `opencode.json` in your workspace:
```json
{
  "mcpServers": {
    "kotlin-mcp": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/kotlin-mcp/build/libs/kotlin-mcp-1.0.0-all.jar"
      ],
      "eager": true,
      "enabled": true,
      "type": "local"
    }
  }
}
```
> A ready-to-copy example config is committed at [`opencode.example.json`](opencode.example.json).

---

### 4. Pi Agent

#### Option A: Pi CLI
Register `kotlin-mcp` with the Pi agent CLI:
```bash
pi mcp add kotlin-mcp java -jar /absolute/path/to/kotlin-mcp/build/libs/kotlin-mcp-1.0.0-all.jar
```

#### Option B: Pi Configuration File
Add to `~/.pi/agent/mcp.json`:
```json
{
  "mcpServers": {
    "kotlin-mcp": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/kotlin-mcp/build/libs/kotlin-mcp-1.0.0-all.jar"
      ],
      "eager": true
    }
  }
}
```

---

### 5. Crush Agent

Add to `~/.config/crush/crush.json` or project-local `crush.json`:
```json
{
  "mcp": {
    "servers": {
      "kotlin-mcp": {
        "command": "java",
        "args": [
          "-jar",
          "/absolute/path/to/kotlin-mcp/build/libs/kotlin-mcp-1.0.0-all.jar"
        ],
        "eager": true
      }
    }
  }
}
```

---

## System Architecture & Technical Details

- **Action-Multiplexed Tool Suite**: A consolidated suite of 11 tools (5 read-only, 6 mutating) covering documentation, code analysis, project inspection, refactoring, library checks, lint/format, and execution — each action-multiplexed to minimize prompt tokens while returning structured, LLM-consumable results.
- **Real Lint & Format Backends**: `kotlin_lint` runs detekt static analysis and ktlint formatting in-process via isolated `ChildFirstClassLoader` instances using dedicated tooling classpaths.
- **Embedded K2 Compiler (`SnippetCompiler`)**: Uses `kotlin-compiler-embeddable` (`K2JVMCompiler`) for in-process static type checking and diagnostic output without launching external Gradle daemons. Unresolved references are reported as hard errors; pass the owning classpath to resolve project types.
- **Host JVM Process Runner (`kotlin_run`)**: Executes Kotlin snippets, Gradle tasks, and JUnit test report parsers in host JVM processes with JVM argument filtering and timeout enforcement.
- **MCP Protocol Extensions**: Exposes bundled stdlib documentation and architectural guidelines as Markdown resources (`kotlin://docs/index.md`, `kotlin://guidelines/architecture.md`) and registers guidance prompts (`kotlin-task`, `kotlin-architecture`).
- **Stdio Transport Safety**: Disables logger startup messages to ensure zero stdout pollution, keeping JSON-RPC transport frames clean over stdio.

---

## Documentation & Wiki

For detailed guides, configuration options, and architectural deep-dives:

- [Wiki Home](https://github.com/gokorei/kotlin-dev-mcp/wiki) — Full project overview and quickstart guide.
- [Configuration Guide](https://github.com/gokorei/kotlin-dev-mcp/wiki/Configuration) — System properties, environment variables, offline/air-gapped setup, and tool loading modes.
- [Security and Sandboxing](https://github.com/gokorei/kotlin-dev-mcp/wiki/Security-And-Sandboxing) — Process isolation model and containerized execution architecture.
- [Release Notes](https://github.com/gokorei/kotlin-dev-mcp/wiki/Release-Notes) — Version history, feature breakdowns, and improvements.

---

## Contributing

Public contributions are currently locked until there is sufficient community interest. See [CONTRIBUTING.md](CONTRIBUTING.md) for architecture guidelines, development setup, and code standards.

Security issues should be reported privately — see [SECURITY.md](SECURITY.md).

---

## License
Distributed under the [Apache License 2.0](LICENSE).
