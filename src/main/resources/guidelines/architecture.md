# Kotlin Architectural & Testability Guidelines

## 1. UI vs Business-Logic Boundary Isolation
- Keep composables, screens, and controllers free of business rules. Extract decision logic into pure
  domain functions or use-cases that are framework-free and trivially testable.
- Compose state should be hoisted (`remember`/`mutableStateOf` at the caller); views render state, they
  do not own or mutate it.
- Prefer unidirectional data flow (UI event → reducer/use-case → new state → render).

## 2. Explicit DTO-to-Domain Mapping
- Never leak DTOs (API/DB/JSON response types) past the data boundary. Map to domain models at the
  boundary with explicit, one-directional mapper functions, e.g. `fun UserDto.toDomain(): User`.
- Keep ingress (DTO → domain) and egress (domain → DTO) mapping obvious; avoid field-by-field mapping
  scattered across the UI layer.

## 3. Boundary Testability
- Constructor-inject collaborators and `CoroutineDispatcher`s (default `Dispatchers.Default`) so tests can
  supply fakes and use virtual time (`runTest` + `StandardTestDispatcher`).
- Depend on interfaces/abstractions across layer boundaries (repository interfaces in domain,
  implementations in data) so each layer is testable in isolation.
- Keep side effects at the edges (I/O, time, randomness) injectable.

## 4. Higher-Level DSLs over Deconstructed Shapes
- Prefer Kotlin DSL builders (type-safe builders with `@DslMarker` and lambda receivers) over long parameter
  lists, repeated deconstructed tuples/pairs, or raw maps when configuring parameters and building complex shapes.
- A higher level of semantic abstraction is significantly more expressive, self-documenting, and maintainable than destructuring.

## 5. Domain Error Modeling (Errors as Values)
- Model expected domain and operational failure variants as explicit `sealed interface` (e.g. `sealed interface ApplicationError`)
  or Arrow `Either<Failure, Success>` with structured metadata signatures instead of throwing unchecked runtime exceptions across boundaries.
- Intercept and translate domain errors into transport responses (HTTP status codes, RPC codes, or UI states) strictly at the architectural boundary.
- For backend resilience and self-healing systems, see `kotlin://guidelines/resilience.md`.

Use `kotlin_project_inspect_structure` (reporting ui/domain/data layering) to verify these boundaries hold.

