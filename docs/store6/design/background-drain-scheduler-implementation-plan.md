# Background drain scheduler — implementation plan

> **For the orchestrating agent:** execute task-by-task with fresh sub-agents per task,
> reviewing between tasks. Steps use checkbox (`- [ ]`) syntax for tracking. The normative
> behavioral spec is [background-drain-scheduler.md](./background-drain-scheduler.md) in
> this directory ("the design"); every task names the design sections its implementer must
> read first. Where this plan and the design disagree, stop and reconcile before coding —
> report the conflict, do not pick silently. This plan was revised once against two
> adversarial plan reviews; the design was revised twice against four review passes.

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

**Path legend:** `<seam>/` abbreviates
`mutations-drain/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/drain/`;
`<seamTest>/` the commonTest mirror; `<adapter>/` abbreviates
`mutations-drain-meeseeks/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/drain/meeseeks/`;
`<adapterTest>/` its commonTest mirror; `<adapterJvmTest>/` its jvmTest mirror.

## Orchestration protocol

**Agents.** One orchestrator (holds this plan and the design; reviews and integrates) and
sub-agents executing one task each (Sol 5.6 and Cursor Grok 4.6). Affinity hints per task:
`[mech]` = intricate concurrency/derivation work, favor Sol; `[wire]` = scaffolding,
build wiring, mechanical edits, favor Grok. Hints are advisory; any agent may take any
task. Each dispatch hands the sub-agent: the task text verbatim, the two doc paths, the
repo root, and the working branch/worktree per the isolation protocol below.

**Time budgets.** Each task states `Budget: soft/hard` in minutes of sub-agent wall time.
The orchestrator records dispatch time and enforces the budgets (sub-agents cannot
reliably self-time):

- At soft budget the orchestrator sends a checkpoint message; the sub-agent finishes the
  current step, commits compiling work-in-progress, and reports status + blockers rather
  than pushing on.
- The hard budget (1.5× soft) is a stop line: the orchestrator halts the sub-agent even
  mid-step and takes the partial report. Then: split the task, reassign with the partial
  report, or — only with an explicit design amendment — descope. Time pressure never
  authorizes silently dropping normative design behavior.
- Separately from budgets: 15 minutes stuck on the same build/environment error (not
  logic) → the sub-agent stops and reports; environment problems are orchestrator-level.
- Budgets include reading the task's design sections (every budget carries a 10–15 min
  reading floor) and exclude orchestrator review time.

**Branch/worktree isolation.** Parallel lanes get one git worktree + branch each, created
from the accepted commit of their newest dependency task. Sub-agents commit to their lane
branch and never merge. The orchestrator integrates accepted lane commits onto the
integration branch in dependency-graph order (cherry-pick or fast-forward), re-runs that
task's verify commands after integration, and dispatches dependent tasks from the new
accepted SHA. Tasks A1, B1, C1, and D1 each edit `settings.gradle` and/or `store6.yml`:
never run two of them concurrently.

**Per-task loop (orchestrator).** On completion: (1) run the task's verify commands; (2)
diff-review against the design tables; (3) check no files outside the task's declared
Files were touched (`git status` + `git diff --stat`); (4) accept → integrate → next, or
bounce with a concrete defect list (one bounce per task before reassigning to the other
agent with both transcripts).

**Dependency graph (parallelization map).**

```
A1 → A2 ─┬─ A3 ─┬─ A4 ─┬─ A6a ─┬─ A6b ─┬─ A9 → A10 ─┐
         ├─ A5 ─┴───────┘      ├─ A7 ──┤            │
         │                     ├─ A8 ──┤            ├─ D1 → D2
         │                     │  (A7+A8) → B1 ─────┤
         └─ C1 → C2 ── (A6a) → C3 ─ (A7) → C4a → C4b → C5 ─┘
```

Reading: A3/A5/C1 parallel after A2; A4 needs A3; A6a needs A3+A4+A5; A6b, A7, A8, C3
parallel after A6a (C3 also needs C2); A9 needs A6b+A7+A8; C4a needs C3+A7; B1 needs
A7+A8; D1 needs A10+B1+C5; D2 last, serial.

**Total soft budget:** 1060 minutes (~17.7 h of sub-agent time; wall time lower with the
parallel lanes). Per-phase: A 600, B 45, C 325, D 90.

**Escalation rules.**

- Any test revealing the design's §6.4 derivation to be wrong (not merely the
  implementation) → stop the lane, report; design amendment is orchestrator-level.
- C4a/C4b verification failures against real Meeseeks are **coordination items, not
  hack-arounds** (design §8.3 item 1): report the exact failing behavior with a minimal
  repro. A red gating verification stays red and gates the adapter release on the
  upstream Meeseeks change (design §8.3); do not `@Ignore` gating tests.
- Never modify files under `core/`, `mutations/` (except reading), or any committed `api/`
  dump of another module. If a task seems to require it, stop and report.

**Verification cheat sheet** (from repo root; JVM-first because it is the fast lane):

```bash
./gradlew :mutations-drain:jvmTest                       # seam tests
./gradlew :mutations-drain:apiDump                       # regen dumps after public API change
./gradlew :mutations-drain:apiCheck                      # dump drift gate
./gradlew :mutations-drain:compileKotlinIosSimulatorArm64  # native compile spot-check (macOS only)
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
  Internal types may be data classes.
- No new dependencies in `mutations-drain` beyond `api(projects.mutations)`. No
  `kotlin-reflect`, no atomicfu. Lock-free shared maps use
  `MutableStateFlow<Map<...>>` + `compareAndSet` loops (kotlinx-coroutines is already a
  dependency); coroutine `Mutex` only inside `suspend` functions. No `GlobalScope`, no
  `runBlocking` in production sources (jvmTest may use it where a task says so), no
  `Dispatchers.Main`.
- Zero diff to `core` and `mutations` sources and dumps.
- Shipped KDoc must be self-contained: where this plan says "copy KDoc from design §X",
  replace design-section shorthand (e.g. "§6.4", "§6.5") with the contract text itself —
  source readers do not have the design document (repo AGENTS.md rule). Design-section
  references belong only in this plan and in test names' comments, not in shipped KDoc.
- Documentation surfaces follow the repo's AGENTS.md discipline: no issue-tracker IDs or
  internal shorthand in KDoc/source comments (module READMEs may link issues; source may
  not); no hype; every claim verifiable.
- Commit per task with the task's stated message; never batch two tasks into one commit;
  never amend or force-push.
- TDD ordering inside every task: failing test → run to see the failure → implement → pass
  → `apiDump` if the public surface changed → commit.
- Coroutine tests use `kotlinx.coroutines.test.runTest` with an explicit timeout
  (`runTest(timeout = 25.seconds)`), matching the mutations module's wrapper pattern —
  EXCEPT the real-Meeseeks jvmTests in C4a/C4b, which must use real time (`runBlocking` +
  polling; Quartz fires on the wall clock, and `runTest` virtual time never advances it).

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
- Create: `<seam>/DrainPlaceholder.kt` (deleted in A2)

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

- [ ] **Step 4:** Create `mutations-drain/src/androidMain/AndroidManifest.xml` with the
byte-identical content of `realtime/src/androidMain/AndroidManifest.xml`.
- [ ] **Step 5:** Create the placeholder source file so the module has a compilation unit:

```kotlin
@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain

private const val DRAIN_MODULE_PLACEHOLDER: Int = 0
```

- [ ] **Step 6:** Run `./gradlew :mutations-drain:compileKotlinJvm :mutations-drain:compileDebugKotlinAndroid :mutations-drain:compileKotlinJs`
Expected: BUILD SUCCESSFUL.
- [ ] **Step 7:** Run `./gradlew :mutations-drain:apiDump`, then verify
`mutations-drain/api/` contains jvm/android `.api` files and a `.klib.api`.
- [ ] **Step 8:** Commit: `Scaffold mutations-drain module`

**Exit criteria:** module compiles on jvm+android+js; dumps committed; `settings.gradle`
diff is one line.

---

### Task A2: Public policy and SPI types `[mech]`

**Budget:** 50/75 min.
**Read first:** design §6.1 in full (normative KDoc), §11, §2 (repo API norms);
`/workspace/mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationInspection.kt`
(plain-class style), `MutationEvents.kt` (event-class style, internal constructors);
`rg -n "MutationsSystemWallClock" mutations/src/commonMain` and read that declaration —
it is a commonMain object over `Clock.System` (no expect/actual); copy its exact shape
and imports.

**Files:**
- Delete: `<seam>/DrainPlaceholder.kt`
- Create (one type per file under `<seam>/`): `DrainConstraints.kt`, `DrainBackoff.kt`,
  `DrainPolicy.kt`, `DrainRequest.kt`, `DrainScheduler.kt`, `DrainPassOutcome.kt`,
  `DrainSchedulerEvents.kt`, `DrainWallClock.kt`
- Test: `<seamTest>/DrainBackoffTest.kt`, `<seamTest>/DrainSchedulerEventsTest.kt`

**Interfaces (produces — later tasks compile against these exact shapes):**

All §6.1 public types EXCEPT `MutationDrainCoordinator`, `mutationDrainCoordinator`,
`InProcessDrainScheduler`, and `DrainScheduler.attach` (those land in A6a/A8 — `attach`
references the coordinator type, so the interface member is added in the same commit that
introduces the coordinator). `DrainScheduler` in this task has exactly `validate`,
`schedule`, `cancel` with the §6.1 KDoc. Additionally:

- `DrainBackoff.delayFor(attempt: Int): Duration` (public member, in the design §6.1):
  `min(initialDelay * multiplier^(attempt-1), maxDelay)` for `attempt >= 1`; `require`
  positive attempt; early-return `initialDelay` when `multiplier == 1.0`.
- `internal val DrainSystemWallClock: WallClock` in `DrainWallClock.kt`, byte-for-byte
  the `MutationsSystemWallClock` implementation shape (commonMain, `Clock.System`-based;
  same imports).
- The six advisory event classes, frozen ABI (all constructors `internal`, all properties
  `public val`, classes `public` and final, sealed parent as in design §6.1):

```kotlin
@ExperimentalStoreApi
public sealed interface DrainSchedulerEvent {
    public val storeName: String
    public val occurredAtEpochMillis: Long
}

public class DrainActivationScheduled internal constructor(
    override val storeName: String,
    override val occurredAtEpochMillis: Long,
    public val delayMillis: Long,
) : DrainSchedulerEvent

public class DrainActivationStarted internal constructor(
    override val storeName: String,
    override val occurredAtEpochMillis: Long,
) : DrainSchedulerEvent

public class DrainPassCompleted internal constructor(
    override val storeName: String,
    override val occurredAtEpochMillis: Long,
    public val pendingIntents: Int,
    public val deadLetters: Int,
) : DrainSchedulerEvent

public class DrainPassFailed internal constructor(
    override val storeName: String,
    override val occurredAtEpochMillis: Long,
    public val message: String,
) : DrainSchedulerEvent

public class DrainScheduleFailed internal constructor(
    override val storeName: String,
    override val occurredAtEpochMillis: Long,
    public val message: String,
) : DrainSchedulerEvent

public class DrainActivationCancelled internal constructor(
    override val storeName: String,
    override val occurredAtEpochMillis: Long,
) : DrainSchedulerEvent
```

(Each carries `@ExperimentalStoreApi` on the class and properties per the mutations event
style — mirror `MutationEnqueued`'s annotation placement exactly.)

- [ ] **Step 1:** Write the failing tests:

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
        assertEquals(1.hours, backoff.delayFor(8))   // 30s * 2^7 = 64 min, capped at 1 h
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

`DrainSchedulerEventsTest`: instantiate each event via an `internal` test helper in the
same package and assert every property round-trips (guards the frozen ABI field list).
- [ ] **Step 2:** Run `./gradlew :mutations-drain:jvmTest`; expected: compilation failure
(types missing).
- [ ] **Step 3:** Implement. Copy signatures and KDoc from design §6.1 (rewriting
section-shorthand into self-contained prose per the global constraint), plus:

```kotlin
// DrainBackoff.kt — inside the class:
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

    /**
     * The follow-up delay for a per-identity head whose completed network attempts for
     * the current generation number [attempt]; [attempt] >= 1. Grows by [multiplier]
     * per attempt from [initialDelay], capped at [maxDelay].
     */
    @ExperimentalStoreApi
    public fun delayFor(attempt: Int): Duration {
        require(attempt >= 1) { "attempt must be >= 1; was $attempt." }
        if (multiplier == 1.0) return initialDelay
        var delay = initialDelay
        repeat(attempt - 1) {
            if (delay >= maxDelay) return maxDelay
            delay = delay * multiplier
        }
        return minOf(delay, maxDelay)
    }
```

- [ ] **Step 4:** Run `./gradlew :mutations-drain:jvmTest`; expected: PASS.
- [ ] **Step 5:** `./gradlew :mutations-drain:apiDump && ./gradlew :mutations-drain:apiCheck`.
- [ ] **Step 6:** Commit: `Add mutations-drain public policy and SPI types`

**Exit criteria:** listed types exist with self-contained KDoc; validation tests pass;
dumps updated; `rg "data class" mutations-drain/src/commonMain` is empty; `rg "expect "
mutations-drain/src/commonMain` is empty (no expect/actual introduced).

---

### Task A3: Test fixtures `[mech]`

**Budget:** 70/105 min.
**Read first:** design §12 (test strategy);
`/workspace/mutations/src/commonTest/.../MutationsTestFixtures.kt` (fixture idioms — NOT
importable across modules, replicate minimally),
`/workspace/mutations/src/commonTest/.../MutationDrainTriggerTest.kt` lines 110–200
(store-opening pattern), `MutationProtocol.kt` (`MutationServer`, `MutationAck`,
`MutationRetirementAck`, `MutationPush`).

**Files:**
- Create: `<seamTest>/DrainTestFixtures.kt`
- Create: `<seamTest>/RecordingDrainScheduler.kt`

**Interfaces (produces — every later A-task test consumes):**
- `internal class DrainTestKey(id: String) : StoreKey` (namespace `"drain"`,
  `canonicalId() = id`) + `internal object DrainTestKeyResolver : MutationKeyResolver<DrainTestKey>`
  (exact-pair, `MutationsTestKeyResolver` shape)
- `internal class DrainFixtureBackend : MutationServer<DrainTestKey, String>` with:
  - `var offline: Boolean` (push throws while true)
  - `val receivedPushes: MutableList<String>` (pushed values in order)
  - `var pushGate: CompletableDeferred<Unit>?` — when non-null, `push` awaits it first
    (drives INFLIGHT/concurrency scenarios)
  - `val maxConcurrentPushes: Int` — high-water mark of overlapping `push` invocations
  - scriptable `retireBehavior: suspend () -> MutationRetirementAck` (default confirms;
    settable to throw — drives checkpoint failures)
  - `suspend fun load(key): String` for the store fetcher
- `internal fun throwingOnceSourceOfTruth(): SourceOfTruth<DrainTestKey, String>` — a
  delegating SoT whose first write after arming throws (drives the post-ack `ADOPTING`
  failure row); model the delegation on the mutations-owned default the builder installs
  (`MutationSourceOfTruth`), wrapping a fresh instance
- `internal class DrainFixture` bundling `storage: InMemoryMutationJournalStorage`,
  `backend: DrainFixtureBackend`, `clock: WallClock` driven by a settable
  `var nowMillis: Long` (tests may move it backward), `appendRef`, and
  `fun openStore(sourceOfTruth: SourceOfTruth<DrainTestKey, String>? = null): MutationStore<DrainTestKey, String>`
  via `mutationStore(...)` with `journalStorage(storage)` + `wallClock(clock)` +
  `fetcher { backend.load(it) }` + optional `persistence(sourceOfTruth)`
- `internal class RecordingDrainScheduler : DrainScheduler` recording every
  `validate`/`schedule`/`cancel` invocation in one ordered log
  (`val log: MutableList<String>` with entries like `"schedule(users, 30s)"` plus typed
  lists `val scheduled: MutableList<DrainRequest>`), with `var scheduleThrows: (() -> Throwable)?`
  (applies to the Nth call via `var scheduleThrowsOnCall: Int?`), and
  `var validateThrows: Throwable?`. A6a adds `attach` (interface member appears then) and
  `suspend fun fireActivation(name): DrainPassOutcome`.

- [ ] **Step 1:** Write the fixtures, modeling directly on the verified upstream idioms:
key/resolver/codec as `MutationsTestKey`/`MutationsTestKeyResolver`/`FixtureStringArgsCodec`
(same shapes, `drain` namespace); registry via
`mutatorRegistry { append = mutator(id = "drain-append", version = 1, codec = <string codec>, stales = { _, _ -> StaleSet(emptySet(), emptySet()) }) { base, suffix -> MutationPresence.Present(((base as? MutationPresence.Present)?.value).orEmpty() + suffix) } }`;
backend `push` unwraps the request the way `FakeBackend.push` does (read it first).
- [ ] **Step 2:** Fixture smoke tests in `DrainTestFixtures.kt`'s test class:
  1. open → `mutate` → `drain()` → `pendingWrites()` empty, one received push.
  2. `offline = true` → mutate → drain → `pendingWrites().single().attempt == 1`,
     `state == MutationPendingState.PENDING`.
  3. `pushGate` set → launch drain → `maxConcurrentPushes == 1` while gated → complete
     gate → drain finishes.
  4. `retireBehavior` throws → drain returns normally → `MutationCheckpointFailed`
     observed on `store.events` (collect with turbine around the drain).
  5. `throwingOnceSourceOfTruth()` armed → mutate → drain → the pass surfaces the post-ack
     failure (drain throws or head remains `ADOPTING`; assert `pendingWrites()` head state
     is `ADOPTING` afterwards — read the design §2 post-ack row first).
- [ ] **Step 3:** Run `./gradlew :mutations-drain:jvmTest`; expected PASS. If the
`MutationServer`/ack shapes fight you for more than 20 minutes, stop and re-read the
upstream `FakeBackend` push handling rather than guessing — then continue.
- [ ] **Step 4:** Commit: `Add mutations-drain test fixtures`

**Exit criteria:** all five smoke tests green on jvm; fixtures expose exactly the
Interfaces list; no production-source changes.

---

### Task A4: Delay derivation, fingerprint, escalation `[mech]`

**Budget:** 75/115 min.
**Read first:** design §6.4 in full (normative tables), §6.6, §2 rows on
`pendingWrites()`/`PendingIntent`/state mapping.

**Files:**
- Create: `<seam>/internal/DelayDerivation.kt`
- Test: `<seamTest>/DelayDerivationTest.kt`

**Interfaces (produces — A6a consumes):**

```kotlin
// package org.mobilenativefoundation.store6.mutations.drain.internal

/** One row's inspection-visible fields; mutationId keys the journal fingerprint. */
internal data class PendingFingerprint(
    val namespace: String,
    val canonicalId: String,
    val mutatorId: String,
    val state: MutationPendingState,
    val attempt: Int,
    val createdAtEpochMillis: Long,
)

internal typealias JournalFingerprint = Map<String, PendingFingerprint> // mutationId -> fields

internal class DerivationState(
    val previousFingerprint: JournalFingerprint?, // null = absent initial state; never equals empty
    val noProgressPasses: Int,                    // k
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

Rules exactly per design §6.4 (`pendingWrites()` returns global durable client-sequence
order — verified: within one identity the earlier enqueue appears first, so the first row
per `(namespace, canonicalId)` group is that identity's head):

- Fingerprint from `rows`; `k' = 0` when `previousFingerprint == null` or differs, else
  `k + 1`; `escalation(k') = ZERO` when `k' == 0` else `backoff.delayFor(k')`.
- Heads: first row per identity group. Per-head delay: `INFLIGHT`/`ADOPTING`/
  `APPLYING_EFFECTS` → ZERO; `attempt == 0` → ZERO; `PENDING`/`REFRESHING` with
  `attempt >= 1` → `backoff.delayFor(attempt)`. `derived = min(heads)`.
- rows empty + !checkpointFailed → `delay = null`; rows empty + checkpointFailed →
  `max(backoff.initialDelay, escalation(k'))`; rows non-empty →
  `max(derived, escalation(k'))`.

- [ ] **Step 1:** Failing tests as separately named functions. Rows come from real
stores driven by the A3 fixture (`PendingIntent` has an internal constructor):
  1. `clearedWhenNoRowsAndNoCheckpointFailure`
  2. `suffixNeverLowersDelay` — same key: offline drain (head attempt=1, PENDING), second
     mutate (suffix attempt=0) → delay == `delayFor(1)` (busy-loop regression, design
     §12.2)
  3. `freshHeadDerivesZero`
  4. `multiHeadTakesMinimum` — key A head attempt=3, key B head attempt=0 → ZERO
  5. `inflightHeadDerivesZero` — pushGate-held drain cancelled → head INFLIGHT → ZERO
  6. `adoptingHeadDerivesZero` — throwing-once SoT → head ADOPTING → ZERO
  7. `checkpointOnlyUsesInitialFloorAndEscalates` — emptyList + checkpointFailed: k=0 →
     `initialDelay`; repeat with equal (empty) fingerprints → k grows →
     `max(initialDelay, delayFor(k))`
  8. `bottomNeverEqualsEmpty` — first pass, empty rows, checkpointFailed=false → k stays 0
  9. `escalationGrowsToMaxAndResetsOnProgress`
  10. `multiplierOneKeepsConstantFloor`
  (`REFRESHING` requires the conflict flow; if driving it exceeds 25 minutes, assert the
  rule with a synthetic-state unit test at the derivation level using rows obtained from
  any state and document in the task report that the REFRESHING row is certified
  end-to-end by A6b's escalation coverage instead.)
- [ ] **Step 2:** Run; expected: fails (function missing). **Step 3:** Implement.
- [ ] **Step 4:** Run → PASS. `./gradlew :mutations-drain:apiCheck` — internal file, dumps
must NOT change.
- [ ] **Step 5:** Commit: `Add drain follow-up delay derivation with no-progress escalation`

**Exit criteria:** all named tests green; dump unchanged; derivation matches §6.4
row-for-row.

---

### Task A5: Internal registry `[mech]`

**Budget:** 40/60 min.
**Read first:** design §6.1 (`register`/`unregister`/`close` KDoc), §9 registry rows, §6.5.

**Files:**
- Create: `<seam>/internal/DrainRegistry.kt`
- Test: `<seamTest>/DrainRegistryTest.kt`

**Interfaces (produces — A6a consumes; all internal):**

```kotlin
internal class DrainRegistration(
    val name: String,
    val store: MutationStore<*, *>,
    val policy: DrainPolicy,
    val epoch: Long,
    val job: Job,                       // completed by unregister/close; watch wires to it
    val passMutex: Mutex,               // the pass's single lock (acquired only by runPass)
    var derivationState: DerivationState, // guarded by passMutex
)

internal class DrainRegistry {
    // MutableStateFlow<Map<String, DrainRegistration>> + compareAndSet loops; no Mutex
    // (register/unregister/close are non-suspending).
    fun register(name: String, store: MutationStore<*, *>, policy: DrainPolicy): DrainRegistration
    fun unregister(name: String): DrainRegistration? // removed registration (job cancelled by caller-visible contract here)
    fun get(name: String): DrainRegistration?
    fun snapshot(): List<DrainRegistration>
    fun close(): List<DrainRegistration>  // marks closed, returns all (jobs cancelled)
    val isClosed: Boolean
}
```

Rules: name regex `Regex("[A-Za-z0-9._-]{1,64}")`; duplicate name → IAE; same store
instance under a second name (identity comparison across the map) → IAE; register/
unregister after close → ISE; `unregister`/`close` cancel the registration `Job`s;
epochs increase monotonically (a plain counter inside the CAS loop state).

- [ ] **Step 1:** Failing tests: `registerRejectsDuplicateInvalidAndSameStoreTwice`,
`unregisterRemovesCancelsJobAndIsIdempotent`, `closeCancelsAllAndPoisonsRegistry`
(post-close register/unregister throw ISE; `isClosed` true), `epochsIncreaseAcrossReRegistration`
(register→unregister→register same name: new epoch differs), `concurrentRegisterSingleWinner`
(two coroutines register the same name; exactly one succeeds, the other IAE).
- [ ] **Step 2:** fail → **Step 3:** implement → **Step 4:** PASS; `apiCheck` (no public
change). **Step 5:** Commit: `Add internal drain registry`

**Exit criteria:** §9 registry rows certified at registry level; no public-surface change.

---

### Task A6a: Public coordinator — runActivation and reconcile `[mech]`

**Budget:** 75/115 min.
**Read first:** design §6.1 (`MutationDrainCoordinator` — every member except `watch`;
`DrainScheduler.attach`; factory), §6.3, §6.4 outcome table, §9 rows: pass mutex,
mid-pass rows, close row; §11.

**Files:**
- Create: `<seam>/MutationDrainCoordinator.kt` — public class with `register`,
  `unregister`, `reconcile`, `runActivation`, `close`, `events` (NOT `watch`; A7 adds it),
  factory `mutationDrainCoordinator(scheduler, wallClock = DrainSystemWallClock)`,
  delegating registry semantics to `internal/DrainRegistry.kt`
- Modify: `<seam>/DrainScheduler.kt` — add `attach(coordinator: MutationDrainCoordinator)`
  with the §6.1 KDoc (self-contained wording)
- Modify: `<seamTest>/RecordingDrainScheduler.kt` — implement `attach` (second call →
  `IllegalStateException`); add `suspend fun fireActivation(name): DrainPassOutcome`
  delegating to the attached coordinator
- Test: `<seamTest>/RunActivationTest.kt`

**Interfaces (consumes):** A4 `deriveFollowUp` + types; A5 registry; A3 fixtures.
**Produces:** working `runActivation`/`reconcile`/`close`/`register`/`unregister`/`events`
public surface; internal
`suspend fun runPass(registration: DrainRegistration, persistSafety: Boolean): DrainPassOutcome`
consumed by A7's watch.

Implementation rules (frozen by review; do not re-derive):

- Event bus: private `MutableSharedFlow<DrainSchedulerEvent>(replay = 0,
  extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)`, `tryEmit`
  only (the `MutationEventBus` pattern); `events` is its `asSharedFlow()`.
- `runActivation(name)`: registry lookup — closed or unknown → emit `DrainPassFailed`,
  return `Unavailable` (never throw; schedulers re-enter here). Else
  `runPass(registration, persistSafety = true)`.
- `runPass` sequence: (1) when `persistSafety`, `scheduler.schedule(DrainRequest(name,
  policy.constraints, policy.backoff.maxDelay))` in try/catch — catch → emit
  `DrainScheduleFailed`, continue (design §6.1: draining is strictly better than not);
  (2) `passMutex.withLock { ... }`: emit `DrainActivationStarted`; checkpoint observation
  (below) around `store.drain()`; catch non-cancellation from `drain()` → remember for
  `DrainPassFailed`; `CancellationException` → rethrow (safety persisted in step 1 when
  the entry path persists); read `rows = store.pendingWrites()` and
  `deadLetters().size` (catch `IllegalStateException` → emit `DrainPassFailed`, return
  `Unavailable`); `deriveFollowUp(rows, checkpointFailed, policy.backoff,
  registration.derivationState)` → store `nextState`; (3) act on the result INSIDE the
  lock: `delay == null` → `scheduler.cancel(name)` + emit `DrainActivationCancelled` +
  emit `DrainPassCompleted` + return `Cleared`; `delay != null` → if the registration is
  still current (same epoch in the registry — re-`get(name)` and compare) →
  `scheduler.schedule(DrainRequest(name, constraints, delay))` in try/catch (throw →
  `DrainScheduleFailed`, `Remaining(count, null)`) + emit `DrainActivationScheduled` +
  `DrainPassCompleted`; epoch gone → skip scheduling; return `Remaining(count, delay-or-null)`.
- Checkpoint observation (frozen shape — no atomicfu):

```kotlin
val checkpointFailures = Channel<Unit>(Channel.CONFLATED)
coroutineScope {
    val observer = launch(start = CoroutineStart.UNDISPATCHED) {
        registration.store.events
            .filterIsInstance<MutationCheckpointFailed>()
            .collect { checkpointFailures.trySend(Unit) }
    }
    try {
        registration.store.drain()
    } finally {
        yield() // best-effort post-pass barrier for an already-buffered emission
        observer.cancelAndJoin()
    }
}
val checkpointFailed = checkpointFailures.tryReceive().isSuccess
```

(Wrap the `drain()` throw handling around this block per the sequence above; the store is
typed `MutationStore<*, *>` — `drain()`/`pendingWrites()`/`events` are star-projection
safe.)

- `reconcile()`: `registry.snapshot()`, per registration `if (!passMutex.tryLock()) continue`
  else run the locked body via an internal `runPassLocked` and `unlock` in `finally`
  (skip-if-busy is normative — design §6.1; a plain `withLock` would wait, not skip).
  Structure `runPass` as: safety persist (unlocked) → `passMutex.withLock { runPassLocked }`,
  so `reconcile` can compose `tryLock` + `runPassLocked` without double-locking.
- `close()`: `registry.close()`, cancel jobs; subsequent `register`/`unregister`/`reconcile`
  throw ISE (`watch` in A7); `runActivation` → `Unavailable`.
- KDoc: self-contained (global constraint); document the non-reentrancy rule (never call
  `runActivation` from `MutationServer`/mutators/conflict policies/SoT code or a watch
  handler — deadlock).

- [ ] **Step 1:** Failing tests, named (fixtures + RecordingDrainScheduler +
`fireActivation`; turbine on `coordinator.events`):
  1. `clearedCancelsTrackedActivation` — online mutate → fire → Cleared; log order:
     `schedule(maxDelay)` … `cancel`; `DrainActivationCancelled` emitted.
  2. `transportFailureSchedulesBackoffDelay` — offline → fire → Remaining(1, 30s).
  3. `passRespectsEngineGate_noRetryStorm` — offline, fire twice immediately: second pass
     pushes nothing new (backend push count unchanged), returns Remaining, escalation may
     raise the delay; no exception.
  4. `postAckAdoptionFailureDerivesZeroThenEscalates` — throwing-once SoT: first fire →
     Remaining with ZERO-derived delay (escalation floor may apply per §6.4), repeat
     without progress → delay grows.
  5. `checkpointFailureAloneKeepsRemaining` — retireBehavior throws; rows empty →
     Remaining(0, initialDelay); `DrainPassCompleted.pendingIntents == 0`.
  6. `parkedOnlyYieldsCleared` — force a projection park (register a mutator whose
     projection throws, mutate, drain) → deadLetters non-empty, rows empty → Cleared;
     `DrainPassCompleted.deadLetters > 0`.
  7. `safetyActivationPersistsBeforeDrainAndIsReplaced` — log order: schedule(maxDelay)
     precedes backend push; then schedule(30s).
  8. `safetyPersistFailureStillRunsPass` — scheduleThrowsOnCall=1 → `DrainScheduleFailed`
     emitted, pass ran, outcome derived.
  9. `followUpScheduleFailureReturnsNullDelay` — scheduleThrowsOnCall=2 →
     Remaining(count, null) + `DrainScheduleFailed`.
  10. `cancellationMidPassLeavesSafetyTracked` — pushGate held; cancel the fireActivation
      job → log's last schedule is the safety request; journal head INFLIGHT.
  11. `unregisterMidPassSuppressesFollowUp` — pushGate held; unregister; release gate →
      pass completes; no follow-up schedule after unregister's cancel.
  12. `closedStoreUnknownNameAndPostCloseYieldUnavailable` (three asserts).
  13. `concurrentActivationsSerializePerName` — two fireActivation jobs;
      `maxConcurrentPushes == 1`; both return.
  14. `reconcileRunsUnconditionalPassAndSkipsBusyStores` — store A gated mid-pass, store B
      pending: reconcile → B drained, A untouched (no second A pass), and a
      previously-failed checkpoint on an otherwise-empty store C is retried
      (retire called again).
  15. `coordinatorRecreationResetsEscalation` — escalate k on coordinator 1; new
      coordinator over the same store → first pass derives from fresh state.
  16. `eventsCarryFields` — field asserts for every emitted event type in the above
      flows, timestamps from the fixture clock.
- [ ] **Step 2:** fail → **Step 3:** implement → **Step 4:** PASS; `apiDump` + `apiCheck`
(public surface: coordinator without `watch`, factory, `DrainScheduler.attach`).
- [ ] **Step 5:** Commit: `Add drain coordinator with pass execution and reconciliation`

**Exit criteria:** §6.4 outcome table rows and §9 pass/registry/close rows each map to a
named green test; `rg "NotImplementedError|TODO" mutations-drain/src` is empty.

---

### Task A6b: Lifecycle and concurrency certification `[mech]`

**Budget:** 60/90 min.
**Read first:** design §9 (every row), §6.1 attach contract, §12.6/§12.8.

**Files:**
- Test: `<seamTest>/CoordinatorLifecycleTest.kt`
- Test: `<seamTest>/DrainSchedulerEventBusTest.kt`
- Modify (only if a certified row exposes a defect): `<seam>/MutationDrainCoordinator.kt`,
  `<seam>/internal/DrainRegistry.kt`

- [ ] **Step 1:** Named tests completing the §9 matrix beyond A6a's coverage:
`attachTwiceThrows` (a second coordinator over the same RecordingDrainScheduler),
`schedulesBeforeAttachThrow` (call the recording double's members pre-attach — certify
the SPI contract wording on the double itself),
`registerValidatesConstraintsThroughScheduler` (validateThrows → register IAE),
`multiStoreIsolation` (A and B registered: A's Cleared cancel and A's follow-ups never
touch B's log entries; B drains independently),
`cancelDuringExecutingActivationIsLegal` (cancel(name) called mid-gated-pass; pass
completes and reinstates a follow-up),
`registerUnregisterConcurrentWithPasses` (loop of register/unregister of OTHER names
while one store passes; no exceptions, pass outcome unaffected),
`eventBusDropsOldestNonBlocking` (fill >64 events with no collector; emission never
suspends; a late collector sees only the newest window — mirror the mutations
`MutationEventsTest` bus assertions).
- [ ] **Step 2:** All PASS; fix defects in place if a row fails (the two Modify files are
in-scope for exactly that reason; anything else → bounce).
- [ ] **Step 3:** Commit: `Certify drain coordinator lifecycle and event bus contracts`

**Exit criteria:** every §9 row now names a green test across A5/A6a/A6b; event-bus
delivery contract certified.

---

### Task A7: watch `[mech]`

**Budget:** 60/90 min.
**Read first:** design §6.1 (`watch` KDoc), §6.2 matrix, §9 watch rows, §12.1.

**Files:**
- Modify: `<seam>/MutationDrainCoordinator.kt` (add `watch`)
- Test: `<seamTest>/WatchTest.kt`

Frozen implementation shape (review-mandated; registration `Job` comes from A5):

```kotlin
public suspend fun watch(name: String): Nothing {
    val registration = requireRegistrationForWatch(name) // IAE unknown; ISE closed
    coroutineScope {
        val watchJob = coroutineContext.job
        val handle = registration.job.invokeOnCompletion { watchJob.cancel() }
        try {
            val kicks = Channel<Unit>(Channel.CONFLATED)
            val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                registration.store.events.collect { event ->
                    if (event is MutationEnqueued) kicks.trySend(Unit)
                }
            }
            runPass(registration, persistSafety = true) // launch reconciliation: unconditional
            while (true) {
                kicks.receive()
                if (registration.policy.drainOnEnqueue) {
                    runPass(registration, persistSafety = false) // fast path: no safety churn
                } else {
                    scheduleZero(registration) // schedule(DrainRequest(name, constraints, ZERO)) with DrainScheduleFailed handling
                }
            }
        } finally {
            handle.dispose()
        }
    }
    // Unreachable: the loop exits only by cancellation, which unwinds coroutineScope.
    error("watch exited without cancellation")
}
```

(`subscription` is a child of the same `coroutineScope`, so cancellation cleans it up; no
explicit `cancelAndJoin` line — the earlier draft's post-loop cleanup was dead code.)

- [ ] **Step 1:** Failing tests, named:
  1. `watchRunsUnconditionalLaunchPass` — pending row from a previous session → watch
     drains it with no enqueue; the launch pass persists a safety activation (log:
     schedule(maxDelay) first).
  2. `watchLaunchPassRetriesCheckpointOnlyWork` — failed retire in a previous session,
     rows empty → launch pass calls retire again.
  3. `fastPathAddsNoSchedulerChurn` — AFTER the launch pass settles (Cleared), an online
     mutate drains via the fast path with **no additional** schedule/cancel log entries
     beyond the launch pass's own (churn regression, design §12.5).
  4. `enqueueBurstCoalescesToAtMostTwoPasses` — gate the backend; mutate 5×; release →
     ≤ 2 passes total after the launch pass (count `DrainActivationStarted` events).
  5. `drainOnEnqueueFalseSchedulesZero` — policy(false): mutate → log has
     `schedule(name, ZERO)`; no pass ran.
  6. `enqueueRacingWatchStartupIsCovered` — mutate after `register` but before launching
     `watch` → the launch pass drains it (subscribe-then-reconcile ordering).
  7. `nonEnqueueEventsIgnored` — a full drain cycle's events cause no extra passes.
  8. `unregisterCancelsWatch` and `closeCancelsWatch` — watcher completes with
     `CancellationException` (assert via `job.join()` + `job.isCancelled`).
  9. `watchUnknownNameThrows`.
- [ ] **Step 2:** fail → **Step 3:** implement → **Step 4:** PASS; `apiDump`+`apiCheck`
(adds `watch` to the dump).
- [ ] **Step 5:** Commit: `Add watch loop with launch reconciliation and fast path`

**Exit criteria:** §6.2 matrix rows all certified; coalescing ≤ 2; churn test asserts
zero additional scheduler calls on the happy fast path.

---

### Task A8: InProcessDrainScheduler `[mech]`

**Budget:** 40/60 min.
**Read first:** design §6.1 (`InProcessDrainScheduler` KDoc), §9 in-process row.

**Files:**
- Create: `<seam>/InProcessDrainScheduler.kt`
- Test: `<seamTest>/InProcessDrainSchedulerTest.kt`

Frozen semantics: `attach` once (second → ISE); `validate {}` accepts everything;
`schedule` requires `scope.isActive` else ISE. Timer map is
`MutableStateFlow<Map<String, Job>>` with `compareAndSet` loops; jobs are created
`CoroutineStart.LAZY`, inserted (replacing and cancelling the previous entry), then
started:

```kotlin
override fun schedule(request: DrainRequest) {
    val coordinator = checkNotNull(attached) { "InProcessDrainScheduler is not attached." }
    check(scope.isActive) { "InProcessDrainScheduler scope is not active." }
    lateinit var job: Job
    job = scope.launch(start = CoroutineStart.LAZY) {
        delay(request.earliestDelay)
        timers.removeIfCurrent(request.storeName, job) // CAS loop; running activation is no longer pending
        coordinator.runActivation(request.storeName)
    }
    val previous = timers.putReplacing(request.storeName, job) // CAS loop returning the replaced entry
    previous?.cancel()
    job.invokeOnCompletion { timers.removeIfCurrent(request.storeName, job) } // cancelled-in-delay cleanup
    job.start()
}
```

(`putReplacing`/`removeIfCurrent` are private helpers doing `MutableStateFlow.value` +
`compareAndSet` retry loops; `removeIfCurrent` removes only when the map still holds this
exact `job` instance, so an old job can never erase its replacement.) `cancel(name)`
CAS-removes and cancels the pending entry; a job that already removed itself (running
activation) is untouched — matches the adopt-running posture.

- [ ] **Step 1:** Failing tests: `firesAfterDelayThroughCoordinator` (virtual time),
`replaceSupersedesPendingTimer` (exactly one activation, at the second request's delay),
`cancelPreventsFiring`, `deadScopeSchedulesThrow`, `concurrentScheduleKeepsOneTimer`
(N concurrent schedules → one activation), `runningActivationNotCancelledByReplace`
(gate the backend mid-activation; schedule again → first pass completes; second fires
later), `preAttachScheduleThrows`.
- [ ] **Step 2–4:** fail → implement → PASS; `apiDump`+`apiCheck`.
- [ ] **Step 5:** Commit: `Add in-process drain scheduler`

**Exit criteria:** listed tests green; no locks in the scheduler (CAS only); replace/
cancel semantics match the SPI logical-slot invariant.

---

### Task A9: Integration guards `[mech]`

**Budget:** 55/85 min.
**Read first:** design §6.6, §9 backward-clock row, §12.4/§12.9.

**Files:**
- Test: `<seamTest>/EngineAlignmentGuardTest.kt`
- Test: `<seamTest>/RestartReplayTest.kt`
- Test: `<seamTest>/BackwardClockTest.kt`

- [ ] **Step 1:** `EngineAlignmentGuardTest`: fixtures + InProcessDrainScheduler on
virtual time; offline; watch live; mutate; iterate attempts 1..6 advancing virtual time
by exactly each pass's `Remaining.scheduledDelay` (read outcomes via a recording wrapper
around runActivation or by observing `DrainActivationScheduled.delayMillis`); assert each
scheduled pass produced exactly one new backend push attempt (the engine gate never
swallowed a scheduled pass). Go online; advance; assert Cleared.
- [ ] **Step 2:** `RestartReplayTest`: session 1 offline mutate → close; session 2 (same
storage): coordinator + watch → launch pass drains once online; pushed value matches;
`pendingWrites()` empty.
- [ ] **Step 3:** `BackwardClockTest`: offline-fail a head to attempt 2; move the fixture
clock BACKWARD beyond the engine window; fire passes: they no-op on that head (push count
flat) while escalation grows the delay (bounded waste per design §9); restore the clock
forward; next pass pushes.
- [ ] **Step 4:** PASS; commit: `Add engine alignment, restart, and clock-skew guards`

**Exit criteria:** all three guards green; the alignment test advances only by
coordinator-reported delays.

---

### Task A10: Seam README, docs snippet, dump audit `[wire]`

**Budget:** 45/70 min.
**Read first:** design §6.5, §10, §11, §13 docs row; `/workspace/realtime/README.md`
(template); repo AGENTS.md; `mutations/src/commonTest/.../docs/` snippet pattern.

**Files:**
- Create: `mutations-drain/README.md`
- Create: `<seamTest>/docs/DrainQuickstartDocsSnippet.kt`
- Verify: `mutations-drain/api/` dumps final

README required content (structure after realtime's): what it is (one paragraph: "maps OS
scheduler activations onto `MutationStore.drain()` passes; owns no transport, no
connectivity monitor"); install block; entry points (factory, register, watch,
runActivation as manual trigger, reconcile, InProcessDrainScheduler); the §10 caveat list
(in-memory journal, iOS force-quit + foreground-reconnect one-line hook, Doze,
multi-process unsupported, at-least-once passes); registration-name discipline (§6.5);
pointer to `mutations-drain-meeseeks`. Every code block lifted from the compiled snippet.

- [ ] **Step 1:** `DrainQuickstartDocsSnippet.kt` — compiling commonTest exercising:
build store (fixtures), `mutationDrainCoordinator(InProcessDrainScheduler(scope))`,
`register`, launch `watch`, manual `runActivation`; assert a drain happened (it is a real
test).
- [ ] **Step 2:** README quoting those snippets; run the documentation three-pass review
(accuracy against signatures; warrant; reader utility).
- [ ] **Step 3:** `./gradlew :mutations-drain:jvmTest :mutations-drain:apiCheck` → PASS.
- [ ] **Step 4:** Commit: `Document mutations-drain`

**Exit criteria:** README code compiles in commonTest; all six §10-derived caveats
present; dumps clean.

---

## Phase B — sample

### Task B1: `mutations-drain-sample` `[wire]`

**Budget:** 45/70 min. **Depends:** A7 + A8.
**Read first:** design §13 sample row, §14 gate 6; `/workspace/mutations-quickstart/`
(module shape, build file, main-class wiring); how `settings.gradle` maps
`realtime-sample` to `realtime/sample` — copy that idiom.

**Files:**
- Modify: `settings.gradle` (`include ':mutations-drain-sample'` + projectDir remap to
  `mutations-drain/sample`)
- Create: `mutations-drain/sample/build.gradle.kts` (JVM application on
  `projects.mutationsDrain`; mirror mutations-quickstart's plugins/mainClass)
- Create: `mutations-drain/sample/src/main/kotlin/DrainSchedulerSample.kt`

Sample narrative (printed steps, `mutations-quickstart` style): in-memory journal storage
held across two "sessions" in one process; session 1: backend offline, `mutate`, print
`pendingWrites()`; "restart": close store, reopen over the same storage, coordinator +
`InProcessDrainScheduler` + `watch`; backend online; launch pass drains; print confirmed
value and empty `pendingWrites()` — design §14 gate 6.

- [ ] **Step 1:** wire module → **Step 2:** write sample → **Step 3:**
`./gradlew :mutations-drain-sample:run` shows offline enqueue → restart → drained.
- [ ] **Step 4:** Commit: `Add mutations-drain sample`

**Exit criteria:** sample runs green from a clean checkout.

---

## Phase C — `mutations-drain-meeseeks` (the adapter)

### Task C1: Adapter scaffold + version catalog `[wire]`

**Budget:** 35/55 min.
**Read first:** design §7 header block, §13; `/workspace/room/build.gradle.kts` (subset
plugin usage), `/workspace/gradle/libs.versions.toml` (note: it has a `[libraries]` entry
`kotlin-serialization-plugin` — that is a compiler-classpath artifact, NOT a `[plugins]`
alias; you are adding the plugins alias).

**Files:**
- Modify: `gradle/libs.versions.toml`:

```toml
# [versions]
meeseeks = "1.1.0"

# [libraries]
meeseeks-runtime = { module = "dev.mattramotar.meeseeks:runtime", version.ref = "meeseeks" }

# [plugins]
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "baseKotlin" }
```

- Modify: `settings.gradle` (`include ':mutations-drain-meeseeks'`)
- Create: `mutations-drain-meeseeks/gradle.properties`
  (`POM_NAME`/`POM_ARTIFACT_ID=mutations-drain-meeseeks`, `VERSION_NAME=6.0.0-SNAPSHOT`)
- Create: `mutations-drain-meeseeks/build.gradle.kts`:

```kotlin
plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform.subset")
    alias(libs.plugins.kotlin.serialization)
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

- Create: `mutations-drain-meeseeks/src/androidMain/AndroidManifest.xml` (same content as
  A1's)
- Create: placeholder source (deleted in C2)

- [ ] **Steps:** wire → verify
`./gradlew :mutations-drain-meeseeks:compileKotlinJvm :mutations-drain-meeseeks:compileKotlinJs :mutations-drain-meeseeks:compileDebugKotlinAndroid`
→ `apiDump` → commit: `Scaffold mutations-drain-meeseeks module`

**Exit criteria:** compiles on jvm+js+android; Meeseeks resolves from Maven Central;
dumps committed. Resolution failure → report, do not vendor.

---

### Task C2: Payload and mapping functions `[mech]`

**Budget:** 40/60 min.
**Read first:** design §7.1, §7.2 first table. Meeseeks signature doubts → inspect the
resolved 1.1.0 artifact (or its committed API dump in the meeseeks repo); trust the
artifact over memory. Verified shapes: `TaskRequest(payload, preconditions, priority,
schedule, retryPolicy)`; `TaskSchedule.OneTime(initialDelay: Duration)`;
`TaskPreconditions(requiresNetwork, requiresCharging, requiresBatteryNotLow)`;
`TaskRetryPolicy.FixedInterval(retryInterval: Duration, maxRetries: Int? = null)`;
`ScheduledTask.task: TaskRequest` (property is `task`, not `request`), `.status`, `.id`.

**Files:**
- Delete placeholder; Create: `<adapter>/StoreDrainPayload.kt`,
  `<adapter>/internal/TaskRequestMapping.kt`
- Test: `<adapterTest>/TaskRequestMappingTest.kt`

**Interfaces (produces):**

```kotlin
@Serializable
@ExperimentalStoreApi
public class StoreDrainPayload(public val storeName: String) : TaskPayload

// internal/TaskRequestMapping.kt
internal fun DrainRequest.toTaskRequest(): TaskRequest
internal fun DrainPassOutcome.toTaskResult(): TaskResult
```

Mappings exactly per design §7.2: OneTime(initialDelay = earliestDelay); preconditions
from the two constraint flags with `requiresBatteryNotLow = false`; priority left at the
`TaskRequest` constructor default; `FixedInterval(retryInterval = 30.seconds,
maxRetries = null)`. Outcomes: Cleared → Success; Remaining(scheduledDelay != null) →
Success; Remaining(null) → Retry; Unavailable → `Failure.Transient(...)` (construct with
a plain `IllegalStateException(reason)` — check the Transient constructor's parameter in
the artifact).

- [ ] **Step 1:** Failing tests asserting each table row field-by-field, decoding the
payload via `Json.encodeToString`/`decodeFromString` round-trip and asserting
`decoded.storeName` (NOT payload object equality — the class has no structural equality).
- [ ] **Step 2–4:** fail → implement → PASS → `apiDump`+`apiCheck`.
- [ ] **Step 5:** Commit: `Add drain payload and Meeseeks request mapping`

**Exit criteria:** mapping tables certified; payload round-trips through JSON.

---

### Task C3: MeeseeksDrainScheduler + StoreDrainWorker `[mech]`

**Budget:** 75/115 min. **Depends:** C2 + A6a.
**Read first:** design §7.1, §7.2 (self-cancellation hazard, tracked-id/staleness/recovery
rules — frozen), §7.4, §9.

**Files:**
- Create: `<adapter>/MeeseeksDrainScheduler.kt`, `<adapter>/StoreDrainWorker.kt`
- Create: `<adapter>/internal/PlatformConstraints.kt` (commonMain `expect`):

```kotlin
internal expect val drainPlatformName: String
internal expect fun unsupportedConstraintKeys(constraints: DrainConstraints): List<String>
```

- Create actuals (exactly these four files; the subset module gets a shared `iosMain`
  from the default hierarchy template — do NOT create per-iOS-target actuals):
  - `mutations-drain-meeseeks/src/androidMain/.../internal/PlatformConstraints.android.kt` → `emptyList()`
  - `mutations-drain-meeseeks/src/iosMain/.../internal/PlatformConstraints.ios.kt` → `emptyList()`
  - `mutations-drain-meeseeks/src/jvmMain/.../internal/PlatformConstraints.jvm.kt` → keys for each set flag
  - `mutations-drain-meeseeks/src/jsMain/.../internal/PlatformConstraints.js.kt` → same as jvm
- Test: `<adapterTest>/MeeseeksDrainSchedulerUnitTest.kt` (scripted `BGTaskManager` fake)
- Test: `mutations-drain-meeseeks/src/jvmTest/.../ValidateFailFastJvmTest.kt`

Frozen rules (design §7.2):

- `validate`: throws IAE naming `drainPlatformName`, the unsupported keys, and the
  documented fix (`DrainConstraints(requiresNetwork = false, requiresCharging = false)`
  or `InProcessDrainScheduler`).
- Tracked-id map: `MutableStateFlow<Map<String, TaskId>>` CAS. The decide+act sequence
  against the manager is not atomic; transient duplicates are bounded and tolerated
  (design §6.1 logical-slot invariant) — the tests assert convergence, not strict
  mutual exclusion.
- `schedule(request)`: refresh tracked id via `getTaskStatus(tracked)` — null or
  `Finished.*` → drop; `Running` → adopt (schedule a NEW task; never `reschedule` a
  possibly-running id); `Pending` → `reschedule(id, request.toTaskRequest())`. No tracked
  id → recovery scan: `manager.listTasks()`, keep rows where
  `runCatching { (it.task.payload as? StoreDrainPayload)?.storeName } .getOrNull() == request.storeName`
  and `it.status` is the pending state → adopt+`reschedule`; running row → adopt the id
  and schedule a NEW task only if the request must supersede (it does not — the running
  pass chains; just adopt); none → `manager.schedule(...)`. Store the resulting id via
  CAS.
- `cancel(name)`: refresh; `manager.cancel(id)` only for a pending tracked id; CAS-remove.
- `StoreDrainWorker.run`: `scheduler.runActivation(payload.storeName)` (an internal
  scheduler member checking attachment and delegating to the coordinator) mapped via
  `toTaskResult()`; `CancellationException` rethrows.

- [ ] **Step 1:** Scripted fake `BGTaskManager` (commonTest): records calls; scriptable
`listTasks`/`getTaskStatus` returns; ids as increasing strings.
- [ ] **Step 2:** Failing tests, named: `scheduleFreshCreatesTask`,
`schedulePendingReschedules`, `scheduleRunningAdoptsWithoutReschedulingRunningId`,
`staleTerminalTrackedIdIsDropped`,
`recoveryScanAdoptsPendingIgnoresTerminalSkipsUndecodable` (script one row whose
`task.payload` getter throws), `concurrentSchedulesConverge` (two coroutines; final map
has one id; at most 2 manager.schedule calls), `cancelOnlyCancelsPending`,
`workerMapsOutcomesPerTable`, and (jvmTest) `validateFailsFastOnJvmDefaults`.
- [ ] **Step 3–4:** implement → PASS → `apiDump`+`apiCheck`.
- [ ] **Step 5:** Commit: `Add Meeseeks-backed drain scheduler and worker`

**Exit criteria:** all §7.2 tracked-id rules certified against the scripted fake; no test
log ever shows `reschedule` on a Running id.

---

### Task C4a: Real-Meeseeks execution verification `[mech]`

**Budget:** 75/115 min. **Depends:** C3 + A7.
**Read first:** design §8.3 item 1 (a)–(c), §12 adapter list. **Real time, not virtual:**
Quartz fires on the wall clock; `runTest` virtual time never advances it. Use
`runBlocking` + polling:

```kotlin
// jvmTest-only harness
fun awaitCondition(timeout: Duration = 30.seconds, poll: Duration = 50.milliseconds, condition: () -> Boolean) =
    runBlocking { withTimeout(timeout) { while (!condition()) delay(poll) } }
```

Use a tight `DrainBackoff(initialDelay = 200.milliseconds, maxDelay = 2.seconds)` and
never `advanceTimeBy`/`testScheduler` in this file.

**Files:**
- Test: `<adapterJvmTest>/MeeseeksExecutionIntegrationTest.kt`
- Test-fixture: `<adapterJvmTest>/AdapterJvmFixtures.kt` — replicate the minimal
  key/backend/store fixture from A3's spec (the seam's commonTest is not importable);
  budget 25 min inside this task; discover the JVM `AppContext` actual from the artifact
  (report if `Meeseeks.initialize` needs more than a constructable context).

- [ ] **Step 1:** Named tests mapping to §8.3 item 1:
  (a) `scheduleFromInsideRunningWorkerFiresLater` — Remaining pass schedules from inside
  the worker → the follow-up task executes (awaitCondition on backend push count).
  (b) `successProducesNoFurtherActivations` — Cleared → wait 3× retryInterval → no second
  run.
  (c) `transientRetriesBoundedByConfig` — worker forced Transient (unregistered name)
  with `maxRetryCount(2)` → ≤ 2 retries then terminal.
  Plus `endToEndOfflineEnqueueBackgroundDrain`: offline enqueue → watch → Meeseeks fires
  → online → drained.
- [ ] **Step 2:** Green (or report per escalation rules). Commit:
`Verify Meeseeks execution behaviors on JVM`

**Exit criteria:** (a)–(c) green against Meeseeks 1.1.0; failures reported as
coordination items with repro, never patched around.

---

### Task C4b: Real-Meeseeks recovery/retention verification `[mech]`

**Budget:** 60/90 min. **Depends:** C4a.
**Read first:** design §8.3 item 1 (d)–(f), §7.2 recovery rules.

**Files:**
- Test: `<adapterJvmTest>/MeeseeksRecoveryIntegrationTest.kt`

- [ ] **Step 1:** Named tests:
  (d) `taskStatusDistinguishesPendingRunningTerminal` — delayed request → Pending; gated
  worker → Running; after completion → Finished.
  (e) `unknownRegistrationRowsAreSurvivable` — manager instance 1 (extra
  `@Serializable class LegacyPayload : TaskPayload` registered) schedules a LegacyPayload
  task; close/abandon instance 1; manager instance 2 over the SAME Meeseeks database
  WITHOUT that registration; run the adapter recovery scan → it must complete without
  throwing and ignore the foreign row. If `listTasks()` itself throws on the unknown
  payload, that is the (e) verification failing: report as the upstream coordination item
  (skip-and-report semantics needed in Meeseeks).
  (f) `terminalRowsRemainBounded` — 20 Cleared cycles → `listTasks()` row count bounded
  (pruned or capped). If it grows linearly, the test FAILS and stays failing: report
  upstream (retention/pruning API), gate the adapter release per design §8.3. No
  `@Ignore`.
- [ ] **Step 2:** Green or formally reported. Commit:
`Verify Meeseeks recovery and retention behaviors on JVM`

**Exit criteria:** (d)–(f) each green or escalated as a named coordination item; no
ignored tests.

---

### Task C5: Adapter README + manual verification recipe `[wire]`

**Budget:** 40/60 min.
**Read first:** design §7.3, §7.4, §10, §12.3 (manual recipe requirement); AGENTS.md.

**Files:**
- Create: `mutations-drain-meeseeks/README.md`
- Create: `<adapterJvmTest>/docs/MeeseeksWiringDocsSnippet.kt` (compiles the JVM wiring;
  Android/iOS README blocks are marked as adapted from it + Meeseeks' platform guides,
  with links)

Required content: host wiring (§7.3: app owns `Meeseeks.initialize`; one
`register<StoreDrainPayload>` line; scheduler + coordinator + watch); iOS Info.plist
identifiers (Meeseeks' two, verbatim); platform matrix (§7.4) incl. the validate
fail-fast guidance; at-least-once statement; the shared caveat set; and the **manual
platform verification recipe** (design §12.3): Android — install a host app wiring the
adapter, toggle network with `adb shell svc wifi disable/enable` + `svc data`, observe
the constraint-gated drain via the advisory events or backend logs; iOS — run in Xcode,
pause, `e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchTask:@"dev.mattramotar.meeseeks.task.processing"]`,
resume, observe the drain; both marked not-CI-gated.

- [ ] **Steps:** snippet → README → three-pass documentation review → `apiCheck` →
commit: `Document mutations-drain-meeseeks`

**Exit criteria:** JVM snippet compiles in jvmTest; platform tables match §7.4; the
manual recipe includes both platforms' exact commands.

---

## Phase D — repo wiring and final audit

### Task D1: CI lanes, STABILITY, quickstart cross-link `[wire]`

**Budget:** 45/70 min. **Depends:** A10 + B1 + C5.
**Read first:** design §13 (the per-list enumeration); `.github/workflows/store6.yml` —
locate every hard-coded module list via `rg -n "realtime|mutations-sqldelight|room" .github/workflows/store6.yml`;
how `room`'s absent targets are encoded in the klib publication check; `STABILITY.md` §3;
`docs/store6/quickstart.md` (drain mention).

**Files:**
- Modify: `.github/workflows/store6.yml`
- Modify: `STABILITY.md` (§3 table: two experimental rows, wording matching siblings)
- Modify: `docs/store6/quickstart.md` (one cross-link sentence at the drain/reconnect
  mention pointing to `mutations-drain`)
- Check-only: `.github/docs-sync-sources.txt` — do NOT add entries; if the docs-sync
  guard trips, follow its documented ack process and report.

- [ ] **Step 1:** For each list containing `realtime` or `mutations-sqldelight`: add
`mutations-drain` (and `mutations-drain-sample` where samples build) and
`mutations-drain-meeseeks` to: linux build+test, core-internal ban list, apple-tests
matrix (seam: iosSimulatorArm64 + macosArm64 as siblings do; adapter: iosSimulatorArm64
only), klib publication modules (adapter with absent-target suffix exclusions mirroring
`room`'s), JS canary (both). List-shaped diffs only; no step-logic changes.
- [ ] **Step 2:** STABILITY §3 rows + quickstart sentence.
- [ ] **Step 3:** Sanity: `./gradlew :mutations-drain:build :mutations-drain-meeseeks:jvmTest :mutations-drain-meeseeks:compileKotlinJs`.
- [ ] **Step 4:** Commit: `Wire drain scheduler modules into CI, stability table, and docs`

**Exit criteria:** every §13-named list contains both modules (or a stated reason);
STABILITY wording matches sibling rows; workflow diff is lists-only.

---

### Task D2: Exit-gate audit (read-only) `[mech]`

**Budget:** 45/70 min. **Depends:** everything.
**Read first:** design §14 (all seven gates), §12.

This task modifies NO files. Its deliverable is the audit report; defects bounce to the
owning task (or a new narrowly-scoped remediation task the orchestrator cuts), each with
its own commit.

- [ ] **Step 1:** Produce the gate table: for design §6.2, §6.4, §9 rows and §8.3
verifications — the certifying test (file + function). Any missing row → report with the
owning task named.
- [ ] **Step 2:** Run the full local matrix:
`./gradlew :mutations-drain:build :mutations-drain-meeseeks:build :mutations-drain-sample:run`
(plus `:mutations-drain:iosSimulatorArm64Test` on a macOS runner, passing the simulator
device property exactly as `store6.yml` does).
- [ ] **Step 3:** Diff audit:
`git diff --stat "$(git merge-base HEAD origin/store6)"..HEAD` — confirm zero changes
under `core/` and `mutations/`, and every changed file belongs to a task's declared
Files set.
- [ ] **Step 4:** Deliver the report; orchestrator dispatches bounces if any.

**Exit criteria:** the seven §14 gates each PASS or carry a named, orchestrator-accepted
coordination item (C4-style upstream items only).

---

## Plan self-review checklist (orchestrator, before execution)

1. **Spec coverage:** §6.1 types → A2/A6a/A7/A8; §6.2 matrix → A7 (+A6a reconcile row);
   §6.3/§6.4 → A4/A6a; §6.5 → A10; §6.6 → A9; §7.1/§7.2 → C2/C3; §7.3/§7.4 → C5 (+C4
   behavior); §8.3-1(a)–(f) → C4a/C4b; §9 → A5/A6a/A6b/A7/A8; §10 → A9 + READMEs; §11 →
   A2/A6a/A6b; §12 → all test tasks; §13 → A1/B1/C1/D1; §14 → D2. §15/§16 need no tasks
   (deferred by design).
2. **No placeholders:** no `TODO`/`NotImplementedError` at any commit boundary; the only
   sanctioned red is a C4 gating verification escalated per §8.3.
3. **Type consistency:** `DrainBackoff.delayFor(attempt)` (A2) is the single delay
   formula consumed by A4 and asserted in A6a/A9; `runPass(registration, persistSafety)`
   is A6a-internal and consumed by A7; `DrainRegistration.job` (A5) is the watch
   cancellation hook (A7); `StoreDrainPayload(storeName)` (C2) is consumed by C3/C4;
   `ScheduledTask.task` is the Meeseeks property name (C3).
