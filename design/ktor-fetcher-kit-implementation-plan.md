# Ktor fetcher kit — implementation plan

Companion to `ktor-fetcher-kit-technical-design.md`. This plan is written to be executed by one
orchestrator agent (Fable) directing sub-agents (GPT-5.6 Sol and Cursor Grok 4.6). It is granular
enough that a sub-agent can pick up a single task with only that task's entry plus the design doc,
and it assigns each task a **time budget** and a **tripwire** so the orchestrator can keep agents
focused and detect a stuck task early.

Section order: how to read the plan and the budget system first, then the guardrails every
sub-agent obeys, then the dependency graph, then the phased tasks, then the checkpoints, gates,
risks, and rollback.

---

## 1. How to use this plan

- **The design doc is the specification.** Every task references design sections. A sub-agent reads
  the referenced sections before writing code. Where the design and a task disagree, the sub-agent
  stops and escalates rather than guessing.
- **Phases gate.** Phase N+1 does not start until Phase N's exit criteria pass, except where the
  parallelization graph ([§5](#5-dependency-graph-and-parallelization)) marks tasks concurrent.
- **The spike is a hard gate.** No module wiring or CI edits happen until T0.1 pins a Ktor version
  on evidence. This is the single most important sequencing rule.
- **Zero core diff is invariant.** No task edits `core/**`. A task that appears to need a core
  change is an escalation, not a workaround.

### 1.1 A note on "time budgets"

These budgets are **agent-execution budgets** — active wall-clock for the agent doing the task,
including the Gradle/Kotlin builds it runs — not human calendar estimates. Kotlin/Native (konan)
builds dominate several budgets; the first native/wasm build on a cold machine can take a long time
(toolchain download plus 12-target link), which is why build-heavy tasks carry large budgets and a
generous tripwire. If a machine has a warm konan cache, expect the low end.

## 2. Roles and model assignment

| Role | Model | Responsibilities |
|---|---|---|
| Orchestrator | Fable | Dispatches tasks, enforces budgets and tripwires, runs checkpoints, adjudicates the spike, makes the naming and HttpCache-default decisions, does the final review and the PR. Holds the only authority to accept a phase exit. |
| Implementer A | GPT-5.6 Sol | Correctness- and judgment-heavy work: the `KtorFetcher` core mapping, the validator-token codec, the Store integration tests, the README, and the dependency spike's decision. |
| Implementer B | Cursor Grok 4.6 | Mechanical, high-throughput work: module and sample skeletons, build/catalog wiring, MockEngine transport unit tests, the sample scenes, API-dump generation, and the CI list edits. |

Assignment rationale: Sol takes tasks where a wrong decision is expensive and hard to detect
(HTTP-semantics mapping, the 304 branches, the token no-flip rule). Grok takes tasks where the
answer is verifiable by a command (does it build? does the dump match? does the grep gate pass?).
The orchestrator never hands a sub-agent a decision the design left open — it resolves the decision
first ([§9 checkpoints](#9-orchestrator-checkpoints)).

## 3. The budget and tripwire system

Each task carries:

- **Focus budget** — the target active agent wall-clock. A task running past its focus budget is
  not yet failing; it is a signal for the orchestrator to check in.
- **Tripwire** — a hard cap. On reaching it the sub-agent **stops**, writes a short status (what
  passed, what is blocked, the exact failing command output), and hands back to the orchestrator.
  Do not push past a tripwire silently.
- **Turn/tool-call guidance** — a soft cap of roughly 40 tool calls without converging (a passing
  verification command). Crossing it triggers the same stop-and-report as a tripwire.

Escalation protocol when a tripwire fires:

1. The sub-agent posts: task ID, last passing step, the failing command and its verbatim output,
   and its best hypothesis.
2. The orchestrator triages: (a) reassign to the other model, (b) split the task, (c) relax scope
   per a design open-decision, or (d) invoke the design's fallback (most often the spike's version
   ladder, [§13.1 of the design](ktor-fetcher-kit-technical-design.md)).
3. Budgets are not silently extended. A deliberate extension is a new, logged decision with a
   reason.

Definition of done (applies to every task unless the entry overrides it): the task's verification
command runs clean and its **output is pasted into the handoff**; no `core/**` file changed; any
copied byte-identical block (the `runTest` shim) is verbatim; new public API is
`@ExperimentalStoreApi`; the working tree builds at least for `:ktor:compileKotlinJvm` (or the
task's stated narrower target).

## 4. Guardrails for every sub-agent

1. **Never edit `core/**`, the seam package, or any other module's sources.** Only `ktor/**`,
   `settings.gradle`, `gradle/libs.versions.toml`, and `.github/workflows/store6.yml` are in scope
   (plus the two design docs, orchestrator-only).
2. **No `core.internal` and no `InternalStoreApi`.** Build against public seam APIs only; the kit's
   private `Fetcher` implementation is `@OptIn(DelicateStoreApi::class)`.
3. **No banned concurrency primitive** in production sources: `runBlocking`, `GlobalScope`,
   `atomicfu`, `Channel`, `actor` (the TD-8 grep, design [§15](ktor-fetcher-kit-technical-design.md)).
   The kit is a stateless suspend mapper and needs none.
4. **Paste verification output.** A "done" claim without the command output is not done.
5. **Copy the `runTest` shim verbatim** from
   `graphql/src/commonTest/.../GraphQlStoreIntegrationTest.kt` lines 251–260, comment included, when
   a test uses Turbine.
6. **Prefer the smallest target for fast feedback.** Iterate on `:ktor:jvmTest` /
   `:ktor:compileKotlinJvm`; run native/wasm only at the task's verification step or when the task
   is explicitly cross-target.
7. **Stop at a tripwire** and report; do not improvise a core change or disable a gate.

## 5. Dependency graph and parallelization

```
T0.1 spike (version pin) ──┬─ gate ─┐
T0.2 name decision ────────┤        │
T0.3 HttpCache API check ──┤        │
T0.4 re-confirm citations ─┘        │
                                    ▼
                         T1.1 module skeleton ── T1.2 sample skeleton
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        ▼                           ▼                           ▼
   T2.1 token codec          T2.2 public types           (docs T5.1 can draft)
        │                           │
        └────────────┬──────────────┘
                     ▼
              T2.3 KtorFetcher core
                     ▼
              T2.4 factories + builder ext
        ┌────────────┼────────────┬───────────────┐
        ▼            ▼            ▼               ▼
   T3.1 transport  T3.2 store   T3.3 HttpCache   T4.1 sample scenes
    unit tests    integration    rejection test
        └────────────┴────────────┴───────────────┘
                     ▼
              T5.3 API dumps ── T6.1 CI wiring ── T7.x verification + PR
```

Concurrency the orchestrator should exploit:

- After T1.1: **T2.1 (Sol)** and **T2.2 (Grok)** run in parallel; the README draft (T5.1) can start
  from the design in parallel too.
- After T2.4: **T3.1 (Grok)**, **T3.2 (Sol)**, **T3.3 (Grok)**, and **T4.1 (Grok)** run in parallel;
  the orchestrator load-balances the three Grok tasks (serialize them or alternate) since one model
  cannot run them truly simultaneously.
- T5.3 (API dumps) must run **after** all public API is frozen (post T2.4 and any test-driven
  signature change), so it is late and single-owner.
- T6.1 (CI wiring) depends only on the name decision (T0.2) and the module existing (T1.1), so it can
  overlap Phase 2/3; but its verification (the gates) needs the finished module, so its check step
  lands in Phase 7.

## 6. Phase 0 — spikes and decisions (gate)

Exit criteria: a Ktor version is pinned by evidence on all 12 targets; the artifact name is decided;
the HttpCache detection API is confirmed; the design's core citations are re-verified.

### T0.1 — Dependency and multiplatform spike (**the gate**)
- Owner: Sol · Focus budget: 90 min · Tripwire: 150 min · Depends on: none
- Design refs: [§13.1](ktor-fetcher-kit-technical-design.md), [§12](ktor-fetcher-kit-technical-design.md).
- Steps:
  1. On a throwaway branch, add catalog entries `ktor`, `ktor-client-core`, `ktor-client-mock` at
     the latest 3.5.x. Add a minimal `:ktor` module (`api(projects.core)`, `api(libs.ktor.client.core)`)
     with one trivial `expect`-free file referencing `HttpClient` so linking pulls Ktor.
  2. `./gradlew :ktor:build -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true`
     and, for each native/wasm target, the link task (`:ktor:linkDebug*` or the target `*Klib`), to
     prove all 12 targets compile against Ktor 3.5.x built with Kotlin 2.3.21.
  3. Resolve dependencies for `commonMain` and `commonTest` (`./gradlew :ktor:dependencies`); record
     the coroutines, coroutines-test, serialization, atomicfu, and kotlinx-io versions that resolve.
     Confirm `coroutines-test` (from `projects.testing`) and `coroutines` end on one version; if not,
     add a constraint aligning them.
  4. If any native/wasm lane fails to link, read the actual Ktor klib manifest
     `abi_version`/`metadata_version`; then walk the fallback ladder 3.5.1 → 3.5.0 → newest 3.4.x.
- DoD: a pinned Ktor version, the resolved kotlinx stack recorded, and a one-paragraph decision note
  (version chosen, why, any constraint added). Verify: the `:ktor:build` command above exits 0 on
  the executed lanes and the native link tasks succeed.
- Tripwire action: if no 3.5.x or 3.4.x version links on all 12 targets within the tripwire, stop
  and hand the manifest evidence to the orchestrator, who decides between narrowing targets (a
  design change, needs a new subset-plugin plan) or escalating a Kotlin patch bump.

### T0.2 — Artifact-name decision
- Owner: Fable (orchestrator) · Focus budget: 10 min · Tripwire: n/a · Depends on: none
- Design ref: [§16 open decision 1](ktor-fetcher-kit-technical-design.md).
- Decide `ktor` vs `ktor-fetcher` with the maintainer before any CI wiring, because a later rename
  touches ~8 lists. Default to `ktor` if no objection. Record the decision in the PR description.

### T0.3 — Confirm the HttpCache detection API
- Owner: Grok · Focus budget: 15 min · Tripwire: 30 min · Depends on: T0.1 (version pinned)
- Design ref: [§13.2](ktor-fetcher-kit-technical-design.md).
- Confirm, in the pinned Ktor version, the exact call to detect an installed plugin
  (`client.pluginOrNull(HttpCache)`) and the `HttpCache` import path. DoD: a two-line snippet that
  compiles in a scratch `jvmTest`.

### T0.4 — Re-confirm core citations
- Owner: Grok · Focus budget: 15 min · Tripwire: 30 min · Depends on: none
- Re-open each `core/**` and `graphql/**` line range in the design's Appendix A and confirm it still
  matches (the branch may have moved since the design was written). DoD: a diff-free confirmation or
  a list of citations to update; hand any drift to the orchestrator to patch the design doc.

## 7. Phase 1 — module skeleton

Exit criteria: `:ktor` and `:ktor-sample` exist, are included in the build, and compile empty on
JVM; nothing is wired into CI yet beyond `settings.gradle`.

### T1.1 — Module skeleton and catalog
- Owner: Grok · Focus budget: 45 min · Tripwire: 90 min · Depends on: T0.1, T0.2
- Design refs: [§12](ktor-fetcher-kit-technical-design.md).
- Steps: create `ktor/build.gradle.kts` (full convention plugin, `api(projects.core)`,
  `api(libs.ktor.client.core)`, test deps), `ktor/gradle.properties` (`VERSION_NAME`, `POM_NAME`,
  `POM_ARTIFACT_ID`), `ktor/src/androidMain/AndroidManifest.xml` (empty), the package dir
  `ktor/src/commonMain/kotlin/org/mobilenativefoundation/store6/ktor/`, and the `settings.gradle`
  includes for `:ktor` and `:ktor-sample`.
- DoD / verify: `./gradlew :ktor:compileKotlinJvm` exits 0 with an empty module.
- Tripwire action: most failures here are convention-plugin misconfig; hand the exact Gradle error
  to the orchestrator.

### T1.2 — Sample skeleton
- Owner: Grok · Focus budget: 20 min · Tripwire: 40 min · Depends on: T1.1
- Design ref: [§12 sample](ktor-fetcher-kit-technical-design.md).
- Create `ktor/sample/build.gradle.kts` (JVM `application`, `implementation(projects.ktor)`,
  `implementation(libs.ktor.client.mock)`) and a `main()` that prints and exits 0.
- DoD / verify: `./gradlew :ktor-sample:run` exits 0.

## 8. Phase 2 — kit implementation (test-driven)

Exit criteria: the public API from design [§6](ktor-fetcher-kit-technical-design.md) exists, all
mapping logic is implemented, and `:ktor:jvmTest` passes for the unit-level tests written alongside.

### T2.1 — Validator-token codec
- Owner: Sol · Focus budget: 50 min · Tripwire: 90 min · Depends on: T1.1
- Design refs: [§5.2](ktor-fetcher-kit-technical-design.md), [§5.4](ktor-fetcher-kit-technical-design.md).
- Implement encode (ETag verbatim; Last-Modified → `LM:` + date; `lastModifiedFallback` gate) and
  decode (`startsWith("LM:")`/`removePrefix`, never `split`), and the response-side selection with
  the **no-flip** rule for 304. Write these test-first: colon-containing ETag `"a:b"`, weak `W/"x"`,
  both-headers precedence, 304-with-only-Last-Modified keeps the prior ETag, `lastModifiedFallback = false`.
- DoD / verify: `./gradlew :ktor:jvmTest --tests '*ValidatorToken*'` passes (paste the summary).

### T2.2 — Public policy, mapper, and error types
- Owner: Grok · Focus budget: 40 min · Tripwire: 75 min · Depends on: T1.1
- Design ref: [§6.2](ktor-fetcher-kit-technical-design.md).
- Implement `KtorNotFoundPolicy`, `KtorExchange`, `KtorOutcome` (sealed), `KtorErrorMapper`
  (non-generic `fun interface` + `Default` returning `Defer`), `KtorFetchException` (non-null
  `status`). All `@ExperimentalStoreApi`.
- DoD / verify: `./gradlew :ktor:compileKotlinJvm` exits 0; a trivial `jvmTest` constructs each type
  and calls `KtorErrorMapper.Default.map(...)` returning `Defer`.

### T2.3 — `KtorFetcher` core
- Owner: Sol · Focus budget: 100 min · Tripwire: 160 min · Depends on: T2.1, T2.2, T0.3
- Design refs: [§7](ktor-fetcher-kit-technical-design.md), [§8](ktor-fetcher-kit-technical-design.md),
  [§9](ktor-fetcher-kit-technical-design.md), [§13.2](ktor-fetcher-kit-technical-design.md).
- Implement the private `Fetcher` (annotated `@OptIn(DelicateStoreApi::class)`): fresh request per
  fetch via `prepareRequest { }.execute { }`; `expectSuccess = false`; remove-then-set the
  conditional header, GET/HEAD only; the [§7](ktor-fetcher-kit-technical-design.md) status table
  including the unconditional-304 anomaly and 206/204 handling; `decode` invoked only on adopted
  2xx inside the execute scope; `CancellationException` re-thrown, other throwables →
  `Error(cause)`; the `errorMapper`-before-default precedence; the `HttpCache` fail-fast check in
  the factory path (built here, surfaced in T2.4).
- DoD / verify: `./gradlew :ktor:jvmTest --tests '*KtorFetcher*'` passes for the transport-level
  cases written in T3.1 that the orchestrator front-loads here, or at minimum
  `:ktor:compileKotlinJvm` plus a smoke test that a 200 maps to `Success` and a conditional 304 maps
  to `NotModified`.
- Tripwire action: the likely blocker is a Ktor API detail (execute scope, header replace,
  `expectSuccess` attribute). Stop, paste the Ktor error, and hand to the orchestrator, who may pull
  the specific Ktor-API confirmation into a micro-spike.

### T2.4 — Factory functions and builder extension
- Owner: Grok · Focus budget: 40 min · Tripwire: 75 min · Depends on: T2.3
- Design ref: [§6.1](ktor-fetcher-kit-technical-design.md).
- Implement `ktorFetcher(...)` and `StoreBuilder<K, V>.ktorFetcher(...)` with the full parameter
  list (including `allowHttpCache`, `lastModifiedFallback`, `notFoundPolicy`, `errorMapper`), wiring
  the `HttpCache` rejection. DoD / verify: `./gradlew :ktor:compileKotlinJvm` exits 0 and a
  `jvmTest` builds a `store { ktorFetcher(...) }` over `MockEngine`.

## 9. Phase 3 — tests

Exit criteria: the full test set passes on `:ktor:jvmTest`, and `:ktor:jsNodeTest` compiles-and-runs
the common tests (the JS lane is the multiplatform canary).

### T3.1 — Transport-level MockEngine unit tests
- Owner: Grok · Focus budget: 75 min · Tripwire: 120 min · Depends on: T2.3
- Design ref: [§14](ktor-fetcher-kit-technical-design.md) (transport list).
- Cover every [§7](ktor-fetcher-kit-technical-design.md) row plus: `If-None-Match` sent on ETag
  plan, `If-Modified-Since` sent on `LM:` plan, GET/HEAD-only (a POST key gets no conditional
  header), header replace-not-append, unconditional-304 anomaly, 404 Error-vs-Delete, cancellation,
  `errorMapper` override precedence. DoD / verify: `./gradlew :ktor:jvmTest` green (paste summary).

### T3.2 — Store integration tests
- Owner: Sol · Focus budget: 75 min · Tripwire: 120 min · Depends on: T2.4
- Design ref: [§14](ktor-fetcher-kit-technical-design.md) (integration + lifetime list).
- Build a real in-memory `Store` over `MockEngine` and assert: invalidate-then-conditional-refetch
  → exactly one `StoreResult.Revalidated`; truly cold 304 → `StoreError.Missing`;
  `Freshness.MustBeFresh` re-requests; typed `KtorFetchException` via `StoreResult.Error` /
  `StoreException`; and the **validator-lifetime** case (`maxIdleKeys(0)` → first post-hydration
  fetch receives a null `etag`). Use the verbatim `runTest` shim.
- DoD / verify: `./gradlew :ktor:jvmTest --tests '*Integration*'` green (paste summary).

### T3.3 — HttpCache rejection test
- Owner: Grok · Focus budget: 30 min · Tripwire: 60 min · Depends on: T2.4
- Design ref: [§13.2](ktor-fetcher-kit-technical-design.md).
- Assert the factory throws `IllegalArgumentException` for a client with `install(HttpCache)` and
  constructs when `allowHttpCache = true`. DoD / verify: the two cases pass in `:ktor:jvmTest`.

### Phase 3 exit — multiplatform canary
- Owner: Grok · Focus budget: 45 min · Tripwire: 90 min · Depends on: T3.1–T3.3
- Run `./gradlew :ktor:jsNodeTest` (and, if the machine has konan warm, `:ktor:macosArm64Test` or
  `:ktor:iosSimulatorArm64Test`). This is where a target-specific MockEngine or coroutines-test
  issue surfaces. DoD / verify: `:ktor:jsNodeTest` green.

## 10. Phase 4 — sample

### T4.1 — Sample scenes
- Owner: Grok · Focus budget: 50 min · Tripwire: 90 min · Depends on: T2.4
- Design ref: [§12 sample](ktor-fetcher-kit-technical-design.md); model on `graphql/sample/.../Main.kt`.
- Scenes over `MockEngine`: (1) a 200 then a conditional 304 → `Revalidated`; (2) typed error
  mapping (a 500 surfaces `KtorFetchException.status == 500`); (3) the 404 Error-vs-Delete policy;
  (4) a Last-Modified round-trip (`If-Modified-Since` on the second request). Headless, deterministic,
  `withTimeout`.
- DoD / verify: `./gradlew :ktor-sample:run` exits 0 and prints each scene's assertion.

## 11. Phase 5 — docs and API surface

### T5.1 — README
- Owner: Sol · Focus budget: 50 min · Tripwire: 90 min · Depends on: T2.4 (API stable enough to
  document); can draft earlier from the design.
- Model on `graphql/README.md`: purpose + tier line ("The seam it consumes is a freeze candidate,
  not frozen — see STABILITY.md"), Install (coordinates `org.mobilenativefoundation.store:ktor:6.0.0-SNAPSHOT`),
  Entry points, Response mapping table, Conditional requests, the **validator-lifetime** caveat
  ([design §3.6](ktor-fetcher-kit-technical-design.md)), the **HttpCache** and **GET/HEAD-only** and
  **representation-identity** caveats, and Sample. Obey the documentation-discipline rules in
  `AGENTS.md`. Do **not** add the README to `.github/docs-sync-sources.txt`.
- DoD: three-pass review (accuracy of protected tokens, warranted claims, reader utility) per
  `AGENTS.md`.

### T5.2 — dokka module doc (optional)
- Owner: Grok · Focus budget: 15 min · Tripwire: 30 min · Depends on: T5.1
- Optional `ktor/dokka/Module.md` modeled on `core/dokka/Module.md`. Skip if it risks a Dokka
  wiring change; it is not required for parity.

### T5.3 — Generate and commit API dumps
- Owner: Grok · Focus budget: 40 min · Tripwire: 75 min · Depends on: all public API frozen
  (post T2.4, T3.x signature settling)
- Run the module's BCV dump (`./gradlew :ktor:apiDump` and the klib dump) and commit
  `ktor/api/jvm/ktor.api`, `ktor/api/android/ktor.api`, `ktor/api/ktor.klib.api`. DoD / verify:
  `./gradlew :ktor:apiCheck` (or `:ktor:build`, which runs it) exits 0 with the committed dumps.
- Tripwire action: if the klib dump is unstable across a Ktor patch, record the exact `io.ktor.*`
  symbols it names and hand to the orchestrator (this can interact with the pinned version).

## 12. Phase 6 — CI wiring

### T6.1 — Wire every store6.yml list
- Owner: Grok · Focus budget: 45 min · Tripwire: 90 min · Depends on: T0.2 (name), T1.1 (module)
- Design ref: [§15](ktor-fetcher-kit-technical-design.md). Edit, at the cited lines: the linux build
  + sample steps, the internal-access grep loop (`ktor ktor/sample`), the TD-8
  `production_source_dirs` (`ktor/src/*Main`), the JS canary (`:ktor:jsNodeTest`), the apple-tests
  list (`:ktor:iosSimulatorArm64Test :ktor:macosArm64Test`), and the klib-publication
  `publishToMavenLocal` list and `modules=(…)` array. Leave the seam-freeze list, docs-sync,
  swift-dumps, and native-stress untouched.
- DoD / verify locally what can be verified without CI: the internal-access grep
  (`grep -rnE 'InternalStoreApi|store6[.]core[.]internal' ktor/src` returns nothing), the TD-8 grep
  over `ktor/src/*Main` returns nothing, and `./gradlew :ktor:publishToMavenLocal` produces the 12
  target artifacts the suffix matrix expects.

## 13. Phase 7 — verification, review, PR

### T7.1 — Full build and gate dry-run
- Owner: Grok · Focus budget: 75 min · Tripwire: 150 min · Depends on: T5.3, T6.1
- Run `./gradlew :ktor:build :ktor-sample:run -Pkotlin.native.enableKlibsCrossCompilation=true
  -Pkotlin.apple.xcodeCompatibility.nowarn=true`, plus `:ktor:jsNodeTest` and (if runnable)
  `:ktor:macosArm64Test`/`:ktor:iosSimulatorArm64Test`, and `:ktor:publishToMavenLocal` with the
  publication verification. DoD / verify: all green; paste the summary.

### T7.2 — Zero-core-diff check
- Owner: Fable · Focus budget: 10 min · Tripwire: n/a · Depends on: everything
- Design ref: [§17](ktor-fetcher-kit-technical-design.md). Run `git diff --name-only <base>...HEAD`
  and assert only `ktor/**`, `settings.gradle`, `gradle/libs.versions.toml`,
  `.github/workflows/store6.yml`, and `design/**` appear — never `core/**`.

### T7.3 — Final adversarial code review
- Owner: Fable dispatches Sol + Grok (whichever did **not** write the code under review) · Focus
  budget: 60 min · Tripwire: 120 min · Depends on: T7.1
- Review against the design's mapping table, the 304 branches, the token no-flip rule, the HTTP
  correctness points (GET/HEAD-only, header replace, redirect caveat, connection cleanup), and the
  guardrails. Fix findings, re-run T7.1. DoD: no unresolved Critical/Major finding.

### T7.4 — PR
- Owner: Fable · Focus budget: 20 min · Depends on: T7.3
- Commit per logical change, push the branch, open the PR against `store6` with the design and plan
  linked, the name and HttpCache decisions recorded, and the zero-core-diff statement.

## 14. Orchestrator checkpoints

The orchestrator pauses for a checkpoint at each of these, and does not release the next phase until
the checkpoint passes:

1. **After Phase 0** — Ktor version pinned with evidence on 12 targets; name decided; citations
   re-confirmed. This is the highest-value checkpoint; a wrong version pin poisons everything
   downstream.
2. **After T2.4** — the public API is real and compiles; freeze it before writing the bulk of the
   tests and before the API dump, so signatures do not churn.
3. **After Phase 3 exit** — the JS canary is green, proving the mapping is target-portable, not
   just JVM-correct.
4. **After T6.1** — the local gate dry-runs pass, so CI is not the first place the wiring is tested.
5. **After T7.3** — the final review is clean.

At each checkpoint the orchestrator also reconciles budgets: sum the focus budgets spent, note any
tripwire that fired, and decide whether the remaining plan is still viable or needs rescoping (for
example, dropping the optional dokka doc T5.2 to protect the critical path).

## 15. Global pre-PR gates (all must pass)

- `./gradlew :ktor:build` (runs `apiCheck`) — green on the executed lanes with cross-compilation.
- `./gradlew :ktor-sample:run` — exits 0.
- `./gradlew :ktor:jsNodeTest` — green.
- `./gradlew :ktor:publishToMavenLocal` — produces all 12 target artifacts with the expected
  suffixes ([design §15](ktor-fetcher-kit-technical-design.md)).
- Internal-access grep over `ktor/src` — empty.
- TD-8 primitive grep over `ktor/src/*Main` — empty.
- `git diff --name-only <base>...HEAD` — no `core/**`.
- README passes the three-pass documentation review.

## 16. Risk register

| Risk | From design | Likelihood | Impact | Mitigation / owner |
|---|---|---|---|---|
| No Ktor version links on all 12 targets from the 2.3.20 compiler | [§13.1](ktor-fetcher-kit-technical-design.md) | Low–Med | High (blocks parity) | T0.1 spike + version ladder; last-resort Kotlin patch bump escalated to orchestrator |
| `coroutines-test` (1.8.1 from `testing`) vs resolved coroutines skew breaks `commonTest` | [§13.1](ktor-fetcher-kit-technical-design.md) | Med | Med | T0.1 aligns the whole kotlinx stack with an explicit constraint |
| A caller's `HttpCache` silently defeats revalidation | [§13.2](ktor-fetcher-kit-technical-design.md) | Med (in the field) | High | Fail-fast factory check (T2.4) + regression test (T3.3) |
| Ktor API detail wrong (execute scope, header replace, `expectSuccess`) | [§8](ktor-fetcher-kit-technical-design.md) | Med | Med | T2.3 micro-spike on tripwire; transport tests (T3.1) catch it |
| klib API dump unstable across Ktor patch | [§12](ktor-fetcher-kit-technical-design.md) | Low | Med | Pin the version (T0.1); regenerate dump only on a deliberate bump (T5.3) |
| Late rename (`ktor` → `ktor-fetcher`) after CI wiring | [§16](ktor-fetcher-kit-technical-design.md) | Low | Med | Resolve the name in T0.2 **before** T6.1 |
| Validator-lifetime limitation surprises a reviewer as a "bug" | [§3.6](ktor-fetcher-kit-technical-design.md) | Med | Low | Pin it with the T3.2 lifetime test and document it in the README (T5.1) |

## 17. Rollback

Every task is on the feature branch; nothing lands on `store6` until the PR merges. If a phase fails
irrecoverably: (a) the module is self-contained under `ktor/**`, so reverting the branch removes it
cleanly; (b) the CI edits are additive lines in `store6.yml` and `settings.gradle` that revert with
the branch; (c) because no `core/**` file is touched (T7.2), a rollback cannot destabilize the read
core or any other extension. The design docs can stay even if the code is deferred — they are the
record of the decision.

## 18. Budget roll-up

Focus budgets sum to roughly **17–18 hours of agent execution** across all tasks, dominated by the
spike (T0.1, 90m), the `KtorFetcher` core (T2.3, 100m), the two test tasks (T3.1/T3.2, 75m each),
and the full build/verify passes (T7.1, 75m). With the parallelization in
[§5](#5-dependency-graph-and-parallelization) — Sol and Grok working concurrently after T1.1 and
again after T2.4 — the critical path is roughly **T0.1 → T1.1 → (T2.1|T2.2) → T2.3 → T2.4 → T3.2 →
Phase 3 exit → T5.3 → T6.1 → T7.1 → T7.3 → T7.4**, about **9–10 hours** of wall-clock if the two
implementers stay busy in parallel and no tripwire forces a reassignment. These are agent-execution
figures for planning the run, not a delivery schedule.
