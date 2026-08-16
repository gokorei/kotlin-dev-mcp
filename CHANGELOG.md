# Changelog

All notable changes to `kotlin-mcp` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-08-16

### Added
- Action-multiplexed MCP tool suite: 11 tools (5 read-only, 6 mutating) covering documentation, snippet diagnostics, code analysis, LSP-style text services, project inspection, refactoring, library checks, lint/format, and execution.
- Embedded K2 compiler (`SnippetCompiler`, `kotlin-compiler-embeddable`) for in-process static type checking and diagnostics without external Gradle daemons.
- Real detekt and ktlint backends (isolated subprocess tooling classpaths) for `kotlin_lint`.
- Host JVM process runner (`kotlin_run`) for Kotlin snippets, Gradle tasks, and JUnit XML test report parsing.
- Progressive discovery: automatic workspace dependency detection (`build.gradle.kts` / `libs.versions.toml`) with framework-aware tool descriptions.
- MCP protocol extensions: bundled stdlib documentation and architecture guidelines as Markdown resources (`kotlin://docs/index.md`, `kotlin://guidelines/architecture.md`) and guidance prompts (`kotlin-task`, `kotlin-architecture`).
- Context injection tools for dependency and API/DB schema digests.
- Built-in agent skill (`skills/kotlin-mcp/SKILL.md`) with action matrices and refactoring pipelines.
- GitHub Actions CI, release, and wiki-sync workflows; issue templates and pull request template.

### Changed
- Unified code analysis and execution on K2 compiler APIs (K1 removed).
- Replaced regex-based source parsing with static K2 PSI AST traversal throughout the codebase.
- Restructured the codebase for embeddable compiler injection (detekt/ktlint run on their own embedded compiler versions).

### Fixed
- Tests no longer perform real network requests.
- Machine-specific JDK path removed from `gradle.properties` so the build is portable across machines and CI.

### Security
- Stdio transport safety: all logging routed to stderr (`kotlin-logging-jvm` + `slf4j-simple`), keeping stdout clean for JSON-RPC frames.
- See [SECURITY.md](SECURITY.md) for the security policy and sandboxing notes in `docs/wiki/Security-And-Sandboxing.md`.

[Unreleased]: https://github.com/gokorei/kotlin-dev-mcp/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/gokorei/kotlin-dev-mcp/releases/tag/v1.0.0