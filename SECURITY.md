# Security Policy

## Reporting a Vulnerability

Please report suspected security vulnerabilities **privately** using GitHub's
private vulnerability reporting (**Security → Report a vulnerability** on this
repository). Do **not** open a public issue.

Please include:

- Affected version and how the server was built/run
- Steps to reproduce
- A minimal proof-of-concept, with any secrets redacted

You should receive an acknowledgement within 3 business days.

## Scope & safe-use notes

- `kotlin_check_snippet(action="mutate")` **compiles and executes arbitrary Kotlin** in a child JVM. Treat any use of this server on untrusted input as equivalent to executing that input.
- Child-process isolation prevents server classloader access and cleans up descendants on normal exit and timeout paths, but it is not an OS sandbox. Mutation snippets retain host-user filesystem, network, and parent-process access unless the server runs inside an appropriately restricted container or platform sandbox.
- For untrusted multi-tenant deployments, use a container with restricted filesystem mounts, disabled network access, and process/PID isolation; follow-up work should provide a supported sandbox launcher and resource limits.
- **Dual Execution Runners**:
  - `host_jvm` (subprocess): Spawns an isolated subprocess using the system Java executable with dedicated memory bounds and timeout protection. This is the default execution runner.
  - `in_process` / `embedded` (`FastSnippetRunner`): Executes snippets in-process on an isolated classloader for low-latency scratchpad evaluations. Output streams are redirected via thread-local print stream interceptors, and snippets containing host-terminating calls (e.g. `exitProcess`, `System.exit`, `Runtime.getRuntime().halt`) are automatically redirected to `host_jvm`.
- **Hardening Toggle**:
  - In security-conscious environments where in-process reflection or execution must be completely disabled, set the environment variable `KMCP_DISABLE_IN_PROCESS_RUNNER=true` (or system property `-Dkmcp.disable_in_process_runner=true`). When enabled, all snippet runs are strictly enforced to execute in isolated `host_jvm` child processes.
- The embedded compiler, snippet execution engines, and stdio transport are the main attack surfaces for this project.
