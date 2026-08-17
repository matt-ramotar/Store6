# Ktor fetcher kit — implementation plan

Companion to `ktor-fetcher-kit-technical-design.md`. This plan is executed by one orchestrator agent
(Fable) directing sub-agents (GPT-5.6 Sol and Cursor Grok 4.6). It is granular enough that a
sub-agent can pick up a single task from its entry plus the design doc, and each task carries a
**time budget** and a **tripwire** so the orchestrator can keep agents focused and catch a stuck
task early.

Revision note: this version was rewritten after two adversarial reviews (Sol, Grok) that ran Gradle
against the existing `graphql` module on a Linux host and found the first draft's Phase 0 gate was
false (Apple targets cannot be linked or tested on Linux; `apiCheck` runs inside `build` and fails
with no committed dump), that parallel edits had no isolation, and that several task dependencies
and Gradle task names were wrong. Appendix B maps each finding to its fix.

Section order: how to read the plan and the budget system, then the guardrails and the host-capability
reality every sub-agent must obey, then concurrency isolation, then the dependency graph, then the
phased tasks, then checkpoints, gates, risks, and rollback.

---

## 1. How to use this plan

- **The design doc is the specification.** Every task references design sections; the sub-agent
  reads them before coding. Where the design and a task disagree, the sub-agent stops and escalates.
- **Phases gate.** Phase N+1 waits on Phase N's exit criteria, except where
  [§5 concurrency](#5-concurrency-and-isolation) marks tasks concurrent.
- **The spike is a hard gate.** No CI wiring happens until T0.1 pins a Ktor version on evidence.
- **Zero core diff is invariant.** No task edits `core/**`. A task that seems to need a core change
  is an escalation, not a workaround.
- **Record the base SHA once.** At kickoff the orchestrator records the merge-base:
  `BASE=$(git merge-base origin/store6 HEAD)`. Every path-diff check uses that exact SHA, since the
  work happens on a feature branch, not on `store6`.

### 1.1 A note on "time budgets"

Budgets are **agent-execution budgets** — active wall-clock for the agent doing the task, including
the Gradle/Kotlin builds it runs — not human calendar estimates. Kotlin/Native (konan) builds
dominate several budgets: the first native build on a cold machine downloads a toolchain and
compiles per target, which can take many minutes each. Build-heavy tasks therefore carry large
budgets and generous tripwires; a warm `~/.konan` hits the low end.

## 2. Roles and model assignment

| Role | Model | Responsibilities |
|---|---|---|
| Orchestrator | Fable | Dispatches tasks, enforces budgets/tripwires, runs checkpoints, adjudicates the spike, makes the naming and HttpCache-default decisions, owns branch/worktree integration, does the final review and the PR. Only authority to accept a phase exit. |
| Implementer A | GPT-5.6 Sol | Correctness- and judgment-heavy work: `KtorFetcher` core mapping, the validator-token codec, Store integration tests, the README, the spike decision. |
| Implementer B | Cursor Grok 4.6 | Mechanical, high-throughput work: module/sample skeletons, build/catalog wiring, MockEngine transport tests, sample scenes, API-dump generation, CI list edits. |

Rationale: Sol takes tasks where a wrong decision is expensive and hard to detect (HTTP-semantics
mapping, 304 branches, the token no-flip rule); Grok takes tasks verifiable by a command (does it
build? does the dump match? does the gate grep pass?). The orchestrator never hands a sub-agent a
decision the design left open — it resolves the decision first ([§9](#9-orchestrator-checkpoints)).

## 3. The budget and tripwire system

Each task carries a **focus budget** (target active wall-clock; running past it is a check-in
signal, not yet a failure) and a **tripwire** (hard cap; on reaching it the sub-agent stops and
hands back). A soft cap of ~40 tool calls without a passing verification command triggers the same
stop-and-report.

Escalation when a tripwire fires:

1. The sub-agent posts: task ID, last passing step, the failing command and its **verbatim** output,
   and its best hypothesis.
2. The orchestrator triages: reassign to the other model, split the task, relax scope per a design
   open-decision, or invoke a design fallback (most often the spike's version ladder,
   [design §13.1](ktor-fetcher-kit-technical-design.md)).
3. Budgets are not silently extended; a deliberate extension is a new, logged decision.

Definition of done (every task unless its entry overrides): the verification command runs clean and
its **evidence is pasted** into the handoff, where evidence means the exact command, the **commit
SHA**, the **host OS** (`uname -s`), the exit code, and — for Gradle — the task outcome list
(no required task `SKIPPED`); no `core/**` file changed; any copied byte-identical block (the
`runTest` shim) is verbatim; new public API is `@ExperimentalStoreApi`. Non-code tasks (decisions,
citation checks) are exempt from the compile requirement and say so.

## 4. Guardrails for every sub-agent

1. **Scope.** Edit only `ktor/**`, `settings.gradle`, `gradle/libs.versions.toml`, and
   `.github/workflows/store6.yml` (plus the two design docs, orchestrator-only). Never `core/**`,
   the seam package, or another module.
2. **No `core.internal`, no `InternalStoreApi`.** Public seam APIs only; the private `Fetcher`
   implementation is `@OptIn(DelicateStoreApi::class)` (as `graphql`'s is).
3. **No banned concurrency primitive** in production sources: `runBlocking`, `GlobalScope`,
   `atomicfu`, `Channel`, `actor` (the TD-8 grep). The kit needs none.
4. **Required Gradle command prefix.** Run every Gradle command with configuration-on-demand off
   and the two CI flags, matching `.github/workflows/store6.yml`:
   ```
   ./gradlew <tasks> \
     -Dorg.gradle.configureondemand=false \
     -Pkotlin.native.enableKlibsCrossCompilation=true \
     -Pkotlin.apple.xcodeCompatibility.nowarn=true
   ```
   The configure-on-demand flag is mandatory for any command that touches `jsNodeTest`/`wasmJsNodeTest`
   (the JS/Wasm lock tasks need one complete project graph; the repo default is on, and CI overrides
   it via `GRADLE_OPTS`).
5. **Paste evidence** ([§3](#3-the-budget-and-tripwire-system)); a "done" claim without it is not done.
6. **Copy the `runTest` shim verbatim** from
   `graphql/src/commonTest/.../GraphQlStoreIntegrationTest.kt` lines 251–260 (comment included; the
   `import kotlinx.coroutines.test.runTest as coroutineRunTest` alias is at line 12) when a test
   uses Turbine.
7. **Never push a `settings.gradle` include of `:ktor` without a committed `ktor/api/**` dump.** Once
   `:ktor` is included and pushed, `ci.yml`'s `clean build` builds it and runs `apiCheck`; a missing
   or stale dump fails CI (see [§4.1](#41-the-apicheck-in-build-hazard) and T1.1/T5.3).
8. **Stop at a tripwire** and report; do not improvise a core change or disable a gate.

### 4.1 The `apiCheck`-in-`build` hazard (read before running any `build`)

The Store 6 convention applies binary-compatibility-validator with klib validation and wires each
`*ApiCheck` onto `check` (`tooling/plugins/.../Store6Conventions.kt` lines 36–50), so `:ktor:build`
runs `apiCheck` against the committed `ktor/api/**` golden files. **A missing or stale dump fails
`build`.** Consequences the plan is built around:

- Do not use `:ktor:build`/`:ktor:check` for iteration before a dump exists; iterate with
  `:ktor:compileKotlinJvm` and `:ktor:jvmTest`.
- T1.1 commits an **empty** dump so the skeleton (and CI, once included) is green from the start.
- Every task that changes public API regenerates and commits the dump as part of its DoD, so the
  golden files never go stale on a pushed branch (see T2.2/T2.3/T2.4).
- T5.3 is the final authoritative regeneration.

### 4.2 Host-capability matrix (what a Linux cloud agent can and cannot do)

Verified by the reviews running the `graphql` equivalents on this Linux host. Apple **link and
test** require macOS; the repo CI splits `linux-build-test` (ubuntu) and `apple-tests` (macos) for
exactly this reason (`.github/workflows/store6.yml` lines 61, 454). BCV klib dumps are computed from
klib metadata (target ABI is **inferred** deterministically), so a Linux-generated klib dump matches
what the ubuntu CI job checks.

| Action | Linux cloud agent | Notes |
|---|---|---|
| `:ktor:compileKotlinJvm`, `:ktor:jvmTest` | Yes | No Android SDK needed for these |
| `:ktor:jsNodeTest`, `:ktor:wasmJsNodeTest` | Yes | Requires `-Dorg.gradle.configureondemand=false` |
| `:ktor:linuxX64Test`, `:ktor:mingwX64Test` | Yes | Kotlin/Native downloads the linux/MinGW toolchains |
| Apple **klib compile** (`compileKotlinIosArm64`, `…Macos…`, with the cross-compile flag) | Yes | Compile-only; produces klibs, not test binaries |
| Apple **link/test** (`macosArm64Test`, `iosSimulatorArm64Test`, `linkDebugTest*`) | **No** | macOS + Xcode/simulator only; **CI-only** |
| `:ktor:apiDump` / `:ktor:apiCheck` (JVM + Android + klib) | Yes* | *`androidApiDump` and `:ktor:build`/`publishToMavenLocal` need an Android SDK (`ANDROID_HOME`) |
| `:ktor:build`, `:ktor:publishToMavenLocal` | Only with Android SDK | `com.android.library` is applied unconditionally (`Store6Conventions.kt` line 33); without an SDK these fail "SDK location not found" |

The correct Gradle task names (there is **no** `:ktor:apiDumpKlib` and no `linkDebug*` wildcard):
`apiDump` (aggregates JVM + Android + klib), `apiCheck`, `jvmApiCheck`, `androidApiCheck`,
`klibApiDump`, `klibApiCheck`; per-target link-test tasks are `linkDebugTest<Target>` (Apple ones
macOS-only).

## 5. Concurrency and isolation

Two sub-agents editing the same `ktor/**` tree on one branch at the same time will clobber each
other and contaminate verification (a task whose DoD is the full `:ktor:jvmTest` fails on a sibling's
work-in-progress). The plan therefore uses one of two isolation modes, orchestrator's choice per
concurrent pair:

- **Preferred — worktree per concurrent task.** The orchestrator creates a git worktree/branch per
  concurrent task (see the `using-git-worktrees` skill), each sub-agent commits only its own files,
  and Fable merges in dependency order, resolving conflicts centrally. Gradle runs stay isolated per
  worktree.
- **Lightweight — file ownership + serialized Gradle.** When a worktree is overkill, assign
  disjoint file ownership (e.g. T2.1 owns the codec files only, T2.2 owns the types files only;
  neither touches `build.gradle.kts` concurrently), serialize any `:ktor:jvmTest` runs, and have
  each task commit only its files with a scoped test filter so it cannot fail on a sibling's WIP.

Rules regardless of mode:

- **Only one agent runs a shared-source-set Gradle test task at a time.**
- **Test-task DoDs use a scoped, fully-qualified `--tests` filter** naming that task's classes (the
  class names are mandated in each task), never the whole suite, so a green result reflects only that
  task.
- **`gradle/libs.versions.toml`, `settings.gradle`, and `store6.yml` are single-writer** — never
  edited by two concurrent tasks.

## 6. Dependency graph and parallelization

```
T0.0 env preflight
   └─ T0.1 spike (T0.1a version+jvm/js, T0.1b linux native matrix) ┐
      T0.2 name decision ─────────────────────────────────────────┤ gate
      T0.3 HttpCache API check ─────────────────────────────────── ┤
      T0.4 re-confirm citations ───────────────────────────────────┘
                                    ▼
                         T1.1 module skeleton (+ empty apiDump)
                                    ▼
                             T1.2 sample skeleton
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        ▼(worktree A)               ▼(worktree B)                ▼
   T2.1 token codec            T2.2 public types           (T5.1 README draft)
        └────────────┬──────────────┘
                     ▼
              T2.3 KtorFetcher core
                     ▼
              T2.4 factories + builder ext   ◄── all tests/sample depend on THIS (factory is public; Fetcher is private)
        ┌────────────┼────────────┬───────────────┐
        ▼            ▼            ▼               ▼
   T3.1 transport  T3.2 store   T3.3 HttpCache   T4.1 sample scenes
   (worktree)      integration   rejection
        └────────────┴────────────┴───────────────┘
                     ▼
        Phase 3 exit (JS/Wasm/linux/mingw canary on Linux; Apple tests = CI)
                     ▼
        T5.3 final API dumps ── T6.1 CI wiring ── T7.1 verify ── T7.3 review ── T7.4 PR
```

Concurrency the orchestrator exploits: after T1.1, **T2.1 (Sol)** and **T2.2 (Grok)** run in
parallel worktrees, and **T5.1 README** can draft from the design. After T2.4, **T3.1 / T3.3 / T4.1
(Grok)** and **T3.2 (Sol)** parallelize, but the three Grok tasks serialize on one model and their
Gradle test runs serialize per [§5](#5-concurrency-and-isolation). T5.3 is late and single-owner
(all public API frozen). T6.1's CI-build-step edits land only after T5.3 (guardrail 7).

## 7. Phase 0 — preflight, spikes, decisions (gate)

Exit: environment verified; a Ktor version pinned by evidence on the lanes a Linux agent can run
plus klib compilation of the Apple targets; the artifact name decided; the HttpCache API confirmed;
core citations re-verified.

### T0.0 — Environment preflight
- Owner: Grok · Focus 20m · Tripwire 40m · Depends on: none
- Check and record: `uname -s`, JDK 17 present, `ANDROID_HOME`/SDK present (needed for `build`,
  `publishToMavenLocal`, `androidApiDump`), Node present (for JS), `~/.konan` warm or cold, and that
  the required Gradle flag prefix ([§4](#4-guardrails-for-every-sub-agent)) is applied. If no Android
  SDK, record that `build`/publish/androidApiDump are CI-only on this agent and JVM/JS iteration
  proceeds locally.
- DoD (non-code): a preflight report the orchestrator uses to route tasks; `./gradlew
  :quickstart:help` or `:core:compileKotlinJvm` configures without error.

### T0.1 — Dependency and multiplatform spike (**the gate**)
Split so a false "12 targets on one host" claim is impossible. Apple **test** execution is CI-only;
the spike proves Apple **klib compilation** on Linux and Apple linking is deferred to CI.

**T0.1a — version resolution + JVM/JS**
- Owner: Sol · Focus 45m · Tripwire 75m · Depends on: T0.0
- Design refs: [§13.1](ktor-fetcher-kit-technical-design.md), [§12](ktor-fetcher-kit-technical-design.md).
- In an isolated spike worktree, add catalog entries `ktor`, `ktor-client-core`, `ktor-client-mock`
  at the latest 3.5.x; add a temporary `:ktor` module (`api(projects.core)`,
  `api(libs.ktor.client.core)`) and a temporary `settings.gradle` include; add one file referencing
  `HttpClient` so linking pulls Ktor.
- Run `:ktor:compileKotlinJvm`, `:ktor:jvmTest` (a trivial test), `:ktor:jsNodeTest`,
  `:ktor:wasmJsNodeTest` with the flag prefix. Run `:ktor:dependencies` and record the resolved
  coroutines, **coroutines-test**, serialization, atomicfu, and kotlinx-io versions; confirm
  coroutines and coroutines-test (the latter from `projects.testing` at 1.8.1) end on **one**
  version, adding a **module-local** constraint if not (never change the repo-wide `kotlinxCoroutines`
  catalog key).
- DoD: JVM + JS + Wasm green; the resolved kotlinx stack recorded; the constraint (if any) named.

**T0.1b — Linux native matrix + Apple klib compile + ladder**
- Owner: Sol · Focus 75m · Tripwire 150m · Depends on: T0.1a
- With the flag prefix, run the buildable native lanes on Linux: `:ktor:linuxX64Test`,
  `:ktor:mingwX64Test`, and **compile** the Apple/tvOS/watchOS klibs
  (`compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64`, `compileKotlinMacosArm64`,
  `compileKotlinTvosArm64`, `compileKotlinWatchosArm64`) — compile-only, since Apple link/test is
  macOS/CI-only ([§4.2](#42-host-capability-matrix-what-a-linux-cloud-agent-can-and-cannot-do)).
- If a lane fails, read the actual Ktor klib manifest `abi_version`/`metadata_version`, then walk the
  ladder 3.5.1 → 3.5.0 → newest 3.4.x, re-running only the failing lane.
- DoD: all Linux-runnable native lanes green and all Apple klibs compile; a one-paragraph decision
  note (version chosen, why, any constraint). Apple **test** execution is explicitly deferred to the
  CI apple-tests job and is **not** part of this gate.
- Tripwire action: if no 3.5.x/3.4.x version satisfies the Linux matrix within the tripwire, stop and
  hand the manifest evidence to the orchestrator to decide between a target-subset change (a design
  change needing the subset plugin) or escalating a Kotlin patch bump.

### T0.2 — Artifact-name decision
- Owner: Fable · Focus 10m · Depends on: none · Design ref [§16.1](ktor-fetcher-kit-technical-design.md).
- Decide `ktor` vs `ktor-fetcher` with the maintainer **before** any CI wiring (a rename touches ~8
  lists). Default `ktor`. Record in the PR description.

### T0.3 — Confirm the HttpCache detection API
- Owner: Grok · Focus 15m · Tripwire 30m · Depends on: T0.1a · Design ref [§13.2](ktor-fetcher-kit-technical-design.md).
- Confirm the exact `client.pluginOrNull(HttpCache)` call and the `HttpCache` import path in the
  pinned version. DoD: a two-line snippet compiling in a scratch `jvmTest`.

### T0.4 — Re-confirm core citations
- Owner: Grok · Focus 15m · Tripwire 30m · Depends on: none.
- Re-open each `core/**` and `graphql/**` range in the design's Appendix A; confirm each matches.
  DoD (non-code): confirmation or a drift list handed to the orchestrator to patch the design.

## 8. Phase 1 — module skeleton

Exit: `:ktor` exists, is included, compiles empty on JVM, and has a committed **empty** API dump so
inclusion never reddens CI; `:ktor-sample` exists and runs.

### T1.1 — Module skeleton, catalog, and empty API dump
- Owner: Grok · Focus 45m · Tripwire 90m · Depends on: T0.1, T0.2 · Design ref [§12](ktor-fetcher-kit-technical-design.md).
- Create `ktor/build.gradle.kts` (full convention plugin; `api(projects.core)`,
  `api(libs.ktor.client.core)`; test deps `projects.testing`, `libs.ktor.client.mock`,
  `libs.kotlinx.coroutines.test`, `libs.turbine`), `ktor/gradle.properties`
  (`VERSION_NAME=6.0.0-SNAPSHOT`, `POM_NAME=ktor`, `POM_ARTIFACT_ID=ktor`),
  `ktor/src/androidMain/AndroidManifest.xml` (empty `<manifest/>`), the package dir, and add **only**
  `include ':ktor'` to `settings.gradle` (the sample is added in T1.2 with its `projectDir`).
- Generate and commit the empty dumps: `:ktor:apiDump` with the flag prefix, committing
  `ktor/api/jvm/ktor.api`, `ktor/api/android/ktor.api`, `ktor/api/ktor.klib.api` (empty at this
  point). If the agent has no Android SDK, generate `jvmApiDump` and `klibApiDump` locally and note
  that `androidApiDump` is finalized at T5.3/CI.
- DoD / verify: `:ktor:compileKotlinJvm` exits 0; `:ktor:apiCheck` passes against the committed empty
  dumps (or, without an SDK, `:ktor:jvmApiCheck` + `:ktor:klibApiCheck` pass).

### T1.2 — Sample skeleton
- Owner: Grok · Focus 25m · Tripwire 45m · Depends on: T1.1 · Design ref [§12 sample](ktor-fetcher-kit-technical-design.md).
- Create `ktor/sample/build.gradle.kts` by copying `graphql/sample/build.gradle.kts` and swapping
  names — it must include `application { mainClass.set("org.mobilenativefoundation.store6.ktor.sample.MainKt") }`
  and `implementation(projects.ktor)` **plus `implementation(libs.ktor.client.mock)`** (test deps are
  not exported to the sample). Add to `settings.gradle`: `include ':ktor-sample'` **and**
  `project(':ktor-sample').projectDir = file('ktor/sample')`. Add a `main()` that prints and exits 0.
- DoD / verify: `:ktor-sample:run` exits 0.

## 9. Phase 2 — kit implementation (test-driven)

Exit: the public API from design [§6](ktor-fetcher-kit-technical-design.md) exists, all mapping logic
is implemented, `:ktor:jvmTest` passes for the co-written unit tests, and the committed API dump is
up to date.

### T2.1 — Validator-token codec
- Owner: Sol (worktree A) · Focus 50m · Tripwire 90m · Depends on: T1.1
- Design refs: [§5.2](ktor-fetcher-kit-technical-design.md), [§5.4](ktor-fetcher-kit-technical-design.md).
- Implement encode (ETag verbatim; Last-Modified → `LM:` + date; `lastModifiedFallback` gate) and
  decode (`startsWith("LM:")`/`removePrefix`, never `split`), and response-side selection with the
  **no-flip** rule. Internal API, so no dump change. Test-first, class `ValidatorTokenTest`:
  colon-containing ETag `"a:b"`, weak `W/"x"`, both-headers precedence, 304-with-only-Last-Modified
  keeps the prior ETag, `lastModifiedFallback = false`.
- DoD / verify: `:ktor:jvmTest --tests 'org.mobilenativefoundation.store6.ktor.ValidatorTokenTest'`
  passes (paste summary).

### T2.2 — Public policy, mapper, and error types
- Owner: Grok (worktree B) · Focus 40m · Tripwire 75m · Depends on: T1.1 · Design ref [§6.2](ktor-fetcher-kit-technical-design.md).
- Implement `KtorNotFoundPolicy`, `KtorExchange`, `KtorOutcome` (sealed), `KtorErrorMapper`
  (non-generic `fun interface` + `Default` returning `Defer`), `KtorFetchException` (non-null
  `status`). All `@ExperimentalStoreApi`.
- DoD / verify: `:ktor:compileKotlinJvm` exits 0; a `jvmTest` constructs each type and asserts
  `KtorErrorMapper.Default.map(...) == KtorOutcome.Defer`. **Regenerate and commit `:ktor:apiDump`**
  (public API grew) so the golden files stay valid (guardrail 7 / [§4.1](#41-the-apicheck-in-build-hazard)).

### T2.3 — `KtorFetcher` core
- Owner: Sol · Focus 100m · Tripwire 160m · Depends on: T2.1, T2.2, T0.3
- Design refs: [§7](ktor-fetcher-kit-technical-design.md), [§8](ktor-fetcher-kit-technical-design.md),
  [§9](ktor-fetcher-kit-technical-design.md), [§13.2](ktor-fetcher-kit-technical-design.md).
- Implement the private `Fetcher` (`@OptIn(DelicateStoreApi::class)`): a fresh request per fetch via
  `client.prepareRequest { … }.execute { … }`; `expectSuccess = false`; remove-then-set the
  conditional header, GET/HEAD only; the [§7](ktor-fetcher-kit-technical-design.md) status table
  including the unconditional-304 anomaly and 206/204 handling; `decode` invoked only on an adopted
  2xx inside the execute scope; `CancellationException` re-thrown, other throwables → `Error(cause)`;
  `errorMapper`-before-default precedence; the `HttpCache` fail-fast check (surfaced through the
  factory in T2.4). The `Fetcher` stays a private class in the factory file, as `graphql` does.
- DoD / verify: `:ktor:compileKotlinJvm` exits 0 and one smoke `jvmTest` that **T2.3 owns**
  (`KtorFetcherSmokeTest`: a 200 over MockEngine → `Success`, a conditional 304 → `NotModified`)
  passes. The full transport suite is T3.1 and is **not** a precondition here.
- Tripwire action: the likely blocker is a Ktor API detail (execute scope, header replace,
  `expectSuccess` attribute). Stop, paste the Ktor error, hand to the orchestrator for a micro-spike.

### T2.4 — Factory functions and builder extension
- Owner: Grok · Focus 40m · Tripwire 75m · Depends on: T2.3 · Design ref [§6.1](ktor-fetcher-kit-technical-design.md).
- Implement `ktorFetcher(...)` and `StoreBuilder<K, V>.ktorFetcher(...)` with the full parameter list
  (`notFoundPolicy`, `lastModifiedFallback`, `errorMapper`, `allowHttpCache`), wiring the `HttpCache`
  rejection. DoD / verify: `:ktor:compileKotlinJvm` exits 0; a `jvmTest` builds
  `store { ktorFetcher(...) }` over `MockEngine`. **Regenerate and commit `:ktor:apiDump`** — this is
  the task that finalizes the public surface, so the dump should now be complete.

## 10. Phase 3 — tests

Exit: the full suite passes on `:ktor:jvmTest`; the JS/Wasm/linux/mingw canary is green on Linux;
Apple test execution is left to CI.

### T3.1 — Transport-level MockEngine unit tests
- Owner: Grok · Focus 75m · Tripwire 120m · Depends on: **T2.4** (constructs via the public factory)
- Design ref [§14 transport list](ktor-fetcher-kit-technical-design.md). Class
  `KtorFetcherTransportTest`. Cover every [§7](ktor-fetcher-kit-technical-design.md) row plus:
  `If-None-Match` sent on an ETag plan, `If-Modified-Since` sent on an `LM:` plan, GET/HEAD-only (a
  POST key gets no conditional header), header replace-not-append, the unconditional-304 anomaly, 404
  Error-vs-Delete, cancellation, and `errorMapper` precedence.
- DoD / verify: `:ktor:jvmTest --tests 'org.mobilenativefoundation.store6.ktor.KtorFetcherTransportTest'`
  green (paste summary).

### T3.2 — Store integration tests
- Owner: Sol · Focus 75m · Tripwire 120m · Depends on: **T2.4**
- Design ref [§14 integration + lifetime](ktor-fetcher-kit-technical-design.md). Class
  `KtorStoreIntegrationTest`, using the verbatim `runTest` shim. Assert: invalidate-then-conditional
  refetch → exactly one `StoreResult.Revalidated`; truly cold 304 → `StoreError.Missing`;
  `Freshness.MustBeFresh` re-requests; typed `KtorFetchException` via `StoreResult.Error` /
  `StoreException`; and the **validator-lifetime** case (`maxIdleKeys(0)` → the first post-hydration
  fetch receives a null `etag`, pinning [design §3.6](ktor-fetcher-kit-technical-design.md)).
- DoD / verify: `:ktor:jvmTest --tests 'org.mobilenativefoundation.store6.ktor.KtorStoreIntegrationTest'`
  green (paste summary).

### T3.3 — HttpCache rejection test
- Owner: Grok · Focus 30m · Tripwire 60m · Depends on: **T2.4** · Design ref [§13.2](ktor-fetcher-kit-technical-design.md).
- Class `KtorHttpCacheGuardTest`: assert the factory throws `IllegalArgumentException` for a client
  with `install(HttpCache)`, and constructs when `allowHttpCache = true`.
- DoD / verify: `:ktor:jvmTest --tests 'org.mobilenativefoundation.store6.ktor.KtorHttpCacheGuardTest'` green.

### Phase 3 exit — Linux multiplatform canary
- Owner: Grok · Focus 45m · Tripwire 90m · Depends on: T3.1–T3.3
- Run, with the flag prefix, `:ktor:jsNodeTest`, `:ktor:wasmJsNodeTest`, `:ktor:linuxX64Test`,
  `:ktor:mingwX64Test`. These surface a target-specific MockEngine or coroutines-test issue. Apple
  test execution is **CI-only** and is not attempted here (gate on `uname`, not konan cache).
- DoD / verify: the four Linux-runnable lanes green (paste each summary; no `SKIPPED`).

## 11. Phase 4 — sample

### T4.1 — Sample scenes
- Owner: Grok · Focus 50m · Tripwire 90m · Depends on: **T2.4** · Design ref [§12 sample](ktor-fetcher-kit-technical-design.md); model on `graphql/sample/.../Main.kt`.
- Scenes over `MockEngine`: (1) 200 then a conditional 304 → `Revalidated`; (2) a 500 surfaces
  `KtorFetchException.status == HttpStatusCode.InternalServerError`; (3) the 404 Error-vs-Delete
  policy; (4) a Last-Modified round-trip (`If-Modified-Since` on the second request). Headless,
  deterministic, `withTimeout`.
- DoD / verify: `:ktor-sample:run` exits 0 and prints each scene's assertion.

## 12. Phase 5 — docs and API surface

### T5.1 — README
- Owner: Sol · Focus 50m · Tripwire 90m · Depends on: T2.4 (may draft earlier from the design).
- Model on `graphql/README.md`: purpose + tier line ("The seam it consumes is a freeze candidate,
  not frozen — see STABILITY.md"), Install (`org.mobilenativefoundation.store:ktor:6.0.0-SNAPSHOT`),
  Entry points, Response-mapping table, Conditional requests, and the caveats — **validator lifetime**
  ([design §3.6](ktor-fetcher-kit-technical-design.md)), **HttpCache incompatibility**,
  **GET/HEAD-only**, **representation identity** — and Sample. Obey `AGENTS.md` documentation
  discipline; do **not** add the README to `.github/docs-sync-sources.txt`.
- DoD: the three-pass review (accuracy / warrant / reader utility) from `AGENTS.md`.

### T5.2 — dokka module doc (optional)
- Owner: Grok · Focus 15m · Tripwire 30m · Depends on: T5.1
- Optional `ktor/dokka/Module.md` modeled on `core/dokka/Module.md` (Dokka is already applied by the
  convention, `Store6Conventions.kt` line 108, so this is just content). Skip to protect the critical
  path; not required for parity.

### T5.3 — Finalize and commit API dumps
- Owner: Grok · Focus 40m · Tripwire 75m · Depends on: all public API frozen (post T2.4 and any
  test-driven signature change)
- Run `:ktor:apiDump` with the flag prefix (the aggregator produces JVM + Android + klib;
  there is no separate `apiDumpKlib`), and commit `ktor/api/jvm/ktor.api`, `ktor/api/android/ktor.api`,
  `ktor/api/ktor.klib.api`. The klib dump uses BCV's deterministic target inference, so a Linux-run
  dump matches what the ubuntu `linux-build-test` job checks; the android dump requires an Android SDK
  (else it is finalized on CI). DoD / verify: `:ktor:apiCheck` passes (do not use `:ktor:build` for
  this unless an Android SDK is present).
- Tripwire action: if the klib dump names unstable `io.ktor.*` symbols across a Ktor patch, record
  them and hand to the orchestrator (interacts with the pinned version).

## 13. Phase 6 — CI wiring

### T6.1 — Wire every store6.yml list
- Owner: Grok · Focus 45m · Tripwire 90m · Depends on: T0.2 (name), T5.3 (dumps committed, so the
  CI `:ktor:build` step is green — guardrail 7)
- Design ref [§15](ktor-fetcher-kit-technical-design.md). Edit, at the cited lines: the linux build +
  sample steps (`:ktor:build`, `:ktor-sample:run`), the internal-access grep loop (`ktor ktor/sample`),
  the TD-8 `production_source_dirs` (`ktor/src/*Main`), the JS canary (`:ktor:jsNodeTest`), the
  apple-tests list (`:ktor:iosSimulatorArm64Test :ktor:macosArm64Test`), and the klib-publication
  `publishToMavenLocal` list and `modules=(…)` array. Leave the seam-freeze list, docs-sync,
  swift-dumps, and native-stress untouched.
- DoD / verify (what a Linux agent can check without CI):
  - `actionlint .github/workflows/store6.yml` passes (workflow syntax).
  - Internal-access gate, using the CI wrapper form so exit codes behave under `set -e`:
    `if grep -rnE 'InternalStoreApi|org[.]mobilenativefoundation[.]store6[.]core[.]internal' ktor/src; then echo FAIL; exit 1; fi` → prints nothing, exits 0.
  - TD-8 grep over `ktor/src/*Main` → no match.
  - If an Android SDK is present: `:ktor:publishToMavenLocal` produces **all 13** publication
    artifacts the suffix matrix expects — the empty (common metadata) suffix **plus** the 12 target
    suffixes (`store6.yml` lines 688–702); copy the exact suffix-verification loop. Full parity means
    no per-module suffix exception. Without an SDK, this check is CI-only and is noted as such.
  - Apple test execution remains **CI-only**; T6.1 does not attempt it.

## 14. Phase 7 — verification, review, PR

### T7.1 — CI-equivalent build and gate dry-run
- Owner: Grok · Focus 75m · Tripwire 150m · Depends on: Phase 3 exit, T4.1, T5.1, T5.3, T6.1
- Reproduce the Linux CI job as closely as the host allows:
  `GRADLE_OPTS=-Dorg.gradle.configureondemand=false ./gradlew clean :ktor:build :ktor-sample:run :ktor:jsNodeTest -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true`
  (root `clean build` is what `ci.yml` runs; scope to `:ktor` first for speed, then a full
  `clean build` if the host can). If an Android SDK is present, also `:ktor:publishToMavenLocal`
  with the 13-artifact check. DoD / verify: all green, no required task `SKIPPED`; paste summaries.
  The macOS gate (`:ktor:macosArm64Test`, `:ktor:iosSimulatorArm64Test`) is **CI-only** and is
  called out as pending the apple-tests job.

### T7.2 — Zero-core-diff check
- Owner: Fable · Focus 10m · Depends on: everything · Design ref [§17](ktor-fetcher-kit-technical-design.md).
- `git diff --name-only "$BASE"...HEAD` (the recorded merge-base) touches only `ktor/**`,
  `settings.gradle`, `gradle/libs.versions.toml`, `.github/workflows/store6.yml`, and `design/**` —
  never `core/**`.

### T7.3 — Final adversarial code review
- Owner: Fable dispatches Sol + Grok (whichever did **not** write the code under review) · Focus 60m
  · Tripwire 120m · Depends on: T7.1
- Review against the design's mapping table, the 304 branches, the token no-flip rule, the HTTP
  correctness points (GET/HEAD-only, header replace, redirect caveat, connection cleanup via the
  execute scope), the HttpCache guard, and the guardrails. Fix findings, re-run T7.1. DoD: no
  unresolved Critical/Major finding.

### T7.4 — PR
- Owner: Fable · Focus 20m · Depends on: T7.3
- Commit per logical change, push, open/update the PR against `store6` with both design docs linked,
  the name and HttpCache decisions recorded, the zero-core-diff statement, and a note that Apple
  test execution is validated by the CI apple-tests job.

## 15. Orchestrator checkpoints

1. **After T0.0** — environment is understood (SDK/konan/Node), so task routing (local vs CI-only)
   is correct before the budget clock runs on build tasks.
2. **After Phase 0** — Ktor version pinned with evidence on the Linux-runnable lanes plus Apple klib
   compilation; name decided; citations re-confirmed. Highest-value checkpoint.
3. **After T2.4** — the public API is real, compiles, and the API dump is regenerated; freeze
   signatures before the bulk of the tests.
4. **After Phase 3 exit** — the JS/Wasm/linux/mingw canary is green, proving target portability;
   Apple remains pending CI.
5. **After T6.1** — local gate dry-runs and `actionlint` pass; CI is not the first test of the wiring
   (Apple execution excepted).
6. **After T7.3** — the final review is clean.

At each checkpoint Fable reconciles budgets (sum spent, tripwires fired) and decides whether to
rescope (for example, drop the optional dokka doc T5.2 to protect the critical path).

## 16. Global pre-PR gates (all must pass; Apple test execution is CI-only)

- `:ktor:build` (runs `apiCheck`) green on the Linux lanes with the flag prefix (needs Android SDK;
  else CI).
- `:ktor-sample:run` exits 0.
- `:ktor:jsNodeTest`, `:ktor:wasmJsNodeTest`, `:ktor:linuxX64Test`, `:ktor:mingwX64Test` green on Linux.
- `:ktor:publishToMavenLocal` produces all 13 publication artifacts (empty + 12 target suffixes) —
  CI or an SDK-equipped host.
- `actionlint` clean on the edited workflow.
- Internal-access grep and TD-8 grep over `ktor/src` — empty.
- `git diff --name-only "$BASE"...HEAD` — no `core/**`.
- README passes the three-pass documentation review.
- CI apple-tests job green (`:ktor:iosSimulatorArm64Test`, `:ktor:macosArm64Test`) — verified on the
  PR's CI run, not locally.

## 17. Risk register

| Risk | From | Likelihood | Impact | Mitigation / owner |
|---|---|---|---|---|
| Phase 0 falsely "passes" on a Linux host that skipped Apple tests | plan [§4.2](#42-host-capability-matrix-what-a-linux-cloud-agent-can-and-cannot-do) | (was high) | High | T0.1 proves Apple **klib compile** only; Apple **test** is a named CI-only gate; DoD rejects `SKIPPED` |
| `:ktor:build` fails `apiCheck` with no/stale dump | plan [§4.1](#41-the-apicheck-in-build-hazard) | High if unmanaged | High | Empty dump in T1.1; regenerate in T2.2/T2.4; final in T5.3; never push an include without a dump |
| Parallel agents clobber the same module | plan [§5](#5-concurrency-and-isolation) | Med | High | Worktree per concurrent task; scoped `--tests`; single-writer shared files |
| No Ktor version links on the Linux matrix from the 2.3.20 compiler | design [§13.1](ktor-fetcher-kit-technical-design.md) | Low–Med | High | T0.1b ladder; last-resort Kotlin patch bump escalated |
| `coroutines-test` (1.8.1) vs resolved coroutines skew breaks `commonTest` | design [§13.1](ktor-fetcher-kit-technical-design.md) | Med | Med | T0.1a aligns both with a **module-local** constraint |
| Missing Android SDK on the agent blocks `build`/publish/androidApiDump | plan [§4.2](#42-host-capability-matrix-what-a-linux-cloud-agent-can-and-cannot-do) | Med | Med | T0.0 preflight routes those tasks to CI or provisions the SDK |
| A caller's `HttpCache` silently defeats revalidation | design [§13.2](ktor-fetcher-kit-technical-design.md) | Med | High | Fail-fast factory check (T2.4) + regression test (T3.3) |
| Ktor API detail wrong (execute scope, header replace, `expectSuccess`) | design [§8](ktor-fetcher-kit-technical-design.md) | Med | Med | T2.3 micro-spike on tripwire; transport tests (T3.1) catch it |
| Late rename (`ktor` → `ktor-fetcher`) after CI wiring | design [§16](ktor-fetcher-kit-technical-design.md) | Low | Med | Resolve in T0.2 before T6.1 |
| Validator-lifetime limitation read as a "bug" | design [§3.6](ktor-fetcher-kit-technical-design.md) | Med | Low | Pin with the T3.2 lifetime test; document in the README |

## 18. Rollback

Everything is on the feature branch; nothing lands on `store6` until the PR merges. If a phase fails
irrecoverably: the module is self-contained under `ktor/**` and reverts with the branch; the CI edits
are additive lines in `store6.yml` and `settings.gradle` that revert with the branch; and because no
`core/**` file is touched (T7.2), a rollback cannot destabilize the read core or any other extension.
The design docs can remain even if the code is deferred — they are the record of the decision.

## 19. Budget roll-up

Focus budgets total roughly **18 hours** of agent execution (~1,070 min), dominated by the spike
(T0.1a+T0.1b, 120m), the `KtorFetcher` core (T2.3, 100m), the two test tasks (T3.1/T3.2, 75m each),
and the CI-equivalent verify (T7.1, 75m). With the [§5](#5-concurrency-and-isolation) parallelization
(Sol and Grok in separate worktrees after T1.1, and again after T2.4, with Grok's tasks serialized on
one model), the critical path is approximately:

```
T0.0 → T0.1a → T0.1b → T1.1 → T2.2 → T2.3 → T2.4 → T3.2 → Phase 3 exit → T5.3 → T6.1 → T7.1 → T7.3 → T7.4
20  +  45   +  75   +  45  +  40  +  100 +  40  +  75  +   45      +  40  +  45  +  75  +  60  +  20  ≈ 725 min
```

about **12 hours** of wall-clock with a warm konan cache and no tripwire-forced reassignment — more
if native caches are cold or the version ladder is walked. These are agent-execution figures for
planning the run, not a delivery schedule; per the operating constraints, no calendar estimate is
implied.

---

### Appendix A — quick task index

| Task | Owner | Focus / tripwire (min) | Depends |
|---|---|---|---|
| T0.0 env preflight | Grok | 20 / 40 | — |
| T0.1a version + JVM/JS | Sol | 45 / 75 | T0.0 |
| T0.1b linux native + Apple klib compile | Sol | 75 / 150 | T0.1a |
| T0.2 name decision | Fable | 10 / — | — |
| T0.3 HttpCache API | Grok | 15 / 30 | T0.1a |
| T0.4 re-confirm citations | Grok | 15 / 30 | — |
| T1.1 skeleton + empty dump | Grok | 45 / 90 | T0.1, T0.2 |
| T1.2 sample skeleton | Grok | 25 / 45 | T1.1 |
| T2.1 token codec | Sol | 50 / 90 | T1.1 |
| T2.2 public types (+dump) | Grok | 40 / 75 | T1.1 |
| T2.3 KtorFetcher core | Sol | 100 / 160 | T2.1, T2.2, T0.3 |
| T2.4 factories (+dump) | Grok | 40 / 75 | T2.3 |
| T3.1 transport tests | Grok | 75 / 120 | T2.4 |
| T3.2 store integration | Sol | 75 / 120 | T2.4 |
| T3.3 HttpCache guard test | Grok | 30 / 60 | T2.4 |
| Phase 3 exit canary | Grok | 45 / 90 | T3.1–T3.3 |
| T4.1 sample scenes | Grok | 50 / 90 | T2.4 |
| T5.1 README | Sol | 50 / 90 | T2.4 (draft earlier) |
| T5.2 dokka (optional) | Grok | 15 / 30 | T5.1 |
| T5.3 finalize API dumps | Grok | 40 / 75 | API frozen |
| T6.1 CI wiring | Grok | 45 / 90 | T0.2, T5.3 |
| T7.1 CI-equivalent verify | Grok | 75 / 150 | P3 exit, T4.1, T5.1, T5.3, T6.1 |
| T7.2 zero-core-diff | Fable | 10 / — | all |
| T7.3 final review | Fable+both | 60 / 120 | T7.1 |
| T7.4 PR | Fable | 20 / — | T7.3 |

### Appendix B — how this revision answered the plan reviews

| Review finding | Resolution |
|---|---|
| "Link all 12 targets on one machine" is false; Apple link/test is macOS-only; `linkDebug*` is not a task (both, Critical) | [§4.2](#42-host-capability-matrix-what-a-linux-cloud-agent-can-and-cannot-do) host matrix; T0.1 split into version+JVM/JS (T0.1a) and Linux-native + Apple-klib-**compile** (T0.1b); Apple **test** is a named CI-only gate; exact task names listed |
| `apiCheck` runs in `build` and fails with no dump; every early `build` fails (Grok, Critical) | [§4.1](#41-the-apicheck-in-build-hazard); empty dump committed in T1.1; regenerated in T2.2/T2.4; finalized in T5.3; guardrail 7 forbids pushing an include without a dump |
| Parallel edits to one module, no isolation (both, Critical) | [§5](#5-concurrency-and-isolation): worktree-per-task (preferred) or file-ownership + serialized Gradle; scoped, FQN `--tests` DoDs |
| `settings.gradle` includes `:ktor-sample` before it exists; missing `projectDir` (Grok, Critical) | T1.1 includes only `:ktor`; T1.2 adds `:ktor-sample` + `projectDir` and copies the graphql sample build incl. `mainClass` |
| T3.1/T3.3/T4.1 depend on the private `Fetcher` (T2.3) but need the factory (T2.4) (both, Major) | Dependencies corrected to T2.4; T2.3 DoD no longer "front-loads" T3.1 and owns only a smoke test |
| Missing Android-SDK / konan / Node / configure-on-demand preflight (both, Major) | T0.0 preflight; guardrail 4 mandates the flag prefix incl. `-Dorg.gradle.configureondemand=false` |
| `configureondemand=false` required for JS/Wasm (Grok, Major) | Guardrail 4 |
| Apple API dump inferred on Linux (Sol, Major) | [§4.2](#42-host-capability-matrix-what-a-linux-cloud-agent-can-and-cannot-do)/T5.3: klib dump uses deterministic BCV inference matching ubuntu CI; android dump needs SDK/CI |
| T6.1 "12 artifacts" → 13; exact suffix loop; actionlint; grep exit code (both, Major/Minor) | T6.1 DoD: 13 artifacts (empty + 12), the exact suffix loop, `actionlint`, and the `if grep…; then` wrapper |
| T6.1 landing before dumps reddens CI (Grok, Major) | T6.1 depends on T5.3; guardrail 7 |
| T7.1 deps incomplete; missing root `clean build`/GRADLE_OPTS (both, Major) | T7.1 deps expanded; CI-equivalent command with `GRADLE_OPTS` and flags; macOS gate CI-only |
| Wrong BCV task names (`apiDumpKlib`) (Grok, Major) | [§4.2](#42-host-capability-matrix-what-a-linux-cloud-agent-can-and-cannot-do)/T5.3: correct names (`apiDump` aggregator, `klibApiDump`, `apiCheck`) |
| Budgets too low; critical path optimistic (both, Major) | T0.1 split and rebudgeted; [§19](#19-budget-roll-up) recomputed (~18h focus, ~12h critical path) |
| DoD evidence weak; `--tests` filters unnamed; `<base>` unresolved (both, Minor) | [§3](#3-the-budget-and-tripwire-system) evidence adds SHA/host/exit/outcomes; tasks mandate FQN class names; `$BASE` merge-base recorded in [§1](#1-how-to-use-this-plan) |
| Verified sound (task names, checkSwiftDumps fixed list, ktlint/spotless not enforced, docs-sync, MinGW builds on Linux, no BOM/index) | Retained; reflected in [§4.2](#42-host-capability-matrix-what-a-linux-cloud-agent-can-and-cannot-do) and [§15](ktor-fetcher-kit-technical-design.md) of the design |
