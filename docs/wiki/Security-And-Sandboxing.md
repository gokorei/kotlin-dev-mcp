# Security, Process Isolation, and Containerized Sandboxing

This guide explains how `kotlin-mcp` handles code execution security, what level of process isolation is provided out-of-the-box, and how to deploy the server inside a containerized sandbox for untrusted environments.

---

## 1. Execution Architecture Overview

`kotlin-mcp` offers real-time Kotlin snippet compilation (`kotlin_check_snippet`), AST-based refactoring (`kotlin_refactor`), static analysis (`kotlin_lint`), and execution (`kotlin_run`).

Execution operations (`kotlin_run` with `action="snippet"`, `action="gradle_task"`, or `action="test_report"`) run host JVM processes using `java` child processes via `ProcessBuilder`.

---

## 2. Process-Level Isolation vs. Container Sandboxing

It is important to understand the boundary between **Process Isolation** and **Container Sandboxing**:

| Isolation Layer | Built-in Behavior | Docker / Containerized Deployment |
| :--- | :--- | :--- |
| **Crash Protection** | ✅ **Yes**: Snippet `System.exit()` or OOM only kills the child process; MCP server stays alive. | ✅ **Yes** |
| **Heap Memory Isolation** | ✅ **Yes**: Runs in a separate JVM process with independent memory limits. | ✅ **Yes** |
| **Timeout Enforcement** | ✅ **Yes**: Process is forcibly terminated if execution exceeds timeout. | ✅ **Yes** |
| **JVM Flag Filtering** | ✅ **Yes**: Agent flags (`-javaagent`, `-agentlib`, `--add-opens`) are stripped. | ✅ **Yes** |
| **Filesystem Isolation** | ❌ **No**: Snippets can access files with host user permissions. | ✅ **Yes**: Restricted to mounted workspace volume only. |
| **Network Socket Limits** | ❌ **No**: Outbound network requests are permitted. | ✅ **Yes**: Can disable network access (`--network none`). |
| **OS Syscall Restrictions** | ❌ **No**: Can invoke sub-processes (`ProcessBuilder`). | ✅ **Yes**: Restricted via seccomp / Landlock profiles. |

> [!IMPORTANT]
> Out of the box, `kotlin-mcp` runs code under the privileges of the host OS user running the server. For trusted local pair programming (e.g. Cursor, Claude Code, Antigravity), this is standard. For multi-tenant or untrusted deployments, run `kotlin-mcp` inside a container.

---

## 3. Deployment Hardening Strategies

### Strategy A: Trusted Local Pair Programming (Default)
For single-user local IDE pair programming where the AI assistant works on your local repository:
```bash
java -jar build/libs/kotlin-mcp-all.jar
```

### Strategy B: Containerized Execution with Docker (Recommended for Untrusted Code)
To completely isolate filesystem write scope and block network access, run `kotlin-mcp` inside a lightweight Docker container with network disabled and read-only root:

```dockerfile
# Dockerfile.sandbox
FROM eclipse-temurin:21-jre-alpine

RUN adduser -D -u 1000 mcpuser
USER mcpuser
WORKDIR /workspace

COPY build/libs/kotlin-mcp-all.jar /app/kotlin-mcp.jar

ENTRYPOINT ["java", "-jar", "/app/kotlin-mcp.jar"]
```

#### Run with Network Disabled & Volume Isolation:
```bash
docker run -i --rm \
  --network none \
  --read-only \
  --tmpfs /tmp:exec,mode=1777 \
  -v $(pwd):/workspace:rw \
  kotlin-mcp-sandbox
```

---

## 4. Summary Checklist for Wiki & Project Adopters

- [x] **Keep README concise**: Mention standard usage and point to this Wiki page for hardening.
- [x] **Audit environment variables**: Use `LINT_SERVICE_MODE` and memory constraints as needed.
- [x] **Use Docker/Podman for multi-tenant MCP hosts**: Ensure untrusted user inputs cannot touch host files outside `/workspace`.
