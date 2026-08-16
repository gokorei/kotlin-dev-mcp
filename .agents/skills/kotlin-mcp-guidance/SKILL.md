---
name: kotlin-mcp-guidance
description: Workflow rules and best practices for Kotlin and Android development using the Kotlin MCP server.
---
# Kotlin MCP Server Workflow Guidelines

When developing Kotlin or Android applications, use the following MCP tool workflow:

## 1. Greenfield & Build Setup
- **Before running Gradle build commands**, invoke `kotlin_project_inspect(action = "diagnose_build")` or `kotlin_project_inspect(action = "structure")` to inspect the project layout, source sets, and configuration files.

## 2. Pre-Write Snippet Validation
- **Before writing or editing Kotlin files**, validate non-trivial logic using:
  - `kotlin_check_snippet(code = "...")` to verify syntax and type checking.
  - `kotlin_lint(code = "...", action = "format_ktlint")` to format code according to official Kotlin style guidelines.

## 3. Public API & Layering Audits
- When adding new domain or module interfaces, call `kotlin_project_inspect(action = "package_api")` to ensure explicit public boundary contracts are maintained between layers.
