# Conflict-strategy pack — implementation plan

Status: reviewed draft (revision 2, after two adversarial model reviews) · Companion to
[design.md](./design.md) · Base branch: `store6`

This plan is written for execution by **one orchestrator agent (Fable)** dispatching **sub-agents
(GPT Sol 5.6 and Cursor Grok 4.6)**. It decomposes the design into tasks with explicit inputs,
deliverables, acceptance gates, model assignments, and **time budgets** so the orchestrator can
keep every agent focused, detect drift early, and apply the cut lines deliberately instead of
under pressure.

## 1. How to use this plan (orchestrator contract)

1. Execute phases (§4) in order. **All tasks that touch the `mutations-conflicts` module run in
   a single serialized lane** — the repository is one shared worktree, and every module gate
   compiles the whole module, so a second agent's half-written file fails the first agent's
   gate. The only true parallelism is T2 (workflow YAML) alongside T1.
2. Dispatch every sub-agent with the **context capsule** (§7) plus its task card. Sub-agents do
   not read this plan end to end; the card plus capsule plus the design sections the card names
   is their whole brief.
3. After every task: review the diff yourself against the task card's deliverables, run the
   task's acceptance gates if the sub-agent's transcript does not show them passing, then commit
   (one commit per task, §8) and push.
4. Enforce budgets (§2). At a soft-budget report, decide: continue, narrow scope, or take over.
   At a hard cap, stop the agent, keep whatever passes gates, and re-plan the remainder.
5. The orchestrator owns all commits, every cross-task integration decision, all edits to shared
   files (`settings.gradle`, `store6.yml`) after Phase 1, and the final exit-gate sweep (T11).

## 2. Time-budget system

Budgets are **agent wall-clock minutes per dispatched run**, covering reading, editing, and
command execution. They are supervision instruments, not effort estimates.

- **Soft budget**: the point where the sub-agent must stop starting new work and report status —
  what passed, what is unfinished, current blockers. The orchestrator decides the next move.
- **Hard cap**: the point where the orchestrator terminates the run and salvages gate-passing
  work. Nothing merges from a capped run without gates passing.
- Sub-agent prompts state both numbers and the reporting duty. Every prompt also carries the
  standing rule: **if a gate command fails twice for the same reason, stop and report rather
  than iterating** — two failed repairs signal a wrong assumption, and assumptions are the
  orchestrator's to fix.
- **Gradle reality**: this is a 12-target KMP repository. First-use Kotlin/Native toolchain
  provisioning and native compilation dominate wall time. The **verification ladder** (§3)
  keeps iteration on the JVM lane; the expensive lanes run once, in T9, against a Kotlin/Native
  toolchain that T0 pre-warms. An agent compiling native targets per edit will blow its cap
  without being wrong about anything else.
- **Cut trigger**: if cumulative spend exceeds **480 minutes** before Phase 3 (T7 + T8) is
  complete, the orchestrator invokes the cut protocol (§11).
- **CI wait time is not task time.** T11's budget covers active work; waiting on GitHub Actions
  (the `linux-build-test` job alone has a 120-minute timeout) is unbudgeted monitoring.

| Task | Owner | Soft | Cap |
|---|---|---:|---:|
| T0 Environment smoke, branch, toolchain warm-up | Orchestrator | 25 | 45 |
| T1 Module scaffold + vocabulary types | Grok 4.6 | 25 | 40 |
| T2 CI wiring | Grok 4.6 | 15 | 25 |
| T3 serverWins / clientWins / lastWriteWins + unit tests | Sol 5.6 | 45 | 75 |
| T4 threeWay + unit tests | Grok 4.6 | 25 | 40 |
| T5 fields + builder + unit tests | Sol 5.6 | 60 | 100 |
| T6 Shared integration fixtures + smoke | Grok 4.6 | 50 | 80 |
| T7 Integration suite | Sol 5.6 | 60 | 100 |
| T8 Restart-determinism suite | Grok 4.6 | 30 | 50 |
| T9 API dumps + full-module verification | Grok 4.6 | 40 | 100 |
| T10 README + docs snippets | Sol 5.6 | 40 | 60 |
| T11 Exit-gate sweep + PR (active work; CI wait excluded) | Orchestrator | 30 | 45 |
| **Total** | | **445** | **760** |

Model assignment rationale: Sol 5.6 takes the API-shape-sensitive, KDoc-heavy, and
semantics-heavy tasks (T3, T5, T7, T10); Grok 4.6 takes the well-specified mechanical and
matrix-shaped tasks (T1, T2, T4, T6, T8, T9). If a Sol task caps out twice, the orchestrator
takes it over rather than rotating models.

## 3. Verification ladder (referenced by every task)

Run from the repository root. Ordered cheap-to-expensive; a task's gates name the highest rung it
must clear. Never run root-level `build` or `clean build` locally.

| Rung | Command | Use |
|---|---|---|
| L1 | `./gradlew :mutations-conflicts:compileKotlinJvm` (production) / `:mutations-conflicts:compileTestKotlinJvm` (tests) | Per-edit compile check |
| L2 | `./gradlew :mutations-conflicts:jvmTest` | Per-change test iteration |
| L3 | `./gradlew :mutations-conflicts:apiDump -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true` | Regenerate BCV dumps (compiles all klib targets) |
| L4 | `./gradlew :mutations-conflicts:build -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true` | Full module gate: all targets, default tests, `apiCheck` |
| L5 | `./gradlew :mutations-conflicts:jsNodeTest -Dorg.gradle.configureondemand=false` | JS lane, mirroring the CI job's environment |

The L3/L4 property flags mirror the `store6.yml` build invocations. Apple-simulator and macOS
test tasks cannot run on the Linux execution VMs; they are validated by the `apple-tests` CI job
after push. Do not spend budget attempting them locally.

## 4. Phases and task order

```text
Phase 0   T0                                  (orchestrator)
Phase 1   T1 ∥ T2                             (disjoint files; T2 has no module-compile gate)
Phase 2   T3 → T4 → T5 → T6                   (module lane, strictly serial)
Phase 3   T7 → T8                             (module lane, strictly serial)
Phase 4   T10 → T9                            (module lane; T9 is last so dumps and the full
                                               build cover the final tree, snippets included)
Phase 5   T11                                 (orchestrator)
```

Why serial: T3, T4, and T5 all extend the same two source files (`MutationMerges.kt` and
`MutationConflictBuilderExtensions.kt` — the namespace object cannot be split across files), and
every module task's L1/L2 gate compiles the whole module's compilation units in the one shared
worktree. Per-agent git worktrees were considered and rejected: they trade gate reliability for
merge overhead the budgets don't carry. T4 must not be dispatched until T3's files exist on
disk; likewise down the lane.

## 5. Ground rules for every sub-agent (embedded in the context capsule)

1. **Scope fence.** Never edit anything under `core/` or `mutations/`. If a task appears to
   require it, stop and report — that is a design error, not an implementation detail.
2. **Read before writing.** Each task card lists input files; read them before the first edit.
   The design document is the specification; where design and card disagree, stop and report.
3. **Kotlin surface rules.** `explicitApi()` is strict. Every public declaration — including
   constructor properties — carries `@ExperimentalStoreApi` (mirror
   `mutations/src/commonMain/.../MutationProtocol.kt`). Files that use marked API open with
   `@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)`.
4. **Documentation discipline** (repository AGENTS.md): no issue-tracker IDs, initiative names,
   or process shorthand in Kotlin sources or workflow files; KDoc states contracts, not
   narration; no hype, no invented precision; label uncertainty or omit.
5. **Banned in production sources** (CI-enforced): `InternalStoreApi`,
   `org.mobilenativefoundation.store6.core.internal`, `runBlocking`, `GlobalScope`, `atomicfu`,
   `Channel`, `actor`. Pack production code additionally contains no `try`/`catch`.
6. **Test conventions.** `kotlin.test` assertions. Every test file (not fixture files) defines
   its own private copy of the repository's shadow-timeout shim, exactly:

   ```kotlin
   import kotlinx.coroutines.test.runTest as coroutineRunTest

   private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
       coroutineRunTest(timeout = 25.seconds, testBody = testBody)
   ```

   Turbine is not expected in this pack; if a card forces it, copy the Turbine-deadline shim
   comment block byte-identically from a Turbine-using test in `mutations/src/commonTest`.
7. **Phase vocabulary.** The durable journal phase `REFRESH_REQUIRED` surfaces publicly as
   `MutationPendingState.REFRESHING` in `pending()`/`pendingWrites()`. Journal rows say
   `REFRESH_REQUIRED`; inspection rows say `REFRESHING`.
8. **Commits.** Do not commit or push; report the working tree. The orchestrator reviews,
   commits, and pushes.
9. **Budget duty.** At the soft budget, stop starting new work and report. Two identical gate
   failures: stop and report.

## 6. Task cards

### T0 — Environment smoke, branch, toolchain warm-up (Orchestrator · soft 25 / cap 45)

- **Do:**
  1. `git fetch origin store6` and create the working branch from `origin/store6` per the
     active branch-naming instructions; push it.
  2. Toolchain smoke: `./gradlew :mutations:tasks --quiet` completes on JDK 17.
  3. Kotlin/Native warm-up (de-risks T9): `./gradlew :mutations:compileKotlinLinuxX64
     -Pkotlin.native.enableKlibsCrossCompilation=true` — provisions `~/.konan` and builds the
     `core`/`mutations` klibs the pack's native compilations will depend on.
  4. Check `python3 -c "import yaml"`; record availability for T2's optional gate.
  5. `git status --porcelain` clean; `git diff --stat origin/store6` empty.
- **Gates:** all five steps succeed (step 4 may record "unavailable").
- **Escalate:** Gradle configuration failure or Kotlin/Native provisioning failure → fix the
  environment before dispatching anything.

### T1 — Module scaffold + vocabulary types (Grok 4.6 · soft 25 / cap 40)

- **Inputs:** design §4–§5 (§5 preamble and code block are normative), §10;
  `settings.gradle`; `graphql/build.gradle.kts` (the dependency-shape template: production dep
  plus `commonTest` test deps); `mutations-testing/gradle.properties` (publishing template);
  `mutations-testing/src/androidMain/AndroidManifest.xml` (copy its bytes exactly).
- **Do:**
  1. `settings.gradle`: add `include ':mutations-conflicts'` beside the other `mutations-*`
     includes.
  2. `mutations-conflicts/build.gradle.kts`: plugin
     `org.mobilenativefoundation.store.store6.multiplatform`; `commonMain`
     `api(projects.mutations)`; `commonTest` `implementation(projects.testing)` and
     `implementation(libs.kotlinx.coroutines.test)` (the convention plugin already supplies
     `kotlin("test")`); `android { namespace = "org.mobilenativefoundation.store6.mutations.conflicts" }`.
     Do **not** copy `mutations-testing/build.gradle.kts`'s dependency shape — that module is
     itself a test library and exposes test deps as `api` in `commonMain`.
  3. `mutations-conflicts/gradle.properties`: `VERSION_NAME=6.0.0-SNAPSHOT`,
     `POM_NAME=mutations-conflicts`, `POM_ARTIFACT_ID=mutations-conflicts`.
  4. `mutations-conflicts/src/androidMain/AndroidManifest.xml`: byte-identical copy of the
     `mutations-testing` manifest.
  5. First source file
     `src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/conflicts/MutationConflictVocabulary.kt`
     containing the `MutationMergeFunction<V>` typealias and `MutationConflictBias` enum exactly
     as design §5 specifies, KDoc included.
- **Deliverables:** the five files above.
- **Gates:** L1 (production) succeeds; `./gradlew :mutations-conflicts:jvmTest` exits 0 —
  `NO-SOURCE`/`SKIPPED` task outcomes are success (no tests exist yet).
- **Don't:** add sample modules, Swift wiring, dokka `Module.md`, or any policy logic.
- **Escalate:** convention plugin resolution failure; any need to touch `tooling/`.

### T2 — CI wiring (Grok 4.6 · soft 15 / cap 25)

- **Inputs:** design §10; `.github/workflows/store6.yml` (whole file).
- **Do:** seven line-level edits at these exact steps (`name:` keys quoted verbatim):
  1. `linux-build-test` → `Build Store6 mutation journal support`: extend the Gradle line to
     `:mutations-testing:build :mutations-sqldelight:build :mutations-conflicts:build`.
  2. `linux-build-test` → `Reject core-internal access from extension modules`: add
     `mutations-conflicts` to the `for module in …` list.
  3. `linux-build-test` → `Enforce the TD-8 primitive whitelist and single-writer residence`:
     add `mutations-conflicts/src/*Main` to `production_source_dirs=(…)`.
  4. `linux-build-test` → `JS lock-discipline canary (full conformance suite on the JS lane)`:
     add `:mutations-conflicts:jsNodeTest`.
  5. `apple-tests` → `Run Store6 Apple tests`: add `:mutations-conflicts:iosSimulatorArm64Test \`
     and `:mutations-conflicts:macosArm64Test \` lines.
  6. `klib-publication-check` → `Publish Store6 core to Maven local without signing`: add
     `:mutations-conflicts:publishToMavenLocal`.
  7. `klib-publication-check` → `Verify common and target publications`: add
     `mutations-conflicts` to `modules=(…)`.
- **Deliverables:** `store6.yml` only.
- **Gates:** content checks, all seven:
  `rg -c ':mutations-conflicts:build|mutations-conflicts ' .github/workflows/store6.yml` style
  greps confirming each token above landed at its named step (one `rg -n 'mutations-conflicts'
  .github/workflows/store6.yml` listing exactly seven hits at the expected steps is sufficient);
  plus, if T0 recorded PyYAML available,
  `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/store6.yml'))"`.
- **Don't:** touch the `Census — default jvmTest…` step, `docs-sync-sources.txt`, `ci.yml`, or
  any other workflow; do not add or reword workflow comments.
- **Escalate:** any named step missing from the file.

### T3 — serverWins / clientWins / lastWriteWins + unit tests (Sol 5.6 · soft 45 / cap 75)

- **Inputs:** design §5 preamble + §5.1–§5.3 + §6 (normative); `MutationStoreBuilder.kt` (door
  signatures and generics), `MutationProtocol.kt` (`MutationPresence`,
  `MutationConflictResolution`).
- **Do:**
  1. Create `MutationMerges.kt`: the namespace object with `serverWins()`, `clientWins()`,
     `lastWriteWins(onMineAbsent, onTheirsAbsent, writtenAt)` per design §5, with the §5.2
     termination paragraph and the §5.3 stamp-bumping precondition and bare-call consequence
     carried in KDoc.
  2. Create `MutationConflictBuilderExtensions.kt`: `serverWins()`, `clientWins()`,
     `lastWriteWins(...)` extensions delegating to the factories via the `merge(...)` door.
     (T4 and T5 will extend both files; leave no placeholder stubs.)
  3. Unit tests `MutationMergesTerminalsTest.kt` and `MutationMergesLastWriteWinsTest.kt` with
     exactly the §10 test names for these families. Identity assertions use
     `assertIs<MutationConflictResolution.Retry<String>>(resolution)` then
     `assertSame(mine, resolution.value)` — legitimate at this layer because the factory output
     is called directly; the engine's copies are the integration layer's concern.
- **Gates:** L1, L2.
- **Don't:** implement `threeWay`/`fields`; no integration tests; no `try`/`catch` in
  production code (a throwing `writtenAt` propagates by construction).
- **Escalate:** the door signature differs from design §2; extension/member resolution
  ambiguity inside `conflicts { }` that requires renaming.

### T4 — threeWay + unit tests (Grok 4.6 · soft 25 / cap 40)

- **Inputs:** design §5 preamble + §5.4 + §6; T3's `MutationMerges.kt` and
  `MutationConflictBuilderExtensions.kt` (both exist on disk before dispatch; extend both).
- **Do:** add the `threeWay(onMineAbsent, onTheirsAbsent, merge)` factory to
  `MutationMerges.kt` and the `threeWayMerge(...)` extension to
  `MutationConflictBuilderExtensions.kt`; add `MutationMergesThreeWayTest.kt` with exactly the
  §10 test names for this family (nine tests: both-present with base `Present` and with base
  `Absent`, equality-no-shortcut, the four absent-cell bias cases, both-absent, merger-throw
  propagation).
- **Gates:** L1, L2.
- **Escalate:** any signature drift from design §5.

### T5 — fields + MutationFieldMergeBuilder + unit tests (Sol 5.6 · soft 60 / cap 100)

- **Inputs:** design §5 preamble + §5.5 (normative, every contract bullet) + §5.4 (the
  presence matrix `fields` inherits) + §6; T3/T4 files (extend both).
- **Do:**
  1. Create `MutationFieldMergeBuilder.kt`: the builder class with both `field` overloads;
     registrations snapshotted into an immutable list when the factory returns;
     escaped-builder `IllegalStateException`; empty-block `IllegalArgumentException` from the
     factory.
  2. Add `MutationMerges.fields(...)` to `MutationMerges.kt` and `mergeFields(...)` to
     `MutationConflictBuilderExtensions.kt`.
  3. Unit tests `MutationMergesFieldsTest.kt` with exactly the §10 test names for this family —
     including the inherited outer presence cells (mine/theirs absent × both biases, both
     absent) and separate throw-propagation tests for `get`, `set`, and `combine`.
- **Gates:** L1, L2.
- **Don't:** add per-field bias enums, `KProperty`-specific overloads, or reflection.
- **Escalate:** any contract bullet in §5.5 that proves unimplementable as written.

### T6 — Shared integration fixtures + smoke (Grok 4.6 · soft 50 / cap 80)

- **Inputs:** design §8 (as amended: client id captured from pushes; retirement left
  unconfirmed); `mutations/src/commonTest/.../MutationsTestFixtures.kt` (reference for
  `FakeBackend` shape) and the `openConflictStore` helper inside
  `mutations/src/commonTest/.../MutationConflictTest.kt` (reference for the exact
  `mutationStore(...)` wiring); `MutationStore.kt` (factory signature);
  `mutations/src/commonMain/.../storage/MutationJournalStorage.kt` (transaction API).
- **Do:** one file `src/commonTest/.../ConflictsPackTestFixtures.kt` providing, and nothing
  more:
  1. A `StoreKey` implementation and a `MutationKeyResolver` for it (the factory requires the
     resolver).
  2. Value types and codecs: plain `String` with a string codec, and a stamped type
     `data class Stamped(val text: String, val writtenAtEpochMillis: Long)` with its codec, for
     the LWW/threeWay integration and restart tests.
  3. `mutatorRegistry` fixtures: one `upsert` mutator per value type (the `String` one declaring
     a `StaleSet` key effect) and one `delete` mutator.
  4. A fake `MutationServer` with scriptable `pushBehavior`/`retireBehavior`, recorded pushes,
     and a seedable fetch source — **default `retireBehavior` returns
     `MutationRetirementAck(confirmedThroughSequence = 0L)`** so journal rows survive pruning
     for assertions.
  5. `conflictException(meta: StoreMeta?, message: String = "conflict", cause: Throwable =
     IllegalStateException("backend conflict cause"))` built as
     `StoreResults.exception(StoreResults.conflict(meta, message), cause)`; a plain
     `StoreMeta` implementation for conflict metadata.
  6. `openStore(...)` helpers (one per value type) mirroring `openConflictStore`: full
     `mutationStore(registry, server, keyResolver, valueCodecVersion = 1, valueCodec) { … }`
     call with `fetcherOfResult`, `journalStorage`, `wallClock` (a `TestWallClock`), and an
     optional `conflicts { }` configurer.
  7. `capturedClientId(server)`: reads `clientId` from the first recorded push and verifies
     `transaction.client(id)` is non-null; fails with a descriptive message otherwise.
  8. A smoke test file `ConflictsPackFixturesSmokeTest.kt` (with its own private `runTest`
     shim): open a store, `mutate`, `drain(key)`, assert one ack recorded and the intent
     retired in the journal via `capturedClientId`.
  No `runTest` shim in the fixtures file itself — each test file carries its own private copy.
- **Gates:** L1 (`compileTestKotlinJvm`), L2 (smoke test green).
- **Escalate:** anything in the fixture requiring non-public mutations API.

### T7 — Integration suite (Sol 5.6 · soft 60 / cap 100)

- **Inputs:** design §8.2 (normative list) + §2 + §6; T6 fixtures; `MutationConflictTest.kt` as
  the pattern reference for journal-row assertions, clock stepping, and drain usage.
- **Facts the tests depend on (from the design, restated so the card is closed):** keyed
  `drain(key)` bypasses drain backoff; conflict receipts stamp the clock, so advance the
  `TestWallClock` (`clock.advanceBy(2.seconds)`) between the receipt-producing drain and the
  resume drain, exactly as `MutationConflictTest` does; journal phase `REFRESH_REQUIRED`
  surfaces as `REFRESHING` in `pendingWrites()`.
- **Do:** `MutationConflictsPackIntegrationTest.kt` with exactly the §10 integration test
  names, covering:
  1. client-wins: conflict on g1 → journal shows g2 with `base` = recaptured theirs, `mine` =
     original projection, a fresh `generationIdempotencyKey`; server accepts g2; intent retires.
  2. explicit server-wins policy: retires with no second push; the declared effect row is
     terminally `SKIPPED`.
  3. lastWriteWins over `Stamped`: newer mine retries and wins; older/tie mine retires to
     server state; one local-delete case per bias knob.
  4. threeWay: merged value observed in g2's `mineBlob` and pushed.
  5. unchanged-bound: client-wins against a server returning an identical `(writtenAt, etag)`
     meta on every push parks after the third conflicted generation — kind `CONFLICT`, detail
     `"conflict-unchanged-bound"`, visible in `deadLetters()`.
  6. composition, proven on both doors: `clientWins()` plus a caller `precondition { }` whose
     selector stamps generation-distinct metadata; assert the selector's metadata on the g1
     **and** g2 attempt rows (proves the selector re-ran for the retry generation) and that g2
     exists at all (proves the canned merge ran).
  7. codec-rejecting `Retry` value: a policy returning a value the codec cannot encode
     propagates out of `drain(key)`; the intent remains `REFRESHING` in `pendingWrites()`.
- **Gates:** L2 (all tests green on JVM).
- **Don't:** assert on event ordering beyond existence; no Turbine.
- **Escalate:** any behavior observed to differ from design §2/§6 — that is reportable
  evidence, not something to paper over in the test.

### T8 — Restart-determinism suite (Grok 4.6 · soft 30 / cap 50)

- **Inputs:** design §8.3 (as amended); T6 fixtures; the restart patterns in
  `MutationConflictTest.kt` — `assertMatrixCell(..., restartAfterReceipt = true)` and
  `runRefreshRequiredRestartCase` (same `InMemoryMutationJournalStorage` instance, `close()`,
  fresh `mutationStore`, drain). Ignore the fail-point tests; `FailPointJournalStorage` is not
  available to this module.
- **Facts the tests depend on:** same three facts as T7's card (keyed drain, clock advance,
  phase vocabulary).
- **Do:** `MutationConflictsPackRestartTest.kt` with exactly the §10 restart test names. Shape
  per test: run an **uninterrupted control** (mutate → conflicted drain → clock advance →
  resume drain) and record the outcome fingerprint — terminal journal phase, attempt
  generations, g2 `mineBlob`/`baseBlob`, idempotency keys, push count; then run the
  **restarted** variant (close the store while `REFRESH_REQUIRED`, reopen over the same
  storage instance with the same policy, clock advance, drain) and assert the identical
  fingerprint. Four cases: serverWins, clientWins, lastWriteWins (over `Stamped`), threeWay.
- **Gates:** L2.
- **Escalate:** hydration behavior that contradicts design §2.

### T9 — API dumps + full-module verification (Grok 4.6 · soft 40 / cap 100)

- **Precondition:** T0's Kotlin/Native warm-up completed (a cold `~/.konan` invalidates this
  card's budget); every earlier module task landed.
- **Inputs:** all merged source; design §5 (the public-surface allowlist) and §14.
- **Do:** run L3; review the generated `mutations-conflicts/api/` dumps against this exact
  expected surface — `MutationConflictBias` (enum + generated `values`/`valueOf`/`entries`
  machinery), `MutationMerges` object with five functions, `MutationFieldMergeBuilder` with two
  `field` overloads, five top-level builder-extension functions, the `MutationMergeFunction`
  typealias (klib dump only; JVM/Android `.api` dumps do not list typealiases), file-facade
  classes (`*Kt`) for the top-level declarations, and `@ExperimentalStoreApi` markers
  throughout. **Any other public symbol is a finding — report it, do not re-dump around it.**
  Then run L4 and L5.
- **Deliverables:** generated, reviewed dumps left in the working tree
  (`api/mutations-conflicts.klib.api`, `api/jvm/mutations-conflicts.api`,
  `api/android/mutations-conflicts.api`) for the orchestrator to commit.
- **Gates:** L3, L4, L5 all green; the dump-vs-allowlist review recorded in the report.
- **Escalate:** any target failing to compile; unexpected public surface; Kotlin/Native
  failures that survive one clean retry.

### T10 — README + docs snippets (Sol 5.6 · soft 40 / cap 60)

- **Inputs:** design §11 (normative) + §5–§7 tables; `realtime/README.md` and
  `graphql/README.md` (shape); the documentation-discipline and code-documentation skills under
  `plugins/internal/documentation/skills/` (read both `SKILL.md` files).
- **Do:**
  1. `src/commonTest/.../docs/ConflictsPackDocsSnippet.kt`: five compiled snippets with these
     exact marker ids — `mutations-conflicts-pack-server-wins`,
     `mutations-conflicts-pack-client-wins`, `mutations-conflicts-pack-last-write-wins`
     (showing a projector that bumps the stamp), `mutations-conflicts-pack-three-way`,
     `mutations-conflicts-pack-fields` — each snippet exercised by a `@Test` method in the same
     file so L2 executes it (private `runTest` shim included).
  2. `mutations-conflicts/README.md` per design §11: lede (promise + experimental tier +
     STABILITY.md link), Install (coordinate + 12-target sentence), one section per policy with
     its decision table, shared semantics (§6), observability pattern (§7), and the two loud
     warnings (LWW stamp precondition; unregistered-fields rule) each with an anti-example.
     README code blocks mirror the snippet bodies.
  3. Run the documentation three-pass review (accuracy / warrant / reader utility) and include
     the pass notes in the task report.
- **Gates:** L2 (snippet tests green); README claims match shipped signatures exactly.
- **Escalate:** any README claim that cannot be evidenced by a test or source line.

### T11 — Exit-gate sweep + PR (Orchestrator · soft 30 / cap 45 active work; CI wait excluded)

- **Do:** walk design §14 gate by gate. Zero-diff check:
  `git diff --stat origin/store6 -- core mutations` empty **and**
  `git status --porcelain -- core mutations` empty. Coverage check: grep the shipped test files
  for every name in §10 of this plan; any missing name is a failure, not a judgment call. Push;
  create or update the PR with the exit-gate checklist and the three-pass documentation review
  note in the body. Monitor CI (`linux-build-test`, `apple-tests`, `klib-publication-check`)
  and mark the PR ready only when green; monitoring time is unbudgeted.
- **Gates:** design §14, all six items.

## 7. Context capsule (prepend to every sub-agent prompt)

> You are implementing part of the `mutations-conflicts` extension for the Store6 repository at
> the workspace root. The binding specification is
> `docs/design/conflict-strategy-pack/design.md`; read the sections named in your task card
> before editing. Ground rules: never edit `core/` or `mutations/`; `explicitApi()` is strict
> and every public declaration including constructor properties carries `@ExperimentalStoreApi`;
> files using marked API open with
> `@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)`; no
> issue-tracker IDs or project shorthand in sources or workflows; KDoc states contracts without
> hype or narration; banned tokens in production sources: `InternalStoreApi`,
> `org.mobilenativefoundation.store6.core.internal`, `runBlocking`, `GlobalScope`, `atomicfu`,
> `Channel`, `actor`; pack production code contains no `try`/`catch`. Tests use `kotlin.test`,
> and every test file defines its own private shim exactly:
> `import kotlinx.coroutines.test.runTest as coroutineRunTest` /
> `private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult = coroutineRunTest(timeout = 25.seconds, testBody = testBody)`.
> The durable journal phase `REFRESH_REQUIRED` surfaces publicly as
> `MutationPendingState.REFRESHING`. Iterate on
> `./gradlew :mutations-conflicts:compileKotlinJvm` / `:mutations-conflicts:compileTestKotlinJvm`
> and `:mutations-conflicts:jvmTest`; run nothing heavier unless your card says so. Do not
> commit or push — report your working tree, gate results, and minutes spent. Budget: soft N
> minutes (stop starting new work and report), hard cap M minutes. If a gate fails twice for
> the same reason, stop and report.

## 8. Orchestrator protocol details

- **Single-lane dispatch:** at most one module-lane task (T1, T3–T10) in flight at any time.
  T2 may run alongside T1 only.
- **Commit convention:** one commit per accepted task, message = imperative summary of the
  task's deliverable (e.g. `Add mutations-conflicts module scaffold`). The orchestrator commits
  T9's dumps. Push after each commit. PR bodies may cite issues; source files may not.
- **Review before commit:** diff-read every file against the task card; run the highest gate
  rung the card names if the transcript is not conclusive; reject work that edits out-of-scope
  files rather than trimming it yourself (re-dispatch with a narrower card).
- **Failure handling:** first gate failure inside a task is the sub-agent's to fix (within
  budget). A capped or twice-failed task gets one re-dispatch with a narrowed card; a second
  failure moves the task to the orchestrator. Evidence from T7/T8 that engine behavior
  contradicts design §2/§6 pauses the phase: the orchestrator verifies against `mutations`
  sources, then either fixes the pack or amends the design — an amendment requires a written
  rationale in the design document's status line and the PR body, and re-issuing every affected
  card. Never adjust a test to mask a contradiction.
- **Reporting cadence:** after each phase, the orchestrator records in the PR description's
  running checklist: gates green, cumulative minutes vs budget, variances, and any cuts.

## 9. Risk register

| Risk | Detection | Mitigation |
|---|---|---|
| Kotlin/Native provisioning or compile time blows T9 | L3/L4 exceeding soft budget | T0 warm-up is mandatory; T9's cap assumes warm `~/.konan`; one clean retry then escalate |
| Sub-agent edits `mutations/` "just a little" | `git status` in review | Hard scope fence in capsule; reject at review; T11 zero-diff gate is the backstop |
| Decision-table drift between design, KDoc, tests, README | T11 name-grep against §10 | Design tables are normative; T3/T4/T5 copy them into KDoc; T10 copies from shipped KDoc, not from memory |
| `conflicts { }` extension/member resolution surprises | T3's L1 gate | T3 escalation path; fallback is factories-only (`merge(MutationMerges.clientWins())`), which is also cut 2 |
| Integration flakiness from drain scheduling/backoff | Repeated L2 runs disagree | Keyed `drain(key)` bypasses backoff; `TestWallClock.advanceBy(2.seconds)` between receipt and resume — stated on the T7/T8 cards, not just here |
| Journal rows pruned before assertion | Empty `intents(...)`/`attempts(...)` in T7/T8 | Fixture default `retireBehavior` confirms nothing (`confirmedThroughSequence = 0L`) — stated on the T6 card |
| Census or docs-sync CI steps accidentally modified | T2 gate greps + orchestrator diff review | Card forbids it explicitly |
| Snippet id collision with existing `mutations-conflicts-policy` | T10 card fixes ids | `mutations-conflicts-pack-*` prefix, enumerated per snippet |
| BCV dumps reveal accidental public surface | T9 dump-vs-allowlist review | `internal` by default; only design-§5 names public |
| Budget spent re-deriving engine semantics | Sub-agent reports reading `MutationEngine.kt` at length | Cards restate the load-bearing engine facts; engine reading is escalation-only |

## 10. Design-fidelity checklist for T11 (normative test names)

Unit — `MutationMergesTerminalsTest`: `serverWins_returnsServerWinsForEveryPresenceCombination`,
`clientWins_returnsRetryMineWhenMinePresent`, `clientWins_returnsRetryAbsentWhenMineAbsent`,
`clientWins_returnsSameMineInstance`.

Unit — `MutationMergesLastWriteWinsTest`: `lastWriteWins_newerMineRetriesMine`,
`lastWriteWins_tieResolvesServerWins`, `lastWriteWins_olderMineResolvesServerWins`,
`lastWriteWins_mineAbsentTheirsBias_serverWins`, `lastWriteWins_mineAbsentMineBias_retryAbsent`,
`lastWriteWins_theirsAbsentTheirsBias_serverWins`,
`lastWriteWins_theirsAbsentMineBias_retryMine`, `lastWriteWins_bothAbsent_serverWins`,
`lastWriteWins_writtenAtThrowPropagates`.

Unit — `MutationMergesThreeWayTest`: `threeWay_bothPresent_basePresent_mergerReceivesBaseValue`,
`threeWay_bothPresent_baseAbsent_mergerReceivesNullBase`,
`threeWay_mergedValueEqualToTheirsStillRetries`, `threeWay_mineAbsentTheirsBias_serverWins`,
`threeWay_mineAbsentMineBias_retryAbsent`, `threeWay_theirsAbsentTheirsBias_serverWins`,
`threeWay_theirsAbsentMineBias_retryMine`, `threeWay_bothAbsent_serverWins`,
`threeWay_mergerThrowPropagates`.

Unit — `MutationMergesFieldsTest`: `fields_basePresent_neitherChanged_keepsTheirs`,
`fields_basePresent_onlyMineChanged_appliesMine`,
`fields_basePresent_onlyTheirsChanged_keepsTheirs`,
`fields_basePresent_identicalChange_keepsTheirs`,
`fields_basePresent_contested_defaultTheirsBias`, `fields_basePresent_contested_mineBias`,
`fields_basePresent_contested_combineRoutes`, `fields_baseAbsent_equalFields_keepTheirs`,
`fields_baseAbsent_differingFields_contested`, `fields_registrationOrderAppliesOverSingleCanvas`,
`fields_overlappingLensesLastRegistrationWins`, `fields_unregisteredFieldResolvesToTheirs`,
`fields_canvasStartsFromTheirs`, `fields_arrayFieldComparesByIdentity`,
`fields_combineReceivesWholeBaseValueOrNull`, `fields_emptyBlockThrowsIllegalArgument`,
`fields_escapedBuilderThrowsIllegalState`, `fields_getThrowPropagates`,
`fields_setThrowPropagates`, `fields_combineThrowPropagates`,
`fields_mineAbsentTheirsBias_serverWins`, `fields_mineAbsentMineBias_retryAbsent`,
`fields_theirsAbsentTheirsBias_serverWins`, `fields_theirsAbsentMineBias_retryMine`,
`fields_bothAbsent_serverWins`.

Integration — `MutationConflictsPackIntegrationTest`:
`clientWins_conflictRetriesGenerationTwoWithRecapturedBase`,
`serverWinsPolicy_retiresWithoutSecondPushAndSkipsEffects`,
`lastWriteWins_newerMineWinsThroughRetry`, `lastWriteWins_staleMineRetiresToServerState`,
`lastWriteWins_localDeleteWithMineBias_pushesDeletion`,
`lastWriteWins_localDeleteWithTheirsBias_retires`, `threeWay_mergedValuePushedOnRetry`,
`unchangedConflictBound_parksClientWinsOnThirdIdenticalReceipt`,
`cannedPolicyComposesWithCallerPrecondition`,
`codecRejectingRetryValuePropagatesAndStaysRefreshing`.

Restart — `MutationConflictsPackRestartTest`: `restart_serverWins_matchesUninterruptedOutcome`,
`restart_clientWins_matchesUninterruptedOutcome`,
`restart_lastWriteWins_matchesUninterruptedOutcome`,
`restart_threeWay_matchesUninterruptedOutcome`.

Any missing name is a T11 failure. Renames are allowed only by editing this list in the same
commit, with the reason in the commit message.

## 11. Cut protocol

Triggered by the §2 cut trigger or a twice-capped T5. Cuts apply in design §13 order, each with
its dependency sweep:

1. **Cut `fields`/`mergeFields` (T5):** also remove the `MutationMergesFieldsTest` block from
   §10, the `mutations-conflicts-pack-fields` snippet and README section from T10, and
   `MutationFieldMergeBuilder` + `fields` + `mergeFields` from T9's dump allowlist. T7/T8 are
   unaffected (no fields integration case exists).
2. **Cut the builder-extension layer:** remove `MutationConflictBuilderExtensions.kt` from
   T3/T4 deliverables and the five extensions from T9's allowlist; README and snippets show
   `merge(MutationMerges.clientWins())` registration instead.

Every applied cut is recorded in the design document (§13) and the PR body in the same commit.
