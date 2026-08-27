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

## 4. Android & Jetpack Compose Development (When Android Profile Detected)
When the active environment profile contains Android:
- **State & Lifecycle**: Always use `flow.collectAsStateWithLifecycle()` from `androidx.lifecycle.compose` rather than raw `collectAsState()`.
- **Navigation**: Define Compose destinations using Kotlin `@Serializable` objects/classes with Navigation Compose 2.8+ type-safe routing.
- **State Preservation**: Use `rememberSaveable` for UI state and inject `SavedStateHandle` into `@HiltViewModel` to survive configuration changes and process death.
- **Edge-to-Edge**: Invoke `enableEdgeToEdge()` in Activity and apply Compose inset modifiers (`safeDrawingPadding()`, `imePadding()`).
- **Media & Permissions**: Prefer PhotoPicker (`ActivityResultContracts.PickVisualMedia`) for photo/video access. Ensure `POST_NOTIFICATIONS` runtime checks on Android 13+.
- **Runtime Resolution & Auditing**:
  - Use `kotlin_project_inspect(action = "android_runtime_target")` to discover package namespace, launcher activity, and synthesized ADB CLI launch commands.
  - Use `kotlin_project_inspect(action = "android_audit")` or `kotlin_code_analyze(action = "workmanager")` to statically audit Compose performance, dangerous permissions, R8 rules, and WorkManager coroutine safety.

