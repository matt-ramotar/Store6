# File source of truth — implementation plan

Status: proposed; revised after two independent adversarial reviews of both this plan and the
design.
Executes: [file-source-of-truth-design.md](./file-source-of-truth-design.md) (the design authority —
this plan adds no design decisions; where a task appears to need one, that is a defect to escalate,
not a gap to fill inline).

## 1. Execution model

- **One orchestrator agent** (Fable) owns: task dispatch, budget enforcement, acceptance review,
  git (all commits and pushes), gate decisions, design-deviation triage, PR creation, and CI
  watching. The orchestrator writes no implementation code except during declared takeovers (§9).
- **Sub-agents** (GPT 5.6 Sol and Cursor Grok 4.6) execute tasks. Each sub-agent starts with no
  conversation context: every dispatch prompt must be self-contained (§8 template). Sub-agents
  never run `git commit`, never switch branches, and never edit files outside their task's
  ownership list.
- **Branch and baseline**: the implementation branch is created from a commit that already
  contains the two reviewed documents in `docs/designs/` (the merged design PR, or the design
  branch head if unmerged). The orchestrator records that commit as `BASE` and pastes both the
  branch name and `BASE` SHA into every dispatch prompt. All §6 diff checks run against `BASE`.
- **Serial by default.** All code-writing tasks execute sequentially in one checkout. Rationale:
  any `:file:*` Gradle invocation compiles every `commonMain`/`commonTest` file, so a parallel
  task's half-written sources break another task's acceptance run — disjoint file ownership does
  not isolate compilation state. **Optional parallelization**: if the orchestrator's platform
  provides isolated-worktree sub-agents, the §5 lanes may run concurrently, one lane per
  worktree, with the orchestrator merging each accepted task back into the main branch before any
  dependent task dispatches. Documentation-only tasks (T5.2 prose, T7.1) may always overlap code
  tasks.
- **Gradle discipline**: one Gradle invocation at a time per checkout. Every JS/Wasm test task
  and every multi-target build runs with `-Dorg.gradle.configureondemand=false` — the repository
  default is `configureondemand=true`, and CI's `store6.yml` sets the `false` value globally
  because "Kotlin JS/Wasm lock tasks must see one complete project graph"; local acceptance must
  match CI's graph or lock-task failures will masquerade as platform gaps. Native-inclusive
  builds additionally pass
  `-Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true`
  like every CI module build. A hung daemon is recovered once with `./gradlew --stop` and a
  retry; a second hang is reported to the orchestrator, not retried silently.
- **BCV dump rule**: `:file:build` runs `apiCheck`, which fails whenever `file/api/` dumps are
  stale. Therefore: T0.2 creates the initial dumps (running `:file:apiDump` **before** its first
  `:file:build`), and every task that changes the public surface (T2.1, T3.2a) runs
  `:file:apiDump` as part of its acceptance and includes the `file/api/` delta in its commit. No
  other task may run `:file:build` or `:file:apiCheck` while dumps are knowingly stale.
- **Environment reality** (verified on the reference VM): JDK 21 present (Gradle toolchains
  auto-provision via the foojay resolver), no Android SDK, no `~/.konan`. Every Store6 library
  module applies `com.android.library`, so **no Gradle task in this repository configures without
  an Android SDK** — T0.1 provisions it as a hard prerequisite. macOS lanes
  (`iosSimulatorArm64Test`, `macosArm64Test`) cannot run locally on a Linux VM — they are
  verified by PR CI (`apple-tests` job), and the plan treats that as the acceptance mechanism for
  those lanes, not a skipped step.

## 2. Time budgets — semantics and enforcement

Budgets are wall-clock minutes for one sub-agent run, stated as **soft / hard**.

- **Soft budget**: the expected completion time. A sub-agent still working at soft budget must
  send the orchestrator a status: done-ness estimate, current blocker, next action.
- **Hard cap**: the orchestrator stops the agent, reviews partial output, and picks one:
  1. **Narrow** — re-dispatch the same task minus completed pieces, with the blocker named.
  2. **Re-dispatch fresh** — same spec, new agent, when output shows drift or thrash
     (rewriting the same file repeatedly, inventing API not in D4, touching unowned files).
  3. **Take over** — orchestrator finishes small remainders (< ~10 min of work) itself.
  4. **Gate** — if the blocker is a design gap or an environment/platform fact, stop the lane and
     run the relevant gate (§7) instead of burning budget.
- **Download exemption**: the budget clock pauses while a first-time toolchain download
  (Kotlin/Native konan, Android SDK packages, Gradle-provisioned JDK) is demonstrably
  progressing in the log. It resumes when compilation output resumes. This applies mainly to
  T0.1/T0.2 and exists so a slow mirror is waited out once — the artifacts are cached after.
- **Two-strike rule**: a task that hits its hard cap twice is never dispatched a third time
  unchanged. The orchestrator re-reads the design section, decides design-gap vs. agent-gap, and
  either amends the design doc (logged in the PR description) or splits the task.
- **Contingency pool**: 90 minutes, spent only by orchestrator decision, tracked in the rollup
  (§10). The gate fallback task (T6.2b) has its own budget outside the pool.

Common causes of budget overrun, named so agents avoid them: running `:file:build` (all 12
targets) when the acceptance column names a single-lane task like `:file:jvmTest`; re-deriving
repository conventions instead of reading the context files (§4); writing tests for behavior the
kits already cover; debugging a JS/Wasm failure without first checking the
`configureondemand` flag (§1).

## 3. Model assignment

| Agent | Use for | Reason |
|---|---|---|
| GPT 5.6 Sol | T1.2, T2.1, T2.2, T3.2a, T3.2b, T4.2a, T4.2b, T6.2 | Concurrency-sensitive code: the D9 shield nesting, signal registry, retry loop, recovery ordering. These tasks have the most ways to be subtly wrong. |
| Cursor Grok 4.6 | T0.1, T0.2, T1.1, T3.1, T4.1, T4.3, T5.1, T5.2, T6.1, T7.1 | Convention-following work with exact specs: scaffolding, byte formats, kit wiring, docs, CI lists. Fast turnaround matters more than novel reasoning. |

Suggestions, not law: the orchestrator may reassign, but a reassignment of a Sol-suggested task to
Grok should add an explicit review pass of the D9/D12 semantics in the acceptance review.

## 4. Context injection (paste into every dispatch prompt)

Every sub-agent prompt lists, as required reading before any edit:

1. `docs/designs/file-source-of-truth-design.md` — at minimum the D-sections the task names.
2. The seam contracts:
   `core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/SourceOfTruth.kt`,
   `.../seam/Bookkeeper.kt` (KDoc is the contract; the task is judged against it).
3. The reference implementations for the pattern being copied:
   `sqldelight/src/commonMain/kotlin/org/mobilenativefoundation/store6/sqldelight/SqlDelightSourceOfTruth.kt`
   (signal registry, `ioContext` convention),
   `room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/RoomSourceOfTruth.kt`
   (admission/cancellation posture),
   `core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/internal/Bookkeeper.kt`
   (`InMemoryBookkeeper` — bookkeeper semantic reference AND the internal-test-gate precedent).
4. **Every file already present under `file/src/` that the task builds on** — named explicitly in
   the prompt (e.g. T2.1/T3.1/T3.2a must read `internal/Envelope.kt`, `internal/FileNames.kt`,
   `internal/FileOps.kt` as created by T1.x; their APIs are task outputs, not design text).
5. For test tasks: `testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/SourceOfTruthContractKit.kt`
   and `BookkeeperContractKit.kt`, plus one wired example
   (`room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomSourceOfTruthContractTest.kt`
   and its sibling test-key file — the pattern for kit fixtures).
6. For build/CI tasks: `settings.gradle`, `gradle/libs.versions.toml`,
   `realtime/build.gradle.kts`, `room/gradle.properties`, `.github/workflows/store6.yml`,
   `tooling/plugins/src/main/kotlin/org/mobilenativefoundation/store/tooling/plugins/` (all three
   plugin files).
7. For documentation tasks: `AGENTS.md`, both skills under
   `plugins/internal/documentation/skills/`, and `room/README.md` + `realtime/README.md` as shape
   precedents.

Standing constraints (also pasted verbatim into every prompt):

- Work in the checkout at the stated workspace path, on the stated branch, at or after the stated
  `BASE` SHA. Never run `git commit`, `git push`, or any branch operation.
- Zero diff outside `file/`, `file/sample/`, `settings.gradle`, `gradle/libs.versions.toml`, and
  `.github/workflows/store6.yml`. Never touch `core/`, `testing/`, any other module, the seam
  package, `STABILITY.md`, or `.github/docs-sync-sources.txt`.
- Production source sets must not contain `runBlocking`, `GlobalScope`, `atomicfu`, `Channel`, or
  `actor` (the TD-8 CI regex runs against `file/src/*Main`).
- `explicitApi()` is on: every public declaration needs an explicit visibility and return type.
- No public API beyond design §D4. New public symbols require orchestrator approval first.
  Internal test gates (D9) are internal constructors, not public API.
- No dependencies beyond `projects.core`, `libs.kotlinx.io.core` (main) and `projects.testing`,
  `libs.kotlinx.coroutines.test`, `libs.turbine` (test).
- Kotlin only; production code in
  `file/src/<sourceSet>/kotlin/org/mobilenativefoundation/store6/file/` (internals under
  `.../file/internal/`); tests in the mirrored `commonTest` path. Test files need
  `@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)`.
- Run only the Gradle tasks your acceptance names (plus single-target compile checks such as
  `:file:compileKotlinJvm`). Single test classes run as
  `./gradlew :file:jvmTest --tests 'org.mobilenativefoundation.store6.file.<ClassName>'`.
- JS/Wasm and multi-target commands take the §1 flags verbatim.

## 5. Task order

Serial spine (default execution order):

```
T0.1 → T0.2 → T1.1 → T1.2 → T2.1 → T2.2 → T3.1 → T3.2a → T3.2b
     → T4.1 [G1 → T6.2b if red] → T4.2a → T4.2b → T4.3 → [G2]
     → T5.1 → T5.2 → T6.1 → T6.2 [G3] → T7.1
```

Dependency facts (what may reorder under worktree parallelization, §1):

- T1.1 ∥ T1.2 (disjoint files, no cross-reads).
- Lane A (T2.1 → T2.2) ∥ lane B (T3.1 → T3.2a → T3.2b) — both depend on T1.x only.
- T4.1 depends on T2.2 only (not on any bookkeeper task). T4.2a/T4.2b depend on T2.2.
  T4.3 depends on T3.2b.
- G1 is decided at T4.1 acceptance and **must precede T5.2, T6.1, and T6.2** (they consume the
  final target matrix). If G1 goes red, T6.2b executes before T5.2/T6.1 dispatch.
- T5.1 depends on T2.2 + T3.2b (its rebuild assertions exercise bookkeeper recovery, not just the
  public API). T5.2 depends on T5.1 and the G1 verdict. T6.1 depends on T5.1 (its acceptance greps require `file/sample/src` to exist) and the
  G1 verdict (the JS-canary edit is target-conditional).
- G2 is an orchestrator-run evidence command after T4.3 (§7), not a task.

## 6. Task specifications

Budget notation: soft/hard minutes. "Acceptance" commands are run by the sub-agent and re-run by
the orchestrator before commit. Flags per §1.

---

### T0.1 — Preflight and toolchain provisioning — 25/40 — Grok

- **Owns**: no repository files (environment only; `ANDROID_HOME` export goes in the shell
  profile or a `local.properties` the repo already gitignores).
- **Steps**:
  1. Verify JDK ≥ 17 on PATH (`java -version`).
  2. Provision the Android SDK — hard prerequisite; every module applies `com.android.library`,
     so no Gradle task configures without it. Procedure: download the current
     `commandlinetools-linux-<version>_latest.zip` from the Android developer site's
     "command line tools only" section; unzip so the tools live at
     `$ANDROID_HOME/cmdline-tools/latest/` (the `latest` directory level is required); export
     `ANDROID_HOME`; then `yes | sdkmanager --licenses --sdk_root="$ANDROID_HOME"` and
     `sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-36" "build-tools;36.0.0"`
     (platform 36 matches the convention's `compileSdk = 36`; AGP 8.10 selects a compatible
     installed build-tools).
  3. Warm Gradle: `./gradlew :core:compileKotlinJvm` (real task; with the Android plugin now
     configurable it also proves SDK wiring).
- **Acceptance**: warmup task green; `sdkmanager --list_installed --sdk_root="$ANDROID_HOME"`
  shows platform 36.
- **Failure**: there is no degraded mode. If the SDK cannot be provisioned, the orchestrator
  halts the plan and reports the environment blocker — nothing downstream can even configure.

### T0.2 — Module scaffold — 35/60 — Grok

- **Owns**: `settings.gradle` (the `include ':file'` line ONLY — sample lines belong to T5.1,
  because including `:file-sample` before `file/sample/build.gradle.kts` exists breaks any
  full-graph configure), `gradle/libs.versions.toml`, `file/build.gradle.kts`,
  `file/gradle.properties`, `file/src/androidMain/AndroidManifest.xml`, `file/api/` (initial
  dumps), one placeholder internal source file.
- **Steps**: apply design D2 exactly minus the sample include (catalog entries named
  `kotlinxIo` / `kotlinx-io-core`, full convention plugin per D3, `api(projects.core)` +
  `api(libs.kotlinx.io.core)` in `commonMain`, test deps in `commonTest`, `android { namespace =
  "org.mobilenativefoundation.store6.file" }`, `<manifest />`). Add one placeholder internal
  declaration so every source set compiles.
- **Acceptance**, in this order (BCV rule, §1): `./gradlew :file:apiDump` (native flags), then
  `./gradlew :file:build` (native flags + `-Dorg.gradle.configureondemand=false`) — the cold-konan
  run; the download exemption (§2) applies. `file/api/` then contains `jvm/file.api`,
  `android/file.api`, `file.klib.api`.
- **Gate G0** passes when acceptance is green: toolchain proven for every later task.

### T1.1 — Encoding primitives: base32, CRC32, envelope, name mapping — 30/45 — Grok

- **Owns**: `file/src/commonMain/.../internal/Base32.kt`, `internal/Crc32.kt`,
  `internal/Envelope.kt`, `internal/FileNames.kt`; mirror test files in `commonTest`.
- **Steps**: implement per D6/D7/D12: lowercase unpadded RFC 4648 base32 with the `""` → `"0"`
  sentinel; table-driven CRC32 (IEEE); envelope writer/reader parameterized by magic (`S6FV`,
  `S6FB`, `S6FW`) returning a structural-corruption verdict distinct from IO failure; the
  159-byte limit check with the D6 exception message shape; `(namespace, canonicalId) → Path`
  mapping and the `.corrupt` sibling-name helper. Everything `internal`, KDoc'd — T2.x/T3.x
  agents consume these APIs by reading the files.
- **Acceptance**: `./gradlew :file:jvmTest` green with: RFC 4648 vectors, sentinel properties
  (`enc("") == "0"`, no non-empty input encodes to `"0"`), CRC check value `0xCBF43926` for
  `"123456789"`, envelope round-trip + each corruption class, limit rejection at 160 / acceptance
  at 159.
- **Out of scope**: any suspend code, any `SystemFileSystem` call (pure byte/string logic only).

### T1.2 — `atomicReplace` and filesystem utilities — 30/45 — Sol

- **Owns**: `file/src/commonMain/.../internal/FileOps.kt` (expect declarations + shared
  utilities), actuals in `jvmMain`, `androidMain`, `nativeMain`, `jsMain`, `wasmJsMain`; tests in
  `commonTest` + `androidUnitTest` (add the needed `kotlin("test")` dependency for
  `androidUnitTest` in `file/build.gradle.kts` if absent — one sourceSet block, still this task's
  scope).
- **Steps**: `internal expect fun atomicReplace(source: Path, destination: Path)` — every actual
  delegates to `SystemFileSystem.atomicMove` except android, which catches the API 24–25
  `UnsupportedOperationException` and falls back to `android.system.Os.rename` (the documented
  `rename(2)` binding, API 21+), translating `ErrnoException` to `IOException`, with the fallback
  body extracted as a directly-testable internal function (D9). Shared utilities:
  ensure-directories, unique tmp/trash name generator (monotonic counter + random), best-effort
  recursive purge, best-effort first-operation sweep — all absorbing failures per D10, none
  holding public API.
- **Acceptance**: `./gradlew :file:jvmTest` (atomicReplace replaces existing destination;
  utilities round-trip in a temp dir) and `./gradlew :file:testDebugUnitTest` green. The
  `Os.rename` branch itself only executes on API < 26 devices; the androidUnitTest proves the
  translation logic around it (D9 states the CI posture).
- **Out of scope**: mutex/shield logic (T2.1), any public type.

### T2.1 — `FileSourceOfTruth`: reader, signals, write, delete — 50/75 — Sol

- **Owns**: `file/src/commonMain/.../FileSourceOfTruth.kt`, `FileCodec.kt` (interface + the two
  built-in codecs), `FileCorruptionPolicy.kt`; smoke tests in `commonTest`
  (`FileSourceOfTruthSmokeTest.kt`); `file/api/` regeneration (public API lands here).
- **Steps**: implement D4 signatures byte-for-byte (annotations included; the public constructor
  delegates to an internal constructor carrying the D9 test gates: before-admission,
  after-admission, before-disk-write, all defaulting to no-ops), the D8 signal registry (copy the
  CAS acquire/release shape from `SqlDelightSourceOfTruth`), the D9 pipeline with the exact
  shield nesting (`mutex.withLock { withContext(NonCancellable) { withContext(io) { … }; bump }
  }`), `ioContext.minusKey(Job)`, the D6 absent-path rule, structural corruption checks under the
  mutex, decode outside with `CancellationException` rethrow and the D7 retry loop
  (decode-failure-with-changed-bytes retries on the new snapshot inside the same emission;
  equal-bytes applies the policy), temp-file writes through T1.2's utilities. `delete(key)` per
  D10 (canonical delete decides the outcome; `.corrupt` cleanup best-effort afterward).
- **Acceptance**: `./gradlew :file:jvmTest` green with smoke tests (write→read round-trip,
  delete→null, equal-value rewrite re-emits, absent-path first emission, oversized key throws
  `IllegalArgumentException` without touching disk); then `./gradlew :file:apiDump` and confirm
  the `file/api/` diff contains exactly the D4 surface.
- **Out of scope**: `deleteNamespace`/`deleteAll` bodies (throw `NotImplementedError` until T2.2
  — smoke tests must not cover them; the members exist from the start so the dumps are already
  final), bookkeeper, kit wiring.

### T2.2 — Namespace/all deletes, trash lifecycle, sweep — 35/55 — Sol

- **Owns**: the T2.1 files (lane-serial, no conflict) plus
  `commonTest/.../FileTrashLifecycleSmokeTest.kt`. No public-surface change, so no dump update.
- **Steps**: D10 exactly: trash-rename with unique-token names, bump-only success on absent
  directories, `deleteAll` on missing `values/`, first-operation sweep wiring, purge best-effort.
- **Acceptance**: `./gradlew :file:jvmTest` green with: deleteNamespace isolation across
  namespaces, second consecutive `deleteAll`, delete of never-written namespace, sweep removes a
  planted stale tmp file and trash directory.
- **Out of scope**: contract kit (T4.1 owns it).

### T3.1 — Bookkeeper persistence layer: record + watermark codecs — 35/55 — Grok

- **Owns**: `file/src/commonMain/.../internal/BookkeeperFormats.kt` + `commonTest` mirror.
- **Steps**: the two D12 payload tables exactly (flag bits, field widths, absent-fields-omitted,
  bit-1-without-bit-0 invalid), over T1.1's envelope with magics `S6FB`/`S6FW` (read
  `internal/Envelope.kt` first — its API is authoritative). Pure byte logic, no IO.
- **Acceptance**: `./gradlew :file:jvmTest` green: round-trip every flag combination, reject the
  invalid flag state, watermarks round-trip with 0 and N namespaces.

### T3.2a — `FileBookkeeper`: mirror and operations — 40/60 — Sol

- **Owns**: `file/src/commonMain/.../FileBookkeeper.kt`,
  `commonTest/.../FileBookkeeperSmokeTest.kt`; `file/api/` regeneration.
- **Steps**: D12 minus recovery: port `InMemoryBookkeeper`'s record/mirror semantics (including
  the sequence-exhaustion `check` and `watermarkOnlyStatus` shape), the public-to-internal
  constructor delegation with test gates (the before-disk-write gate doubles as the
  storage-failure injector), the three infallible operations with the D12 absorption boundary
  (mirror + sequence + payload encoding outside the catch; exactly the persistence call inside),
  the five fallible maintenance operations persist-first with trash-rename forgets
  (absent-directory = mirror-only success), the D9 shield shape for all disk writes,
  `atomicReplace` for every rename. Recovery scan may be a stub that only ensures directories.
- **Acceptance**: `./gradlew :file:jvmTest` green with smoke tests: success→status round-trip,
  failure streak counting, markStale, watermark advance, forget leaves watermarks, absorbed
  injected write failure (via the gate) keeps process-local status correct; then
  `./gradlew :file:apiDump`, diff shows exactly `FileBookkeeper`.
- **Out of scope**: recovery-from-disk (T3.2b), kit wiring (T4.3).

### T3.2b — `FileBookkeeper`: recovery, reopen, corruption — 30/45 — Sol

- **Owns**: the T3.2a files plus `commonTest/.../FileBookkeeperRecoverySmokeTest.kt`. No
  public-surface change.
- **Steps**: the D12 recovery scan (absent paths = empty state; sequence = max over persisted
  sequences and watermarks), corrupt-record quarantine-and-skip, corrupt-watermarks handling in
  the D12 crash-safe order (exhaustion check before mutation → best-effort diagnostic copy →
  tmp + `atomicReplace` directly onto the canonical watermarks path with
  `globalStaleWatermark = recoveredMax + 1`).
- **Acceptance**: `./gradlew :file:jvmTest` green: reopen preserves status and sequence
  monotonicity (pre-restart watermark still outranks pre-restart success); corrupt watermarks →
  every surviving success durably stale and the swap leaves no missing-file window (assert the
  canonical file exists throughout via the test gate); corrupt record + intact watermark →
  watermark-only status.

### T4.1 — SoT contract kit + degenerate/restart tests — 35/55 — Grok — **carries gate G1**

- **Depends on**: T2.2 only.
- **Owns**: `file/src/commonTest/.../FileTestFixtures.kt` (the `TestKey` implementation and
  temp-directory helpers — the Room kit wiring keeps fixtures in a sibling file; same pattern),
  `FileSourceOfTruthContractTest.kt`, `FileSourceOfTruthRestartTest.kt`,
  `FileDegenerateShapeTest.kt`.
- **Steps**: extend `SourceOfTruthContractKit<TestKey, String>` overriding exactly its five
  abstract members (`createSourceOfTruth`, `keyA`, `keyB`, `keyOtherNamespace`, `value`), fresh
  `SystemTemporaryDirectory`-rooted directory per `createSourceOfTruth()`, `Utf8StringFileCodec`;
  design §5.2 restart tests and §5.5 degenerate-shape tests (empty namespace, empty id, both;
  `deleteAll` twice; fresh-directory mutations).
- **Acceptance**: `./gradlew :file:jvmTest :file:linuxX64Test` green, then the **G1 evidence
  run**: `./gradlew :file:jsNodeTest :file:wasmJsNodeTest -Dorg.gradle.configureondemand=false`.
  Report each lane's verdict separately.
- **Gate G1** (orchestrator decision, ≤ 15 min): all four lanes green → full 12-target matrix
  confirmed, proceed. A js/wasmJs failure that reproduces on a trivial kotlinx-io repro (write +
  atomicMove + read in a temp dir, no adapter code, same flags) → invoke design D3 fallback via
  T6.2b **now** (before T5.x/T6.1). A failure that does not reproduce standalone is an adapter
  bug: fix within lane budgets.

### T4.2a — Corruption tests — 25/40 — Sol

- **Owns**: `file/src/commonTest/.../FileCorruptionTest.kt` (+ a jvm-only source-set test where
  byte-planting needs `java.io`-level control; prefer planting via kotlinx-io from commonTest —
  `SystemFileSystem.sink` writes arbitrary bytes, which covers truncation/magic/CRC cases).
- **Steps**: design §5.3 SoT cases: truncation/magic/CRC/decode-throw × {QUARANTINE, PROPAGATE};
  retry-loop behavior via a gated codec (decode blocks on a gate while the orchestrating test
  replaces the file, then asserts no quarantine and the eventual valid emission); pure-function
  tests of the snapshot-comparison decision; quarantined file not re-read; decode
  `CancellationException` propagation without filesystem mutation.
- **Acceptance**: `./gradlew :file:jvmTest` green.

### T4.2b — Cancellation + cross-instance tests — 25/40 — Sol

- **Owns**: `file/src/commonTest/.../FileCancellationTest.kt`, `FileCrossInstanceTest.kt`.
- **Steps**: design §5.6 via the D9 internal gates (before-admission gate: cancel there, assert
  nothing applied; after-admission gate: cancel there, assert the mutation completes, notifies,
  and returns normally); §5.8 instance-scoped signals (already-active reader on instance B does
  not re-emit for instance A's write; a new B collection starts with A's value).
- **Acceptance**: `./gradlew :file:jvmTest :file:jsNodeTest -Dorg.gradle.configureondemand=false`
  green (js included deliberately: the single-threaded lane exercises different interleavings).

### T4.3 — Bookkeeper contract kit + restart/corruption tests — 35/55 — Grok

- **Depends on**: T3.2b.
- **Owns**: `file/src/commonTest/.../FileBookkeeperContractTest.kt`,
  `FileBookkeeperRestartTest.kt`, `FileBookkeeperCorruptionTest.kt`.
- **Steps**: extend `BookkeeperContractKit` overriding `createBookkeeper` (fresh temp directory
  per call); §5.2 bookkeeper restart tests; §5.3 bookkeeper corruption tests (corrupt record +
  intact watermark → watermark-only status; corrupt watermarks → `recoveredMax + 1`, every
  surviving success stale).
- **Acceptance**: `./gradlew :file:jvmTest :file:linuxX64Test` green.

### Gate G2 — orchestrator evidence command (no task, ~15 min)

After T4.3 (and T6.2b when G1 was red), the orchestrator runs the combined four-lane evidence:

```
./gradlew :file:jvmTest :file:linuxX64Test :file:jsNodeTest :file:wasmJsNodeTest \
  -Dorg.gradle.configureondemand=false
```

(minus lanes G1 removed). Green → dispatch T5.x. Red → route to the owning lane with output;
two-strike rule applies.

### T5.1 — Sample module — 35/50 — Grok

- **Depends on**: T2.2, T3.2b (serve-without-refetch and invalidation-across-rebuild both
  exercise the recovery scan).
- **Owns**: `settings.gradle` (adds the two sample lines: `include ':file-sample'` +
  `projectDir`), `file/sample/build.gradle.kts`, `file/sample/src/main/kotlin/.../Main.kt`.
- **Steps**: design §7 — JVM `application` (the `room/sample` build shape, minus KSP), a
  scripted `main` that asserts the four behaviors (persist; serve-without-refetch after rebuild;
  durable invalidation across rebuild; corruption recovery optional) and exits nonzero on
  failure, storing under a temp directory it cleans up.
- **Acceptance**: `./gradlew :file-sample:run` exits zero; a deliberately broken assertion exits
  nonzero (verify once, then restore).

### T5.2 — README + KDoc audit — 40/60 — Grok

- **Depends on**: T5.1, G1 verdict (the target list and, under a subset, the gap paragraph are
  inputs — the dispatch prompt states the final matrix).
- **Owns**: `file/README.md`; KDoc-only edits inside `file/src/commonMain`.
- **Steps**: design §7 README contents (every named disclosure: durability incl. power-loss undo,
  single-instance rule, key limits + empty-string handling, corruption handling, no
  `TransactionalSourceOfTruth` + mutations consequence, Android fallback, mingwX64 ANSI paths,
  js/wasmJs event-loop blocking; install snippet; codec recipe; kit instructions; sample
  command; the G1-final target list). Then one KDoc audit pass over the public surface against
  the discipline skills. **This task must follow `AGENTS.md` and both documentation skills,
  including the three-pass review; the dispatch prompt links them as required reading.**
- **Acceptance**: orchestrator review against design §7's checklist (every listed disclosure
  present, no unsupported claim, no internal process references); `./gradlew :file:jvmTest`
  still green; `git diff` inside `file/src/` shows comment-only changes.

### T6.1 — CI wiring — 20/35 — Grok

- **Depends on**: T5.1 (its acceptance greps need `file/sample/src`), G1 verdict (the JS-canary
  and klib-check edits are target-conditional).
- **Owns**: `.github/workflows/store6.yml`.
- **Steps**: design §6 table, exactly: two `linux-build-test` steps (placed after the realtime
  steps, same flag shape), the module list in "Reject core-internal access from extension
  modules" (add `file` and `file/sample`), `production_source_dirs` in "Enforce the TD-8
  primitive whitelist and single-writer residence" (add `file/src/*Main`), the "JS
  lock-discipline canary (full conformance suite on the JS lane)" task list (add
  `:file:jsNodeTest` — only if js survived G1), the two `apple-tests` entries, the
  `klib-publication-check` publish command + `modules=(...)` array (+ `case` exceptions per
  T6.2b when the matrix shrank).
- **Acceptance**: `git diff` review against the design table (orchestrator); local execution of
  the two grep-based check bodies against the working tree (they are plain bash — run them
  verbatim) both pass.

### T6.2 — Full local gate sweep — 45/80 — Sol — **carries gate G3**

- **Owns**: no source files (fix-forward diffs go back to the owning task's agent, or takeover
  when trivial). `file/api/` only if the dump-verify step finds drift, which is itself a defect
  to report (the BCV rule says dumps ride the API-changing tasks).
- **Steps**, serialized:
  1. `./gradlew :file:apiCheck` — dumps current (no `apiDump`; drift is a finding).
  2. `./gradlew :file:build` (§1 flags) — the full matrix including android unit, js, wasmJs
     test lanes.
  3. `./gradlew :file-sample:run`.
  4. The two grep gate bodies (as in T6.1), verbatim, green.
  5. Allowlist check, executable:
     `git diff --name-only "$BASE"...HEAD | grep -vE '^(file/|docs/designs/|settings\.gradle$|gradle/libs\.versions\.toml$|\.github/workflows/store6\.yml$)'`
     must print nothing.
  6. Zero-core-diff evidence: `git diff --exit-code "$BASE"...HEAD -- core testing` passes, plus
     `./gradlew :core:apiCheck :testing:apiCheck` green.
- **Gate G3**: all green → T7.1. Any red: route to the owning task's agent with the failure
  output; re-run only the failed step after the fix.

### T6.2b — Contingency: D3 subset fallback — 30/45 — Sol — **only if G1 invokes it; runs before T5.x/T6.1**

- **Owns**: `file/build.gradle.kts`, `file/api/file.klib.api` regeneration. (README target list
  is T5.2's via its G1 input; workflow `case` exceptions are T6.1's via its G1 input.)
- **Steps**: switch to `org.mobilenativefoundation.store.store6.multiplatform.subset`; declare
  the surviving targets (drop exactly the lane(s) G1's standalone repro condemned) with a
  build-file comment naming the exact kotlinx-io failure (the `room` comment shape, no tracker
  links in source); re-run `:file:apiDump`. Downstream command substitution, recorded by the
  orchestrator for every later task: dropped-lane test tasks disappear from G2/T6.2 evidence;
  `klib-publication-check` gains `file:-wasm-js` and/or `file:-js` `case` lines (via T6.1); the
  JS-canary edit is skipped when js was dropped. The upstream kotlinx-io report becomes a PR
  action item (maintainer-filed; the local `gh` credential is read-only).
- **Acceptance**: `./gradlew :file:build` (§1 flags) green on the reduced matrix.

### T7.1 — PR body assembly — 20/30 — Grok

- **Owns**: a PR title + body draft handed to the orchestrator (no repository files).
- **Steps**: draft against the repository's `pull_request_template.md` (repo root): closes/relates
  [#533](https://github.com/MobileNativeFoundation/Store/issues/533) context, description mapping
  commits to design sections, test-plan section listing every acceptance command with its result,
  the zero-core-diff statement with the §6 evidence, G1's verdict (full matrix, or named subset +
  the upstream-report action item), the mingwX64 upstream-report action item, honest checklist
  answers (apple lanes are CI-verified, not local).
- **Orchestrator follow-through** (outside task budgets): create the draft PR against `store6`,
  watch CI (`store6.yml` — all jobs including `apple-tests` and `klib-publication-check`), route
  failures like G3, and mark ready-for-review when green.

## 7. Gates (orchestrator-owned decision points)

| Gate | After | Question | Green path | Red path |
|---|---|---|---|---|
| G0 | T0.2 | Does the full 12-target module build here? | T1.1 | Fix scaffold within budget; SDK/toolchain gaps → halt per T0.1 |
| G1 | T4.1 | Do js/wasmJs kit lanes pass on the real filesystem (CI-equivalent flags)? | Keep 12 targets | Standalone kotlinx-io repro fails → T6.2b immediately, then continue with substituted commands; repro passes → adapter bug, stay in lane |
| G2 | T4.3 (+T6.2b if invoked) | Four-lane combined evidence command green? | T5.1 | Route failures to owning task; two-strike rule |
| G3 | T6.2 | Sweep green, allowlist exact, dumps current, core/testing untouched? | T7.1 | Route to owning task; re-run failed step only |

## 8. Dispatch prompt template (orchestrator fills the brackets)

```
Task [ID] — [name]. You are implementing part of the Store 6 `file` adapter.

Workspace: [absolute checkout path]. Branch: [branch] (already checked out; BASE = [sha]).
Never run git commit/push/checkout. The orchestrator owns git.

Read first (do not skip): docs/designs/file-source-of-truth-design.md sections [list];
[context files from §4 for this task type, including every existing file/src file this task
builds on]. The design document is authoritative; if a needed decision is not in it, STOP and
report the gap instead of inventing.

You own exactly these files: [list]. Do not create, edit, or delete any other file.

Standing constraints: [paste §4 constraint block verbatim].

Steps: [task Steps].

Acceptance (run yourself before reporting done, exactly these Gradle invocations with these
flags): [task Acceptance commands with §1 flags spelled out].

Budget: [soft] minutes soft / [hard] hard. At [soft] minutes, report status (done-ness, blocker,
next action) and continue only if no blocker. The clock pauses only for demonstrably progressing
toolchain downloads. If a Gradle daemon hangs: ./gradlew --stop once, retry once, then report.

Report back: files changed; acceptance command output (verbatim tail); any deviation from the
design and why; open questions.
```

## 9. Acceptance review checklists (orchestrator, per task type)

**Implementation tasks** (T1.x, T2.x, T3.x): acceptance commands re-run green; diff touches only
owned files; no banned primitive (`grep -rnE '(^|[^[:alnum:]_])(runBlocking|GlobalScope|atomicfu|Channel|actor)([^[:alnum:]_]|$)' file/src/commonMain file/src/*Main`);
public surface matches D4 exactly (for API-carrying tasks: the committed `file/api/` diff is the
evidence; for others: `git diff file/api/` is empty); the D9 shield shape present where the task
claims it (read the code, not the summary); KDoc on public symbols states the contracts the
design assigns them; internal test gates default to no-ops and appear in no dump.

**Test tasks** (T4.x): tests assert the design's behavior, not the implementation's (a test that
merely mirrors internals is rejected); kit subclasses override exactly the kit's abstract members
(five on `SourceOfTruthContractKit`, one on `BookkeeperContractKit`) and nothing else; every §5
bullet the task claims is present; failure messages would identify the broken invariant.

**Docs tasks** (T5.2, T7.1): three-pass review per the discipline skill (accuracy against source,
warrant for every claim, reader utility); no internal-process references in source surfaces; all
protected tokens (versions, paths, commands, limits) verified against the code.

**Takeover threshold**: if a review finds < ~10 minutes of remedial work, the orchestrator fixes
and notes it in the commit; otherwise it re-dispatches with the review findings.

## 10. Budget rollup

| Task | Soft | Hard | Suggested agent |
|---|---|---|---|
| T0.1 preflight | 25 | 40 | Grok |
| T0.2 scaffold | 35 | 60 | Grok |
| T1.1 encoding primitives | 30 | 45 | Grok |
| T1.2 atomicReplace + fs utils | 30 | 45 | Sol |
| T2.1 SoT core | 50 | 75 | Sol |
| T2.2 deletes + trash | 35 | 55 | Sol |
| T3.1 bookkeeper formats | 35 | 55 | Grok |
| T3.2a bookkeeper mirror + ops | 40 | 60 | Sol |
| T3.2b bookkeeper recovery | 30 | 45 | Sol |
| T4.1 SoT kit + G1 evidence | 35 | 55 | Grok |
| T4.2a corruption tests | 25 | 40 | Sol |
| T4.2b cancellation + cross-instance | 25 | 40 | Sol |
| T4.3 bookkeeper kit | 35 | 55 | Grok |
| T5.1 sample | 35 | 50 | Grok |
| T5.2 README + KDoc | 40 | 60 | Grok |
| T6.1 CI wiring | 20 | 35 | Grok |
| T6.2 sweep | 45 | 80 | Sol |
| T7.1 PR body | 20 | 30 | Grok |
| **Sum (serial agent time)** | **590** | **925** | |
| Contingency pool (orchestrator-gated) | 90 | — | |
| T6.2b subset fallback (only via G1) | 30 | 45 | Sol |
| Orchestrator overhead (reviews, gates, commits, G2, CI watch) | ~150 | — | Fable |

Serial execution is the default: ~590 soft minutes of agent time plus orchestrator overhead.
Under worktree parallelization (§1), lane B (T3.1–T3.2b, 105) overlaps lane A (T2.1–T2.2, 85),
T4.3 overlaps T4.2a/b, and T1.1 ∥ T1.2 — an elapsed spine of roughly 420–470 soft minutes, the
spread being worktree merge overhead.

## 11. Risk register (execution risks; design risks live in design §8)

| Risk | Signal | Response |
|---|---|---|
| Cold-toolchain time in T0.1/T0.2 | download progress dominating the log | §2 download exemption; wait it out once, cached after |
| js/wasmJs kit failures | T4.1 lane output | Gate G1 protocol; run the standalone kotlinx-io repro with CI-equivalent flags before condemning the platform; never debug kotlinx-io internals past that step |
| Stale BCV dumps failing mid-plan builds | `apiCheck` failure in any `:file:build` | The §1 BCV rule: only T0.2/T2.1/T3.2a run `apiDump`; a failure elsewhere means a task changed public API it did not own — treat as review rejection, not a dump refresh |
| Local JS graph differs from CI | lock-task errors mentioning project graph | Always pass `-Dorg.gradle.configureondemand=false` on js/wasm/multi-target runs (§1); do not chase phantom platform bugs first |
| Parallel-mode half-written sources breaking builds | compile errors in files outside the failing task | Only run parallel lanes in isolated worktrees (§1); in the shared checkout, serial only |
| Sub-agent invents public API | `file/api/` diff in review | Reject at review (checklist); re-dispatch with D4 quoted |
| runTest + Turbine timeouts on slow IO lanes | flaky `awaitItem` timeouts in CI but not locally | Prefer the kits' own `turbineScope`/`testIn` patterns; a genuine slow-lane timeout is a design-gap escalation, not a sprinkled-timeout fix |
| Windows-only semantics regress silently | none available (no mingw runner) | Accepted repository-wide posture; README states it; do not spend budget on local Wine/mingw runners |

## 12. Definition of done

1. Every §6 task accepted; G0–G3 green; contract kits green on jvm, linuxX64, js, wasmJs locally
   (minus lanes G1 removed, with T6.2b executed and recorded) and android/apple lanes green on
   PR CI.
2. The §6.T6.2 allowlist check prints nothing: the diff against `BASE` touches only `file/**`,
   `file/sample/**`, `settings.gradle`, `gradle/libs.versions.toml`,
   `.github/workflows/store6.yml`, and `docs/designs/**` (amendment log in the PR when the
   design changed during execution).
3. `git diff --exit-code "$BASE"...HEAD -- core testing` and `:core:apiCheck` +
   `:testing:apiCheck` all pass — the zero-core-diff claim is command evidence, not assertion.
4. The PR body maps every design decision D1–D15 to its implementing commit or names it as
   unexercised (D3 fallback path when G1 stayed green), lists the G2 evidence command output,
   and records the two upstream action items (mingwX64 ANSI paths; any G1-condemned lane).
