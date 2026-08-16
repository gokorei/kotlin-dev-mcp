# Kotlin Coroutines & Structured Concurrency Guide

Kotlin Coroutines provide a lightweight, non-blocking way to write asynchronous code.

## Key Concepts
- `suspend fun`: Functions that can pause and resume without blocking worker threads.
- `CoroutineScope`: Manages coroutine lifecycles and structured cancellation.
- `Dispatchers`:
  - `Dispatchers.Main`: UI operations on main looper thread.
  - `Dispatchers.IO`: Blocking disk & network I/O.
  - `Dispatchers.Default`: CPU-bound computation.
- `Flow`: Cold asynchronous data streams emitting multiple values sequentially.

## Testing Best Practices
Use `kotlinx-coroutines-test` `runTest` and inject `TestDispatcher` / `StandardTestDispatcher` for virtual time testing.
