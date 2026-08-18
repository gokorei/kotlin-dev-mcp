# Kotlin Multiplatform Storage & Persistence Guidelines (Web/Wasm/JS)

## 1. Room 3.0 on Web (OPFS vs. IndexedDB)
- In Kotlin Multiplatform (KMP) targeting Web (Kotlin/Wasm and Kotlin/JS), Room stores relational database files directly inside **OPFS (Origin Private File System)** and executes queries via SQLite compiled to WebAssembly (`sqlite-wasm` or `sql.js`).
- Room does **not** map tables to IndexedDB object stores or browser `localStorage`. Relational schemas, transactions, migrations, and standard SQLite constraints behave consistently across Android, JVM, iOS (Native), and Web.

## 2. SQLite Driver Asymmetry & `sqlite-async`
- **Synchronous vs. Asynchronous Drivers**: On JVM and Native targets, low-level SQLite C-driver operations (`prepare`, `step`) are synchronous/blocking. On Web targets, browser file system and Web Worker message-passing APIs are inherently asynchronous.
- **Common Code Abstraction**: When sharing common KMP database code that targets both Web and Non-Web platforms, opt into `androidx.sqlite:sqlite-async` and use suspending extension functions instead of blocking driver calls.
- **No `runBlocking` in Web Storage**: Never use `runBlocking { }` to bridge asynchronous SQLite drivers in common or web code. JavaScript and WebAssembly browser runtimes are single-threaded event loops where thread blocking freezes UI rendering or crashes the runtime.

## 3. Room 3.0 Coroutine-Native Architecture
- **Suspending Connection Pool**: Room 3.0 replaces thread-blocking connection acquisition with coroutine-based suspension (`Mutex`). When connection limits are reached, callers suspend until a connection is released.
- **Suspending Callbacks**: `RoomDatabase.Callback` lifecycle hooks (`onCreate`, `onOpen`, `onDestructiveMigration`) are suspending functions.
- **Flow Observation**: Database query observation is natively Kotlin `Flow`-driven for lifecycle-safe, backpressure-aware streams.

## 4. DataStore Web Storage Selection
- Choose the appropriate AndroidX DataStore storage backend based on persistence scope and performance requirements:
  - **`WebLocalStorage`**: Synchronous browser storage for lightweight persistent key-values (< 5MB, e.g. theme preference) that persist across browser restarts.
  - **`WebSessionStorage`**: Synchronous tab-scoped ephemeral storage discarded when the browser tab closes.
  - **`WebOpfsStorage`**: Asynchronous file-based storage recommended for larger, structured Okio or Protobuf serialized schemas.

## 5. Cross-Origin Isolation Headers (COOP / COEP)
- High-performance SQLite Wasm implementations using Web Workers, `SharedArrayBuffer`, and synchronous access handles in OPFS require modern browsers to enforce cross-origin isolation.
- Ensure the web deployment server (or reverse proxy) emits the following HTTP response headers:
  ```http
  Cross-Origin-Opener-Policy: same-origin
  Cross-Origin-Embedder-Policy: require-corp
  ```
- Without these headers, browsers restrict `SharedArrayBuffer`, causing persistent SQLite Web Workers to fail or fall back to non-persistent in-memory modes.
