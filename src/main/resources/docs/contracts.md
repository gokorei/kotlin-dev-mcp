# Kotlin Contracts Guide

Kotlin Contracts inform the compiler about function behavior and smart-casting guarantees.

## Capabilities
- `returns(true) implies (value != null)`: Smart-casts variables after function returns true (e.g. `isNullOrEmpty()`, `requireNotNull()`).
- `callsInPlace(kind = InvocationKind.EXACTLY_ONCE)`: Informs compiler that lambda executes exactly once, enabling variable initializations inside lambda blocks.
