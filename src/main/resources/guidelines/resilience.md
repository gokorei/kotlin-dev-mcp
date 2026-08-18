# Kotlin Backend Resilience & Fault-Tolerance Guidelines

## 1. Independent Verification Probing (Silence != Recovery)
- Never assume that a recovery action (reconnecting a pool, clearing a cache, failing over a replica, or executing a retry) succeeded simply because it did not throw an exception or returned `Unit`/`done`.
- Every automated remediation must be gated by an **independent health probe** (e.g. executing an active validation query or endpoint ping) to verify that system health is genuinely restored before declaring success.

## 2. Verifiable State Caching (Memory Must Not Lie)
- In adaptive or self-healing systems, only record **demonstrably probe-verified recoveries** in memory or cache stores.
- Reject failed or speculative remediation attempts from adaptive recall so the system never develops false recovery heuristics.

## 3. Deterministic Typed State Machines for Remediation
- Model production resilience workflows as bounded, strictly typed state machines (e.g., `ApplicationError -> AgentOutcome`) with zero escape hatches.
- Separate remediation into:
  - **Adaptive Path**: Instant recall of verified antibody recipes for known error signatures.
  - **Innate Path**: Step-by-step exploration of bounded, idempotent micro-remediations (reconnect, flush, failover), each gated by an independent probe.
- Avoid unconstrained, non-deterministic LLM prompt loops inside critical HTTP request lifecycles.

## 4. Boundary Observability & Trace Correlation
- Bind request correlation (`CallId` in Ktor) directly to OpenTelemetry spans, metrics, and downstream response headers.
- Classify failures and assign HTTP status codes centrally at the architectural boundary (e.g. `StatusPages`), keeping route handlers clean of ad-hoc error-mapping boilerplate.
