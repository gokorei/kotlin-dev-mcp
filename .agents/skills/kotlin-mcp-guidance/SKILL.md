---
name: kotlin-mcp-guidance
description: Workflow rules and best practices for Kotlin and Android development using the Kotlin MCP server.
---
# Kotlin MCP Server Workflow Guidelines

When developing Kotlin or Android applications, use the following MCP tool workflow:

## 1. Greenfield & Build Setup
- **Before running Gradle build commands**, invoke `kotlin_project_inspect(action = "diagnose_build")` or `kotlin_project_inspect(action = "structure")` to inspect the project layout, source sets, and configuration files.

## 2. Pre-Write Snippet Validation & Tiered Verification
- **Tier 1 (Instant Static Pre-Flight, <100ms)**:
  Before writing or editing Kotlin files, validate non-trivial logic without waiting for slow Gradle daemon spinups:
  - `kotlin_check_snippet(code = "...")` to verify syntax and type checking in-process.
  - `kotlin_lint(code = "...", action = "format_ktlint")` to format code according to official Kotlin style guidelines.
- **Tier 2 (Full Integration Gate)**:
  Run `kotlin_run(action = "gradle_task", taskName = ":module:test", timeoutSeconds = 180)` for module-level verification. Do not replace the final build oracle; use Tier 1 to eliminate avoidable compilation round-trips.

## 3. Proactive "Trigger-on-Sight" Rules
- **MANDATORY Compose Trigger**:
  Whenever modifying, generating, or reviewing `@Composable` UI code, `LazyColumn`, `LazyRow`, or `remember`:
  **ALWAYS run `kotlin_code_analyze(action = "compose", code = ...)` (or `workspacePath = "."`) before writing or building.**
  This statically catches:
  - Keys referencing mutated variables (`globalIndex++`), duplicate keys in `item(key = "...")`, and constant keys in `items(..., key = { "id" })` (preventing runtime `IllegalArgumentException: Key was already used` crashes).
  - Missing `key` parameters on dynamic `items(...)` collections.
  - Missing `modifier: Modifier = Modifier` default parameters.
  - Missing lifecycle handling (`collectAsState` -> `collectAsStateWithLifecycle`).
  - Unstable parameter types and legacy system UI insets.
- **MANDATORY Coroutines Trigger**:
  Whenever modifying code involving `CoroutineScope`, `launch`, `async`, or `Flow`:
  **Run `kotlin_code_analyze(action = "coroutines", code = ...)`** to detect unconfined dispatchers, missing cancellation propagation, or leaked job handles.

## 4. Public API & Layering Audits
- When adding new domain or module interfaces, call `kotlin_project_inspect(action = "package_api")` to ensure explicit public boundary contracts are maintained between layers.

## 5. Android & Jetpack Compose Development (When Android Profile Detected)
When the active environment profile contains Android:
- **Lazy List Keys**: Always supply explicit, unique keys for dynamic collections: `items(items = words, key = { it.id })`. Never hardcode constant literals in key lambdas.
- **State & Lifecycle**: Always use `flow.collectAsStateWithLifecycle()` from `androidx.lifecycle.compose` rather than raw `collectAsState()`.
- **Navigation**: Define Compose destinations using Kotlin `@Serializable` objects/classes with Navigation Compose 2.8+ type-safe routing.
- **State Preservation**: Use `rememberSaveable` for UI state and inject `SavedStateHandle` into `@HiltViewModel` to survive configuration changes and process death.
- **Edge-to-Edge**: Invoke `enableEdgeToEdge()` in Activity and apply Compose inset modifiers (`safeDrawingPadding()`, `imePadding()`).
- **Media & Permissions**: Prefer PhotoPicker (`ActivityResultContracts.PickVisualMedia`) for photo/video access. Ensure `POST_NOTIFICATIONS` runtime checks on Android 13+.
- **Runtime Resolution & Auditing**:
  - Use `kotlin_project_inspect(action = "android_runtime_target")` to discover package namespace, launcher activity, and synthesized ADB CLI launch commands.
  - Use `kotlin_project_inspect(action = "android_audit")` or `kotlin_code_analyze(action = "workmanager")` to statically audit Compose performance, dangerous permissions, R8 rules, and WorkManager coroutine safety.

