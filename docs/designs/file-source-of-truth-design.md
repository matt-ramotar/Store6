# File source of truth — technical design

Status: proposed; revised after two independent adversarial reviews.
Target artifact: `org.mobilenativefoundation.store:file` (experimental tier).

This document is the design authority for a filesystem-backed `SourceOfTruth` and `Bookkeeper`
adapter for Store 6. The companion execution document is
[file-source-of-truth-implementation-plan.md](./file-source-of-truth-implementation-plan.md).

## 1. Problem and demand

Store has never shipped a file-backed persistence adapter.
[#533](https://github.com/MobileNativeFoundation/Store/issues/533) asked where the Store 3
filesystem support went. Blob-shaped values — images, documents, response bodies — do not belong
in SQLite rows, and the two shipped persistence adapters (`room`, `sqldelight`) both assume a SQL
database the application already operates.

The Store 6 roadmap invites exactly this contribution: "The source-of-truth seam is small on
purpose. An adapter for a store we do not cover is a self-contained contribution"
([ROADMAP.md](../../ROADMAP.md), "How to contribute"). This adapter also serves a second purpose:
it proves the `testing` contract kits against a persistence shape with no transaction manager, no
query engine, and no change notifications — the hardest fit the seam has been asked to cover so
far.

## 2. Goals and non-goals

### Goals

1. A `SourceOfTruth<K, V>` implementation that stores one value per canonical key as one file,
   passing all 15 inherited tests of
   `org.mobilenativefoundation.store6.testing.SourceOfTruthContractKit` on every target it ships.
2. A sibling `Bookkeeper` implementation passing all 6 inherited tests of
   `org.mobilenativefoundation.store6.testing.BookkeeperContractKit`, with freshness metadata and
   watermarks that survive process restart.
3. Zero diff to `core` and `testing`. The seam package
   (`org.mobilenativefoundation.store6.core.seam`) is a freeze candidate enforced by the
   "Verify seam package matches the TD-13 freeze list" CI step; this adapter must not grow it.
4. Full repository-convention compliance: convention plugin, `explicitApi()`, BCV dumps including
   klib dumps, the `store6.yml` gate lists, a runnable sample, and a README following the adapter
   README shape.

### Non-goals (deliberate, with rationale)

- **No `TransactionalSourceOfTruth`.** See decision D5.
- **No cross-instance or cross-process change propagation.** No file watching. The seam contract
  makes reactivity to external changes implementation-specific and only requires that external
  changes appear in the first emission of a new collection, which a disk read at collection start
  satisfies.
- **No fsync durability guarantee in v1.** See decision D11 for the exact posture and its
  consequences.
- **No value streaming in v1.** The codec API is streaming-shaped (`Source`/`Sink`) so streaming
  can be added without a signature break, but v1 materializes payloads in memory.
- **No encryption, compression, or eviction policy.** Encryption composes as a codec or a future
  decorator artifact; size-based eviction is an engine/maintenance concern, not a SoT concern.
- **No kotlinx-serialization dependency.** A JSON codec is a five-line user-side recipe shown in
  the README (decision D14).

## 3. Decision record

Each decision is numbered so review can attack them individually.

### D1 — IO library: kotlinx-io 0.9.1 (over Okio 3.17.0)

Neither library is in `gradle/libs.versions.toml` today; this is a fresh pick.

| Criterion | kotlinx-io 0.9.1 | Okio 3.17.0 |
|---|---|---|
| Store6 12-target coverage | All 12 published, including filesystem access on `js` and `wasmJs` under Node.js (shared Node filesystem implementation) and on every native target | No `wasmJs` filesystem. Node filesystem is exposed for Kotlin/JS only; Wasm support is WASI-only (`okio-wasifilesystem`), which is not the `wasmJs` target Store6 ships |
| API stability | Filesystem API documented as "unstable and subject to change"; `FileSystem` is a sealed interface "until API stabilization" | Stable since 3.0 (2022) |
| Test fakes | None (sealed `FileSystem` forbids user implementations) | `FakeFileSystem` artifact (carries a kotlinx-datetime coupling that has caused klib link conflicts on wasmJs) |
| Atomic move | `FileSystem.atomicMove(source, destination)` — "Atomically renames source to destination overriding destination if it already exists" | `atomicMove` with a documented NTFS/FAT caveat: replace-over-existing degrades to atomic-delete + atomic-rename, not atomic in aggregate |
| fsync exposure | None | None |
| Hashing utilities | None | `ByteString.sha256()` in common |
| Ecosystem direction | JetBrains kotlinx; Ktor 3's IO layer | Square; mature, widely deployed |
| Kotlin 2.3.20 fit | 0.9.0+ built against Kotlin 2.3; the Wasm/JS `require` removal in Kotlin 2.3.20 was fixed in 0.8.1–0.9.1 | Compatible |

**Chosen: kotlinx-io.** The deciding criterion is target coverage: Store6's full convention is 12
targets with `js { nodejs() }` and `wasmJs { nodejs() }`, and only kotlinx-io reaches the
filesystem on both. Losing `FakeFileSystem` costs little here because contract tests should run
against the real filesystem in per-test temporary directories anyway — that is what proves the
adapter on each CI platform. Losing `sha256` costs nothing because the filename scheme needs no
hashing (D6).

**Two verified implementation facts constrain the design** (both from kotlinx-io 0.9.1 sources):

1. **Android API 24–25 has no NIO.** The JVM `SystemFileSystem.atomicMove` probes
   `Class.forName("java.nio.file.Files")` once and, when the class is absent, installs a mover
   that always throws `UnsupportedOperationException("Atomic move not supported")`.
   `java.nio.file` arrived in Android API 26; this repository's convention sets `minSdk = 24`.
   Everything else in the JVM implementation (`source`, `sink`, `delete`, `createDirectories`,
   `exists`, `list`, `metadataOrNull`) is `java.io.File`-based and works on API 21+. D9 defines
   the `androidMain` fallback that closes this gap; without it every mutation would throw on
   API 24–25 devices, and host-JVM unit tests would never see it.
2. **mingwX64 uses ANSI Win32 APIs.** The MinGW implementation calls `MoveFileExA` and
   `GetFullPathNameA`, not the wide-character variants. Adapter-generated names are ASCII by
   construction (D6), but a caller-provided root containing characters outside the active code
   page can mis-encode. Documented as a platform limitation (§8, README); not fixable in the
   adapter.

**Accepted risk:** the kotlinx-io filesystem API is pre-1.0 and can break. Containment: the
adapter is an experimental-tier artifact (breaking dependency bumps are permitted by
[STABILITY.md](../../STABILITY.md) §2), the version is pinned in the catalog, and the leaked
kotlinx-io types in our public API are limited to `kotlinx.io.files.Path` (constructors) and
`kotlinx.io.Source`/`kotlinx.io.Sink` (codec). This mirrors how `room` and `sqldelight` expose
`RoomDatabase` and `SqlDriver`. If kotlinx-io's instability proves disruptive before this artifact
is considered for graduation, the graduation review re-runs this decision; Okio is the named
fallback and the public surface above is the full blast radius.

### D2 — Module identity and scaffold

- Gradle path `:file`, directory `file/`, sample `:file-sample` at `file/sample`
  (matching the `include`/`projectDir` pattern in `settings.gradle`).
- Maven artifact `org.mobilenativefoundation.store:file`, via module `gradle.properties`:
  `VERSION_NAME=6.0.0-SNAPSHOT`, `POM_NAME=file`, `POM_ARTIFACT_ID=file` (the `room` shape).
- Package `org.mobilenativefoundation.store6.file`; Android namespace the same string.
- `file/src/androidMain/AndroidManifest.xml` containing `<manifest />` — the convention plugin
  hard-codes this manifest path (`Store6Conventions.kt`), and the `realtime`/`graphql`
  module-addition commits both added it.
- Dependencies: `commonMain` declares `api(projects.core)` and `api(libs.kotlinx.io.core)` —
  `api`, not `implementation`, because `Path`/`Source`/`Sink` and the seam types appear in public
  signatures (the `room`/`sqldelight` precedent for leaked types). `commonTest` declares
  `implementation(projects.testing)`, `implementation(libs.kotlinx.coroutines.test)`,
  `implementation(libs.turbine)`.
- Version catalog additions: `kotlinxIo = "0.9.1"` under `[versions]`;
  `kotlinx-io-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-io-core", version.ref = "kotlinxIo" }`
  under `[libraries]` (naming shape of `kotlinx-coroutines-core`).
- Every public declaration carries `@ExperimentalStoreApi`, consistent with the packaging rule in
  STABILITY.md §2 and with every shipped adapter.

### D3 — Target matrix: full 12-target convention plugin, with a named fallback

Apply `org.mobilenativefoundation.store.store6.multiplatform` (the full 12-target convention:
android, jvm, iosX64, iosArm64, iosSimulatorArm64, macosArm64, watchosArm64, tvosArm64, linuxX64,
mingwX64, js/Node, wasmJs/Node). kotlinx-io publishes all of them.

**Fallback, decided by evidence, not preference:** if `:file:wasmJsNodeTest` or `:file:jsNodeTest`
fail on real filesystem operations and the failure is a kotlinx-io platform gap rather than an
adapter bug, switch to `org.mobilenativefoundation.store.store6.multiplatform.subset` and drop
exactly the failing web target(s), following the `room` precedent (subset plugin, a build-file
comment naming the exact reason, and `case` exceptions in the "Verify common and target
publications" step of `store6.yml`). The implementation plan carries this as an explicit decision
gate with a time budget.

### D4 — Public API surface

No builder sugar and no DSL extension — `room` and `sqldelight` set the precedent that adapters
are constructed directly and passed to `store { persistence(...) }` / `bookkeeper(...)`.

The seam interfaces carry `@SubclassOptInRequired(DelicateStoreApi::class)`, so both
implementation classes opt in explicitly (the `RoomSourceOfTruth` / `SqlDelightSourceOfTruth`
pattern). Default parameter values in public constructors are established precedent
(`SqlDelightSourceOfTruth` ships them; BCV records the synthetic `$default` constructor).

```kotlin
package org.mobilenativefoundation.store6.file

@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class FileSourceOfTruth<K : StoreKey, V : Any>(
    directory: Path,
    codec: FileCodec<V>,
    corruptionPolicy: FileCorruptionPolicy = FileCorruptionPolicy.QUARANTINE,
    ioContext: CoroutineContext = Dispatchers.Default,
) : SourceOfTruth<K, V>

@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class FileBookkeeper(
    directory: Path,
    ioContext: CoroutineContext = Dispatchers.Default,
) : Bookkeeper

/**
 * Encodes one value as one file payload.
 *
 * Round-trip law: decode(encode(v)) must be structurally equal to v — the contract kits assert
 * read-your-writes with structural equality. Implementations must not close or retain the
 * supplied Source/Sink (the adapter owns them), must write the complete payload before
 * returning from encode, and must treat the Source as exactly one payload. A throw from encode
 * fails the mutation (nothing is applied); a throw from decode is handled per D7.
 */
@ExperimentalStoreApi
public interface FileCodec<V : Any> {
    public fun encode(value: V, sink: Sink)
    public fun decode(source: Source): V
}

@ExperimentalStoreApi
public object ByteArrayFileCodec : FileCodec<ByteArray>

@ExperimentalStoreApi
public object Utf8StringFileCodec : FileCodec<String>

@ExperimentalStoreApi
public enum class FileCorruptionPolicy {
    /** Move the unreadable file aside with a `.corrupt` suffix (best-effort) and treat the row as absent. */
    QUARANTINE,
    /** Throw from the reading operation. Reader collections then follow the engine's retry contract. */
    PROPAGATE,
}
```

Notes:

- `directory` may be the same `Path` for both classes: `FileSourceOfTruth` owns the `values/`,
  `values-tmp/`, and `values-trash/` subtrees; `FileBookkeeper` owns `bookkeeping/`,
  `bookkeeping-tmp/`, and `bookkeeping-trash/`. The subtrees are disjoint by construction. No
  other coupling exists between the two classes; unlike `sqldelight`'s shared sidecar, they are
  independent components (the `room` precedent).
- `ioContext` follows the `sqldelight` adapter's `readContext: CoroutineContext =
  Dispatchers.Default` convention. `Dispatchers.IO` is not referencable from common code; JVM and
  native callers can pass it. The adapter strips any `Job` from the supplied context
  (`ioContext.minusKey(Job)`) so a caller-provided element cannot re-parent internal work.
- `WallClock` is not a parameter: the SoT stores values only, and `Bookkeeper` receives
  timestamps through `recordSuccess(key, meta)`/`recordFailure(key, atEpochMillis)` arguments.
- Directories are created lazily on first use, so constructing the classes performs no IO and
  cannot throw for filesystem reasons. The first operation of each instance ensures its subtrees
  exist (`createDirectories(..., mustCreate = false)`) — including the tmp and trash parents that
  later atomic renames target — and sweeps leftover tmp/trash entries best-effort.

### D5 — `SourceOfTruth` only; no `TransactionalSourceOfTruth`

`TransactionalSourceOfTruth` KDoc: "the engine never assumes it and there is deliberately no
silent non-atomic default." A plain filesystem offers per-file atomicity (rename) but no
multi-operation atomicity; an implementation of `withTransaction` that merely serializes
operations would be exactly the silent non-atomic default the seam refuses to provide.

Consequences, stated where users will read them (README + KDoc):

- The mutations transactional ack-path decorator, which detects `TransactionalSourceOfTruth`,
  does not engage over this adapter. Applications combining `mutations` with a file SoT get the
  same non-transactional posture as any non-transactional SoT.
- A future journal-based transactional variant is a named deferral (§9), not a v1 promise.

### D6 — On-disk key mapping: base32 names, two-level layout, hard length limit, no hashing

```
<directory>/
  values/
    <enc(namespace.value)>/
      <enc(key.canonicalId())>        one value file per canonical key
  values-tmp/                         staging for atomic renames (same volume by construction)
  values-trash/                       renamed-away subtrees awaiting best-effort purge
```

`enc(s)` = RFC 4648 base32 of the UTF-8 bytes of `s`, lowercase alphabet (`a–z`, `2–7`), no
padding, with one carve-out: **the empty string encodes to `"0"`**. `StoreKey` does not forbid
empty `namespace.value` or `canonicalId()`, and base32 of `""` is `""`, which would collapse an
empty canonical id onto its namespace directory and an empty namespace onto `values/` itself
(making `deleteNamespace(StoreNamespace(""))` destroy every namespace). `0` is outside the base32
alphabet, so the sentinel cannot collide with any non-empty encoding. Implemented in the module
(internal, test-vectored); kotlinx-io provides no codec and no dependency is added for one.

Why base32 and not base64url or percent-escaping: APFS (macOS default) and NTFS (Windows default)
are case-insensitive. Base64url is case-sensitive, so two distinct canonical ids could collide on
one file. Base32 lowercase is case-stable, filesystem-safe on every supported platform, and
reversible (useful for debugging; reversibility is not load-bearing — no code path decodes names).
The `.corrupt` quarantine suffix (D7) contains `.`, which is outside the alphabet, so quarantine
names cannot collide with value names either.

**Length limit.** Name-component limits are 255 bytes on ext4 and APFS and 255 UTF-16 code units
on NTFS; encoded names here are ASCII, so one 255-character budget satisfies both. Base32 expands
by 8/5, so the limit is: UTF-8 byte length of `namespace.value` and of `canonicalId()` each ≤ 159
bytes (`ceil(159 × 8 / 5) = 255`). Violations throw `IllegalArgumentException` naming the
offending part, the limit, and the actual length, from the mutation or reader call. Throwing
satisfies the mutation contract ("throwing means it was not applied"). The limit and the exception
are documented API. Hash-based name compression was rejected: it would force a hash dependency or
a hand-rolled SHA-256, and keys of this size indicate a modeling problem a cache should surface,
not absorb (the `key-design` doc already steers canonical ids toward short stable forms).

Windows `MAX_PATH` (260 chars) can still be exceeded by a deep `directory` plus two encoded
components; the README instructs Windows consumers to mount shallow roots. Not detected in v1.

**Absent-path rule (uniform, both components):** a path that does not exist is absence, never an
error and never corruption. Reading a missing file (or a file whose `values/<ns>/` parent was
never created) emits `null`. Deleting a missing file, namespace directory, or `values/` root is a
successful no-op that still publishes its notification. The bookkeeper recovery scan treats a
missing `records/` directory or missing watermarks file as empty state.

### D7 — Value file format: versioned envelope with CRC32

Byte layout (all integers big-endian, written/read with `kotlinx.io.Buffer`):

| Offset | Size | Field | Value |
|---|---|---|---|
| 0 | 4 | magic | ASCII `S6FV` |
| 4 | 1 | format version | `0x01` |
| 5 | 8 | payload length | byte count of payload |
| 13 | 4 | CRC32 of payload | IEEE 802.3 polynomial, implemented internally (test-vectored) |
| 17 | n | payload | `FileCodec.encode` output |

A file is **structurally corrupt** when: shorter than 17 bytes, wrong magic, unknown version,
actual payload byte count ≠ length field, or CRC mismatch. Structural checks run under the
instance mutex, on the same bytes just read, so a structural-corruption verdict and its
quarantine act on a consistent snapshot.

`FileCodec.decode` runs **outside** the mutex (D9). Its failure handling:

1. A `CancellationException` from decode always rethrows — "Collection cancellation propagates"
   — and never mutates the filesystem.
2. Under `PROPAGATE`, any other decode throw propagates out of the reading operation.
3. Under `QUARANTINE`, the adapter re-acquires the mutex, re-reads the canonical file, and
   quarantines **only if the bytes equal the snapshot that failed to decode** (a concurrent
   mutation may have replaced the file with a valid row; quarantining that would destroy live
   data). If the bytes differ, the read attempt **retries with the new snapshot inside the same
   emission**: the reader's per-signal read is a loop of (read + structural checks under the
   mutex → decode outside), repeating on decode-failure-with-changed-bytes until a decode
   succeeds, a stable snapshot is quarantined or propagated, or `CancellationException` exits.
   Each retry consumes a strictly newer snapshot, so the loop only continues while concurrent
   mutations keep landing — deferral the conflation clause already permits. The equal/differ
   decision is a pure function of the two byte snapshots, unit-testable in isolation.

Quarantine renames the file to `<name>.corrupt` beside the original (best-effort; on rename
failure it falls back to best-effort delete) and reports absence. `.corrupt` files are never
re-read; they are removed by best-effort cleanup when their key is next written or deleted and
disappear with their directory on namespace/all deletion.

Why the envelope exists: rename gives atomic *visibility*, not durability. Without fsync (D11), a
power loss shortly after rename can leave a correctly-named file with truncated or zero-filled
content on journaled-metadata filesystems. The CRC turns that case into detected corruption →
absence → refetch, which is the conservative direction ("prefer doing work twice over losing it"
is the stated crash posture in STABILITY.md §8b). CRC32 detection is probabilistic (a corrupt
payload passes with probability ≈ 2⁻³²); D11 states what it cannot promise.

### D8 — Reactivity: instance-scoped per-key version signals, re-read on bump

The mechanism is the `sqldelight` adapter's, minus SQL:

- A CAS-maintained map `(namespace.value, canonicalId) → MutableStateFlow<Long>` holds one
  version signal per key that has at least one active reader, with reference-counted
  acquire/release (copied from `SqlDelightSourceOfTruth.acquireReader`/`releaseReader`).
- `reader(key)` = acquire signal → `emitAll(signal.map { readCurrentRow(key) })` → release in
  `finally`. The `StateFlow` initial value drives the first emission (current row or `null`);
  every successful mutation bumps matching signals before returning, which forces a re-read.
- Contract fit, clause by clause:
  - *first emission is current row*: initial `StateFlow` value triggers an immediate disk read.
  - *emits every subsequent change, equal-value rewrites included*: every mutation bumps; `map`
    performs no equality suppression. Conflation of rapid bumps is explicitly permitted
    ("Emissions may be conflated").
  - *never completes normally*: `StateFlow` never completes.
  - *read-your-writes / notification-before-return*: the bump is a synchronous
    `MutableStateFlow.update` executed before the mutation returns; queued downstream processing
    is exactly what the contract permits ("may still be queued in downstream operators"). A
    re-read that observes a later mutation's state is a conflation of the two notifications,
    which the same clauses permit ("a notification that supersedes a successfully returned
    mutation is ordered after the return boundary").
  - *engine retry after reader failure*: a throw from the disk read or decode (PROPAGATE policy)
    propagates out of the flow; each new collection re-reads current state.
- Signals are **instance-scoped**, like `sqldelight`: "changes made through another adapter
  instance are not bridged into an already-active reader. Those external changes are read from the
  database by the first emission of every new collection" — the same sentence holds here with
  "database" replaced by "directory".

### D9 — Concurrency model: one instance-wide mutex, `ioContext` dispatch, cancellation shield

- **One `kotlinx.coroutines.sync.Mutex` per instance serializes every filesystem touch on that
  instance's subtrees** (reads, writes, deletes, purges). What the mutex is for: (a) mutations
  are multi-step sequences (ensure directories → write tmp → rename → notify) that must not
  interleave; (b) on Windows, a rename over a concurrently-open file can fail with a sharing
  violation, so reads and renames on the same subtree must not race. `sqldelight`'s single
  `DriverAccess` gate is the shape precedent. Per-key striping is a named non-goal until a real
  workload shows contention (§9).
- **Cross-instance concurrency**: kotlinx-io documents `FileSystem` implementations as not
  thread-safe in general. All five shipped `SystemFileSystem` implementation families at the
  pinned 0.9.1 were audited for this design — JVM (stateless object over `java.io`/`java.nio`
  statics; the one retained field is the immutable lazily-initialized NIO mover), Unix native and
  Apple (per-call POSIX `rename(2)`/`mkdir`/`realpath`; Apple metadata via a per-call
  `NSFileManager.defaultManager()`), MinGW (per-call Win32/POSIX calls), and the shared Node
  implementation for js/wasmJs (per-call `fs.*Sync`) — none holds mutable state across calls.
  Under the D13 ownership rule, distinct instances touch disjoint subtrees, so cross-instance
  calls are concurrent platform-API calls on distinct paths. **The audit is valid for the pinned
  version only**: re-run it on any kotlinx-io bump (a named dependency-update step), and if a
  future version adds shared state, the fallback is a companion-object striped gate shared by all
  instances (the `RoomDatabaseAdmissionCoordinator` shape) — a one-file change.
- **Payload work stays outside the mutex.** `encode` runs before acquisition (into a `Buffer`);
  `decode` runs after release (from the byte snapshot read under the mutex; structural checks
  happen under the mutex per D7). User codec time never blocks other keys; only raw file transfer
  holds the lock.
- **All file IO runs on `ioContext`** (stripped of any `Job`, D4) via `withContext`.
- **Cancellation shield shape (load-bearing).** `withContext(NonCancellable + ioContext)` is a
  known kotlinx.coroutines hazard: when the context changes dispatchers, the dispatched resume
  back to a cancelled caller checks the caller's job and can throw `CancellationException` after
  the block completed — turning an applied mutation into a boundary throw. The `room` adapter
  never combines the two; neither does this design. The required nesting is:

  ```kotlin
  mutex.withLock {
      withContext(NonCancellable) {          // same dispatcher: undispatched resume, no job check
          withContext(io) { /* file ops */ } // resumes into the NonCancellable scope, not the caller
          bumpSignals()                      // inside the shield: applied implies announced
      }
  }
  ```

  Cancellation observed before the mutex admits the operation cancels cleanly (nothing applied);
  after admission the operation runs to completion, returns normally, and the caller observes its
  cancellation at its own next suspension point — the `room` posture ("caller cancellation before
  admission remains cancellable; after admission it cannot turn a durable commit into a boundary
  throw").
- **Mutation pipeline** (write shown; deletes are the same shape):
  1. Validate key lengths (D6); encode into a `Buffer`; wrap with envelope header. Cancellable —
     cancellation or codec failure here means nothing was applied.
  2. Admission and shielded apply, exactly the nesting above: ensure directories exist, write to
     `values-tmp/<unique>`, `atomicReplace` into place, bump matching signals.
  3. On any throw before the rename completes: best-effort delete of the temp file; the canonical
     path is untouched — "throwing means it was not applied" holds exactly.
- **`atomicReplace` (internal expect/actual).** Every rename in both components goes through one
  internal function. On jvm, native, js, and wasmJs the actual is `SystemFileSystem.atomicMove`
  (itself POSIX `rename(2)` on Unix/Apple, `MoveFileEx` with replace on Windows, `fs.renameSync`
  on Node — verified in kotlinx-io 0.9.1 sources). On android the actual calls
  `SystemFileSystem.atomicMove` and, when it throws the API 24–25 `UnsupportedOperationException`
  (D1 fact 1), falls back to `android.system.Os.rename` — available since API 21 and documented
  as the `rename(2)` binding, so the atomic-replace contract is the platform's, not an
  implementation accident (`java.io.File.renameTo` was rejected here: its contract explicitly
  permits non-atomic behavior and failure when the destination exists) — translating
  `ErrnoException` to `IOException`. The fallback branch is a separately-testable internal
  function; CI has no Android-device lane (the repository posture for every module), so the
  branch is proven by direct unit test, not emulator, and only executes below API 26.
- **Internal test gates.** Deterministic interleaving tests (pre-admission vs. post-admission
  cancellation, storage-failure absorption) need controlled suspension and fault points. The
  precedent is core's own `InMemoryBookkeeper(beforeMaintenancePublishTestGate: () -> Unit = {})`
  internal constructor parameter. Both classes here follow it: the public D4 constructor delegates
  to an `internal` constructor carrying no-op-defaulted gates (before-admission, after-admission,
  before-disk-write). Internal declarations do not appear in BCV dumps, so the public surface is
  unchanged; same-module `commonTest` reaches them directly. A gate that throws inside the
  bookkeeper's persistence call is also the storage-failure injector for the absorption boundary
  (D12).
- **TD-8 compliance**: the implementation uses `Mutex` and `StateFlow` only — no `runBlocking`,
  `GlobalScope`, `atomicfu`, `Channel`, or `actor` — the banned-primitive regex in the "Enforce
  the TD-8 primitive whitelist and single-writer residence" step runs against `file/src/*Main`
  once the module is registered there.

### D10 — `deleteNamespace` / `deleteAll`: atomic trash-rename, then best-effort purge

Recursive file-by-file deletion cannot satisfy "throwing means it was not applied" — a mid-loop
failure leaves a partial delete. Instead:

- `delete(key)`: `delete(path, mustExist = false)` on the canonical file is the operation whose
  outcome defines the mutation. Cleanup of a stale `.corrupt` sibling follows only after success
  and is best-effort (absorbed failures) — it must not turn an applied deletion into a throw.
- `deleteNamespace(ns)`: if `values/<enc(ns)>/` exists, `atomicReplace` it to
  `values-trash/<unique>/` — one atomic operation that makes the whole namespace logically
  absent. Trash entry names are unique tokens only (monotonic counter + random component), never
  derived from the namespace: an encoded namespace can already be 255 characters, so any suffix
  would exceed the component limit. Then, still under the shield: bump every signal in the
  namespace, and purge the trashed subtree best-effort (failures ignored; leftovers are re-purged
  by the next instance's first-operation sweep, D4). If the rename itself throws, nothing changed
  — exception-atomic. If the directory does not exist, the operation only bumps signals (active
  readers of absent rows observe a `null` re-emission, which the contract permits).
- `deleteAll()`: same shape with `values/` itself as the renamed directory, bumping all signals.
  `values/` is recreated lazily by the next write; a second `deleteAll` (or one on a fresh
  directory) finds no `values/` and is a bump-only success per the D6 absent-path rule.
- The first-operation sweep (D4) makes `values-tmp/` and `values-trash/` exist before any rename
  targets them and clears leftovers from crashed predecessors, so crashed instances leak only
  unreferenced bytes until the next sweep succeeds.

Rejected alternative: logical-delete epochs in a control file (single-file atomicity, lazy
physical cleanup). It reaches the same guarantee but adds a control file, an epoch stamp in every
value envelope, and epoch checks on every read; trash-rename gets the guarantee from one primitive
the filesystem already has.

### D11 — Durability posture (stated, not rounded up)

kotlinx-io (and Okio) expose no fsync. v1 therefore guarantees, and documents, exactly:

- **Atomic visibility**: readers observe either the previous or the new complete file, never a
  mix, on every supported platform (POSIX rename; `MoveFileEx`-based replace on Windows;
  `rename(2)` via the Android fallback).
- **Process-crash durability**: a mutation that returned before a process crash is visible after
  restart (the rename entered the OS before return).
- **No power-loss durability**: after an OS crash or power loss, a returned mutation may be
  **undone** — the previous complete row (or absence) reappears, because the rename itself was
  not yet durable — or the new file may exist with damaged content. Damaged content is detected
  by the envelope checks (probabilistically, per D7) and handled as corruption; an undone rename
  is undetectable by construction and surfaces as the earlier committed state. The README states
  both outcomes plainly: after power loss this adapter can lose recent mutations; detected
  corruption yields absence and a refetch.

An opt-in `SYNC` durability mode (per-platform fsync via expect/actual) is a named deferral
(§9), not silently absent: the README's durability section links it.

### D12 — `FileBookkeeper`: in-memory mirror with write-through records and a watermark control file

Semantics are `core`'s `InMemoryBookkeeper`, made durable. That class is the semantic reference:
one store-local monotone sequence shared by successes, per-key stale marks, namespace watermarks,
and the global watermark; durable staleness is exactly `max(mark/ns/global) > (success ?: 0)`;
sequence exhaustion at `Long.MAX_VALUE` fails before mutation (an invariant failure, distinct
from a storage failure, and not absorbed).

```
<directory>/
  bookkeeping/
    records/<enc(ns)>/<enc(id)>       one record file per canonical key
    watermarks                        one control file
  bookkeeping-tmp/                    staging for atomic rewrites
  bookkeeping-trash/                  trash-rename target for forgetNamespace/forgetAll
```

- **Record file** — `S6FB` envelope: the D7 header with magic `S6FB`, whose payload is:

  | Field | Size | Presence |
  |---|---|---|
  | flags | 1 | always; bit 0 = has meta, bit 1 = has etag, bit 2 = has success sequence, bit 3 = has failure timestamp, bit 4 = has stale sequence; bits 5–7 zero |
  | meta.writtenAtEpochMillis | 8 | when bit 0 |
  | meta.etag byte length + UTF-8 bytes | 4 + n | when bit 0 and bit 1 (bit 1 without bit 0 is invalid) |
  | lastSuccessSequence | 8 | when bit 2 |
  | lastFailureAtEpochMillis | 8 | when bit 3 |
  | consecutiveFailures | 4 | always |
  | staleSequence | 8 | when bit 4 |

  Absent fields are omitted, not zeroed. The field set is exactly `InMemoryBookkeeper.Record`.
- **Watermarks control file** — `S6FW` envelope whose payload is: `globalStaleWatermark` (8), a
  namespace count (4), then per namespace: name byte length (4) + UTF-8 name bytes + watermark
  (8). Rewritten atomically (tmp + `atomicReplace`) on every watermark advance; size is bounded
  by the number of distinct namespaces.
- **In-memory mirror**: on first operation, one recovery scan lists `records/` and reads every
  record plus the control file into memory (missing paths are empty state, D6). All reads
  (`status`) are then memory-only. The monotone sequence is recovered as the maximum over every
  persisted `lastSuccessSequence`, `staleSequence`, and watermark — allocation can therefore never
  reuse a value that any surviving persisted artifact carries.
- **Infallible operations** (`recordSuccess`, `recordFailure`, `forget`): allocate the sequence
  and update the mirror first (invariant failures like sequence exhaustion throw here, before any
  absorption boundary), encode the record payload outside any catch (an encoding defect is a bug
  and must surface), then hand the finished bytes to one persistence call — write tmp,
  `atomicReplace` — and absorb any non-cancellation exception **that call** throws:
  `kotlinx.io.IOException` is the common case, `UnsupportedOperationException` and platform
  surprises are covered by the same boundary, because the seam says these operations "absorb or
  report their own storage failures and do not throw them through this interface". The absorption
  boundary is exactly the disk call, so it cannot swallow programming defects elsewhere.
  `CancellationException` always propagates. **Divergence consequence, stated accurately**: while
  storage is failing, the mirror and disk diverge; process-local answers stay correct, and a
  restart resumes from the last durably written record for each key — which may be older than
  what the mirror reported, or absent. It is not guaranteed to look never-fetched.
- **Fallible maintenance operations** (`markStale`, `advanceStaleWatermark`,
  `advanceGlobalStaleWatermark`, `forgetNamespace`, `forgetAll`): persist first, then update the
  mirror. Single-file atomic writes for marks and watermark advances; trash-rename (D10
  mechanism, unique-token names) for `forgetNamespace`/`forgetAll`, with absent directories
  treated as already-empty (mirror-only success). A throw leaves both disk and mirror unchanged —
  exception-atomic as the seam requires. Forgets never touch the watermarks file (watermarks
  "never reset").
- **Corruption during recovery**: a corrupt record file is quarantined and skipped — the key then
  has no per-key record, so `status()` returns null unless a covering watermark exists, in which
  case it reports the watermark-only durably-stale shape (the `watermarkOnlyKey_reportsDurablyStale`
  contract). A corrupt watermarks file is replaced by a control file carrying
  `globalStaleWatermark = recoveredMax + 1`, where `recoveredMax` is the maximum sequence over all
  surviving records (0 when none) — the `+ 1` matters because the staleness comparison is strict
  (`>`), so a watermark equal to a surviving success would leave that key fresh. **Replacement
  ordering is crash-safe by construction**: first the exhaustion check
  (`recoveredMax = Long.MAX_VALUE` fails recovery with the invariant error before any mutation),
  then a best-effort diagnostic copy of the corrupt bytes to a `.corrupt` sibling, then the
  replacement is written to tmp and `atomicReplace`d **directly onto the canonical watermarks
  path** — one atomic swap, so no crash window ever exposes a missing watermarks file (a missing
  file would read as empty state and silently forget invalidations, the exact failure this rule
  exists to prevent). The live sequence advances to the same value. Every surviving success is
  then outranked, forcing refetch rather than silently forgetting invalidations. Both behaviors
  are documented.
- Concurrency and cancellation follow D9 (own mutex, own `ioContext` dispatch, the same
  `NonCancellable`-then-dispatch nesting for disk writes, `atomicReplace` for every rename).

### D13 — Ownership: one live instance per directory

Concurrent instances (same or different process) over one directory are unsupported and
documented as such: signal maps are per-instance (readers of instance A do not observe instance
B's writes until a new collection — contract-permitted), and concurrent trash purges or watermark
rewrites lose updates. One `FileSourceOfTruth` plus one `FileBookkeeper` on the same directory is
supported — their subtrees are disjoint (D4). No lock-file detection in v1: kotlinx-io offers no
portable file locking, and marker files lie after crashes. The README states the rule; a detection
mechanism is not planned.

### D14 — Built-in codecs stop at bytes and strings

`ByteArrayFileCodec` and `Utf8StringFileCodec` cover blob and text values with zero dependencies.
The README shows the kotlinx-serialization recipe:

```kotlin
class JsonFileCodec<V : Any>(private val serializer: KSerializer<V>) : FileCodec<V> {
    override fun encode(value: V, sink: Sink) =
        sink.writeString(Json.encodeToString(serializer, value))
    override fun decode(source: Source): V =
        Json.decodeFromString(serializer, source.readString())
}
```

A `file-serialization` artifact is not created: it would add a dependency edge for one class users
can paste.

### D15 — Repository integration: zero core diff, deliberate documentation scope

Following the `realtime` and `graphql` extension-PR precedent:

- No `core/`, `testing/`, or seam changes; `core` and `testing` BCV dumps must show zero diff.
- `STABILITY.md` §3 gains no row in the implementation PR (its listed files are docs-site-pinned
  behind the docs-sync guard); the artifact-table row is a release-process follow-up, as it was
  for `realtime` and `graphql`.
- The module README is created but **not** added to `.github/docs-sync-sources.txt` (that list
  pins already-published pages).
- `CHANGELOG.md` remains untouched (it has no Store 6 section yet; release notes are cut at
  release time per `RELEASING.md`).

## 4. Contract-to-mechanism map

Every clause of the `SourceOfTruth` KDoc, and the mechanism that discharges it:

| Contract clause | Mechanism |
|---|---|
| First emission = current row or `null` | Initial `StateFlow` value → disk read at collection start (D8); absent paths are absence (D6) |
| Active collection sees every change through this instance, equal-value rewrites included | Post-mutation signal bump; no equality suppression (D8) |
| Emissions may be conflated | `StateFlow` conflation, explicitly relied on |
| Reader never completes normally; failures retried by engine; cancellation propagates | `StateFlow`-driven flow never completes; PROPAGATE throws; `CancellationException` always rethrown (D7) |
| Read-your-writes on normal return; notification published before return | Bump inside the cancellation shield, before return (D9) |
| Mutations exception-atomic for every `Throwable` incl. cancellation | Temp-file + `atomicReplace`; the D9 shield nesting; pre-admission throws touch nothing (D9, D10) |
| `deleteNamespace`/`deleteAll` apply fully or not at all | Single trash-rename or bump-only no-op (D10) |
| External changes appear in a new collection's first emission | Every collection start reads the directory (D8) |
| Keys identified by `(namespace.value, canonicalId())` | Encoding of exactly those two strings, empty-string sentinel included (D6) |

`Bookkeeper` clauses map in D12 (identity normalization, shared sequence, watermark algebra,
infallible vs. fallible operations, forget-never-resets-watermarks).

## 5. Testing strategy

1. **Contract kits** (the non-negotiable core):
   `FileSourceOfTruthContractTest : SourceOfTruthContractKit<TestKey, String>` (using
   `Utf8StringFileCodec`, fresh temp directory per `createSourceOfTruth()`), and
   `FileBookkeeperContractTest : BookkeeperContractKit`. Both in `commonTest`, executing on every
   compiled target — this is what "run the contract kit throughout the alpha line" means for this
   adapter. Temporary directories come from `kotlinx.io.files.SystemTemporaryDirectory` plus a
   random component per test.
2. **Durability and restart tests** (beyond the kits, `commonTest`): write → new instance over the
   same directory → first emission is the written value; bookkeeper success/watermark →
   new instance → `status` preserved, staleness algebra intact, sequence monotone across reopen
   (watermark advanced pre-restart still outranks a pre-restart success after restart).
3. **Corruption tests**: truncated file, bad magic, bad CRC, decode throw × {QUARANTINE,
   PROPAGATE}; quarantine re-check keeps a concurrently-replaced valid file (pure-function test
   on the two-snapshot decision per D7); quarantined file is not re-read; `CancellationException`
   from decode propagates and quarantines nothing; corrupt watermarks file → global watermark =
   recoveredMax + 1 and every surviving success reports durably stale; corrupt record + intact
   covering watermark → watermark-only durably-stale status after restart.
4. **Envelope/encoding unit tests**: base32 vectors (RFC 4648 test vectors, lowercase), the
   empty-string sentinel properties (`enc("") == "0"`; no non-empty input can encode to `"0"`
   because `0` is outside the alphabet), CRC32 vectors (IEEE check value `0xCBF43926` for
   `"123456789"`), envelope round-trip, record/watermark payload round-trips per the D12 tables,
   length-limit rejection at 160 bytes and acceptance at 159.
5. **Degenerate-shape tests**: empty `canonicalId`, empty `namespace.value`, both empty — write,
   read, delete, deleteNamespace isolation; `deleteAll` twice in a row; `deleteNamespace` of a
   never-written namespace; first operation on a completely fresh directory
   (`readerFirstEmissionIsNullWhenAbsent` covers the reader case; the mutation cases need their
   own).
6. **Cancellation tests**: cancellation before admission applies nothing; a mutation admitted
   under a cancelled caller still completes, notifies, and returns normally (the D9 shield).
   Deterministic interleaving comes from the D9 internal test gates, not timing.
7. **Android fallback test**: the `Os.rename`-based branch of the android `atomicReplace` actual,
   exercised directly (host unit tests always have NIO, so the branch is called explicitly).
8. **Cross-instance visibility test**: write via instance A; an already-active reader on instance
   B does not re-emit; a new collection on B starts with A's value.
9. **Platform lanes**: `:file:build` on Linux CI covers jvm, android unit, linuxX64, js/Node, and
   wasmJs/Node lanes; `:file:iosSimulatorArm64Test` and `:file:macosArm64Test` in the
   `apple-tests` job; `:file:jsNodeTest` joins the JS lock-discipline canary. These are the first
   real-filesystem contract-kit runs on the js/wasmJs lanes in this repository (`room` has no web
   tests; `sqldelight`'s SQL test set is jvm/android/native), which is why D3 carries an explicit
   fallback gate. kotlinx-io's Node filesystem calls are synchronous, so on js/wasmJs each
   operation briefly blocks the event loop; documented in the README. mingwX64 compiles (klib
   cross-compilation) but has no CI test runner today — the Windows rename-semantics risk is
   therefore mitigated in-design (single mutex excludes open-file races; `atomicReplace` is the
   only replace primitive used) and stated in the README, not CI-proven. This matches the
   repository posture for every other module (none runs mingwX64 tests).

## 6. CI and build integration (exact anchors)

All in `.github/workflows/store6.yml` unless noted:

| Anchor | Change |
|---|---|
| `settings.gradle` | `include ':file'`; `include ':file-sample'` + `projectDir = file('file/sample')` |
| `gradle/libs.versions.toml` | `kotlinxIo = "0.9.1"`; `kotlinx-io-core` library entry (exact shapes in D2) |
| Module scaffold | `file/build.gradle.kts` (full convention plugin, D2 dependencies), `file/gradle.properties`, `file/src/androidMain/AndroidManifest.xml` (`<manifest />`) |
| `linux-build-test` job | New steps "Build Store6 file adapter" (`:file:build` with the two `-P` flags every module build passes) and "Run Store6 file sample" (`:file-sample:run`) |
| "Reject core-internal access from extension modules" | Add `file` and `file/sample` to the module list |
| "Enforce the TD-8 primitive whitelist and single-writer residence" | Add `file/src/*Main` to `production_source_dirs` |
| "JS lock-discipline canary (full conformance suite on the JS lane)" | Add `:file:jsNodeTest` |
| `apple-tests` job | Add `:file:iosSimulatorArm64Test`, `:file:macosArm64Test` |
| `klib-publication-check` job | Add `:file:publishToMavenLocal` to the publish command and `file` to `modules=(...)`; no `case` exception if D3 holds at 12 targets (the subset fallback adds one) |
| BCV | `./gradlew :file:apiDump` output committed: `file/api/jvm/file.api`, `file/api/android/file.api`, `file/api/file.klib.api` |
| Untouched | `swift-dumps`, `swift-facade`, `native-stress`, seam freeze list, `.github/docs-sync-sources.txt` |

## 7. Sample and README

- `file/sample`: JVM `application` module (the `room/sample` shape) demonstrating: a store over
  `FileSourceOfTruth` + `FileBookkeeper`; first fetch persists; a rebuilt store over the same
  directory serves without refetch (durable metadata); `invalidateNamespace` survives a rebuild
  and forces refetch. Exits nonzero when a check fails, so `:file-sample:run` is a CI assertion,
  not a demo.
- `file/README.md` follows the adapter README shape (`room`, `realtime` precedents): purpose
  sentence; experimental tier + freeze-candidate pointer to STABILITY.md; install snippet;
  entry-point walkthrough; target list; **a "Semantics and limits" section that states, plainly:
  the durability posture including power-loss undo (D11), the single-instance rule (D13), the
  key-length limit and empty-string handling (D6), corruption handling (D7), the missing
  `TransactionalSourceOfTruth` and its mutations consequence (D5), the Android API 24–25 rename
  fallback (D9), the mingwX64 ANSI-path limitation (D1), and event-loop blocking on js/wasmJs
  (§5)**; the kotlinx-serialization codec recipe (D14); contract-kit testing instructions; sample
  command.

## 8. Risks

| Risk | Likelihood | Contained by |
|---|---|---|
| kotlinx-io filesystem gaps on `wasmJs`/`js` Node lanes | Medium | D3 fallback gate: subset plugin, `room` precedent; decided by test evidence during implementation |
| kotlinx-io 0.x API break on version bump | Medium over the artifact's life | Pinned catalog version; experimental tier; public-surface blast radius limited to `Path`/`Source`/`Sink` (D1) |
| Android API 24–25 `atomicMove` unsupported in kotlinx-io | Certain (verified in source) | D9 `atomicReplace` android fallback via `android.system.Os.rename`; branch unit-tested; README statement |
| mingwX64 ANSI Win32 APIs mis-encode non-ASCII roots | Certain for affected paths (verified in source) | Adapter names are ASCII; README constrains roots; an upstream kotlinx-io report is a recorded PR action item (maintainer-filed) |
| Windows rename-over-open-file semantics | Low (mutex excludes in-instance races; cross-instance is unsupported per D13) | D9 single mutex; README statement; no mingwX64 CI runner exists to prove more |
| Power-loss undo or torn writes without fsync | Real but bounded | D7 CRC → detected absence → refetch; D11 documents undo of recent mutations; SYNC mode deferred |
| `runTest` + real IO flakiness (virtual time vs. real dispatchers) | Low | The kits drive real Room/SQLDelight IO under `runTest` on jvm/native lanes today; js/wasmJs are new ground and carry the D3 gate |
| Filename length or `MAX_PATH` surprises | Low | D6 hard limit with a named exception; README guidance for Windows roots |
| Trash/tmp accumulation after repeated crashes | Low | Best-effort sweep on first operation of every instance |

## 9. Deferred work (named, not implied)

1. `SYNC` durability mode (per-platform fsync via expect/actual behind a constructor parameter).
2. Journal-based `TransactionalSourceOfTruth` variant (would also unlock the mutations ack-path
   decorator).
3. Streaming payloads end-to-end (codec API already permits it without a break).
4. Per-key lock striping if a real workload shows mutex contention.
5. `STABILITY.md` §3 artifact row + docs-site page via the docs-sync process.
6. Size/TTL-based eviction utilities (an engine/maintenance concern).

## 10. Alternatives considered and rejected

- **Okio as the IO library** — full analysis in D1; loses `wasmJs`, gains stability and fakes.
- **Hash-based filenames (SHA-256)** — unbounded key length support at the cost of a hash
  implementation and opaque directories; rejected for D6's limit-plus-clear-error.
- **Logical-delete epochs instead of trash-rename** — same atomicity, more moving parts (D10).
- **A single ledger file for bookkeeping** — O(all keys) rewrite per `recordSuccess`; rejected
  for per-key record files + bounded control file (D12).
- **Sharing one sidecar between SoT and Bookkeeper (`sqldelight` shape)** — couples two components
  the seam keeps separate; without a transaction manager the shared-sidecar atomicity argument
  that motivates `sqldelight`'s design does not exist here (D4, D12).
- **A `FileSystem` constructor parameter** — kotlinx-io's `FileSystem` is sealed; the parameter
  would admit exactly one value (`SystemFileSystem`) while promising pluggability the type system
  forbids. Reconsider when kotlinx-io opens the interface.
- **Raising the artifact's Android floor to API 26** — would dodge the `atomicMove` gap by
  excluding devices instead of handling them; rejected for the one-function fallback (D9), which
  keeps the repository-wide `minSdk = 24`.
