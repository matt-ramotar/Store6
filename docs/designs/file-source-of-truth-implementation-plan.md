# File source of truth — implementation plan

Status: proposed; revised after two independent adversarial reviews.
Executes: [file-source-of-truth-design.md](./file-source-of-truth-design.md) (the design authority —
this plan adds no design decisions; where a task appears to need one, that is a defect to escalate,
not a gap to fill inline).

## 1. Execution model

- **One orchestrator agent** (Fable) owns: task dispatch, budget enforcement, acceptance review,
  git (all commits and pushes), gate decisions, and design-deviation triage. The orchestrator
  writes no implementation code except during declared takeovers (§9).
- **Sub-agents** (GPT 5.6 Sol and Cursor Grok 4.6) execute tasks. Each sub-agent starts with no
  conversation context: every dispatch prompt must be self-contained (§8 template).
- **Workspace discipline**: all agents share one checkout on one implementation branch (created
  from `store6`; naming per the repository's branch conventions). Tasks running in parallel must
  own disjoint files (each task spec lists owned files). At most one Gradle acceptance run
  executes at a time — the orchestrator serializes them, because concurrent Gradle invocations in
  one project directory contend on configuration-cache and daemon locks. Sub-agents do not commit;
  the orchestrator commits after accepting each task, one commit per task, message shaped like the
  repository's history (imperative subject, body only when it adds information).
- **Environment reality** (verified on the reference VM): JDK 21 present (Gradle toolchains
  auto-provision the JDK 11 toolchain via the foojay resolver), no Android SDK, no `~/.konan`.
  T0.1 exists to close those gaps. macOS lanes (`iosSimulatorArm64Test`, `macosArm64Test`) cannot
  run locally on a Linux VM — they are verified by PR CI (`apple-tests` job), and the plan treats
  that as the acceptance mechanism for those lanes, not a skipped step.

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
- **Two-strike rule**: a task that hits its hard cap twice is never dispatched a third time
  unchanged. The orchestrator re-reads the design section, decides design-gap vs. agent-gap, and
  either amends the design doc (logged in the PR description) or splits the task.
- **Contingency pool**: 90 minutes, spent only by orchestrator decision, tracked in the rollup
  (§10). Gate fallback tasks (T6.2b) have their own budgets outside the pool.

Common causes of budget overrun, named so agents avoid them: cold Kotlin/Native toolchain
downloads (front-loaded into T0.1/T0.2 on purpose); running `:file:build` (all 12 targets) when
the acceptance column names a single-lane task like `:file:jvmTest`; re-deriving repository
conventions instead of reading the context files (§4); writing tests for behavior the kits
already cover.

## 3. Model assignment

| Agent | Use for | Reason |
|---|---|---|
| GPT 5.6 Sol | T1.2, T2.1, T2.2, T3.2, T4.2, T6.2 | Concurrency-sensitive code: the D9 shield nesting, signal registry, recovery semantics. These tasks have the most ways to be subtly wrong. |
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
   (`InMemoryBookkeeper` — bookkeeper semantic reference).
4. For test tasks: `testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/SourceOfTruthContractKit.kt`
   and `BookkeeperContractKit.kt`, plus one wired example
   (`room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomSourceOfTruthContractTest.kt`).
5. For build/CI tasks: `settings.gradle`, `gradle/libs.versions.toml`,
   `realtime/build.gradle.kts`, `room/gradle.properties`, `.github/workflows/store6.yml`,
   `tooling/plugins/src/main/kotlin/org/mobilenativefoundation/store/tooling/plugins/` (all three
   plugin files).
6. For documentation tasks: `AGENTS.md`, both skills under
   `plugins/internal/documentation/skills/`, and `room/README.md` + `realtime/README.md` as shape
   precedents.

Standing constraints (also pasted verbatim into every prompt):

- Zero diff outside `file/`, `file/sample/`, `settings.gradle`, `gradle/libs.versions.toml`, and
  `.github/workflows/store6.yml`. Never touch `core/`, `testing/`, any other module, the seam
  package, `STABILITY.md`, or `.github/docs-sync-sources.txt`.
- Production source sets must not contain `runBlocking`, `GlobalScope`, `atomicfu`, `Channel`, or
  `actor` (the TD-8 CI regex runs against `file/src/*Main`).
- `explicitApi()` is on: every public declaration needs an explicit visibility and return type.
- No public API beyond design §D4. New public symbols require orchestrator approval first.
- No dependencies beyond `projects.core`, `libs.kotlinx.io.core` (main) and `projects.testing`,
  `libs.kotlinx.coroutines.test`, `libs.turbine` (test).
- Kotlin only; match the file layout `file/src/<sourceSet>/kotlin/org/mobilenativefoundation/store6/file/`.

## 5. Task DAG

```
T0.1 ── T0.2 ──┬── T1.1 ──┬───────────────┬── T2.1 ── T2.2 ──┬── T4.1(G1) ── T4.2 ─┐
               └── T1.2 ──┘               │                   │                     ├─ T6.2(G3) ── T7.1
                                          └── T3.1 ── T3.2 ───┴── T4.3 ─────────────┤
                          T5.1 (after T2.2 + T3.2) ── T5.2 ──────────────────────────┤
                          T6.1 (any time after T0.2) ────────────────────────────────┘
```

Parallelism: T1.1 ∥ T1.2; then lane A (T2.x → T4.1/T4.2) ∥ lane B (T3.x → T4.3) ∥ lane C
(T6.1, later T5.x). Lane C's T5.x waits on both implementation lanes because the sample and README
consume the final API.

## 6. Task specifications

Budget notation: soft/hard minutes. "Acceptance" commands are run by the sub-agent and re-run by
the orchestrator before commit (orchestrator may batch re-runs, §1). Native-inclusive builds pass
`-Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true`
like every CI module build.

---

### T0.1 — Preflight and toolchain provisioning — 20/35 — Grok

- **Owns**: no repository files (environment only).
- **Steps**: verify JDK ≥ 17 on PATH; provision the Android SDK (commandline-tools; then
  `sdkmanager "platforms;android-36" "build-tools;36.0.0"` and license acceptance; export
  `ANDROID_HOME`); warm Gradle with `./gradlew :core:compileKotlinJvm`. Kotlin/Native toolchain
  downloads are deferred to T0.2's first `:file:build` — do not pre-download.
- **Acceptance**: `./gradlew :core:compileKotlinJvm` succeeds; `sdkmanager --list_installed`
  shows platform 36.
- **Fallback (named)**: if the Android SDK cannot be provisioned in budget, record
  "no-Android-SDK mode" with the orchestrator: every later `:file:build` acceptance is replaced
  by the target-scoped set `:file:jvmTest :file:compileKotlinLinuxX64 :file:linuxX64Test
  :file:jsNodeTest :file:wasmJsNodeTest` plus `:file:apiDump`, and Android compilation is
  verified by PR CI. This costs CI round-trips, not correctness.

### T0.2 — Module scaffold — 35/60 — Grok

- **Owns**: `settings.gradle`, `gradle/libs.versions.toml`, `file/build.gradle.kts`,
  `file/gradle.properties`, `file/src/androidMain/AndroidManifest.xml`, empty source tree.
- **Steps**: apply design D2 exactly (includes, catalog entries with the exact names
  `kotlinxIo` / `kotlinx-io-core`, full convention plugin per D3, `api(projects.core)` +
  `api(libs.kotlinx.io.core)` in `commonMain`, test deps in `commonTest`, `android { namespace =
  "org.mobilenativefoundation.store6.file" }`, `<manifest />`). Add one placeholder internal
  declaration so every source set compiles. Run `:file:apiDump` to create the initial (empty)
  BCV dumps.
- **Acceptance**: `./gradlew :file:build` (with the native flags) green — this is the cold-konan
  run, hence the 60 hard cap; `file/api/` contains `jvm/file.api`, `android/file.api`,
  `file.klib.api`.
- **Gate G0** passes when acceptance is green: toolchain proven for every later task.

### T1.1 — Encoding primitives: base32, CRC32, envelope, name mapping — 30/45 — Grok

- **Owns**: `file/src/commonMain/.../internal/Base32.kt`, `internal/Crc32.kt`,
  `internal/Envelope.kt`, `internal/FileNames.kt`; mirror test files in `commonTest`.
- **Steps**: implement per D6/D7/D12: lowercase unpadded RFC 4648 base32 with the `""` → `"0"`
  sentinel; table-driven CRC32 (IEEE); envelope writer/reader parameterized by magic (`S6FV`,
  `S6FB`, `S6FW`) returning a structural-corruption verdict distinct from IO failure; the
  159-byte limit check with the D6 exception message shape; `(namespace, canonicalId) → Path`
  mapping and the `.corrupt` sibling-name helper. Everything `internal`.
- **Acceptance**: `./gradlew :file:jvmTest` green with: RFC 4648 vectors, sentinel properties,
  CRC check value `0xCBF43926` for `"123456789"`, envelope round-trip + each corruption class,
  limit rejection at 160 / acceptance at 159.
- **Out of scope**: any suspend code, any `SystemFileSystem` call (pure byte/string logic only —
  keeps this task parallel-safe with T1.2).

### T1.2 — `atomicReplace` and filesystem utilities — 30/45 — Sol

- **Owns**: `file/src/commonMain/.../internal/FileOps.kt` (expect declarations + shared
  utilities), actuals in `jvmMain`, `androidMain`, `nativeMain`, `jsMain`, `wasmJsMain`; tests in
  `commonTest` + `androidUnitTest`.
- **Steps**: `internal expect fun atomicReplace(source: Path, destination: Path)` — every actual
  delegates to `SystemFileSystem.atomicMove` except android, which catches the API 24–25
  `UnsupportedOperationException` and falls back to `java.io.File.renameTo` (throwing
  `IOException` on `false`), with the fallback body extracted as a directly-testable internal
  function (D9). Shared utilities: ensure-directories, unique tmp/trash name generator
  (monotonic counter + random), best-effort recursive purge, best-effort first-operation sweep —
  all absorbing failures per D10, none holding public API.
- **Acceptance**: `./gradlew :file:jvmTest` (atomicReplace replaces existing destination;
  utilities round-trip in a temp dir) and `./gradlew :file:testDebugUnitTest` (fallback branch
  test) green. If T0.1 fell back to no-Android-SDK mode, the android lane defers to PR CI and the
  orchestrator notes it.
- **Out of scope**: mutex/shield logic (T2.1), any public type.

### T2.1 — `FileSourceOfTruth`: reader, signals, write, delete — 50/75 — Sol

- **Owns**: `file/src/commonMain/.../FileSourceOfTruth.kt`, `FileCodec.kt` (interface + the two
  built-in codecs), `FileCorruptionPolicy.kt`; smoke tests in `commonTest`
  (`FileSourceOfTruthSmokeTest.kt`).
- **Steps**: implement D4 signatures byte-for-byte (annotations included), the D8 signal registry
  (copy the CAS acquire/release shape from `SqlDelightSourceOfTruth`), the D9 pipeline with the
  exact shield nesting (`mutex.withLock { withContext(NonCancellable) { withContext(io) { … };
  bump } }`), `ioContext.minusKey(Job)`, the D6 absent-path rule, structural corruption checks
  under the mutex, decode outside with `CancellationException` rethrow and the QUARANTINE
  re-check protocol (D7 — the byte-snapshot comparison as a pure internal function), temp-file
  writes through T1.2's utilities. `delete(key)` per D10 (canonical delete decides the outcome;
  `.corrupt` cleanup best-effort afterward).
- **Acceptance**: `./gradlew :file:jvmTest` green with smoke tests: write→read round-trip,
  delete→null, equal-value rewrite re-emits, absent-path first emission, oversized key throws
  `IllegalArgumentException` without touching disk.
- **Out of scope**: `deleteNamespace`/`deleteAll` bodies (throw `NotImplementedError` until T2.2
  — smoke tests must not cover them), bookkeeper, kit wiring.

### T2.2 — Namespace/all deletes, trash lifecycle, sweep — 35/55 — Sol

- **Owns**: same files as T2.1 (lane-serial, no conflict) plus
  `commonTest/.../FileTrashLifecycleSmokeTest.kt`.
- **Steps**: D10 exactly: trash-rename with unique-token names, bump-only success on absent
  directories, `deleteAll` on missing `values/`, first-operation sweep wiring, purge best-effort.
- **Acceptance**: `./gradlew :file:jvmTest` green with: deleteNamespace isolation across
  namespaces, second consecutive `deleteAll`, delete of never-written namespace, sweep removes a
  planted stale tmp file and trash directory, rename failure (planted read-only trash parent —
  jvm-only test) leaves values intact.
- **Out of scope**: contract kit (T4.1 owns it).

### T3.1 — Bookkeeper persistence layer: record + watermark codecs — 35/55 — Grok

- **Owns**: `file/src/commonMain/.../internal/BookkeeperFormats.kt` + `commonTest` mirror.
- **Steps**: the two D12 payload tables exactly (flag bits, field widths, absent-fields-omitted,
  bit-1-without-bit-0 invalid), over T1.1's envelope with magics `S6FB`/`S6FW`. Pure byte logic,
  no IO — parallel-safe with T2.x.
- **Acceptance**: `./gradlew :file:jvmTest` green: round-trip every flag combination, reject the
  invalid flag state, watermarks round-trip with 0 and N namespaces.

### T3.2 — `FileBookkeeper` — 45/70 — Sol

- **Owns**: `file/src/commonMain/.../FileBookkeeper.kt`,
  `commonTest/.../FileBookkeeperSmokeTest.kt`.
- **Steps**: D12 in full: port `InMemoryBookkeeper`'s record/mirror semantics (including the
  sequence-exhaustion `check` and `watermarkOnlyStatus` shape), first-operation recovery scan
  with absent-paths-as-empty, corrupt-record quarantine-and-skip, corrupt-watermarks →
  `recoveredMax + 1` global watermark, write-through with mirror-first for infallible operations
  (absorbing non-cancellation `Exception`s), persist-first for fallible maintenance operations,
  trash-rename forgets, D9 shield shape for all disk writes.
- **Acceptance**: `./gradlew :file:jvmTest` green with smoke tests: success→status round-trip,
  failure streak counting, markStale, watermark advance, forget leaves watermarks, reopen
  preserves state and sequence monotonicity.
- **Out of scope**: kit wiring (T4.3).

### T4.1 — SoT contract kit + degenerate/restart tests — 35/55 — Grok — **carries gate G1**

- **Owns**: `file/src/commonTest/.../FileSourceOfTruthContractTest.kt`,
  `FileSourceOfTruthRestartTest.kt`, `FileDegenerateShapeTest.kt`.
- **Steps**: wire `SourceOfTruthContractKit<TestKey, String>` per the Room example (fresh
  `SystemTemporaryDirectory`-rooted directory per `createSourceOfTruth()`); design §5.2 restart
  tests and §5.5 degenerate-shape tests (empty namespace, empty id, both; `deleteAll` twice;
  fresh-directory mutations).
- **Acceptance**: `./gradlew :file:jvmTest :file:linuxX64Test` green, then **G1 evidence run**:
  `./gradlew :file:jsNodeTest :file:wasmJsNodeTest`. Report each lane's verdict separately.
- **Gate G1** (orchestrator decision, ≤ 15 min): all four lanes green → full 12-target matrix
  confirmed, proceed. A js/wasmJs failure that reproduces on a trivial kotlinx-io repro (write +
  atomicMove + read in a temp dir, no adapter code) → invoke design D3 fallback via T6.2b. A
  failure that does not reproduce standalone is an adapter bug: fix within lane A budgets.

### T4.2 — Corruption, cancellation, cross-instance tests — 40/60 — Sol

- **Owns**: `file/src/commonTest/.../FileCorruptionTest.kt`, `FileCancellationTest.kt`,
  `FileCrossInstanceTest.kt` (+ a jvm-only source-set test where byte-planting needs
  `java.io`-level control, if common APIs cannot plant the fixture).
- **Steps**: design §5.3 (truncation/magic/CRC/decode-throw × both policies, quarantine re-check
  pure-function cases, quarantined-file-not-reread, decode `CancellationException` propagation),
  §5.6 (pre-admission cancellation applies nothing; post-admission cancelled caller still
  completes + notifies + returns normally), §5.8 (instance-scoped signals).
- **Acceptance**: `./gradlew :file:jvmTest :file:jsNodeTest` green.

### T4.3 — Bookkeeper contract kit + restart/corruption tests — 35/55 — Grok

- **Owns**: `file/src/commonTest/.../FileBookkeeperContractTest.kt`,
  `FileBookkeeperRestartTest.kt`, `FileBookkeeperCorruptionTest.kt`.
- **Steps**: wire `BookkeeperContractKit`; §5.2 bookkeeper restart tests (watermark-vs-success
  ordering across reopen); §5.3 bookkeeper corruption tests (corrupt record + intact watermark →
  watermark-only status; corrupt watermarks → `recoveredMax + 1`, every surviving success stale).
- **Acceptance**: `./gradlew :file:jvmTest :file:linuxX64Test :file:jsNodeTest` green.

### T5.1 — Sample module — 35/50 — Grok

- **Owns**: `settings.gradle` (two sample lines), `file/sample/build.gradle.kts`,
  `file/sample/src/main/kotlin/.../Main.kt`.
- **Steps**: design §7 — JVM `application` (the `room/sample` build shape, minus KSP), a
  scripted `main` that asserts the four behaviors (persist, serve-without-refetch after rebuild,
  durable invalidation, corruption recovery is optional) and exits nonzero on failure, storing
  under a temp directory it cleans up.
- **Acceptance**: `./gradlew :file-sample:run` exits zero; a deliberately broken assertion exits
  nonzero (verify once, then restore).

### T5.2 — README + KDoc audit — 40/60 — Grok

- **Owns**: `file/README.md`; KDoc-only edits inside `file/src/commonMain`.
- **Steps**: design §7 README contents (every named disclosure: durability incl. power-loss undo,
  single-instance rule, key limits + empty-string handling, corruption handling, no
  `TransactionalSourceOfTruth` + mutations consequence, Android fallback, mingwX64 ANSI paths,
  js/wasmJs event-loop blocking; install snippet; codec recipe; kit instructions; sample
  command). Then one KDoc audit pass over the public surface against the discipline skills.
  **This task must follow `AGENTS.md` and both documentation skills, including the three-pass
  review; the dispatch prompt links them as required reading.**
- **Acceptance**: orchestrator review against design §7's checklist (every listed disclosure
  present, no unsupported claim, no internal process references); `./gradlew :file:jvmTest`
  still green (KDoc edits cannot change behavior — reject any non-comment diff).

### T6.1 — CI wiring — 20/35 — Grok

- **Owns**: `.github/workflows/store6.yml`.
- **Steps**: design §6 table, exactly: two `linux-build-test` steps (placed after the realtime
  steps, same flag shape), the module list in "Reject core-internal access from extension
  modules" (add `file` and `file/sample`), `production_source_dirs` in "Enforce the TD-8
  primitive whitelist and single-writer residence" (add `file/src/*Main`), the "JS
  lock-discipline canary (full conformance suite on the JS lane)" task list (add
  `:file:jsNodeTest`), the two `apple-tests` entries, the `klib-publication-check` publish
  command + `modules=(...)` array.
- **Acceptance**: `git diff` review against the design table (orchestrator); local execution of
  the two grep-based checks' bodies against the working tree (they are plain bash — run them
  verbatim) both pass.

### T6.2 — Full local gate sweep + BCV dumps — 35/60 — Sol — **carries gate G3**

- **Owns**: `file/api/` dumps; no source files (fix-forward diffs go back to the owning lane's
  agent or are taken over if trivial).
- **Steps**: `./gradlew :file:apiDump` and commit-ready dumps; then the sweep, serialized:
  `./gradlew :file:build` (native flags), `./gradlew :file-sample:run`,
  `./gradlew :file:jsNodeTest :file:wasmJsNodeTest`, the two grep gates (as in T6.1),
  `git diff --stat store6` confirming the touched-file set is exactly §4's allowed list, and
  `./gradlew :core:apiCheck :testing:apiCheck` proving the zero-core-diff claim.
- **Gate G3**: all green → T7.1. Any red: route to the owning task's agent with the failure
  output; re-run only the failed lane after the fix.

### T6.2b — Contingency: D3 subset fallback — 30/45 — Sol — **only if G1 invokes it**

- **Owns**: `file/build.gradle.kts`, `.github/workflows/store6.yml` (klib-check `case`
  exceptions), `file/README.md` target list, `file/api/file.klib.api` regeneration.
- **Steps**: switch to the subset convention plugin; declare the 10–11 surviving targets with a
  build-file comment naming the exact kotlinx-io failure (the `room` comment shape); add the
  publication-check `case` exceptions; update README targets; re-run `apiDump`; file the upstream
  kotlinx-io issue and link it in the PR description (not in source comments).
- **Acceptance**: `./gradlew :file:build` green on the reduced matrix; klib-check bash body
  passes locally.

### T7.1 — PR assembly — 20/30 — Grok

- **Owns**: PR title/body only.
- **Steps**: PR against `store6` following `pull_request_template.md`: closes/relates
  [#533](https://github.com/MobileNativeFoundation/Store/issues/533) context, description mapping
  commits to design sections, test-plan section listing every acceptance command with its result,
  the zero-core-diff statement with the `apiCheck` evidence, G1's verdict (full matrix or named
  subset + upstream issue link). Mark draft until CI (`store6.yml` all jobs, including
  `apple-tests`) is green; the orchestrator watches CI and routes failures like G3.
- **Acceptance**: CI green on the PR; every checklist item in the template answered honestly.

## 7. Gates (orchestrator-owned decision points)

| Gate | After | Question | Green path | Red path |
|---|---|---|---|---|
| G0 | T0.2 | Does the full 12-target module build here? | Phase 1 dispatch | Fix scaffold within T0.2 budget; environment gaps → T0.1 fallback mode |
| G1 | T4.1 | Do js/wasmJs kit lanes pass on real filesystem? | Keep 12 targets | Standalone kotlinx-io repro fails → T6.2b; repro passes → adapter bug, stay in lane A |
| G2 | T4.1+T4.2+T4.3 | All kits + targeted tests green on jvm, linuxX64, js, wasmJs? | T5.x/T6.2 | Route failures to owning lane; two-strike rule applies |
| G3 | T6.2 | Full sweep green, diff-set exact, dumps committed? | T7.1 | Route to owning lane; re-sweep failed lanes only |

## 8. Dispatch prompt template (orchestrator fills the brackets)

```
Task [ID] — [name]. You are implementing part of the Store 6 `file` adapter.

Read first (do not skip): [design doc path + named D-sections]; [context files from §4 for this
task type]. The design document is authoritative; if a needed decision is not in it, STOP and
report the gap instead of inventing.

You own exactly these files: [list]. Do not create, edit, or delete any other file.

Standing constraints: [paste §4 constraint block].

Steps: [task Steps].

Acceptance (run yourself before reporting done): [task Acceptance commands].

Budget: [soft] minutes soft / [hard] hard. At [soft] minutes, report status (done-ness, blocker,
next action) and continue only if no blocker. Do not run Gradle tasks other than the acceptance
commands and single-target compile checks.

Report back: files changed; acceptance command output (verbatim tail); any deviation from the
design and why; open questions.
```

## 9. Acceptance review checklists (orchestrator, per task type)

**Implementation tasks** (T1.x, T2.x, T3.x): acceptance commands re-run green; diff touches only
owned files; no banned primitive (`grep -rnE '(^|[^[:alnum:]_])(runBlocking|GlobalScope|atomicfu|Channel|actor)([^[:alnum:]_]|$)' file/src/commonMain file/src/*Main`);
public surface unchanged from D4 (`./gradlew :file:apiDump && git diff file/api/` shows only
expected additions); the D9 shield shape present where the task claims it (read the code, not the
summary); KDoc on public symbols states the contracts the design assigns them.

**Test tasks** (T4.x): tests assert the design's behavior, not the implementation's (a test that
merely mirrors internals is rejected); kit subclasses override nothing but the four factory
members; every §5 bullet the task claims is present; failure messages would identify the broken
invariant.

**Docs tasks** (T5.2, T7.1): three-pass review per the discipline skill (accuracy against source,
warrant for every claim, reader utility); no internal-process references in source surfaces; all
protected tokens (versions, paths, commands, limits) verified against the code.

**Takeover threshold**: if a review finds < ~10 minutes of remedial work, the orchestrator fixes
and notes it in the commit; otherwise it re-dispatches with the review findings.

## 10. Budget rollup

| Task | Soft | Hard | Suggested agent |
|---|---|---|---|
| T0.1 preflight | 20 | 35 | Grok |
| T0.2 scaffold | 35 | 60 | Grok |
| T1.1 encoding primitives | 30 | 45 | Grok |
| T1.2 atomicReplace + fs utils | 30 | 45 | Sol |
| T2.1 SoT core | 50 | 75 | Sol |
| T2.2 deletes + trash | 35 | 55 | Sol |
| T3.1 bookkeeper formats | 35 | 55 | Grok |
| T3.2 FileBookkeeper | 45 | 70 | Sol |
| T4.1 SoT kit + G1 evidence | 35 | 55 | Grok |
| T4.2 corruption/cancel/cross-instance | 40 | 60 | Sol |
| T4.3 bookkeeper kit | 35 | 55 | Grok |
| T5.1 sample | 35 | 50 | Grok |
| T5.2 README + KDoc | 40 | 60 | Grok |
| T6.1 CI wiring | 20 | 35 | Grok |
| T6.2 sweep + dumps | 35 | 60 | Sol |
| T7.1 PR | 20 | 30 | Grok |
| **Sum (sequential agent time)** | **540** | **845** | |
| Contingency pool (orchestrator-gated) | 90 | — | |
| T6.2b subset fallback (only via G1) | 30 | 45 | Sol |

With the §5 lanes, the critical path is T0.1 → T0.2 → T1.x → T2.1 → T2.2 → T4.1 → T4.2 → T6.2 →
T7.1 ≈ 295 soft minutes of agent time plus orchestrator review/serialization overhead; lanes B and
C overlap it. Orchestrator time (reviews, gates, commits, CI watching) is budgeted at ~25% of
dispatched agent time and comes out of no task budget.

## 11. Risk register (execution risks; design risks live in design §8)

| Risk | Signal | Response |
|---|---|---|
| Cold-toolchain time blows T0.2 | konan download dominating the log | Hard cap already sized for it; do not re-dispatch for a slow download — wait it out once, it is cached after |
| js/wasmJs kit failures | T4.1 lane output | Gate G1 protocol; never debug kotlinx-io internals past the standalone-repro step |
| Parallel agents both edit `settings.gradle` | merge conflict at commit time | Only T0.2 and T5.1 may touch it, and they are ordered; orchestrator enforces ownership lists at review |
| Android lane unverifiable locally | T0.1 fallback mode active | Accept per-task with the deferred lanes named; PR CI is the acceptance of record for them |
| Sub-agent invents public API | `file/api/` diff in review | Reject at review (checklist); re-dispatch with D4 quoted |
| runTest + Turbine timeouts on slow IO lanes | flaky `awaitItem` timeouts in CI but not locally | Prefer `turbineScope`/`testIn` patterns the kits already use; if a genuine slow-lane timeout appears, raise it as a design-gap escalation rather than sprinkling timeout overrides |
| Windows-only semantics regress silently | none available (no mingw runner) | Accepted repository-wide posture; README states it; do not spend budget attempting local Wine/mingw runners |

## 12. Definition of done

1. Every §6 task accepted; G0–G3 green; contract kits green on jvm, linuxX64, js, wasmJs locally
   and android/apple lanes green on PR CI.
2. `git diff store6 --stat` touches exactly: `file/**`, `file/sample/**`, `settings.gradle`,
   `gradle/libs.versions.toml`, `.github/workflows/store6.yml`, plus the two documents this plan
   belongs to if amended (with the amendment log in the PR).
3. `:core:apiCheck` and `:testing:apiCheck` pass unchanged — the zero-core-diff claim is CI
   evidence, not assertion.
4. The PR body maps every design decision D1–D15 to its implementing commit or names it as
   unexercised (D3 fallback path when G1 stayed green).
