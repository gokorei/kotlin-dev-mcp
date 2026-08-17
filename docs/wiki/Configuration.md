# Configuration & Environment Guide

This document outlines the environment variables, JVM system properties, and client configuration options for tuning `kotlin-mcp`.

---

## Environment Variables & System Properties

| System Property | Environment Variable | Default | Purpose |
| :--- | :--- | :--- | :--- |
| `kmcp.disable_network_audits` | `KMCP_DISABLE_NETWORK_AUDITS` | `false` | When set to `true`, disables outbound HTTP requests to external databases (e.g., OSV.dev vulnerability queries and `kotlinlang.org` stdlib index fetching). Forces `kotlin-mcp` to use built-in offline baselines. |
| `org.slf4j.simpleLogger.defaultLogLevel` | `SLF4J_SIMPLE_LOGGER_DEFAULT_LOG_LEVEL` | `info` | Controls logging verbosity emitted to `stderr`. Valid values: `trace`, `debug`, `info`, `warn`, `error`, `off`. |
| `JAVA_HOME` | `JAVA_HOME` | *(Host Default)* | Path to the JDK installation used by the host process runner (`kotlin_run`) and subprocess linters (`kotlin_lint`). Requires Java 21+. |

---

## Offline & Air-Gapped Operation

For high-security or offline environments where outbound internet access is restricted or disabled:

1. **Disable Network Audits:** Pass `-Dkmcp.disable_network_audits=true` to the `java` launch command or set `KMCP_DISABLE_NETWORK_AUDITS=true` in your MCP client configuration.
2. **Offline Vulnerability Scans:** `kotlin_project_inspect` automatically falls back to an embedded, offline vulnerability baseline for critical Maven dependency CVEs (e.g., Log4Shell, Jackson, Netty, Commons Compress).
3. **Offline Standard Library Docs:** `kotlin_docs_read` seeds from an embedded JSON symbol index packaged directly inside `kotlin-mcp-1.0.0-all.jar`.

---

## MCP Tool Loading Modes

MCP clients support two tool loading strategies:

### 1. Eager Tool Definition Injection (`"eager": true`, Recommended)
Injects all 11 tool definitions and parameter schemas directly into the LLM system prompt on agent startup. This removes two-step tool discovery friction, allowing models to invoke tools immediately.

```json
{
  "mcpServers": {
    "kotlin-mcp": {
      "command": "java",
      "args": ["-jar", "/path/to/kotlin-mcp-1.0.0-all.jar"],
      "eager": true
    }
  }
}
```

### 2. Lazy Discovery Mode (`"eager": false`)
Defers tool schema loading until requested by the client context. Recommended if prompt token usage must be strictly minimized on agent startup.

---

[← Home](Home)
