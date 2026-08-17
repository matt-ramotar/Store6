# File source of truth — technical design

Status: proposed. Target artifact: `org.mobilenativefoundation.store:file` (experimental tier).

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
| Store6 12-target coverage | All 12, including filesystem access on `js` and `wasmJs` under Node.js (shared Node filesystem implementation) and on every native target | No `wasmJs` filesystem. Node filesystem is exposed for Kotlin/JS only; Wasm support is WASI-only (`okio-wasifilesystem`), which is not the `wasmJs` target Store6 ships |
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

**Accepted risk:** the kotlinx-io filesystem API is pre-1.0 and can break. Containment: the
adapter is an experimental-tier artifact (breaking dependency bumps are permitted by
[STABILITY.md](../../STABILITY.md) §2), the version is pinned in the catalog, and the leaked
kotlinx-io types in our public API are limited to `kotlinx.io.files.Path` (constructors) and
`kotlinx.io.Source`/`kotlinx.io.Sink` (codec). This mirrors how `room` and `sqldelight` expose
`RoomDatabase` and `SqlDriver`. If kotlinx-io's instability proves disruptive before this artifact
is considered for graduation, the graduation review re-runs this decision; Okio is the named
fallback and the public surface above is the full blast radius.

### D2 — Module identity

- Gradle path `:file`, directory `file/`, sample `:file-sample` at `file/sample`
  (matching the `include`/`projectDir` pattern in `settings.gradle`).
- Maven artifact `org.mobilenativefoundation.store:file`, `VERSION_NAME=6.0.0-SNAPSHOT`,
  `POM_NAME=file`, `POM_ARTIFACT_ID=file` (per-module `gradle.properties`, matching `room`).
- Package `org.mobilenativefoundation.store6.file`; Android namespace the same string.
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

```kotlin
package org.mobilenativefoundation.store6.file

@ExperimentalStoreApi
public class FileSourceOfTruth<K : StoreKey, V : Any>(
    directory: Path,
    codec: FileCodec<V>,
    corruptionPolicy: FileCorruptionPolicy = FileCorruptionPolicy.QUARANTINE,
    ioContext: CoroutineContext = Dispatchers.Default,
) : SourceOfTruth<K, V>

@ExperimentalStoreApi
public class FileBookkeeper(
    directory: Path,
    ioContext: CoroutineContext = Dispatchers.Default,
) : Bookkeeper

/** Encodes one value as one file payload. Implementations must be pure and deterministic per value. */
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
  `bookkeeping-tmp/`, and `bookkeeping-trash/`. No other layout coupling exists between the two
  classes; unlike `sqldelight`'s shared sidecar, they are independent components (the `room`
  precedent).
- `ioContext` follows the `sqldelight` adapter's `readContext: CoroutineContext =
  Dispatchers.Default` convention. `Dispatchers.IO` is not referencable from common code; JVM and
  native callers can pass it.
- `WallClock` is not a parameter: the SoT stores values only, and `Bookkeeper` receives
  timestamps through `recordSuccess(key, meta)`/`recordFailure(key, atEpochMillis)` arguments.
- Directories are created lazily (`createDirectories(..., mustCreate = false)`) on first use, so
  constructing the classes performs no IO and cannot throw for filesystem reasons.

### D5 — `SourceOfTruth` only; no `TransactionalSourceOfTruth`

`TransactionalSourceOfTruth` KDoc: "the engine never assumes it and there is deliberately no
silent non-atomic default." A plain filesystem offers per-file atomicity (rename) but no
multi-operation atomicity; an implementation of `withTransaction` that merely serializes
operations would be exactly the silent non-atomic default the seam refuses to provide.

Consequences, stated where users will read them (README + KDoc):

- The mutations transactional ack-path decorator, which detects `TransactionalSourceOfTruth`,
  does not engage over this adapter. Applications combining `mutations` with a file SoT get the
  same non-transactional posture as any non-transactional SoT.
- A future journal-based transactional variant is a named deferral (§12), not a v1 promise.

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
padding. Implemented in the module (internal, test-vectored); kotlinx-io provides no codec and no
dependency is added for one.

Why base32 and not base64url or percent-escaping: APFS (macOS default) and NTFS (Windows default)
are case-insensitive. Base64url is case-sensitive, so two distinct canonical ids could collide on
one file. Base32 lowercase is case-stable, filesystem-safe on every supported platform, and
reversible (useful for debugging; reversibility is not load-bearing — no code path decodes names).

**Length limit.** Common filesystems cap a single name component at 255 bytes. Base32 expands by
8/5, so the limit is: UTF-8 byte length of `namespace.value` and of `canonicalId()` each ≤ 159
bytes. Violations throw `IllegalArgumentException` naming the offending part, the limit, and the
actual length, from the mutation or reader call. Throwing satisfies the mutation contract
("throwing means it was not applied"). The limit and the exception are documented API. Hash-based
name compression was rejected: it would force a hash dependency or a hand-rolled SHA-256, and
keys of this size indicate a modeling problem a cache should surface, not absorb (the `key-design`
doc already steers canonical ids toward short stable forms).

Windows `MAX_PATH` (260 chars) can still be exceeded by a deep `directory` plus two encoded
components; the README instructs Windows consumers to mount shallow roots. Not detected in v1.

### D7 — Value file format: versioned envelope with CRC32

Byte layout (all integers big-endian, written/read with `kotlinx.io.Buffer`):

| Offset | Size | Field | Value |
|---|---|---|---|
| 0 | 4 | magic | ASCII `S6FV` |
| 4 | 1 | format version | `0x01` |
| 5 | 8 | payload length | byte count of payload |
| 13 | 4 | CRC32 of payload | IEEE 802.3 polynomial, implemented internally (test-vectored) |
| 17 | n | payload | `FileCodec.encode` output |

A file is **corrupt** when: shorter than 17 bytes, wrong magic, unknown version, actual payload
byte count ≠ length field, CRC mismatch, or `FileCodec.decode` throws. Corruption dispatches on
`FileCorruptionPolicy` (D4). Quarantine renames the file to `<name>.corrupt` beside the original
(best-effort; on rename failure it falls back to best-effort delete) and reports absence.
`.corrupt` files are never re-read and are removed when their namespace directory is deleted or
trashed.

Why the envelope exists: rename gives atomic *visibility*, not durability. Without fsync (D11), a
power loss shortly after rename can leave a correctly-named file with truncated or zero-filled
content on journaled-metadata filesystems. The CRC turns that into detected corruption → absence →
refetch, which is the conservative direction ("prefer doing work twice over losing it" is the
stated crash posture in STABILITY.md §8b).

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
    is exactly what the contract permits ("may still be queued in downstream operators").
  - *engine retry after reader failure*: a throw from the disk read or decode (PROPAGATE policy)
    propagates out of the flow; each new collection re-reads current state.
- Signals are **instance-scoped**, like `sqldelight`: "changes made through another adapter
  instance are not bridged into an already-active reader. Those external changes are read from the
  database by the first emission of every new collection" — the same sentence holds here with
  "database" replaced by "directory".

### D9 — Concurrency model: one instance-wide mutex, `ioContext` dispatch, cancellation shield

- **One `kotlinx.coroutines.sync.Mutex` per instance serializes every filesystem touch** (reads,
  writes, deletes, purges). Rationale: kotlinx-io documents `FileSystem` implementations as not
  thread-safe; and on Windows, a rename over a concurrently-open file can fail with a sharing
  violation, so excluding read/rename races inside the instance is a correctness measure, not
  just simplicity. `sqldelight`'s single `DriverAccess` gate is the precedent. Per-key striping is
  a named non-goal until a real workload shows contention (§12).
- **Payload work stays outside the mutex.** `encode` runs before acquisition (into a `Buffer`);
  `decode` and CRC verification run after release (from a `Buffer` read under the mutex). User
  codec time never blocks other keys; only raw file transfer holds the lock.
- **All file IO runs on `ioContext`** via `withContext`.
- **Mutation pipeline** (write shown; deletes are the same shape):
  1. Validate key lengths (D6); encode into a `Buffer`; wrap with envelope header. Cancellable —
     cancellation or codec failure here means nothing was applied.
  2. `mutex.withLock { withContext(NonCancellable + ioContext) { createDirectories; write to
     values-tmp/<unique>; atomicMove into place }; bump matching signals }`. After the lock is
     acquired the operation runs to completion under `NonCancellable`, so caller cancellation
     cannot strand a renamed-but-unannounced file; this is the `room` adapter's admission
     posture ("caller cancellation before admission remains cancellable; after admission it
     cannot turn a durable commit into a boundary throw").
  3. On any throw before the rename completes: best-effort delete of the temp file; the canonical
     path is untouched — "throwing means it was not applied" holds exactly.
- **TD-8 compliance**: the implementation uses `Mutex`, `StateFlow`, `SharedFlow`-free signal
  design, and no `runBlocking`, `GlobalScope`, `atomicfu`, `Channel`, or `actor` — the banned-
  primitive regex in the "Enforce the TD-8 primitive whitelist" step runs against `file/src/*Main`
  once the module is registered there.

### D10 — `deleteNamespace` / `deleteAll`: atomic trash-rename, then best-effort purge

Recursive file-by-file deletion cannot satisfy "throwing means it was not applied" — a mid-loop
failure leaves a partial delete. Instead:

- `delete(key)`: single-file `delete(path, mustExist = false)`; also removes a stale `.corrupt`
  sibling. Single filesystem operation; exception-atomic by construction.
- `deleteNamespace(ns)`: if `values/<enc(ns)>/` exists, `atomicMove` it to
  `values-trash/<enc(ns)>-<unique>/` — one atomic operation that makes the whole namespace
  logically absent. Then, still under the mutex: bump every signal in the namespace, and purge the
  trashed subtree best-effort (failures ignored; leftovers are re-purged on the next instance's
  first operation). If the rename itself throws, nothing changed — exception-atomic. If the
  directory does not exist, the operation only bumps signals (active readers of absent rows
  observe a `null` re-emission, which the contract permits).
- `deleteAll()`: `atomicMove` of `values/` itself to `values-trash/values-<unique>/`, bump all
  signals, best-effort purge. `values/` is lazily recreated by the next write.
- Instance startup (first operation) sweeps `values-tmp/` and `values-trash/` best-effort, so
  crashed instances leak no live data, only unreferenced bytes until the next sweep succeeds.

Rejected alternative: logical-delete epochs in a control file (single-file atomicity, lazy
physical cleanup). It reaches the same guarantee but adds a control file, an epoch stamp in every
value envelope, and epoch checks on every read; trash-rename gets the guarantee from one primitive
the filesystem already has.

### D11 — Durability posture (stated, not rounded up)

kotlinx-io (and Okio) expose no fsync. v1 therefore guarantees, and documents, exactly:

- **Atomic visibility**: readers observe either the previous or the new complete file, never a
  mix, on every supported platform (POSIX rename; `MoveFileEx`-based replace on Windows).
- **Process-crash durability**: a mutation that returned before a process crash is visible after
  restart (the rename entered the OS before return).
- **No power-loss durability**: after an OS crash or power loss, a recently returned mutation may
  be absent, or present-but-corrupt; the envelope CRC (D7) converts the corrupt case into
  detected absence. The README states this and names the consequence: Store refetches, it does not
  serve garbage.

An opt-in `SYNC` durability mode (per-platform fsync via expect/actual) is a named deferral
(§12), not silently absent: the README's durability section links it.

### D12 — `FileBookkeeper`: in-memory mirror with write-through records and a watermark control file

Semantics are `core`'s `InMemoryBookkeeper`, made durable. That class is the semantic reference:
one store-local monotone sequence shared by successes, per-key stale marks, namespace watermarks,
and the global watermark; durable staleness is exactly `max(mark/ns/global) > (success ?: 0)`;
sequence exhaustion at `Long.MAX_VALUE` fails before mutation.

```
<directory>/
  bookkeeping/
    records/<enc(ns)>/<enc(id)>       one record file per canonical key
    watermarks                        one control file
  bookkeeping-tmp/                    staging for atomic rewrites
  bookkeeping-trash/                  trash-rename target for forgetNamespace/forgetAll
```

- **Record file** (`S6FB` envelope, same header shape as D7): flags byte marking which fields are
  present, then `meta.writtenAtEpochMillis` (8), `meta.etag` (length-prefixed UTF-8, nullable),
  `lastSuccessSequence` (8, nullable), `lastFailureAtEpochMillis` (8, nullable),
  `consecutiveFailures` (4), `staleSequence` (8, nullable) — the exact field set of
  `InMemoryBookkeeper.Record`.
- **Watermarks control file** (`S6FW` envelope): `globalStaleWatermark` (8), then a count-prefixed
  list of `(namespace UTF-8, watermark)` pairs. Rewritten atomically (tmp + `atomicMove`) on every
  watermark advance; size is bounded by the number of distinct namespaces.
- **In-memory mirror**: on first operation, one recovery scan lists `records/` and reads every
  record plus the control file into memory. All reads (`status`) are then memory-only. The
  monotone sequence is recovered as the maximum over every persisted `lastSuccessSequence`,
  `staleSequence`, and watermark — allocation can therefore never reuse a value that any surviving
  persisted artifact carries.
- **Infallible operations** (`recordSuccess`, `recordFailure`, `forget`): update the mirror first
  (cannot fail), then write through to disk absorbing `kotlinx.io.IOException` (cancellation still
  propagates, as the seam KDoc permits). An absorbed failure degrades durability, never process-
  local correctness; after restart the key looks never-fetched, which is the conservative
  direction (refetch).
- **Fallible maintenance operations** (`markStale`, `advanceStaleWatermark`,
  `advanceGlobalStaleWatermark`, `forgetNamespace`, `forgetAll`): persist first, then update the
  mirror. Single-file atomic writes for marks and watermark advances; trash-rename (D10 mechanism)
  for `forgetNamespace`/`forgetAll`. A throw leaves both disk and mirror unchanged —
  exception-atomic as the seam requires. Forgets never touch the watermarks file (watermarks
  "never reset").
- **Corruption during recovery**: a corrupt record file is quarantined and skipped — that key
  reverts to never-fetched (conservative). A corrupt watermarks file is quarantined and replaced
  by `globalStaleWatermark = recovered sequence maximum` — every known key becomes durably stale,
  forcing refetch rather than silently forgetting invalidations. Both behaviors are documented.
- Concurrency and cancellation follow D9 (own mutex, own `ioContext` dispatch, `NonCancellable`
  after admission for disk writes).

### D13 — Ownership: one live instance per directory

Concurrent instances (same or different process) over one directory are unsupported and
documented as such: signal maps are per-instance (readers of instance A do not observe instance
B's writes until a new collection — contract-permitted), and concurrent trash purges or watermark
rewrites lose updates. No lock-file detection in v1: kotlinx-io offers no portable file locking,
and marker files lie after crashes. The README states the rule; a detection mechanism is not
planned.

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
| First emission = current row or `null` | Initial `StateFlow` value → disk read at collection start (D8) |
| Active collection sees every change through this instance, equal-value rewrites included | Post-mutation signal bump; no equality suppression (D8) |
| Emissions may be conflated | `StateFlow` conflation, explicitly relied on |
| Reader never completes normally; failures retried by engine; cancellation propagates | `StateFlow`-driven flow never completes; PROPAGATE throws; no catch of `CancellationException` |
| Read-your-writes on normal return; notification published before return | Bump under the mutation's mutex hold, before return (D9) |
| Mutations exception-atomic for every `Throwable` incl. cancellation | Temp-file + `atomicMove`; `NonCancellable` after admission; pre-admission throws touch nothing (D9, D10) |
| `deleteNamespace`/`deleteAll` apply fully or not at all | Single trash-rename (D10) |
| External changes appear in a new collection's first emission | Every collection start reads the directory (D8) |
| Keys identified by `(namespace.value, canonicalId())` | Encoding of exactly those two strings (D6) |

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
3. **Corruption tests**: truncated file, bad magic, bad CRC, decoding throw × {QUARANTINE,
   PROPAGATE}; quarantined file is not re-read; corrupt watermarks file forces global staleness.
4. **Envelope/encoding unit tests**: base32 vectors (RFC 4648 test vectors, lowercase), CRC32
   vectors (IEEE check value `0xCBF43926` for `"123456789"`), envelope round-trip, length-limit
   rejection at 160 bytes and acceptance at 159.
5. **Cancellation tests**: cancellation before admission applies nothing; a mutation admitted
   under a cancelled caller still completes and notifies (mirroring the `room` adapter's
   documented posture).
6. **Cross-instance visibility test**: write via instance A; an already-active reader on instance
   B does not re-emit; a new collection on B starts with A's value.
7. **Platform lanes**: `:file:build` on Linux CI covers jvm, android unit, linuxX64, js/Node,
   wasmJs/Node lanes; `:file:iosSimulatorArm64Test` and `:file:macosArm64Test` in the `apple-tests`
   job; `:file:jsNodeTest` joins the JS lock-discipline canary. mingwX64 compiles (klib
   cross-compilation) but has no CI test runner today — the Windows rename-semantics risk is
   therefore mitigated in-design (single mutex excludes open-file races; `atomicMove` is the only
   replace primitive used) and stated in the README, not CI-proven. This matches the repository
   posture for every other module (none runs mingwX64 tests).

## 6. CI and build integration (exact anchors)

All in `.github/workflows/store6.yml` unless noted:

| Anchor | Change |
|---|---|
| `settings.gradle` | `include ':file'`; `include ':file-sample'` + `projectDir = file('file/sample')` |
| `gradle/libs.versions.toml` | `kotlinxIo = "0.9.1"`; `kotlinx-io-core` library entry |
| `linux-build-test` job | New steps "Build Store6 file adapter" (`:file:build` with the two `-P` flags every module build passes) and "Run Store6 file sample" (`:file-sample:run`) |
| "Reject core-internal access from extension modules" | Add `file` and `file/sample` to the module list |
| "Enforce the TD-8 primitive whitelist and single-writer residence" | Add `file/src/*Main` to `production_source_dirs` |
| "JS lock-discipline canary" | Add `:file:jsNodeTest` |
| `apple-tests` job | Add `:file:iosSimulatorArm64Test`, `:file:macosArm64Test` |
| `klib-publication-check` job | Add `:file:publishToMavenLocal` and `file` to `modules=(...)`; no `case` exception if D3 holds at 12 targets (subset fallback adds one) |
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
  entry-point walkthrough; target list; **a "Semantics and limits" section that states, verbatim
  honestly: the durability posture (D11), the single-instance rule (D13), the key-length limit
  (D6), corruption handling (D7), the missing `TransactionalSourceOfTruth` and its mutations
  consequence (D5)**; the kotlinx-serialization codec recipe (D14); contract-kit testing
  instructions; sample command.

## 8. Risks

| Risk | Likelihood | Contained by |
|---|---|---|
| kotlinx-io filesystem gaps on `wasmJs`/`js` Node lanes | Medium | D3 fallback gate: subset plugin, `room` precedent; decided by test evidence during implementation |
| kotlinx-io 0.x API break on version bump | Medium over the artifact's life | Pinned catalog version; experimental tier; public-surface blast radius limited to `Path`/`Source`/`Sink` (D1) |
| Windows rename-over-open-file semantics | Low (mutex excludes in-instance races; cross-instance is unsupported per D13) | D9 single mutex; README statement; no mingwX64 CI runner exists to prove more |
| Power-loss torn writes without fsync | Real but bounded | D7 CRC → detected absence → refetch; D11 documented posture; SYNC mode deferred |
| `runTest` + real IO flakiness (virtual time vs. real dispatchers) | Low | Kits already run real Room/SQLDelight IO under `runTest` on all targets; same pattern |
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
