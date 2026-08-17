# Kotlin Coroutines & Structured Concurrency Guide

Kotlin Coroutines provide a lightweight, concurrency and non-blocking way to write asynchronous code.

## Key Concepts
- `suspend fun`: Functions that can pause and resume without blocking worker threads.
- `CoroutineScope`: Manages coroutine lifecycles and structured cancellation.
- `Dispatchers`:
  - `Dispatchers.Main`: UI operations on main looper thread.
  - `Dispatchers.IO`: Blocking disk & network I/O.
  - `Dispatchers.Default`: CPU-bound computation.
- `Flow`: Cold asynchronous data streams emitting multiple values sequentially.

## Cooperative Cancellation
Cancellation is cooperative: `cancel()` only takes effect at a suspension point.
- `delay()`, `yield()`, and suspending I/O check cancellation; a tight CPU loop that never suspends does NOT stop.
- `Thread.sleep()` inside a coroutine blocks and is NOT cancellable — use `delay()`.
- Catching `CancellationException` for cleanup is legal, but never swallow it: the coroutine stays cancelled — re-throw after cleanup or use `finally`.
- `yield()` is an explicit checkpoint for cancellation/progress.

## Structured Concurrency & Exception Propagation
Default `coroutineScope { }` semantics:
- A parent waits for all its children before completing.
- One child's exception cancels the parent AND all siblings, then propagates.
- `supervisorScope { }` isolates failures: a failing child cancels only itself; siblings and scope survive. Use for independent sub-tasks.
- Supervision only changes child→sibling propagation, NOT parent cancellation (parent cancel still cascades).

## Flow Backpressure
- `Flow` is COLD: no work until collected; each collector triggers a fresh producer run (emissions repeat). A `Channel` is a QUEUE with point-to-point delivery: one receiver consumes each element (multiple consumers compete). Use `SharedFlow` to broadcast to all collectors.
- Default: producer suspends whenever the collector is slower (sequential, naturally backpressured).
- `buffer(capacity)`: decouples producer/consumer via a channel; producer runs ahead, queueing up to `capacity` (default 64). Unbounded buffering can exhaust memory.
- `conflate()`: only the LATEST value matters; a slow consumer skips intermediate emissions, producer never blocks. Use for UI tickers where stale intermediate values are fine.
- Producer exceptions are delivered to the collector and abort the flow; use `catch`/`retry`/`onEach` for operator-level handling.

## Start-All-Then-Await (Barrier)
Launch all independent `async` handles FIRST, then await — otherwise they run sequentially.
```kotlin
// (Runs inside `coroutineScope { }` — `async` needs a CoroutineScope receiver)
// WRONG: b only starts after a finishes -> serialized
val a = async { fetch("a") }.await()
val b = async { fetch("b") }.await()
// RIGHT: both start immediately, then we await
val d1 = async { fetch("a") }; val d2 = async { fetch("b") }
val a = d1.await(); val b = d2.await()
// Homogeneous: awaitAll()
val results = (1..3).map { async { repo.fetch(it) } }.awaitAll()
```
Gather all `async` handles before any `await`; use `awaitAll()` for a homogeneous batch.

## `select` is Biased
`select { }` suspends until one clause is ready. When several clauses are ready simultaneously, the EARLIEST-listed clause deterministically wins (syntax-order bias, NOT random).
`selectUnbiased { }` picks uniformly at random among ready clauses. Use `selectUnbiased` for fair load distribution across ready channels; plain `select` for deterministic tie-breaking. Clauses differ in result type; the block returns the chosen clause's value (`onAwait`, `onReceive`, `onSend`).

## Testing Best Practices
Use `kotlinx-coroutines-test` `runTest` and inject `TestDispatcher` / `StandardTestDispatcher` for virtual time testing.
Use `@BeforeAll`/`@AfterAll` correctly in Kotlin: either `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` on the class or `companion object` + `@JvmStatic` — a plain instance `@BeforeAll` throws a JUnit configuration error (must be static unless PER_CLASS), it is not silently ignored.