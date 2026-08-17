# Release Notes

Overview of new features, bug fixes, and improvements shipped in each `kotlin-mcp` release.

---

## v1.0.0 — 2026-08-16

### New Features

- **Action-multiplexed tool suite** — 11 tools (5 read-only, 6 mutating) covering documentation, snippet diagnostics, code analysis, LSP-style text services, project inspection, refactoring, library checks, lint/format, and execution.
- **Embedded K2 compiler** (`SnippetCompiler`, `kotlin-compiler-embeddable`) — in-process static type checking and diagnostics without external Gradle daemons.
- **Real detekt and ktlint backends** — isolated subprocess tooling classpaths power `kotlin_lint`.
- **Host JVM process runner** (`kotlin_run`) — executes Kotlin snippets, Gradle tasks, and JUnit XML test report parsing.
- **Progressive discovery** — automatic workspace dependency detection (`build.gradle.kts` / `libs.versions.toml`) with framework-aware tool descriptions.
- **MCP protocol extensions** — bundled stdlib documentation and architecture guidelines as Markdown resources (`kotlin://docs/index.md`, `kotlin://guidelines/architecture.md`) plus the `kotlin-task` and `kotlin-architecture` guidance prompts.
- **Context injection tools** — dependency and API/DB schema digests.
- **Built-in agent skill** (`skills/kotlin-mcp/SKILL.md`) with action matrices and refactoring pipelines.
- **CI, release, and wiki workflows** — GitHub Actions automation with issue and pull request templates.

### Bug Fixes

> No bug fixes in this release.

### Improvements

> No improvements in this release.

---

[← Home](Home)