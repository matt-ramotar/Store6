# Background drain scheduler — implementation plan

> **For the orchestrating agent:** execute task-by-task with fresh sub-agents per task,
> reviewing between tasks. Steps use checkbox (`- [ ]`) syntax for tracking. The normative
> behavioral spec is [background-drain-scheduler.md](./background-drain-scheduler.md) in
> this directory ("the design"); every task names the design sections its implementer must
> read first. Where this plan and the design disagree, stop and reconcile before coding —
> report the conflict, do not pick silently.

**Goal:** ship two experimental artifacts — `mutations-drain` (DrainScheduler SPI +
MutationDrainCoordinator over the public `MutationStore` drain/inspection surface) and
`mutations-drain-meeseeks` (adapter over `dev.mattramotar.meeseeks:runtime:1.1.0`) — with
the design's §6.2/§6.4/§9 tables fully test-certified, zero diff to `core` and `mutations`.

**Architecture:** the journal is the outbox; the coordinator derives all scheduling
decisions from `pendingWrites()` state plus policy (per-identity heads, state table,
no-progress escalation); schedulers are constraint-gated at-least-once alarms; Meeseeks
supplies the OS alarms on android/jvm/ios/js. See design §5.

**Tech stack:** Kotlin Multiplatform (Kotlin 2.3 floor), repo convention plugins
(`store6.multiplatform`, `store6.multiplatform.subset`), kotlinx-coroutines,
kotlinx-coroutines-test + turbine for tests, kotlinx-serialization (adapter only),
binary-compatibility-validator dumps, Meeseeks 1.1.0.

## Orchestration protocol

**Agents.** One orchestrator (holds this plan and the design; reviews and integrates) and
sub-agents executing one task each (Sol 5.6 and Cursor Grok 4.6). Affinity hints per task:
`[mech]` = intricate concurrency/derivation work, favor Sol; `[wire]` = scaffolding,
build wiring, mechanical edits, favor Grok. Hints are advisory; any agent may take any
task.

**Time budgets.** Each task states `Budget: soft/hard` in minutes of sub-agent wall time.
Semantics:

- The sub-agent self-checks at the soft budget: if the exit criteria are not close, finish
  the current step, commit compiling work-in-progress on the task branch state, and report
  status + blockers to the orchestrator instead of pushing on.
- The hard budget (1.5× soft) is a stop line: at hard, the sub-agent stops even mid-step
  and reports. The orchestrator then decides: split the task, reassign with the partial
  report, or descope against the design's open questions.
- Separately from budgets: 15 minutes stuck on the same build/environment error (not
  logic) → stop and report; environment problems are orchestrator-level, not
  grind-through-level.
- Budgets exclude orchestrator review time. They assume the reading list is read; reading
  is inside the budget, which is why every budget includes a 10–15 min floor for it.

**Per-task loop (orchestrator).** Dispatch task with: the task text, the design-section
reading list, the repo paths, and the branch name. On completion: (1) run the task's
verify commands yourself or via the sub-agent's transcript; (2) diff-review against the
design tables; (3) check no files outside the task's declared Files were touched (`git
status`); (4) accept → next task, or bounce with a concrete defect list (one bounce per
task before escalating to a rewrite-by-other-agent).

**Dependency graph (parallelization map).**

```
A1 ─ A2 ─┬─ A3 ─┬─ A4 ─┬─ A6 ─ A7 ─┬─ A9 ─ A10 ─┐
         │      └─ A5 ─┘           │            │
         └─ A8 ────────────────────┤            ├─ D1 ─ D2
         └─ C1 ─ C2 ─ (A6) ─ C3 ─ C4 ─ C5 ──────┤
                        (A7) ─ B1 ──────────────┘
```

Safe parallel lanes after A2 lands: {A3→A4→…}, {A8}, {C1→C2}. B1 needs A7. C3 needs A6
(coordinator public API published). D1 needs every module to exist; D2 is last and
serial. Do not parallelize two tasks that both edit `settings.gradle` or `store6.yml`
(A1, B1, C1, D1) — serialize those four or accept rebase churn.

**Total soft budget:** 970 minutes (~16 h of sub-agent time; wall time lower with the
parallel lanes).

**Escalation rules.**

- Any test revealing the design's §6.4 derivation to be wrong (not merely the
  implementation) → stop the lane, report to orchestrator; design amendment is
  orchestrator-level.
- C4 verification failures against real Meeseeks are **coordination items, not
  hack-arounds** (design §8.3 item 1): report the exact failing behavior with a minimal
  repro; do not fork behavior on undocumented Meeseeks internals.
- Never modify files under `core/`, `mutations/` (except reading), or any committed `api/`
  dump of another module. If a task seems to require it, stop and report — that violates
  the design's zero-diff goal.

**Verification cheat sheet** (from repo root; JVM-first because it is the fast lane):

```bash
./gradlew :mutations-drain:jvmTest                       # seam tests
./gradlew :mutations-drain:apiDump                       # regen dumps after public API change
./gradlew :mutations-drain:apiCheck                      # dump drift gate
./gradlew :mutations-drain:compileKotlinIosSimulatorArm64  # native compile spot-check (macOS runner only)
./gradlew :mutations-drain-meeseeks:jvmTest              # adapter tests (real Meeseeks on Quartz)
./gradlew :mutations-drain-sample:run                    # sample end-to-end
```

Linux CI runners cannot run Apple targets; treat Apple compilation as CI-verified unless
the orchestrator runs on macOS.

## Global constraints (apply to every task)

- Kotlin floor 2.3; JVM toolchain and Android minSdk 24 / compileSdk 36 come from the
  convention plugin — do not restate them per module.
- `explicitApi()` is enforced by the convention plugin: every public declaration needs an
  explicit visibility and return type.
- Every public symbol in both new artifacts carries `@ExperimentalStoreApi`
  (import `org.mobilenativefoundation.store6.core.ExperimentalStoreApi`).
- Public API uses plain classes with `val`s — **no data classes, no `copy`, no
  `equals`/`hashCode`** on public types (repo norm: `PendingIntent`, `MutationFailure`).
- No new dependencies in `mutations-drain` beyond `api(projects.mutations)`. No
  `kotlin-reflect`. No `GlobalScope`, no `runBlocking` in production sources, no
  `Dispatchers.Main`.
- Zero diff to `core` and `mutations` sources and dumps.
- Documentation surfaces follow the repo's AGENTS.md discipline: no issue-tracker IDs or
  internal shorthand in KDoc/source comments (module READMEs may link issues; source may
  not); no hype; every claim verifiable.
- Commit per task with the task's stated message; never batch two tasks into one commit;
  never amend or force-push.
- TDD ordering inside every task: failing test → run to see the failure → implement → pass
  → `apiDump` if the public surface changed → commit.
- Coroutine tests use `kotlinx.coroutines.test.runTest` with an explicit timeout
  (`runTest(timeout = 25.seconds)`), matching the mutations module's wrapper pattern.

---

## Phase A — `mutations-drain` (the seam)

### Task A1: Module scaffold `[wire]`

**Budget:** 30/45 min.
**Read first:** design §6 (header block), §13; `/workspace/realtime/build.gradle.kts`,
`/workspace/realtime/gradle.properties`, `/workspace/settings.gradle`.

**Files:**
- Modify: `settings.gradle` (one `include` line, placed with the mutations family)
- Create: `mutations-drain/build.gradle.kts`
- Create: `mutations-drain/gradle.properties`
- Create: `mutations-drain/src/androidMain/AndroidManifest.xml`
- Create: `mutations-drain/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/drain/DrainPlaceholder.kt` (deleted in A2)

**Interfaces:** produces a compiling, publishable-shaped empty module every later A-task
edits.

- [ ] **Step 1:** Add to `settings.gradle` after the `':mutations-sqldelight'` include:

```groovy
include ':mutations-drain'
```

- [ ] **Step 2:** Create `mutations-drain/gradle.properties`:

```properties
VERSION_NAME=6.0.0-SNAPSHOT
POM_NAME=mutations-drain
POM_ARTIFACT_ID=mutations-drain
```

- [ ] **Step 3:** Create `mutations-drain/build.gradle.kts`:

```kotlin
plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.mutations)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.mutations.drain"
}
```

- [ ] **Step 4:** Create `mutations-drain/src/androidMain/AndroidManifest.xml`:

```xml
<manifest />
```

(Copy the exact content of `realtime/src/androidMain/AndroidManifest.xml` if it differs.)

- [ ] **Step 5:** Create the placeholder source file so the module has a compilation unit:

```kotlin
@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain

private const val DRAIN_MODULE_PLACEHOLDER: Int = 0
```

- [ ] **Step 6:** Verify: `./gradlew :mutations-drain:compileKotlinJvm :mutations-drain:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.
- [ ] **Step 7:** Generate initial (empty) dumps: `./gradlew :mutations-drain:apiDump`,
then verify `mutations-drain/api/` contains jvm/android `.api` files and a `.klib.api`.
- [ ] **Step 8:** Commit: `Scaffold mutations-drain module`

**Exit criteria:** module compiles on jvm+android; dumps committed; `settings.gradle`
diff is one line.

---

### Task A2: Public API types `[mech]`

**Budget:** 45/70 min.
**Read first:** design §6.1 in full (it contains the normative KDoc), §2 (repo API norms);
`/workspace/mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationInspection.kt`
(plain-class style), `core` seam `WallClock` (`rg "interface WallClock" core/src`).

**Files:**
- Delete: `DrainPlaceholder.kt`
- Create (package `org.mobilenativefoundation.store6.mutations.drain`, one type per file
  in `mutations-drain/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/drain/`):
  `DrainConstraints.kt`, `DrainBackoff.kt`, `DrainPolicy.kt`, `DrainRequest.kt`,
  `DrainScheduler.kt`, `DrainPassOutcome.kt`, `DrainSchedulerEvents.kt`,
  `DrainWallClock.kt`
- Test: `mutations-drain/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/drain/DrainBackoffTest.kt`

**Interfaces (produces — later tasks compile against these exact shapes):**
- All §6.1 public types except `MutationDrainCoordinator`, `mutationDrainCoordinator`,
  `InProcessDrainScheduler` (those land in A5–A8). For `DrainScheduler`, `attach` takes
  `MutationDrainCoordinator`, which does not exist yet — declare the interface in this
  task with the `attach` line commented out and a `// A5 adds attach(coordinator)` marker
  is FORBIDDEN (no placeholders); instead: declare `DrainScheduler` in this task WITHOUT
  `attach`, and A5 adds `attach` in the same commit that introduces the coordinator type.
  Produces additionally: `internal object DrainSystemWallClock : WallClock`.
- `DrainBackoff.delayFor(attempt: Int): Duration` (public function on the class):
  `min(initialDelay * multiplier^(attempt-1), maxDelay)` for `attempt >= 1`; require
  `attempt >= 1`.

- [ ] **Step 1:** Write the failing test:

```kotlin
@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DrainBackoffTest {
    @Test
    fun defaults_are30sTimes2CappedAt1h() {
        val backoff = DrainBackoff()
        assertEquals(30.seconds, backoff.delayFor(1))
        assertEquals(60.seconds, backoff.delayFor(2))
        assertEquals(30.seconds * 128, backoff.delayFor(8).coerceAtMost(2.hours)) // capped below
        assertEquals(1.hours, backoff.delayFor(8))
        assertEquals(1.hours, backoff.delayFor(20))
    }

    @Test
    fun multiplierOne_isConstantFloor() {
        val backoff = DrainBackoff(initialDelay = 10.seconds, multiplier = 1.0, maxDelay = 1.hours)
        assertEquals(10.seconds, backoff.delayFor(1))
        assertEquals(10.seconds, backoff.delayFor(9))
    }

    @Test
    fun validation_rejectsZeroInitialNonFiniteAndInvertedBounds() {
        assertFailsWith<IllegalArgumentException> { DrainBackoff(initialDelay = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { DrainBackoff(initialDelay = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { DrainBackoff(multiplier = 0.5) }
        assertFailsWith<IllegalArgumentException> { DrainBackoff(multiplier = Double.NaN) }
        assertFailsWith<IllegalArgumentException> {
            DrainBackoff(initialDelay = 10.minutes, maxDelay = 1.minutes)
        }
        assertFailsWith<IllegalArgumentException> { DrainBackoff(maxDelay = Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { DrainBackoff().delayFor(0) }
    }

    @Test
    fun requestAndConstraintDefaults() {
        val constraints = DrainConstraints()
        assertEquals(true, constraints.requiresNetwork)
        assertEquals(false, constraints.requiresCharging)
        val policy = DrainPolicy()
        assertEquals(true, policy.drainOnEnqueue)
    }
}
```

- [ ] **Step 2:** Run `./gradlew :mutations-drain:jvmTest`; expected: compilation failure
(types missing).
- [ ] **Step 3:** Implement the types. Copy the signatures and KDoc from design §6.1
verbatim (they are normative), adding:

```kotlin
// DrainBackoff.kt — the class from design §6.1 plus:
    init {
        require(initialDelay > Duration.ZERO && initialDelay.isFinite()) {
            "initialDelay must be positive and finite; was $initialDelay."
        }
        require(maxDelay.isFinite() && maxDelay >= initialDelay) {
            "maxDelay must be finite and >= initialDelay; was $maxDelay."
        }
        require(multiplier.isFinite() && multiplier >= 1.0) {
            "multiplier must be finite and >= 1.0; was $multiplier."
        }
    }

    /** The §6.4 delay for a head with the given completed-attempt count; [attempt] >= 1. */
    @ExperimentalStoreApi
    public fun delayFor(attempt: Int): Duration {
        require(attempt >= 1) { "attempt must be >= 1; was $attempt." }
        var delay = initialDelay
        repeat(attempt - 1) {
            if (delay >= maxDelay) return maxDelay
            delay = delay * multiplier
        }
        return minOf(delay, maxDelay)
    }
```

(The iterative form avoids Double-power overflow for large attempts.)

```kotlin
// DrainWallClock.kt
@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain

import org.mobilenativefoundation.store6.core.seam.WallClock

/** System wall clock stamping advisory drain events. */
internal expect object DrainSystemWallClock : WallClock
```

with per-platform `actual` objects returning epoch millis (jvm/android:
`System.currentTimeMillis()`; native: `NSDate` on Apple via
`(NSDate().timeIntervalSince1970 * 1000).toLong()` or `kotlin.system.getTimeMillis()`
where available; js/wasmJs: `Date.now().toLong()`). **Check first** whether `mutations`
already ships an internal system clock pattern to copy
(`rg "MutationsSystemWallClock" mutations/src` — replicate its expect/actual layout
exactly; it exists and is the proven 12-target implementation).
`DrainSchedulerEvents.kt` holds the sealed interface + six event classes from §6.1 with
internal constructors (library-only construction, like `MutationEvent` types).
- [ ] **Step 4:** Run `./gradlew :mutations-drain:jvmTest`; expected: PASS.
- [ ] **Step 5:** `./gradlew :mutations-drain:apiDump && ./gradlew :mutations-drain:apiCheck`.
- [ ] **Step 6:** Commit: `Add mutations-drain public policy and SPI types`

**Exit criteria:** all §6.1 types except coordinator/in-process exist with normative KDoc;
validation tests pass; dumps updated; no data classes (`rg "data class" mutations-drain/src/commonMain` is empty).

---

### Task A3: Test fixtures `[mech]`

**Budget:** 60/90 min.
**Read first:** design §12 (test strategy);
`/workspace/mutations/src/commonTest/.../MutationsTestFixtures.kt` (fixture idioms — NOT
importable across modules, replicate minimally),
`/workspace/mutations/src/commonTest/.../MutationDrainTriggerTest.kt` lines 110–200
(store-opening pattern), `MutationProtocol.kt` (`MutationServer`, `MutationAck`,
`MutationRetirementAck`).

**Files:**
- Create: `mutations-drain/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/drain/DrainTestFixtures.kt`
- Create: `.../drain/RecordingDrainScheduler.kt` (commonTest)

**Interfaces (produces — every later A-task test consumes):**
- `internal class DrainTestKey(id: String) : StoreKey` (namespace `"drain"`, `canonicalId() = id`)
- `internal object DrainTestKeyResolver : MutationKeyResolver<DrainTestKey>`
- `internal class DrainFixtureBackend : MutationServer<DrainTestKey, String>` with
  `var offline: Boolean`, `val receivedPushes: List<...>`, scriptable
  `pushBehavior`/`retireBehavior` including a mode where `retire` throws (checkpoint
  failure) — model on `FakeBackend` but keep only what §12 tests need
- `internal class DrainFixture` bundling `storage: InMemoryMutationJournalStorage`,
  `backend`, `clock: WallClock` driven by `testScheduler.currentTime`, `appendRef`, and
  `fun openStore(): MutationStore<DrainTestKey, String>` via `mutationStore(...)` with
  `journalStorage(storage)` + `wallClock(clock)` + `fetcher { backend.load(it) }`
- `internal class RecordingDrainScheduler : DrainScheduler` recording
  `schedule`/`cancel`/`validate` invocations in order, with a settable
  `var scheduleThrows: Throwable?`, `var validateThrows: Throwable?`, and (after A5 adds
  `attach`) holding the coordinator for manual `fireActivation(name)` helpers — in this
  task, implement without `attach` and extend in A6 when activations become runnable.

- [ ] **Step 1:** Write the fixtures, modeling directly on the verified upstream idioms:
key + resolver + string args codec exactly as `MutationsTestKey` /
`MutationsTestKeyResolver` / `FixtureStringArgsCodec` do (same shapes, `drain` namespace);
registry via `mutatorRegistry { mutator(id = "drain-append", version = 1, codec = ..., stales = { _, _ -> StaleSet(emptySet(), emptySet()) }) { base, suffix -> MutationPresence.Present(((base as? MutationPresence.Present)?.value).orEmpty() + suffix) } }`;
backend implementing `push` (offline → throw; else ack Present) and `retire`
(scriptable success `MutationRetirementAck(confirmedThroughSequence = ...)` / throw).
- [ ] **Step 2:** Write a fixture smoke test in the same file's companion test class:
open store → `mutate` → `drain()` → assert `pendingWrites()` empty and one received push;
then `offline = true` → mutate → drain → assert `pendingWrites().single().attempt == 1`
and `state == MutationPendingState.PENDING`.
- [ ] **Step 3:** Run `./gradlew :mutations-drain:jvmTest`; expected PASS. If the
`MutationServer`/ack shapes fight you for more than 20 minutes, stop and re-read the
upstream `FakeBackend` push-unwrapping (`request.mine`, `request.key` accessors) rather
than guessing — then continue.
- [ ] **Step 4:** Commit: `Add mutations-drain test fixtures`

**Exit criteria:** smoke test green on jvm; fixtures expose exactly the Interfaces list;
no production-source changes.

---

### Task A4: Delay derivation, fingerprint, escalation `[mech]`

**Budget:** 75/115 min.
**Read first:** design §6.4 in full (normative tables), §6.6, §2 rows on
`pendingWrites()`/`PendingIntent`/state mapping.

**Files:**
- Create: `mutations-drain/src/commonMain/.../drain/internal/DelayDerivation.kt`
- Test: `mutations-drain/src/commonTest/.../drain/DelayDerivationTest.kt`

**Interfaces (produces — A6 consumes):**

```kotlin
// package org.mobilenativefoundation.store6.mutations.drain.internal
internal class DerivationState(
    val previousFingerprint: Set<List<Any>>?, // null = absent initial state (⊥)
    val noProgressPasses: Int,                // k
)

internal class DerivationResult(
    val delay: Duration?,        // null = Cleared (nothing to schedule)
    val pendingIntents: Int,
    val nextState: DerivationState,
)

internal fun deriveFollowUp(
    rows: List<PendingIntent>,
    checkpointFailed: Boolean,
    backoff: DrainBackoff,
    state: DerivationState,
): DerivationResult
```

Fingerprint element per row (frozen by review): `listOf(mutationId, namespace,
canonicalId, state, attempt)`; the fingerprint set is a multiset — implement as
`Map<List<Any>, Int>` counts or sorted list; equality must treat duplicates correctly.
`⊥` (null previous) never equals anything, including empty. `k` transitions exactly:
equal fingerprints → `k+1`; otherwise → `0`. Delay rules exactly per §6.4:
heads = first row per `(namespace, canonicalId)` in given order; per-head ZERO for
`INFLIGHT`/`ADOPTING`/`APPLYING_EFFECTS` or `attempt == 0`, else
`backoff.delayFor(attempt)`; `derived = min(heads)`; checkpoint-only (rows empty,
checkpointFailed) → `max(backoff.initialDelay, escalation(k))`; rows empty and no
checkpoint failure → `delay = null`; non-empty → `max(derived, escalation(k))`.
`escalation(k) = ZERO` when `k == 0` else `backoff.delayFor(k)` (same formula — document
that reuse in KDoc).

- [ ] **Step 1:** Write failing tests covering, as separately named test functions
(construct `PendingIntent` rows via the A3 fixture by driving a real store — the type has
an internal constructor, so rows must come from `pendingWrites()`; drive scenarios:
offline-fail for attempt≥1 heads, plain mutate for attempt=0, two keys for multi-head,
two mutates on one key for head+suffix):
  1. `clearedWhenNoRowsAndNoCheckpointFailure` → `delay == null`, k resets per rule.
  2. `suffixNeverLowersDelay` — one key, two intents, head failed once (attempt=1,
     PENDING), suffix attempt=0 → delay == `backoff.delayFor(1)` (the §12.2 busy-loop
     regression).
  3. `freshHeadDerivesZero` — attempt=0 head → ZERO.
  4. `multiHeadTakesMinimum` — key A head attempt=3, key B head attempt=0 → ZERO.
  5. `checkpointOnlyUsesInitialFloorAndEscalates` — rows empty + checkpointFailed, k=0 →
     `initialDelay`; same again (equal empty fingerprints) → k=1 →
     `max(initialDelay, escalation(1)) == initialDelay`; at k=3 with multiplier 2 →
     `initialDelay * 4`.
  6. `bottomNeverEqualsEmpty` — first-ever pass with empty rows: k stays 0.
  7. `escalationGrowsToMaxAndResetsOnProgress` — identical non-empty fingerprints across
     passes grow k; any row change (drive one more mutate) resets to 0.
  8. `multiplierOneKeepsConstantFloor`.
(For state-table rows the fixture cannot easily produce — `ADOPTING`, `APPLYING_EFFECTS`,
`INFLIGHT` — drive them where feasible: `INFLIGHT` via a backend push that suspends on a
`CompletableDeferred` while the pass is cancelled, per the upstream `MutationBackoffTest`
cancellation idiom; `ADOPTING` is coverable by a `pushBehavior` ack + a `persistence` SoT
whose write throws — if that exceeds 25 minutes of fixture work, cover those two rows in
A6's integration tests instead and note it in the task report.)
- [ ] **Step 2:** Run; expected: fails (function missing).
- [ ] **Step 3:** Implement `DelayDerivation.kt` per the Interfaces block.
- [ ] **Step 4:** Run; expected: PASS. Also run `./gradlew :mutations-drain:apiCheck` —
this file is `internal`, dumps must NOT change; if they did, visibility is wrong.
- [ ] **Step 5:** Commit: `Add drain follow-up delay derivation with no-progress escalation`

**Exit criteria:** all named tests green; dump unchanged; derivation matches §6.4 tables
row-for-row.

---

### Task A5: Coordinator registry and lifecycle `[mech]`

**Budget:** 50/75 min.
**Read first:** design §6.1 (`MutationDrainCoordinator`, `mutationDrainCoordinator`,
`DrainScheduler.attach`), §9 rows 1–4 and the close row, §6.5.

**Files:**
- Create: `mutations-drain/src/commonMain/.../drain/MutationDrainCoordinator.kt`
  (class + factory; pass logic arrives in A6 — in this task `runActivation`, `watch`,
  `reconcile` are declared but implemented as the §9-correct trivial forms:
  `runActivation` returns `Unavailable(name, "not registered")` for unknown/closed and
  otherwise delegates to an internal `runPass` that this task implements as
  `TODO()`-FREE minimal form: acquire mutex, return `Unavailable(name, "pass not implemented")`
  is NOT acceptable — instead structure the class so A6 fills one internal function, and
  in THIS task only registry/lifecycle members are public-behavior-complete; keep
  `runActivation`/`watch`/`reconcile` out of this task's commit by implementing the class
  in two files? No — single file, and this task implements registry + close + attach and
  declares the three pass members calling an internal `notYetWired(name)` that throws
  `NotImplementedError`. That error never ships: A6 and A7 replace it, and A10's README
  lands after. The BCV dump records only signatures, so the public surface is final from
  this task.)
- Modify: `DrainScheduler.kt` — add the `attach(coordinator: MutationDrainCoordinator)`
  member with the §6.1 KDoc.
- Modify: `RecordingDrainScheduler.kt` — implement `attach` (store ref; second call
  fails test-style with `error(...)`).
- Test: `.../drain/CoordinatorLifecycleTest.kt`

**Interfaces (produces):** the full public `MutationDrainCoordinator` surface and
`mutationDrainCoordinator(scheduler, wallClock = DrainSystemWallClock)` factory exactly as
design §6.1; internal `class Registration(store, policy, epoch: Long)` map guarded by a
`Mutex`; name regex `Regex("[A-Za-z0-9._-]{1,64}")`.

- [ ] **Step 1:** Failing tests (named): `attachHappensExactlyOnceViaFactory` (factory
attaches; constructing a second coordinator on the same scheduler throws
`IllegalStateException` from `attach`), `registerValidatesConstraintsThroughScheduler`
(RecordingDrainScheduler.validateThrows → register throws IAE),
`registerRejectsDuplicateNameInvalidNameAndSameStoreTwice`,
`unregisterCancelsTrackedActivationAndIsIdempotent` (cancel recorded once; second
unregister no-op), `closeMakesRegistrationApisThrowAndRunActivationUnavailable`
(post-close `register`/`unregister`/`reconcile`/`watch` throw ISE — `watch`/`reconcile`
assertions may use the A5 stub behavior only for the throw-before-work part —
and `runActivation("x")` returns `Unavailable`).
- [ ] **Step 2:** Run → fail. **Step 3:** Implement. **Step 4:** Run → PASS;
`apiDump` + `apiCheck`.
- [ ] **Step 5:** Commit: `Add drain coordinator registry and lifecycle`

**Exit criteria:** §9 registry rows certified; public coordinator surface frozen in
dumps; `NotImplementedError` present only inside the two unwired pass members.

---

### Task A6: runActivation — pass execution, outcome, safety activation `[mech]`

**Budget:** 90/135 min.
**Read first:** design §6.1 (`runActivation` KDoc — the five-step sequence), §6.3, §6.4
outcome table, §9 rows (pass mutex, unregister-mid-pass epoch suppression, closed store,
cancel-during-activation), §11 (event emission points).

**Files:**
- Modify: `MutationDrainCoordinator.kt` (replace the `runActivation` stub; add internal
  event bus mirroring `MutationEventBus`: replay 0, extraBufferCapacity 64, DROP_OLDEST,
  tryEmit)
- Modify: `RecordingDrainScheduler.kt` — add `suspend fun fireActivation(name): DrainPassOutcome`
  delegating to the attached coordinator (test convenience)
- Test: `.../drain/RunActivationTest.kt`

**Interfaces (consumes):** A4 `deriveFollowUp`; A3 fixtures. **Produces:** working
`runActivation` + `events` emissions (`DrainActivationStarted`, `DrainPassCompleted`,
`DrainPassFailed`, `DrainScheduleFailed`, `DrainActivationScheduled`,
`DrainActivationCancelled` per §6.1 emission rules).

Implementation notes (frozen by review; do not re-derive):

- Per-name `Mutex`; **the pass is the only acquirer** (watch/reconcile never lock it
  directly — they call `runActivation`/internal pass which locks).
- Entry paths: `runActivation` persists the safety activation
  (`schedule(DrainRequest(name, constraints, backoff.maxDelay))`) BEFORE the pass; the
  internal fast-path entry used by `watch` (A7) skips it. Model as
  `internal suspend fun runPass(name: String, persistSafety: Boolean): DrainPassOutcome`
  with `runActivation(name) = runPass(name, persistSafety = true)`.
- Safety-persist failure: catch, emit `DrainScheduleFailed`, proceed with the pass.
- Checkpoint observation (frozen shape): inside the pass, before `drain()`:

```kotlin
val checkpointFailed = atomic(false) // or a plain var guarded by the pass; single-threaded within the pass
coroutineScope {
    val observer = launch(start = CoroutineStart.UNDISPATCHED) {
        store.events
            .filterIsInstance<MutationCheckpointFailed>()
            .collect { checkpointFailed.value = true }
    }
    try {
        store.drain()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        drainThrew = true // emit DrainPassFailed with sanitized message later
    }
    yield() // post-pass barrier: let an already-buffered emission be collected
    observer.cancelAndJoin()
}
```

- Outcome: read `pendingWrites()` (catch `IllegalStateException` → `Unavailable`), call
  `deriveFollowUp`, store `nextState` in the registration, then: `delay == null` →
  cancel tracked activation (`scheduler.cancel(name)` — emit `DrainActivationCancelled`
  only when the scheduler had a tracked pending request; the recording double reports
  that), return `Cleared`; `delay != null` → epoch check (registration unchanged?) then
  `scheduler.schedule(DrainRequest(name, constraints, delay))` inside the registry mutex
  snapshot check; schedule-throw → `DrainScheduleFailed` + `Remaining(count, null)`.
- `CancellationException` from `drain()` propagates after the safety persist (KDoc
  contract); do not run derivation on that path.

- [ ] **Step 1:** Failing tests, named (drive stores via fixtures; assert on
`RecordingDrainScheduler` invocation log + returned outcomes + `coordinator.events` via
turbine):
  1. `clearedCancelsTrackedActivation` — online mutate → fire → Cleared; recorded
     `schedule(maxDelay)` then `cancel`.
  2. `transportFailureSchedulesBackoffDelay` — offline → fire → Remaining(1, 30s);
     recorded follow-up delay 30s.
  3. `passRespectsEngineGate_noRetryStorm` — offline fire twice quickly: second pass
     inside engine window pushes nothing (backend push count unchanged) and reschedules
     (escalation may raise delay); no exception.
  4. `postAckAdoptionFailureDerivesZeroThenEscalates` — pushBehavior acks but SoT write
     throws (or, if A4 deferred this row, use backend ack + a `persistence(...)` fixture
     SoT that throws once): first outcome Remaining(…, ZERO-floored-by-escalation
     semantics per §6.4), repeated identical pass escalates.
  5. `checkpointFailureAloneKeepsRemaining` — retireBehavior throws; journal otherwise
     clears → Remaining(0, initialDelay); `DrainPassCompleted` shows 0 pending.
  6. `safetyActivationPersistsBeforeDrainAndIsReplaced` — invocation order in the log:
     schedule(maxDelay) precedes backend push; afterwards schedule(30s) replaces.
  7. `safetyPersistFailureStillRunsPass` — scheduleThrows on first call only →
     `DrainScheduleFailed` emitted, pass ran, outcome derived.
  8. `cancellationMidPassLeavesSafetyTracked` — backend push suspends; cancel the
     `fireActivation` job → safety request still the tracked one; store journal shows
     INFLIGHT row (replayable).
  9. `unregisterMidPassSuppressesFollowUp` — backend suspends; unregister; release →
     pass completes, no follow-up schedule recorded after the unregister's cancel.
  10. `closedStoreYieldsUnavailable` + `unknownNameYieldsUnavailable` +
      `postCloseYieldsUnavailable`.
  11. `concurrentActivationsSerializePerName` — two fireActivation jobs; assert pass
      bodies did not interleave (fixture backend records concurrent push depth) and both
      returned.
  12. `eventsCarryFields` — turbine-assert `DrainActivationStarted`/`DrainPassCompleted`
      fields (`storeName`, counts, `occurredAtEpochMillis` from the injected test clock).
- [ ] **Step 2:** Run → fail. **Step 3:** Implement per notes. **Step 4:** PASS + `apiCheck`
(surface should not change; events classes already landed in A2).
- [ ] **Step 5:** Commit: `Wire drain pass execution with safety activations and outcome derivation`

**Exit criteria:** §6.4 outcome table and §9 pass rows each map to a named green test;
no `NotImplementedError` remains in `runActivation`.

---

### Task A7: watch and reconcile `[mech]`

**Budget:** 60/90 min.
**Read first:** design §6.1 (`watch`, `reconcile` KDoc), §6.2 matrix, §9 watch rows.

**Files:**
- Modify: `MutationDrainCoordinator.kt` (implement `watch` + `reconcile`)
- Test: `.../drain/WatchAndReconcileTest.kt`

Frozen loop shape (review-mandated; implement exactly this structure):

```kotlin
public suspend fun watch(name: String): Nothing {
    val registration = requireRegistration(name) // IAE if unknown; ISE if closed
    coroutineScope {
        val kicks = Channel<Unit>(Channel.CONFLATED)
        val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
            registration.store.events.collect { event ->
                if (event is MutationEnqueued) kicks.trySend(Unit)
            }
        }
        registration.closed.invokeOnCompletionCancel(this) // wire epoch/close → cancel this scope (implement via a registration Job the coordinator cancels in unregister/close)
        runPass(name, persistSafety = true) // launch reconciliation: unconditional
        for (kick in kicks) {
            if (registration.policy.drainOnEnqueue) {
                runPass(name, persistSafety = false) // fast path: no safety churn
            } else {
                scheduleZero(registration)
            }
        }
        subscription.cancelAndJoin()
        awaitCancellation()
    }
}
```

(`invokeOnCompletionCancel` is illustrative pseudo-wiring — implement registration-close
propagation with a per-registration `Job` stored at register time and cancelled by
`unregister`/`close`, made a parent of watch's `coroutineScope` work; the observable
contract is the §9 row: watcher completes with `CancellationException`.)

`reconcile()`: snapshot registrations under the registry mutex; for each, if its pass
mutex is locked, skip; else `runPass(name, persistSafety = true)`; sequential is fine.

- [ ] **Step 1:** Failing tests, named:
  1. `watchRunsUnconditionalLaunchPass` — pending row from a previous store session →
     `watch` (launched in a test scope) drains it without any enqueue.
  2. `watchLaunchPassRetriesCheckpointOnlyWork` — retire failed in previous session (rows
     empty) → launch pass calls retire again (backend retire count grows) — certifies the
     §10 checkpoint-recovery row.
  3. `enqueueDuringWatchDrainsFastPathWithoutSafetyChurn` — online mutate after watch is
     live → drained; scheduler log has NO schedule call (churn regression test).
  4. `enqueueBurstCoalescesToAtMostTwoPasses` — suspend backend, mutate 5× while first
     pass in flight, release → total passes ≤ 2 (count backend push-attempt batches or
     instrument runPass via events).
  5. `drainOnEnqueueFalseSchedulesZero` — policy(false) → mutate → recorded
     schedule(delay=ZERO), no pass.
  6. `nonEnqueueEventsIgnored` — drive a full drain (events like Acknowledged/Retired
     flow) → no extra passes beyond the triggering ones.
  7. `unregisterCancelsWatch` / `closeCancelsWatch` — watcher job completes with
     CancellationException.
  8. `watchUnknownNameThrows`.
  9. `reconcileSkipsMidPassStores` — store A pass suspended, reconcile() → A untouched
     (no second concurrent pass), B drained.
- [ ] **Step 2:** Run → fail. **Step 3:** Implement. **Step 4:** PASS + `apiCheck`.
- [ ] **Step 5:** Commit: `Add watch loop and launch reconciliation`

**Exit criteria:** §6.2 matrix rows all certified; coalescing test asserts ≤ 2; churn
test asserts zero schedule calls on the happy fast path.

---

### Task A8: InProcessDrainScheduler `[mech]`

**Budget:** 40/60 min.
**Read first:** design §6.1 (`InProcessDrainScheduler` KDoc), §9 in-process rows.

**Files:**
- Create: `mutations-drain/src/commonMain/.../drain/InProcessDrainScheduler.kt`
- Test: `.../drain/InProcessDrainSchedulerTest.kt`

Implementation: constructor `(private val scope: CoroutineScope)`; `attach` stores the
coordinator (second call → ISE); `validate {}` accepts everything; `schedule` requires
`scope.isActive` else ISE, then under an internal `Mutex`-guarded
`MutableMap<String, Job>`: cancel the previous job for the name, launch
`scope.launch { delay(request.earliestDelay); coordinator.runActivation(request.storeName) }`
and store it; `cancel(name)` cancels+removes. The launched job removes itself from the
map when it starts running (so a running activation is not "pending" and a concurrent
`schedule` starts a fresh timer rather than cancelling the running pass — matches the
§7.2 adopt-running posture).

- [ ] **Step 1:** Failing tests: `firesAfterDelayThroughCoordinator` (virtual time; use a
registered fixture store; assert drain happened), `replaceSupersedesPendingTimer` (two
schedules 10s apart → exactly one activation at the later request's delay),
`cancelPreventsFiring`, `deadScopeSchedulesThrow` (cancelled scope → ISE),
`concurrentScheduleKeepsOneTimer` (launch N concurrent schedule calls → one activation),
`runningActivationNotCancelledByReplace` (suspend backend mid-activation, schedule again
→ first pass completes; second timer fires later).
- [ ] **Step 2–4:** fail → implement → PASS; `apiDump`+`apiCheck` (public class added).
- [ ] **Step 5:** Commit: `Add in-process drain scheduler`

**Exit criteria:** listed tests green on jvmTest; class is the reference SPI
implementation with no constraint evaluation, as documented.

---

### Task A9: Integration guards `[mech]`

**Budget:** 50/75 min.
**Read first:** design §6.6 (alignment), §12.4/§12.9.

**Files:**
- Test: `.../drain/EngineAlignmentGuardTest.kt`
- Test: `.../drain/RestartReplayTest.kt`

- [ ] **Step 1:** `EngineAlignmentGuardTest`: with fixtures + `InProcessDrainScheduler` on
virtual time: go offline; watch live; mutate; let passes fail and escalate through
attempts 1..6 by advancing virtual time exactly by each `Remaining.scheduledDelay`; at
each round assert the backend saw exactly one new push attempt (the engine gate never
swallowed a scheduled pass — this is the property test that fails if
`BACKOFF_CAP_MILLIS`/formula ever outgrow the coordinator defaults). Then go online,
advance once more, assert Cleared.
- [ ] **Step 2:** `RestartReplayTest`: session 1: offline mutate → close store. Session 2
(same `InMemoryMutationJournalStorage`): new store + coordinator + watch → launch pass
drains after backend back online; assert pushed value matches and `pendingWrites()`
empty. Mirrors the design §10 "app killed with pending intents" row at jvm level.
- [ ] **Step 3:** PASS both; commit: `Add engine alignment and restart replay guards`

**Exit criteria:** both guards green; alignment test advances only by
coordinator-reported delays (no magic sleeps).

---

### Task A10: Seam README, docs snippet, dump audit `[wire]`

**Budget:** 45/70 min.
**Read first:** design §10, §11, §13 docs row; `/workspace/realtime/README.md` (template);
repo AGENTS.md (documentation discipline); `mutations/src/commonTest/.../docs/` snippet
pattern.

**Files:**
- Create: `mutations-drain/README.md`
- Create: `mutations-drain/src/commonTest/.../drain/docs/DrainQuickstartDocsSnippet.kt`
- Verify: `mutations-drain/api/` dumps final

README required content (structure after realtime's): what it is (one paragraph, "maps OS
scheduler activations onto `MutationStore.drain()` passes; owns no transport, no
connectivity monitor"); install block; entry points (coordinator factory, register, watch,
runActivation, reconcile, InProcessDrainScheduler); the §10 caveat list verbatim-in-spirit
(in-memory journal, iOS force-quit + foreground-reconnect one-liner hook, Doze, multi-process
unsupported, at-least-once passes); registration-name discipline (§6.5); pointer to
`mutations-drain-meeseeks` for OS backends. Every code block must be lifted from the
compiled docs snippet file. No issue IDs in the README body except the standard demand
links if realtime's README carries none — follow realtime exactly.

- [ ] **Step 1:** Write `DrainQuickstartDocsSnippet.kt` — a compiling commonTest file
exercising: build store (fixtures), `mutationDrainCoordinator(InProcessDrainScheduler(scope))`,
`register`, `watch` launch, manual `runActivation`. Assert it drains (it is a real test).
- [ ] **Step 2:** Write README quoting those snippets. Run the three-pass review from the
repo documentation skill (accuracy against signatures; warrant; reader utility).
- [ ] **Step 3:** `./gradlew :mutations-drain:jvmTest :mutations-drain:apiCheck` → PASS.
- [ ] **Step 4:** Commit: `Document mutations-drain`

**Exit criteria:** README code compiles in commonTest; caveats present; dumps clean.

---

## Phase B — sample

### Task B1: `mutations-drain-sample` `[wire]`

**Budget:** 45/70 min.
**Read first:** design §13 sample row, §14 gate 6; `/workspace/mutations-quickstart/`
(module shape, build file, main-class wiring), `settings.gradle` sample include patterns.

**Files:**
- Modify: `settings.gradle` (`include ':mutations-drain-sample'` +
  `project(':mutations-drain-sample').projectDir = file('mutations-drain/sample')` —
  match the exact idiom used by `realtime-sample`)
- Create: `mutations-drain/sample/build.gradle.kts` (JVM application, dependencies on
  `projects.mutationsDrain`; copy mutations-quickstart's plugin/mainClass shape)
- Create: `mutations-drain/sample/src/main/kotlin/.../DrainSchedulerSample.kt`

Sample narrative (printed steps, `mutations-quickstart` style): build a store with a
file-free in-memory journal shared across two "sessions" in one process; session 1:
backend offline, `mutate`, show `pendingWrites()`; "restart": close store, reopen over
the same storage, coordinator + `InProcessDrainScheduler` + `watch`; backend comes online;
show launch pass draining, print confirmed value and empty `pendingWrites()` — the design
§14 gate-6 storyline.

- [ ] **Step 1:** Wire module; **Step 2:** write sample; **Step 3:**
`./gradlew :mutations-drain-sample:run` → output shows offline enqueue → restart → drained.
- [ ] **Step 4:** Commit: `Add mutations-drain sample`

**Exit criteria:** sample runs green from a clean checkout command.

---

## Phase C — `mutations-drain-meeseeks` (the adapter)

### Task C1: Adapter scaffold + version catalog `[wire]`

**Budget:** 35/55 min.
**Read first:** design §7 header block, §13; `/workspace/room/build.gradle.kts` (subset
plugin usage), `/workspace/gradle/libs.versions.toml`.

**Files:**
- Modify: `gradle/libs.versions.toml` — add under versions/libraries/plugins as fits the
  file's existing sections:

```toml
meeseeks = "1.1.0"
meeseeks-runtime = { module = "dev.mattramotar.meeseeks:runtime", version.ref = "meeseeks" }
```

  and confirm `kotlin-serialization-plugin` already exists (it does — reuse; do not
  duplicate).
- Modify: `settings.gradle` (`include ':mutations-drain-meeseeks'`)
- Create: `mutations-drain-meeseeks/gradle.properties`
  (`POM_NAME`/`POM_ARTIFACT_ID=mutations-drain-meeseeks`, `VERSION_NAME=6.0.0-SNAPSHOT`)
- Create: `mutations-drain-meeseeks/build.gradle.kts`:

```kotlin
plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform.subset")
    alias(libs.plugins.kotlin.serialization) // verify exact alias name in libs.versions.toml [plugins]; add if absent
}

kotlin {
    androidTarget()
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    js {
        nodejs()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.mutationsDrain)
                api(libs.meeseeks.runtime)
                implementation(libs.kotlinx.serialization.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.mutations.drain.meeseeks"
}
```

  (If the catalog has no `kotlin.serialization` plugin alias, add
  `kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "baseKotlin" }`
  to `[plugins]` — check how sibling modules apply plugin aliases first and match.)
- Create: `mutations-drain-meeseeks/src/androidMain/AndroidManifest.xml` (`<manifest />`)
- Create: placeholder source (deleted in C2)

- [ ] **Steps:** wire → `./gradlew :mutations-drain-meeseeks:compileKotlinJvm` (resolves
Meeseeks from Maven Central) → `apiDump` → commit: `Scaffold mutations-drain-meeseeks module`

**Exit criteria:** compiles on jvm + js + android; meeseeks resolves; dumps committed.
If Meeseeks resolution fails on the CI/network, report — do not vendor.

---

### Task C2: Payload and mapping functions `[mech]`

**Budget:** 40/60 min.
**Read first:** design §7.1, §7.2 first table; Meeseeks API dump at
`https://raw.githubusercontent.com/matt-ramotar/meeseeks/main/runtime/api/jvm/runtime.api`
if signature doubt arises (or decompile from the resolved artifact — trust the artifact
over memory).

**Files:**
- Delete placeholder; create in
  `mutations-drain-meeseeks/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/drain/meeseeks/`:
  `StoreDrainPayload.kt`, `internal/TaskRequestMapping.kt`
- Test: `.../meeseeks/TaskRequestMappingTest.kt` (commonTest)

**Interfaces (produces):**

```kotlin
@Serializable
@ExperimentalStoreApi
public class StoreDrainPayload(public val storeName: String) : TaskPayload

// internal/TaskRequestMapping.kt
internal fun DrainRequest.toTaskRequest(): TaskRequest   // per design §7.2 table:
// payload = StoreDrainPayload(storeName)
// preconditions = TaskPreconditions(requiresNetwork, requiresCharging, requiresBatteryNotLow = false)
// priority = Meeseeks default (omit / TaskPriority default per constructor)
// schedule = TaskSchedule.OneTime(initialDelay = earliestDelay)
// retryPolicy = TaskRetryPolicy.FixedInterval(retryInterval = 30.seconds, maxRetries = null)

internal fun DrainPassOutcome.toTaskResult(): TaskResult  // per design §7.2 second table:
// Cleared -> TaskResult.Success
// Remaining(scheduledDelay != null) -> TaskResult.Success
// Remaining(scheduledDelay == null) -> TaskResult.Retry
// Unavailable -> TaskResult.Failure.Transient(...)
```

- [ ] **Step 1:** Failing tests asserting each table row field-by-field (construct
`DrainRequest`s; inspect produced `TaskRequest` properties; all four outcome mappings).
- [ ] **Step 2–4:** fail → implement → PASS → `apiDump`+`apiCheck`.
- [ ] **Step 5:** Commit: `Add drain payload and Meeseeks request mapping`

**Exit criteria:** mapping tables certified by unit tests; payload serializes round-trip
(add one `Json.encodeToString`/decode test).

---

### Task C3: MeeseeksDrainScheduler + StoreDrainWorker `[mech]`

**Budget:** 75/115 min.
**Read first:** design §7.1, §7.2 (self-cancellation hazard + tracked-id rules — frozen),
§7.4, §9.

**Files:**
- Create: `.../meeseeks/MeeseeksDrainScheduler.kt`, `.../meeseeks/StoreDrainWorker.kt`
- Test: `.../meeseeks/MeeseeksDrainSchedulerUnitTest.kt` (commonTest, against a
  scripted `BGTaskManager` fake implementing the interface)

Frozen rules to implement exactly (design §7.2):

- `validate(constraints)`: per-platform static matrix via a small
  `internal expect fun platformSupports(constraints: DrainConstraints): List<String>`
  returning unsupported keys — android/ios actuals: empty; jvm/js actuals: listOf entries
  for `requiresNetwork`/`requiresCharging` when set. Throw IAE naming platform + keys +
  the documented fix.
- Tracked-id map `MutableMap<String, TaskId>` + `Mutex`; every `schedule`/`cancel`
  critical section: refresh (`getTaskStatus(tracked)`: null/`Finished.*` → drop tracked),
  then decide: no tracked → recovery scan (below) → still none → `manager.schedule(request.toTaskRequest())`,
  store id; tracked pending → `manager.reschedule(id, request.toTaskRequest())`, store
  returned id; tracked running → `manager.schedule(new)` (never touch the running id),
  store new id.
- Recovery scan: `manager.listTasks()`, filter
  `it.request.payload is StoreDrainPayload && payload.storeName == name && it.status is Pending-equivalent`,
  wrap per-row payload access in `runCatching` (undeserializable → skip); adopt a Running
  row's id without scheduling only in the scan-for-schedule path per §7.2.
- `cancel(name)`: refresh; cancel tracked pending id via `manager.cancel(id)`; drop.
- `StoreDrainWorker.run`: `scheduler.runActivation(payload.storeName)` (delegate through
  the scheduler to its attached coordinator; expose
  `internal suspend fun runActivation(name): DrainPassOutcome` on the scheduler that
  checks attachment and calls the coordinator), map via `toTaskResult()`;
  `CancellationException` rethrows.

- [ ] **Step 1:** Write the scripted fake `BGTaskManager` (commonTest): records calls,
scriptable `listTasks`/`getTaskStatus` responses, ids as increasing strings.
- [ ] **Step 2:** Failing tests, named: `scheduleFreshCreatesTask`,
`schedulePendingReschedules`, `scheduleRunningCreatesNewIdAndNeverCancelsRunning`,
`staleTerminalTrackedIdIsDropped`, `recoveryScanAdoptsPendingIgnoresTerminalSkipsUndecodable`,
`concurrentSchedulesSingleWinner` (two coroutines, one `manager.schedule` recorded),
`cancelOnlyCancelsPending`, `validateFailsFastOnJvmDefaults` (jvm source-set test),
`workerMapsOutcomesPerTable` (worker with stubbed scheduler-runActivation returning each
outcome).
- [ ] **Step 3–4:** implement → PASS → `apiDump`+`apiCheck`.
- [ ] **Step 5:** Commit: `Add Meeseeks-backed drain scheduler and worker`

**Exit criteria:** all §7.2 tracked-id rules certified against the scripted fake; no
`reschedule` call ever issued for a Running id in any test log.

---

### Task C4: Real-Meeseeks verification suite `[mech]`

**Budget:** 90/135 min.
**Read first:** design §8.3 item 1 (a)–(f) — these are release-gating verifications; §12
adapter list; Meeseeks docs `docs/platforms/js.md`/`android.md` only if behavior needs
interpreting.

**Files:**
- Test: `mutations-drain-meeseeks/src/jvmTest/kotlin/.../meeseeks/MeeseeksIntegrationTest.kt`

Setup: `Meeseeks.initialize(appContext)` on JVM (find the JVM `AppContext` actual in the
resolved artifact — likely a no-arg object/class; report if initialization needs more),
registering `StoreDrainWorker` per design §7.3; a real `mutations-drain` coordinator +
fixture store (reuse patterns from A3 — the adapter cannot import A3's internals, so
replicate the minimal key/backend fixture in this module's jvmTest).

- [ ] **Step 1:** Named tests mapping one-to-one to §8.3 item 1:
  a. `scheduleFromInsideRunningWorkerFiresLater` — worker's pass leaves Remaining →
     coordinator schedules from inside the pass → assert the follow-up task executes
     (real Quartz timers: keep delays ≤ 2s via a tight `DrainBackoff`).
  b. `successProducesNoFurtherActivations` — Cleared pass → wait 3× retryInterval →
     no second worker run.
  c. `transientRetriesBoundedByConfig` — worker forced `Failure.Transient` (unregistered
     name) with `maxRetryCount(2)` at initialize → observe ≤ 2 retries then terminal.
  d. `taskStatusDistinguishesPendingRunningTerminal` — schedule with delay → Pending;
     during a gated worker (suspend inside pass) → Running; after → Finished.
  e. `undeserializablePayloadRowIsSurvivable` — schedule a task with a DIFFERENT payload
     type registered in the same manager (define a tiny `@Serializable OtherPayload`),
     then run the adapter recovery scan → no throw, other-payload row ignored.
  f. `terminalRowsDoNotAccumulateUnbounded` — run N=20 Cleared cycles; assert
     `listTasks()` size stays bounded (Meeseeks prunes) — if it grows linearly, the test
     FAILS and that failure is the §8.3 coordination-item signal: report upstream, mark
     test `@Ignore` with a comment naming the verified growth measurement, and notify the
     orchestrator (this is the one sanctioned `@Ignore` in the plan).
- [ ] **Step 2:** Plus the round trip: offline enqueue → coordinator watch → Meeseeks
  fires activation → online → drained (end-to-end with real scheduling).
- [ ] **Step 3:** All green (or (f) reported per its rule). Commit:
`Verify Meeseeks integration behaviors on JVM`

**Exit criteria:** (a)–(e) green against Meeseeks 1.1.0; (f) green or formally reported;
any other red = coordination item escalation, not a workaround.

---

### Task C5: Adapter README + dumps `[wire]`

**Budget:** 35/55 min.
**Read first:** design §7.3, §7.4, §10; AGENTS.md discipline.

**Files:**
- Create: `mutations-drain-meeseeks/README.md`
- Create: `.../meeseeks/docs/MeeseeksWiringDocsSnippet.kt` (jvmTest — compiles the JVM
  wiring; the Android/iOS blocks in the README are marked as adapted from the compiled
  JVM snippet + Meeseeks' own platform guides, with links)

Required content: host wiring (the §7.3 story: app owns `Meeseeks.initialize`; one
`register<StoreDrainPayload>` line; scheduler + coordinator + watch), iOS Info.plist
identifiers (Meeseeks' two, verbatim), platform matrix table (§7.4) including the
validate fail-fast guidance for JVM/JS, the at-least-once and manual-e2e-testing
boundary statement, and the caveat set shared with the seam README.

- [ ] **Steps:** snippet → README → three-pass review → `apiCheck` → commit:
`Document mutations-drain-meeseeks`

**Exit criteria:** README's JVM code compiles in jvmTest; platform tables match design
§7.4 exactly.

---

## Phase D — repo wiring and final audit

### Task D1: CI lanes, STABILITY, quickstart cross-link `[wire]`

**Budget:** 45/70 min.
**Read first:** design §13 (the per-list enumeration); `.github/workflows/store6.yml`
(find each hard-coded module list by searching for `realtime` occurrences — every list
containing `realtime` likely needs the seam module, and target-constrained lists need
adapter handling); `STABILITY.md` §3; `docs/store6/quickstart.md` (mutations drain
mention).

**Files:**
- Modify: `.github/workflows/store6.yml`
- Modify: `STABILITY.md` (§3 artifact table: two new experimental rows, wording matching
  existing rows)
- Modify: `docs/store6/quickstart.md` (one cross-link sentence at the existing
  drain/reconnect mention pointing to `mutations-drain`)
- Check-only: `.github/docs-sync-sources.txt` — do NOT add entries (realtime/graphql are
  not in it either); if editing store6.yml trips the docs-sync-guard, follow the guard's
  documented ack process and report.

- [ ] **Step 1:** Enumerate every `store6.yml` list containing `realtime` or `mutations-sqldelight`;
add `mutations-drain` (+ sample where samples are built) and `mutations-drain-meeseeks`
to: linux build+test steps, core-internal ban list, apple-tests matrix (seam: yes;
adapter: iosSimulatorArm64 only targets it has), klib publication modules (with the
adapter's absent-target suffix exclusions mirroring how `room`'s exclusions are encoded —
find `room` in the publication check first), JS canary (both modules have js). Keep the
diff minimal and list-shaped; no step logic changes.
- [ ] **Step 2:** STABILITY §3 rows + quickstart sentence.
- [ ] **Step 3:** Sanity: `./gradlew :mutations-drain:build :mutations-drain-meeseeks:jvmTest`
(full `build` exercises apiCheck + all compilable-on-host targets).
- [ ] **Step 4:** Commit: `Wire drain scheduler modules into CI, stability table, and docs`

**Exit criteria:** every list §13 names has both modules (or a stated reason why not);
STABILITY table row wording matches siblings; no workflow logic changes beyond lists.

---

### Task D2: Exit-gate audit `[mech]`

**Budget:** 60/90 min.
**Read first:** design §14 (all seven gates), §12.

- [ ] **Step 1:** Walk design §14 gate-by-gate; for gates 1–3 produce a table mapping
every §6.2/§6.4/§9 row and §8.3 verification to its named test (file + function).
Missing row → write the missing test now (budget priority) or report if > 20 min.
- [ ] **Step 2:** Run the full local matrix: `./gradlew :mutations-drain:build
:mutations-drain-meeseeks:build :mutations-drain-sample:run` and (on macOS runner if
available) `:mutations-drain:iosSimulatorArm64Test`.
- [ ] **Step 3:** `git diff store6 --stat` — confirm zero changes under `core/` and
`mutations/`; confirm every changed/created file is inside the plan's declared file
sets.
- [ ] **Step 4:** Write the audit table into the task report (not the repo). Fix drift,
commit any final fixes: `Close drain scheduler exit-gate audit`

**Exit criteria:** the seven §14 gates each PASS or carry a named, orchestrator-accepted
exception (only (f)-style coordination items qualify).

---

## Plan self-review checklist (orchestrator, before execution)

1. **Spec coverage:** §6.1 types → A2/A5–A8; §6.2 matrix → A7 tests; §6.3/§6.4 → A4/A6;
   §6.5 → A10 README; §6.6 → A9; §7.1/§7.2 → C2/C3; §7.3/§7.4 → C5 (+C4 behavior);
   §8.3-1 → C4; §9 → A5/A6/A7/A8 tests; §10 → A9 + READMEs; §11 → A2/A6; §12 → all test
   tasks; §13 → A1/B1/C1/D1; §14 → D2. §15/§16 need no tasks (deferred by design).
2. **No placeholders:** the only sanctioned deviation is C4(f)'s reported-`@Ignore` path.
3. **Type consistency:** `DrainBackoff.delayFor(attempt)` (A2) is the single delay
   formula consumed by A4 (`deriveFollowUp`) and referenced in A6–A9;
   `runPass(name, persistSafety)` is A6-internal and consumed by A7;
   `StoreDrainPayload(storeName)` is C2-produced, C3/C4-consumed.
