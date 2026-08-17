# Background drain scheduler — technical design

Two new experimental artifacts that connect the mutation journal to OS background schedulers,
so pending mutations are pushed when connectivity or charging conditions are met — including
after process death — without the host hand-wiring WorkManager or BGTaskScheduler code.

- `mutations-drain` — the scheduling seam: a `DrainScheduler` SPI, a coordinator that derives
  scheduling decisions from journal state, and an in-process reference scheduler.
- `mutations-drain-meeseeks` — the OS-scheduler backend, built on
  [Meeseeks](https://github.com/matt-ramotar/meeseeks) (`dev.mattramotar.meeseeks:runtime`),
  which maps to WorkManager on Android, BGTaskScheduler on iOS, Quartz on JVM, and a
  best-effort runner on JS.

Status: design for review, revised twice against two adversarial review rounds (four review
passes total). Nothing here is implemented.

## 1. Problem

`mutations` journals every write intent durably and replays it across restart, but transport
happens only when the host calls one of two facade methods, both documented as
"idempotent, scheduler-agnostic foreground pass":

```kotlin
// mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt
public suspend fun drain(key: K)
public suspend fun drain()
```

Nothing in the library calls them. `MutationDrainTriggerTest` certifies the intended host
patterns — drain at launch, drain on connectivity regain, keyed drain after `mutate` — and
certifies that `mutate`, construction, and hydration never start transport. The realtime
module's README says the same thing from the other side: "the host still calls
`MutationStore.drain()` / `drain(key)` to flush the mutation journal."

The consequence is the gap [#677](https://github.com/MobileNativeFoundation/Store/issues/677)
describes: a value written offline and never read again stays local forever unless the app
builds its own WorkManager/BGTaskScheduler wiring. The
[Improving Sync RFC (#697)](https://github.com/MobileNativeFoundation/Store/pull/697) named
this the "Failure Recovery Mechanism for Periodically Syncing in Background" and named
Meeseeks as the intended scheduling engine. That RFC targeted Store 5's
`Bookkeeper`/`Updater` model; this design is its Store 6 successor, where the durable ledger
is the mutation journal and the retry unit is a drain pass.

## 2. What exists today (verified against source)

Facts the design builds on, from `mutations` at this revision:

| Fact | Source |
|---|---|
| `drain()` (global) and `drain(key)` (keyed) are the only transport triggers; both suspend, return `Unit`, and are idempotent | `MutationStore.kt` |
| A drain pass pushes each eligible per-identity FIFO head once, "with no retry or backoff" at the pass level; it never walks past an ineligible head into the suffix | `MutationStore.drain` KDoc, `MutationEngine.nextEligibleHead` |
| The engine has an internal per-intent backoff gate: window `= 1_000ms · 2^(attempt-1)` capped at `300_000ms`, full jitter uniform on `[0, window]`, eligible when `now >= lastAttemptAt + jitter`. The gate applies only to heads in durable phases `READY`/`REFRESH_REQUIRED` with `attempt > 0`; every other phase is immediately eligible. Constants are private; "no public policy door exists" | `MutationEngine.kt` (`BACKOFF_BASE_MILLIS`, `BACKOFF_CAP_MILLIS`, `isBackoffEligible`), `MutationBackoffTest` |
| Global `drain()` respects that gate (skips ineligible heads); keyed `drain(key)` overrides it | `MutationBackoffTest`, engine `overrideBackoff` |
| `attempt` and `lastAttemptAt` are durable; restart recomputes eligibility from them. `PendingIntent.attempt` counts "completed network attempts for the current generation" — a conflict that prepares a new generation resets it to 0, and the reset head is immediately eligible | `MutationBackoffTest`, `MutationInspection.kt`, engine conflict resumption |
| Inspection is the durable truth: `pending(key)`, `pendingWrites()`, `deadLetters()`. `pendingWrites()` returns every nonterminal intent (heads and FIFO suffixes) in durable client-sequence order. `PendingIntent` exposes exactly: `namespace`, `canonicalId`, `mutationId`, `mutatorId`, `state`, `attempt`, `createdAtEpochMillis`. Neither `generation` nor `lastAttemptAt` is exposed | `MutationInspection.kt` |
| Public state mapping: `UNPREPARED`/`READY` → `PENDING`, `INFLIGHT` → `INFLIGHT`, `REFRESH_REQUIRED` → `REFRESHING`, `ACKED` → `ADOPTING`, `EFFECTS_PENDING` → `APPLYING_EFFECTS`; parked executions appear only in `deadLetters()` | `MutationInspection.kt` |
| `events: SharedFlow<MutationEvent>` is advisory only: replay 0, buffer 64, `DROP_OLDEST`, `tryEmit`. It is "never a drain, acknowledgement, retry, or settlement protocol." There is no drain-finished/failed event | `MutationEvents.kt` |
| There is no drain outcome API. A retryable transport failure returns normally from `drain()` (head stays pending with incremented durable `attempt`); a sanctioned retryable post-ack failure is rethrown and does not increment `attempt`; parks land in `deadLetters()` and never re-enter the FIFO | drain KDoc, `MutationDrainParkingTest`, engine post-ack handling |
| Every drain pass ends by flushing the retirement checkpoint (`MutationServer.retire`). A checkpoint transport/protocol/persistence failure emits an advisory `MutationCheckpointFailed` and the pass returns normally; retired rows do not appear in `pendingWrites()`, so checkpoint retry work is invisible to inspection | engine `flushRetirementCheckpoint`, `MutationEvents.kt` |
| Cancelling a pass mid-push is safe: the intent stays `INFLIGHT` and a later drain replays the exact same generation. This is the documented two-step durable ack posture | `MutationDrainResumabilityMatrixTest`, STABILITY.md §8(b) |
| The journal survives process death only with a durable `journalStorage` (e.g. `mutations-sqldelight`); the default is in-memory | `MutationStoreBuilder.journalStorage` KDoc |
| `MutationStore` has `close()` and no `start()`; closed-store operations throw `IllegalStateException("Store is closed.")` | `MutationStore.kt` |

Extension conventions the artifacts must follow:

- Separate experimental artifacts; every public symbol `@ExperimentalStoreApi`; group
  `org.mobilenativefoundation.store`; packages `org.mobilenativefoundation.store6.*`
  (STABILITY.md §2–3).
- `explicitApi()`, committed BCV JVM + klib dumps, CI lanes in `store6.yml` with
  hard-coded module lists (build, apple tests, core-internal ban, klib publication, JS
  canary) that must each be edited for a new module.
- Core telemetry carries no mutation vocabulary; drain-related events are extension-owned
  (`extension-probe` demonstrates this deliberately).
- Public API classes are plain classes with `val`s, not data classes, matching
  `PendingIntent` / `MutationFailure` / the event types.
- Sibling precedent for "seam artifact + backend artifact": `mutations` +
  `mutations-sqldelight`.

## 3. Goals

1. Conditional liveness for pending mutations, stated precisely: while retryable work
   exists in the journal, a follow-up drain trigger exists — an OS request, an in-process
   timer, or the launch-reconciliation pass — except in the enumerated gaps: a
   `DrainScheduler.schedule` infrastructure failure (recovered by the next enqueue, manual
   trigger, or launch), iOS force-quit (recovered at next user open), and platform
   deferral (Doze, standby, best-effort BGTask grants). Work is pushed whenever the
   platform grants execution and the app's registration bootstrap runs. §10 states each
   gap.
2. The scheduler layer holds no durable state of its own. Every scheduling decision is
   derivable from the journal (`pendingWrites()`, per-intent `attempt` and `state`) plus
   policy, with one bounded in-memory heuristic (the no-progress escalation in §6.4).
   OS-side persisted requests are treated as hints that launch reconciliation re-derives.
3. Zero diff to `core` and zero diff to `mutations`. Everything consumes the public
   `MutationStore` surface. §15 names the follow-up mutations APIs that would sharpen the
   derivations this design approximates.
4. The OS-facing machinery is Meeseeks, behind a Store-owned SPI small enough that a
   different backend (or a hand-rolled platform scheduler) is a page of code.
5. Works with multiple `MutationStore`s in one process, each with its own constraints
   policy. One process per journal; multi-process access to one journal is out of scope
   and documented as unsupported.

## 4. Non-goals

- Read-side background refresh, prefetch, or periodic fetch scheduling. This is the
  mutations drain seam only.
- Conflict policy packs ([#678](https://github.com/MobileNativeFoundation/Store/issues/678))
  and fetcher retry/backoff. Separate features.
- Live in-app connectivity monitoring (`ConnectivityManager` callbacks, `NWPathMonitor`).
  Constraint-gated OS scheduling plus the immediate in-process attempt (§6.3) covers most
  of the same ground; the residual gap (iOS foreground reconnect without a new write) is
  documented in §10 with a one-line host hook rather than a platform monitor dependency.
- New policy doors in `mutations` (e.g. exposing the engine's internal backoff constants or
  a structured drain report). Desirable later — named in §15 — but this design is
  zero-mutations-diff.
- Multi-process support. WorkManager runs workers in the default process; a
  `MutationStore` living in another process is unsupported in v1 and the README says so.
- A macOS/watchOS/tvOS OS-scheduler backend (`NSBackgroundActivityScheduler`,
  `WKApplicationRefreshBackgroundTask`). The seam compiles on all 12 targets; those
  platforms get the in-process scheduler until someone contributes a backend.

## 5. Design overview

```
┌────────────────────────────────────────────────────────────────────────┐
│ app process                                                            │
│                                                                        │
│  MutationStore("users")  MutationStore("posts")                        │
│        │ events / pendingWrites / drain()                              │
│        ▼                                                               │
│  MutationDrainCoordinator            ← mutations-drain (all 12 targets)│
│   · registry: name → (store, policy, registration epoch)               │
│   · watch(name): subscribe → launch pass → react to enqueues           │
│   · runActivation(name): OS re-entry point and manual trigger          │
│   · owns ALL retry policy (delay derivation §6.4 + safety activations) │
│        │ schedule(DrainRequest) / cancel(name) / validate(constraints) │
│        ▼                                                               │
│  DrainScheduler (SPI)                                                  │
│   ├─ InProcessDrainScheduler         ← mutations-drain (reference)     │
│   └─ MeeseeksDrainScheduler          ← mutations-drain-meeseeks        │
│            │ BGTaskManager.schedule(TaskRequest)                       │
└────────────┼───────────────────────────────────────────────────────────┘
             ▼
   Meeseeks runtime → WorkManager (Android) / BGTaskScheduler (iOS)
                      / Quartz (JVM) / runner (JS)
             │ OS wakes process, runs registered worker (at-least-once)
             ▼
   StoreDrainWorker → coordinator.runActivation(name)
```

Division of responsibility, stated once and load-bearing everywhere below:

- **The journal is the outbox.** What needs pushing, per-intent attempt counts and states,
  and terminal parks live only there.
- **The coordinator is the brain.** When to attempt a pass, what a pass outcome means, and
  when/how far out to schedule the next activation live only there.
- **The scheduler is a constraint-gated alarm.** `schedule(request)` means "run one
  activation for this store name when the constraints hold, no earlier than the delay."
  It never decides retries. Activations are at-least-once: OS schedulers may replay or
  overlap them, and every pass is idempotent by the engine's contract.
- **Meeseeks is the alarm's implementation** on the four platforms it supports, bringing the
  WorkManager factory wiring, BGTask identifier registration and expiration handling, and
  process-death re-entry that Store would otherwise own.

## 6. Artifact 1: `mutations-drain` (the seam)

- Coordinates: `org.mobilenativefoundation.store:mutations-drain:6.0.0-SNAPSHOT`
- Package / Android namespace: `org.mobilenativefoundation.store6.mutations.drain`
- Plugin: `org.mobilenativefoundation.store.store6.multiplatform` (canonical 12 targets)
- Dependencies: `api(projects.mutations)` only
- Tier: experimental; every public symbol `@ExperimentalStoreApi`

### 6.1 Public API

```kotlin
package org.mobilenativefoundation.store6.mutations.drain

/** OS execution constraints for a scheduled drain activation. */
@ExperimentalStoreApi
public class DrainConstraints(
    /** Require network connectivity before an activation runs. Default true. */
    public val requiresNetwork: Boolean = true,
    /** Require external power before an activation runs. Default false. */
    public val requiresCharging: Boolean = false,
)

/**
 * Delay policy between scheduled activations for the same store, applied by the
 * coordinator (§6.4). `delay(attempt) = min(initialDelay * multiplier^(attempt - 1),
 * maxDelay)` for attempt >= 1. The same values drive the no-progress escalation floor;
 * with `multiplier == 1.0` the floor stays constant at `initialDelay` (bounded, never a
 * hot loop) instead of growing toward `maxDelay`.
 *
 * init requires: `initialDelay` strictly positive and finite, `maxDelay` finite and
 * `>= initialDelay`, `multiplier` finite and `>= 1.0`. Violations throw
 * IllegalArgumentException.
 */
@ExperimentalStoreApi
public class DrainBackoff(
    public val initialDelay: Duration = 30.seconds,
    public val multiplier: Double = 2.0,
    public val maxDelay: Duration = 1.hours,
)

/** Per-store scheduling policy. */
@ExperimentalStoreApi
public class DrainPolicy(
    public val constraints: DrainConstraints = DrainConstraints(),
    public val backoff: DrainBackoff = DrainBackoff(),
    /**
     * When true, `watch` runs an immediate in-process pass on every journal enqueue
     * (the fast path: no pre-pass safety activation is persisted — §6.3). When false,
     * `watch` only schedules an activation with Duration.ZERO delay. Default true.
     */
    public val drainOnEnqueue: Boolean = true,
)

/** One request for one future constraint-gated activation. */
@ExperimentalStoreApi
public class DrainRequest(
    /** The coordinator registration name; stable across process restarts (§6.5). */
    public val storeName: String,
    public val constraints: DrainConstraints,
    /** Earliest execution delay from now. Duration.ZERO = as soon as constraints hold. */
    public val earliestDelay: Duration,
)

/**
 * A constraint-gated alarm. Implementations run
 * [MutationDrainCoordinator.runActivation] for [DrainRequest.storeName] when an
 * activation fires. Firing is at-least-once: replays and overlapping activations are
 * permitted and safe (passes are idempotent; the coordinator serializes per store).
 *
 * Logical-slot invariant: the scheduler tracks at most one known pending request per
 * [DrainRequest.storeName]; [schedule] replaces the tracked pending request, and [cancel]
 * cancels it. Implementations may transiently hold duplicates (e.g. recovery after
 * process death); duplicates are bounded and harmless, not forbidden.
 */
@ExperimentalStoreApi
public interface DrainScheduler {
    /**
     * Binds the coordinator. Called exactly once by [mutationDrainCoordinator] before any
     * other member; a second call throws IllegalStateException. [schedule] and [cancel]
     * before attachment throw IllegalStateException.
     */
    public fun attach(coordinator: MutationDrainCoordinator)

    /**
     * Fail-fast capability check, called by the coordinator at registration time.
     * Implementations answer from a static capability matrix (no scheduling dry-run).
     * Throws IllegalArgumentException naming the platform and the unsupported constraint
     * keys (no silent downgrade), mirroring Meeseeks' schedule-time behavior.
     */
    public fun validate(constraints: DrainConstraints)

    /**
     * Requests one future activation. Constraint validity was established by [validate]
     * at registration; infrastructure failures (dead scope, scheduler backend rejection)
     * are reported by throwing, and the coordinator converts them to
     * [DrainScheduleFailed] advisory events plus a null scheduledDelay on the outcome.
     */
    public fun schedule(request: DrainRequest)

    /** Cancels the tracked pending activation for [storeName], if any. */
    public fun cancel(storeName: String)
}

/** The truthful result of one coordinator pass, derived per §6.4. */
@ExperimentalStoreApi
public sealed interface DrainPassOutcome {
    /**
     * No nonterminal intents remain and no retirement-checkpoint failure was observed
     * during the pass. Dead letters may exist; they are terminal.
     */
    @ExperimentalStoreApi
    public class Cleared : DrainPassOutcome

    /** Work remains; the coordinator attempted to schedule a follow-up. */
    @ExperimentalStoreApi
    public class Remaining(
        /** Count of nonterminal intents after the pass (0 when only checkpoint work remains). */
        public val pendingIntents: Int,
        /**
         * The follow-up delay the coordinator scheduled, or null when [DrainScheduler.schedule]
         * threw (a [DrainScheduleFailed] event was emitted; recovery is the next enqueue,
         * manual trigger, or launch pass).
         */
        public val scheduledDelay: Duration?,
    ) : DrainPassOutcome

    /**
     * The name is not registered (including after [MutationDrainCoordinator.close]), or
     * its store is closed (`IllegalStateException("Store is closed.")` observed). Possibly
     * transient during app startup, when an OS worker can fire before host registration
     * completes.
     */
    @ExperimentalStoreApi
    public class Unavailable(
        public val storeName: String,
        public val reason: String,
    ) : DrainPassOutcome
}

@ExperimentalStoreApi
public class MutationDrainCoordinator internal constructor(
    private val scheduler: DrainScheduler,
    private val wallClock: WallClock,
) {
    /**
     * Registers [store] under [name] and validates `policy.constraints` against the
     * scheduler ([DrainScheduler.validate]). Names are stable across launches (they appear
     * in OS scheduler payloads) and match `[A-Za-z0-9._-]{1,64}`. Each registration gets a
     * fresh epoch; a pass started under an epoch schedules no follow-up once that epoch is
     * unregistered. Throws IllegalArgumentException on a duplicate name, an invalid name,
     * an unsupported constraint set, or a store instance already registered under another
     * name. Throws IllegalStateException after [close].
     */
    public fun <K : StoreKey, V : Any> register(
        name: String,
        store: MutationStore<K, V>,
        policy: DrainPolicy = DrainPolicy(),
    )

    /**
     * Removes the registration, cancels the tracked pending activation, completes any
     * active [watch] collector for [name] with CancellationException, and suppresses
     * follow-up scheduling from any in-flight pass of the removed epoch. Unknown names are
     * a no-op. Throws IllegalStateException after [close].
     */
    public fun unregister(name: String)

    /**
     * Launch reconciliation for hosts that do not run [watch]: one full pass
     * ([runActivation]) per registered store, skipping stores already mid-pass. The pass
     * is unconditional — it does not pre-check `pendingWrites()` — because
     * retirement-checkpoint retry work is invisible to inspection and only an actual
     * drain retries it. An empty journal makes the pass a cheap no-op. Safe to call
     * repeatedly and concurrently with passes.
     */
    public suspend fun reconcile()

    /**
     * The per-store reactive loop. In order: (1) subscribes to `store.events`; (2) once
     * the subscription is active, runs one unconditional pass for [name] (launch
     * reconciliation, same semantics as [reconcile] for this store); (3) reacts to
     * `MutationEnqueued`: with `drainOnEnqueue = true`, runs an in-process fast-path pass
     * (no pre-pass safety activation — §6.3); with `false`, schedules an activation with
     * Duration.ZERO. All other event types are ignored; pass-time derivation covers them.
     * Enqueue bursts coalesce (conflated trigger): events arriving during a pass cause at
     * most one further pass.
     *
     * Never returns normally. Completes with CancellationException when the registration
     * is removed or the coordinator closes. Throws IllegalArgumentException for an
     * unregistered [name].
     */
    public suspend fun watch(name: String): Nothing

    /**
     * The activation entry point: called by schedulers when an activation fires, and
     * callable directly by hosts as a manual "drain now" trigger (e.g. on an
     * app-foregrounded or connectivity-regained signal the host already observes).
     *
     * Sequence: (1) persist a safety activation at `backoff.maxDelay` — crash, OS
     * execution-window expiry, and process suspension mid-pass then still leave a durable
     * wake-up hint. If this persist throws, the coordinator emits [DrainScheduleFailed]
     * and proceeds with the pass anyway (draining is strictly better than not; the
     * cancellation cover below is then absent for this pass). (2) Run the §6.3 pass and
     * §6.4 derivation. (3) Replace the safety activation with the derived follow-up, or
     * cancel it on Cleared. CancellationException from the pass propagates; when step 1
     * succeeded, the safety activation is the durable cover.
     *
     * Reentrancy: passes serialize on a per-name mutex that is NOT reentrant. Never call
     * this from code on the drain stack — `MutationServer`, mutators, conflict policies,
     * or SourceOfTruth implementations — or from a [watch] event handler; deadlock
     * results. [watch] itself never holds the mutex; only the pass does.
     *
     * After [close], returns [DrainPassOutcome.Unavailable] (never throws): schedulers
     * re-enter here and must not crash platform workers.
     */
    public suspend fun runActivation(storeName: String): DrainPassOutcome

    /**
     * Marks the coordinator closed: watchers complete with CancellationException;
     * [register], [unregister], [reconcile], and [watch] then throw
     * IllegalStateException; [runActivation] returns [DrainPassOutcome.Unavailable].
     * Pending OS activations are deliberately NOT cancelled — they must survive the
     * process to be useful. Registered stores are not closed; store lifecycle belongs to
     * the host.
     */
    public fun close()

    /**
     * Advisory scheduler lifecycle events. Same delivery contract as
     * `MutationStore.events`: replay 0, extra buffer 64, DROP_OLDEST, tryEmit-only.
     * Never a scheduling or settlement protocol; durable truth remains journal inspection.
     */
    public val events: SharedFlow<DrainSchedulerEvent>
}

/**
 * Creates a coordinator and attaches it to [scheduler]. [wallClock] stamps advisory
 * events; inject a test clock for virtual-time tests. The default reads system time.
 */
@ExperimentalStoreApi
public fun mutationDrainCoordinator(
    scheduler: DrainScheduler,
    wallClock: WallClock = DrainSystemWallClock,
): MutationDrainCoordinator

/**
 * Delay-based scheduler for hosts without an OS scheduler (desktop JVM, macOS, Linux,
 * Windows, Node, browser tabs) and for tests. Constraints are not evaluated and
 * [validate] accepts everything (documented): activations fire after the delay
 * regardless; a pass attempted while offline fails transport and reschedules with §6.4
 * backoff, so an offline host settles at escalating-delay passes rather than spinning.
 * [schedule] throws IllegalStateException when [scope] is no longer active.
 */
@ExperimentalStoreApi
public class InProcessDrainScheduler(
    private val scope: CoroutineScope,
) : DrainScheduler
```

Advisory event vocabulary (plain classes, `storeName` and `occurredAtEpochMillis` on each,
no `Throwable` fields, mirroring the `MutationEvent` shape):

```kotlin
@ExperimentalStoreApi
public sealed interface DrainSchedulerEvent {
    public val storeName: String
    public val occurredAtEpochMillis: Long
}

public class DrainActivationScheduled(..., public val delayMillis: Long) : DrainSchedulerEvent
public class DrainActivationStarted(...) : DrainSchedulerEvent
public class DrainPassCompleted(
    ...,
    public val pendingIntents: Int,
    public val deadLetters: Int,
) : DrainSchedulerEvent
/** The pass itself failed (drain threw, or the store was closed/unregistered). */
public class DrainPassFailed(..., public val message: String) : DrainSchedulerEvent
/** [DrainScheduler.schedule] threw; no pending activation is tracked for the store. */
public class DrainScheduleFailed(..., public val message: String) : DrainSchedulerEvent
/**
 * Emitted only when [DrainScheduler.cancel] cancels a tracked still-pending request
 * (unregister, or Cleared cancelling a safety activation) — not on replacement, and not
 * for CancellationException inside a pass.
 */
public class DrainActivationCancelled(...) : DrainSchedulerEvent
```

### 6.2 Trigger matrix

| Trigger | Coordinator action |
|---|---|
| `watch` subscription becomes active (startup) | One unconditional pass for the name (launch reconciliation; covers pending intents AND invisible checkpoint work) |
| `MutationEnqueued` observed in `watch` (drainOnEnqueue=true) | Fast-path pass: no pre-pass safety activation; §6.4 outcome handling schedules a follow-up only if work remains |
| `MutationEnqueued` observed in `watch` (drainOnEnqueue=false) | `schedule(name, constraints, ZERO)` |
| Any other `MutationEvent` in `watch` | Ignored; pass-time derivation covers state changes |
| `reconcile()` | One unconditional pass per registered store, skipping stores mid-pass |
| Activation fires → `runActivation(name)` | Safety activation, pass, derivation, follow-up (§6.1 sequence) |
| Host manual trigger (foreground/connectivity signal) | Host calls `runActivation(name)` directly |
| `unregister(name)` | `cancel(name)`; watcher completes; in-flight pass of that epoch schedules no follow-up |
| `close()` | Watchers complete; OS requests untouched |

### 6.3 Pass execution

A pass is: global `drain()` → outcome derivation → follow-up scheduling, with a safety
activation persisted first on the `runActivation` entry paths. Rules:

- Scheduled passes use **global `drain()`**, never keyed `drain(key)`. Global respects the
  engine's internal per-intent backoff gate, so an activation can never hammer a head the
  engine considers ineligible; keyed drain's backoff override stays a deliberate
  foreground/manual affordance for hosts.
- One pass per store at a time, enforced by a coordinator-internal per-name `Mutex` whose
  only acquirer is the pass itself. Concurrent activation attempts (OS replay + a manual
  trigger) queue briefly. The engine additionally serializes per identity/namespace
  internally, and passes are idempotent, so overlap from a second coordinator or a host's
  own `drain()` calls in the same process is safe if wasteful.
- **Safety activations** cover abrupt termination mid-pass, on two of the three entry
  paths: `runActivation` (scheduler-fired and host-manual) persists one at
  `backoff.maxDelay` before draining. The `watch` fast path (`drainOnEnqueue = true`)
  deliberately does not: an OS enqueue-plus-cancel per online write is unacceptable
  scheduler churn, so the fast path's abrupt-death window is covered by the engine's
  `INFLIGHT` replay plus the unconditional launch pass instead. This narrower cover is a
  deliberate trade documented in §10.
- During the pass the coordinator observes the store's advisory events solely for
  `MutationCheckpointFailed`: collection starts undispatched before `drain()` begins,
  forwards into a conflated flag, and is read only after a post-pass yield barrier so a
  slow collector cannot miss an already-buffered emission. Best-effort remains
  best-effort: `tryEmit` drops under pressure delay checkpoint retry until the next pass
  (every launch runs one), and never lose intent data.

### 6.4 Outcome and follow-up delay derivation

Inputs after the pass: `rows = store.pendingWrites()` (durable truth),
`checkpointFailed` (observed during this pass, best-effort), whether `drain()` threw a
non-cancellation failure, and the no-progress state `(previousFingerprint, k)` defined
below.

**Progress fingerprint.** After each pass the coordinator computes
`fingerprint = multiset of (mutationId, namespace, canonicalId, state, attempt)` over all
rows — exactly the fields `PendingIntent` exposes. `k` (consecutive no-progress passes) is
updated only after real `drain()` passes: `k = 0` when `previousFingerprint` is absent
(first pass of this coordinator instance) or differs from the current fingerprint,
`k + 1` when equal. An empty fingerprint equals only another empty fingerprint, never the
absent initial state, so repeated checkpoint-only passes escalate predictably. The state
is process-local; a restart resets it, which is safe (reconciliation re-derives).
Limitation, accepted: a generation change that lands back on identical public fields is
invisible and escalates conservatively.

`escalation(k) = ZERO` for `k = 0`, else
`min(initialDelay · multiplier^(k-1), maxDelay)` — strictly positive because `initialDelay`
is validated strictly positive; with `multiplier = 1.0` it stays a constant `initialDelay`
floor.

**Outcome:**

| Condition | Outcome |
|---|---|
| `rows` empty and not `checkpointFailed` | `Cleared` → cancel the tracked pending activation; nothing scheduled |
| `rows` empty and `checkpointFailed` | `Remaining(0, delay)` → follow-up at `max(backoff.initialDelay, escalation(k))` |
| `rows` non-empty (whether or not `drain()` threw) | `Remaining(rows.size, delay)` → follow-up per the head derivation below |
| Store closed / name unknown / coordinator closed (IllegalStateException from any store call) | `Unavailable` → emit `DrainPassFailed`; schedule nothing; launch reconciliation recovers |

**Head derivation.** `pendingWrites()` returns every nonterminal row; only per-identity
FIFO heads are schedulable by a global drain, and suffix rows must not influence delay
(a never-attempted suffix behind an in-window head would otherwise derive a zero delay the
engine cannot act on — a busy loop). So:

1. Group rows by `(namespace, canonicalId)`; the first row per group in the returned
   durable client-sequence order is that identity's head.
2. Per-head delay:

| Head `state` | Head `attempt` | Delay |
|---|---|---|
| `INFLIGHT`, `ADOPTING`, `APPLYING_EFFECTS` | any | `ZERO` — these phases are immediately eligible in the engine (no backoff gate); the crash-window recovery path (STABILITY.md §8(b)) must not wait |
| `PENDING`, `REFRESHING` | `0` | `ZERO` — never attempted (or generation reset after a conflict); immediately eligible |
| `PENDING`, `REFRESHING` | `>= 1` | `backoff.delay(attempt)` |

3. `derived = min(per-head delays)` — progress-first: the least-delayed identity sets the
   wake-up, and identities still inside their own engine window are skipped by the engine
   and picked up by a later activation.
4. `effective = max(derived, escalation(k))`.

The escalation floor is what prevents hot loops that the state table alone cannot: an
`ADOPTING` head whose adoption keeps throwing derives `ZERO` forever (its `attempt` does
not increment post-ack), an `INFLIGHT` row held by a concurrent pass derives `ZERO`, and a
failing checkpoint has no row at all. Each converges to bounded, spaced retries (growing
toward `maxDelay` for `multiplier > 1`), and any real progress snaps the delay back to the
derived value.

**Follow-up scheduling.** The follow-up (or the surviving safety activation on the
CancellationException path) replaces the tracked pending request. If the pass's epoch was
unregistered mid-pass, no follow-up is scheduled. If `DrainScheduler.schedule` throws, the
coordinator emits `DrainScheduleFailed` and returns `Remaining(…, scheduledDelay = null)`;
recovery is the next enqueue, manual trigger, or launch pass (and, on the Meeseeks worker
path, the bounded native retry fallback — §7.2).

### 6.5 Registration names

Names appear inside persisted OS payloads (WorkManager/Meeseeks rows survive app updates),
so they are part of the app's persistent contract. Renaming a registration across app
versions orphans old payloads: the activation fires, resolves `Unavailable`, and the
adapter's grace handling (§7.2) plus launch reconciliation under the new name recover the
work. The README documents: pick names like package names, never derive them from build
flavors or user state.

### 6.6 Alignment with the engine's internal backoff gate

The engine gates `READY`/`REFRESH_REQUIRED` heads with `attempt >= 1` by
`now >= lastAttemptAt + jitter`, `jitter` uniform on `[0, min(1s · 2^(attempt-1), 300s)]`.
The coordinator's `delay(attempt) = min(30s · 2^(attempt-1), 3600s)` for the same head is
measured from pass end (pass end ≥ `lastAttemptAt`; `lastAttemptAt` itself is not public).
`delay(attempt) ≥ engine window(attempt)` for every `attempt ≥ 1`:

- `attempt ≤ 7` (both uncapped): `30 · 2^(attempt-1)s ≥ 2^(attempt-1)s`.
- `attempt = 8` (coordinator capped, engine not): `3600s > 128s`.
- `attempt = 9`: `3600s > 256s`.
- `attempt ≥ 10` (both capped): `3600s > 300s`.

So with the defaults, a follow-up scheduled for a gated head never fires inside that head's
ineligibility window. Heads in ungated phases derive `ZERO` by the §6.4 table, which
matches their immediate engine eligibility. This is a documented property of the defaults,
not an enforced constraint: hosts can configure smaller delays and merely get occasional
no-op passes that the escalation floor keeps bounded. The engine constants are private and
may change; one integration test (§12.4) certifies the relationship against the real
engine rather than restating constants.

Because reconciliation and follow-ups measure delays from "now" rather than from the
unknown `lastAttemptAt`, a relaunch can wait up to one full `delay(attempt)` longer than
strictly necessary. Accepted: conservative, bounded, and disappears once `mutations`
exposes retry timing (§15).

## 7. Artifact 2: `mutations-drain-meeseeks` (the OS backend)

- Coordinates: `org.mobilenativefoundation.store:mutations-drain-meeseeks:6.0.0-SNAPSHOT`
- Package / namespace: `org.mobilenativefoundation.store6.mutations.drain.meeseeks`
- Plugin: `org.mobilenativefoundation.store.store6.multiplatform.subset` with exactly
  Meeseeks' published matrix: `androidTarget()`, `jvm()`, `iosArm64()`,
  `iosSimulatorArm64()`, `iosX64()`, `js { nodejs() }` (subset precedent: `room`;
  `mutations-sqldelight` uses the full plugin and is NOT the template for targets)
- Dependencies: `api(projects.mutationsDrain)`,
  `api(libs.meeseeks.runtime)` (`dev.mattramotar.meeseeks:runtime:1.1.0`),
  kotlinx-serialization plugin (payload serializer)
- Tier: experimental

### 7.1 Public API

```kotlin
package org.mobilenativefoundation.store6.mutations.drain.meeseeks

/** The serialized activation payload; carries only the coordinator registration name. */
@Serializable
@ExperimentalStoreApi
public class StoreDrainPayload(public val storeName: String) : TaskPayload

/**
 * The Meeseeks worker for drain activations. Register it in the app's
 * `Meeseeks.initialize` block; it delegates to the scheduler's attached coordinator.
 */
@ExperimentalStoreApi
public class StoreDrainWorker(
    appContext: AppContext,
    private val scheduler: MeeseeksDrainScheduler,
) : Worker<StoreDrainPayload>(appContext) {
    override suspend fun run(payload: StoreDrainPayload, context: RuntimeContext): TaskResult
}

/**
 * A [DrainScheduler] backed by a host-initialized Meeseeks [BGTaskManager]. This artifact
 * never calls `Meeseeks.initialize`; the manager, its worker registrations, and its
 * platform setup (WorkManager factory, Info.plist identifiers) belong to the app. The
 * [manager] function must return the same instance for the scheduler's lifetime.
 */
@ExperimentalStoreApi
public class MeeseeksDrainScheduler(
    private val manager: () -> BGTaskManager,
) : DrainScheduler
```

### 7.2 Mapping

`DrainRequest` → Meeseeks `TaskRequest` (constructor:
`TaskRequest(payload, preconditions, priority, schedule, retryPolicy)`):

| DrainRequest field | TaskRequest mapping |
|---|---|
| `storeName` | `StoreDrainPayload(storeName)` |
| `constraints.requiresNetwork` | `TaskPreconditions(requiresNetwork = …)` |
| `constraints.requiresCharging` | `TaskPreconditions(requiresCharging = …)` |
| `earliestDelay` | `TaskSchedule.OneTime(initialDelay = earliestDelay)` |
| — | priority: Meeseeks default. Expedited dispatch is a host decision (`allowExpedited` in the app's Meeseeks config); the adapter requests none |
| — | `TaskRetryPolicy.FixedInterval(retryInterval = 30.seconds, maxRetries = null)` |

On retries, exactly: **returning `TaskResult.Success` from the worker is the mechanism
that disables Meeseeks-native retry chains** — Meeseeks retries only on `TaskResult.Retry`
and `Failure.Transient`, capped at `min(request.maxRetries ?: config.maxRetryCount,
config.maxRetryCount)` (host config, default 3). The adapter leaves `maxRetries = null` so
the small host-configured budget remains available for exactly two cases: the startup
grace period and the schedule-failure fallback below. Verified behavior, not assumption,
is an exit gate (§8.3 item 1, §14).

Worker result mapping (`StoreDrainWorker.run` → `scheduler.runActivation(payload.storeName)`):

| Coordinator result | `TaskResult` |
|---|---|
| `Cleared` | `TaskResult.Success` |
| `Remaining` (scheduledDelay != null) | `TaskResult.Success` — the follow-up was already scheduled as a NEW task from inside the pass (below); returning `Retry` would create a second, competing chain |
| `Remaining` (scheduledDelay == null — scheduler backend rejected the follow-up) | `TaskResult.Retry` — Meeseeks' own bounded chain is the fallback wake-up. After the host-configured budget exhausts, the task is terminal and only the next enqueue, manual trigger, or launch pass recovers; checkpoint-only work specifically relies on the unconditional launch pass |
| `Unavailable` | `TaskResult.Failure.Transient` — a worker can fire before host registration completes during process start; the bounded Meeseeks retry budget is the grace period, and launch reconciliation is the authoritative recovery. Never `Failure.Permanent`: names that reappear after a hotfix or a slow `Application.onCreate` must not have their wake-up hint deleted |
| `CancellationException` from the pass | rethrow. Meeseeks 1.1.0 classifies it internally rather than propagating (§8.3); the §6.3 safety activation is the durable cover either way |

**Scheduling from inside a running worker — the self-cancellation hazard.** Meeseeks'
`reschedule(id, request)` cancels the current platform work for `id` before re-enqueueing.
Called from inside that task's own worker, it can cancel the caller, and unique-work
re-enqueue can be rejected while the work is still marked running. The adapter therefore
never calls `reschedule` on an id whose activation may be executing:

- Follow-ups scheduled from inside a pass always use `manager.schedule(newRequest)` — a
  new task id, no interaction with the running task. The tracked-id map then points at the
  new id; the finishing task completes `Success` and is terminal.
- `reschedule(id, request)` is used only for ids whose current `getTaskStatus(id)` is
  pending (enqueued, not running) — the watch/reconciliation path replacing a
  not-yet-fired request.
- `cancel(storeName)` cancels the tracked id if still pending; cancelling a running
  activation is legal (pass idempotence + safety activation cover it) but the adapter
  avoids it.

**Tracked-id map, staleness, and recovery.** The scheduler keeps an in-memory
`storeName → TaskId` map guarded by the same lock as `schedule`/`cancel` (lookup, decide,
act, update is one critical section — two concurrent `schedule` calls must not both
`manager.schedule`). Tracked ids go stale when their task completes: every `schedule` and
`cancel` first refreshes via `getTaskStatus(tracked)` — `null` or terminal
(`Finished.Cancelled/Completed/Failed`) means no tracked request, running means adopt
(leave it; its pass chains), pending means replace via `reschedule`. After process death
the map is empty; before scheduling anew, the adapter scans `manager.listTasks()` for rows
whose payload is a `StoreDrainPayload` with a matching name **and pending status** —
running rows are adopted into the map without scheduling, terminal rows are ignored, and a
payload that fails to deserialize (schema drift from an old app version) is skipped,
falling back to scheduling fresh. Transient duplicates that slip through are bounded and
harmless: passes are idempotent and the next replace collapses back to one tracked id.
(A first-class unique-key API in Meeseeks deletes this scan — §8.3 item 2.)

### 7.3 Host wiring

New app (Android shown; iOS/JVM analogous per Meeseeks platform docs). Registration and
watch startup are synchronous-enough by construction: `register` runs before `onCreate`
returns, and `watch` performs its own subscribe-then-reconcile, so an OS worker firing
during startup is covered by the `Unavailable → Transient` grace plus reconciliation.

```kotlin
class App : Application(), Configuration.Provider {
    val drainScheduler = MeeseeksDrainScheduler(manager = { bgTaskManager })

    // Meeseeks' AppContext is `actual typealias AppContext = Context` on Android.
    val bgTaskManager: BGTaskManager by lazy {
        Meeseeks.initialize(this) {
            // Serializer derived from the payload's @Serializable descriptor.
            register<StoreDrainPayload> { appContext -> StoreDrainWorker(appContext, drainScheduler) }
        }
    }

    val coordinator = mutationDrainCoordinator(drainScheduler)

    override fun onCreate() {
        super.onCreate()
        coordinator.register("users", usersStore)   // durable journalStorage required for
                                                    // cross-restart draining; see §10
        appScope.launch { coordinator.watch("users") }   // subscribes, then runs the launch pass
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(DelegatingWorkerFactory().apply {
                addFactory(MeeseeksWorkerFactory(bgTaskManager))
            })
            .build()
}
```

An app already running Meeseeks adds one `register<StoreDrainPayload>` line to its existing
`initialize` block and shares the manager. iOS additionally lists Meeseeks' two task
identifiers (`dev.mattramotar.meeseeks.task.refresh`,
`dev.mattramotar.meeseeks.task.processing`) in `BGTaskSchedulerPermittedIdentifiers`, per
Meeseeks' iOS guide — this artifact adds no identifiers of its own. Hosts that want iOS
foreground-reconnect coverage add one line on their app-active signal:
`scope.launch { coordinator.runActivation("users") }` (§10).

### 7.4 Platform behavior inherited from Meeseeks (and its limits, stated)

| Platform | Mechanism | Constraint support | Behavior notes |
|---|---|---|---|
| Android | WorkManager (`BGTaskCoroutineWorker` + `MeeseeksWorkerFactory`) | network, charging | Constraint-met work runs whether the app is foreground or background, subject to Doze, App Standby buckets, and background restrictions — deferral by hours is possible on idle devices. The adapter requests no expedited dispatch in v1. Requests survive process death and reboot via WorkManager's own store |
| iOS | BGTaskScheduler (`BGAppRefreshTask`/`BGProcessingTask` under Meeseeks' identifiers) | network, charging | Wakes are OS-managed and best-effort; activations do not fire while the app is foregrounded; force-quit suppresses launches until the next user open; requests are hints — Meeseeks' docs state "Meeseeks database is the source of truth; platform task requests are hints to the OS," and this design layers the same posture with the journal as truth |
| JVM | Quartz | none (Meeseeks fails fast on constraints) | Registration with default constraints fails at `validate` with a message naming the fix: `DrainConstraints(requiresNetwork = false, requiresCharging = false)` or `InProcessDrainScheduler`. Desktop hosts without an existing Meeseeks setup should prefer in-process |
| JS | Meeseeks runner | none | Same `validate` behavior. Browser/Node hosts should prefer `InProcessDrainScheduler`; there is no background execution beyond the live page/process either way |

The `validate` fail-fast (coordinator calls it at `register`) is deliberate: Meeseeks
throws `IllegalArgumentException` at schedule time for unsupported preconditions ("no
silent downgrade"); surfacing that at registration converts a runtime scheduling failure
into an immediate, actionable configuration error. The adapter's `validate` answers from
the Meeseeks capability matrix compiled per platform source set.

## 8. The Meeseeks decision

The task brief requires this design to either incorporate
[meeseeks](https://github.com/matt-ramotar/meeseeks) or answer "why not meeseeks." The
answer: **incorporate it as the OS-scheduler backend, behind a Store-owned seam, as a
separate adapter artifact.** Context that informs this: Meeseeks was authored for Store —
the Improving Sync RFC names it as the scheduling engine for background retry, and Store and
Meeseeks share a maintainer, so integration-driven Meeseeks changes and an eventual MNF port
are live options rather than external asks.

### 8.1 Options considered

| Option | Verdict | Reasoning |
|---|---|---|
| A. First-party WorkManager + BGTaskScheduler adapters, no Meeseeks | Rejected | Re-implements scheduling machinery that already exists, is published stable (1.1.0, SemVer'd, JVM+klib API-checked), was purpose-built for this integration, and is maintained by the same owner. Store would own WorkManager factory quirks, BGTask registration/expiration, and their test matrices for zero differentiated value |
| B. Meeseeks as a hard dependency of a single scheduler artifact | Rejected | Meeseeks publishes android/jvm/iosArm64/iosSimulatorArm64/iosX64/js — no wasmJs, macOS, watchOS, tvOS, Linux, Windows. A hard dependency shrinks the seam below the family's canonical 12 targets and forces Meeseeks' transitive surface (SQLDelight drivers, `kotlinx-serialization` as `api`, `kotlin-reflect`, `androidx.work` as `api`, Quartz on JVM, sql.js on JS) onto every consumer, including tests and desktop hosts that only need the in-process scheduler. It also couples the seam's graduation timeline to Meeseeks' |
| C. Seam artifact + Meeseeks adapter artifact (chosen) | **Chosen** | Mirrors `mutations` + `mutations-sqldelight`: contract in one artifact, backend in a sibling. The seam builds on all 12 targets with zero new dependencies; the adapter carries the Meeseeks matrix and weight only for apps that want OS scheduling. Tier isolation: a Meeseeks 2.x migration revs one adapter artifact |
| D. No scheduler feature; document manual wiring | Rejected | Leaves #677 open; the beta shortlist ranks this feature #2 precisely because the drain pipeline exists and the OS hookup is the missing offline-first piece |

### 8.2 What Meeseeks is and is not used for here

Used for: constraint-gated OS wake-ups, worker registration and process-death re-entry
wiring, platform quirk ownership (WorkManager configuration, BGTask identifiers and
expiration, Quartz), request persistence hygiene (`reschedulePendingTasks`), a bounded
native retry budget as the startup grace period and schedule-failure fallback (§7.2), and
its telemetry for hosts that already aggregate Meeseeks events.

Deliberately not used for: primary retry policy (the coordinator derives delays from
journal state; the worker returns `Success` on the normal path), payload state (payload
carries only the registration name), periodic chains, checkpointing (`CheckpointedWorker` —
the journal already provides resumability), and completion replay (`replayTerminalEvents` —
launch reconciliation re-derives from the journal instead). This keeps one primary retry
brain and one durable truth. The overlap cost that remains is honest and small: Meeseeks
persists its task rows in its own SQLDelight database, so on Android there are three
persisted layers (journal, WorkManager's store, Meeseeks'); the journal remains
authoritative and the other two are self-healing hints.

### 8.3 Coordination items against Meeseeks (owner-confirmed as changeable)

Item 1 blocks the adapter's exit gate (not the seam's); items 2–6 are upstream improvements
the adapter works around until they land.

1. **Blocking verification (adapter jvmTest, §12):** (a) `manager.schedule(new)` from
   inside a running worker enqueues and later fires, on Quartz and WorkManager paths;
   (b) `TaskResult.Success` produces no further activations regardless of
   `maxRetryCount`; (c) `Failure.Transient` retries are bounded by
   `min(request.maxRetries ?: config.maxRetryCount, config.maxRetryCount)` as read from
   1.1.0 sources; (d) `getTaskStatus`/`listTasks` distinguish pending from running from
   terminal (`TaskStatus.Pending`/`Running`/`Finished.*`) for the §7.2 predicates; (e) a
   `StoreDrainPayload` that fails to deserialize in `listTasks` is survivable (skip, not
   throw); (f) terminal task rows are pruned or bounded, so the recovery scan does not
   degrade with app age. Any failed verification converts its workaround into an upstream
   Meeseeks change gating the adapter's first release.
2. **Upstream: unique-key scheduling.** `schedule(request, uniqueKey, replacePolicy)` or
   equivalent, scoped to non-terminal tasks, eliminating the `listTasks` scan and the
   transient-duplicate window.
3. **Upstream: rethrow `CancellationException`.** 1.1.0's executor catches all throwables
   and classifies cancellation as retryable; expiry/cancellation should propagate so
   workers and platform runners see the platform-native contract. The safety activation
   makes this non-blocking for the adapter.
4. **Upstream: schedule-successor-on-completion API** (worker returns a follow-up request
   Meeseeks schedules after the task finishes), removing the schedule-new-from-inside-
   worker pattern entirely.
5. **Upstream: wasmJs target**, widening the adapter matrix.
6. **Governance:** publish Meeseeks under MNF (or mirror under
   `org.mobilenativefoundation` coordinates) before `mutations-drain-meeseeks` is proposed
   for graduation past experimental. While both artifacts are experimental, a
   `dev.mattramotar` dependency is acceptable with the tier stated on the artifact — and
   this resolves the placement question the RFC itself left open ("potentially MNF").

## 9. Lifecycle and concurrency contract

Every cell is a certified behavior (§12), not prose.

| Situation | Behavior |
|---|---|
| `attach` called twice, or `schedule`/`cancel` before `attach` | `IllegalStateException` |
| `register` twice with the same name, invalid name, same store under two names, unsupported constraints | `IllegalArgumentException` (from `validate` for constraints) |
| `register` / `unregister` concurrent with passes | Registry map is mutex-guarded; a pass holds its (store, policy, epoch) snapshot and finishes on it; `unregister` drops the mapping, cancels the pending activation, and the in-flight pass of the removed epoch schedules no follow-up |
| `watch(name)` for an unregistered name | `IllegalArgumentException` |
| `watch` while a pass is running | Enqueue triggers conflate to at most one further pass |
| `unregister` / `close` during active `watch` | Watcher completes with `CancellationException` |
| `watch` cancelled externally mid-pass | Journal state replayable (`INFLIGHT` contract); the launch pass recovers; no coordinator cleanup needed |
| `reconcile` while a pass runs for some store | That store skipped; the running pass schedules its own follow-up |
| Two concurrent `runActivation(name)` (OS replay + manual) | Second waits on the per-name pass mutex, then runs; both derive from durable truth; idempotent |
| `runActivation` from code on the drain stack (`MutationServer`, mutators, conflict policies, SoT) or from a `watch` handler | Forbidden — the pass mutex is not reentrant; deadlock. KDoc states it; not runtime-detected |
| `cancel(name)` while that activation is executing | Legal; pass completes and its follow-up scheduling reinstates a request if work remains |
| Store closed mid-pass | `IllegalStateException("Store is closed.")` from `drain()`/`pendingWrites()` caught → `Unavailable` |
| Coordinator `close()` | Watchers cancelled; `register`/`unregister`/`reconcile`/`watch` throw `IllegalStateException`; `runActivation` returns `Unavailable`; OS requests untouched (they outlive the process by design) |
| `InProcessDrainScheduler.schedule` on a cancelled scope | Throws `IllegalStateException` → coordinator emits `DrainScheduleFailed` |
| Backward wall-clock jump | Engine eligibility uses absolute epoch millis; passes may no-op until the clock catches up; escalation bounds the waste; documented, not worked around |

## 10. Process-death and platform-gap matrix

| Scenario | What happens |
|---|---|
| Enqueue online, app foreground | `watch` fast path runs the pass; push completes; `Cleared`; no OS request was created (no churn) |
| Enqueue offline, app foreground | Fast-path pass fails transport (attempt=1 durable); follow-up scheduled (network constraint, 30s floor); Android runs it on connectivity regain, foreground or background |
| Process dies during a fast-path pass (no safety activation) | Engine `INFLIGHT` replay preserves the write; if a follow-up had been scheduled by an earlier outcome it fires, otherwise the next launch pass recovers. Deliberate trade for zero per-write scheduler churn |
| Process dies during a scheduler-fired or manual pass | Safety activation (persisted pre-pass) fires at `backoff.maxDelay`; `INFLIGHT` replay covers the transport window |
| iOS: connectivity regained in foreground, no new writes | **Not covered by OS scheduling** (BGTask fires background-only) and deliberately no `NWPathMonitor` dependency. Covered when the host calls `runActivation(name)` on its app-active or reachability signal (one line, §7.3); otherwise the next background grant, enqueue, or launch drains |
| App killed with pending intents, durable journal | Next launch: `watch`'s unconditional pass (or `reconcile()`) drains and schedules. Independently, a persisted OS request may fire first and re-enter via the worker; both paths converge on idempotent passes |
| Retirement checkpoint failed, journal otherwise empty | Pass-scoped observation reschedules with the `initialDelay` floor + escalation; if that request is lost (schedule failure + fallback exhaustion), the next launch's unconditional pass retries the flush — this is why reconciliation never pre-checks `pendingWrites()` |
| OS wakes dead process (Android) | WorkManager starts the process; worker may fire before host registration completes → `Unavailable` → `Failure.Transient` grace retries (bounded by host Meeseeks config), then launch reconciliation is authoritative |
| OS wakes dead process (iOS) | BGTask launch handler (registered by Meeseeks before launch completes) runs the worker; same grace path. Execution-window expiry cancels the pass; safety activation + `INFLIGHT` replay cover it |
| Force-quit (iOS) | No background launches until the user reopens; first foreground launch reconciles. Documented, not worked around |
| Doze / App Standby (Android) | Constraint-met work may defer for hours on idle devices; journal durability makes this a latency, not a loss. Documented; no expedited dispatch in v1 |
| In-memory journal (default `journalStorage`) | Scheduling works while the process lives (connectivity-return draining); after process death there is nothing to drain and the launch pass finds an empty journal. README states plainly: durable draining requires durable journal storage (`mutations-sqldelight`) |
| Registration renamed across app versions | Old persisted payloads resolve `Unavailable` → bounded grace → terminal; new-name launch pass schedules fresh. No loss; documented naming discipline (§6.5) |
| Device reboot (Android) | WorkManager persists across reboot; the launch pass additionally covers every platform uniformly |

## 11. Observability

Extension-owned advisory events only (§6.1 vocabulary), delivered with the exact
`MutationEventBus` contract (replay 0, buffer 64, `DROP_OLDEST`, `tryEmit`). No new core or
mutations telemetry hooks; no wire format decided (same deferral as devtools). The
`DrainPassCompleted` event carries `pendingIntents` and `deadLetters` counts so a host can
drive an "N items not synced" affordance from one subscription, answering the inspection ask
in #677 without new mutations API. Hosts that want durable analytics read
`pendingWrites()` / `deadLetters()` directly; events are never the settlement protocol, and
the coordinator never consumes its own events.

## 12. Testing strategy

`mutations-drain` (commonTest, `kotlinx-coroutines-test` + turbine, matching `mutations`
test style; virtual time via the store's `wallClock` door and the coordinator's `wallClock`
parameter; a `RecordingDrainScheduler` test double):

1. **Trigger matrix** — every §6.2 row, including: watch startup runs the unconditional
   pass after subscription is active (an enqueue racing watch startup is never lost);
   enqueue-burst coalescing asserts at most 2 passes for an N-burst (one running + one
   conflated trailing); non-enqueue events ignored; drainOnEnqueue=false schedules ZERO.
2. **Outcome + head derivation** — every §6.4 row: all-retired → Cleared;
   transport-failure head (`PENDING`, attempt≥1) → `delay(attempt)`; never-attempted
   in-FIFO suffix behind an in-window head → suffix does NOT lower the delay (the §6.4
   busy-loop regression test); post-ack throw with `ADOPTING` head → ZERO + escalation on
   repeat; `INFLIGHT` head (concurrent pass) → ZERO + escalation; `REFRESHING` in-window
   head skipped by the engine → escalation bounds the loop; conflict generation reset
   (attempt back to 0) → ZERO and engine-eligible; checkpoint-failure observed with empty
   `pendingWrites` → Remaining(0) at `initialDelay` floor; parked-only → Cleared (dead
   letters never reschedule); closed store → Unavailable; schedule() throwing →
   `DrainScheduleFailed` + `scheduledDelay = null`; safety-persist failure → pass still
   runs.
3. **Escalation and fingerprint** — fingerprint is exactly the multiset of
   `(mutationId, namespace, canonicalId, state, attempt)`; absent-initial vs empty
   distinction; `k` grows only across real passes; growth to `maxDelay`
   (`multiplier > 1`) and constant floor (`multiplier = 1.0`); reset on any journal
   progress; reset with a new coordinator instance.
4. **Alignment guard** — offline-fail a head to attempt N against the real engine,
   advance virtual time by the coordinator's `delay(N)`, run a pass, assert the head was
   attempted (fails if the engine's window ever outgrows the coordinator's delay).
5. **Safety activation** — persisted before scheduler-fired/manual passes only (fast path
   persists none — churn regression test asserts zero schedule calls for a successful
   online fast-path pass); replaced by the derived follow-up; cancelled on Cleared;
   survives a cancelled pass.
6. **Lifecycle contract** — every §9 row: attach-twice, pre-attach schedule, duplicate
   register, same-store-two-names, unregister-during-watch, unregister-mid-pass
   suppresses the follow-up, close semantics (`runActivation` → Unavailable, others
   throw), concurrent `runActivation`, `reconcile`-during-pass skip, multi-store isolation
   (store A's schedule/cancel never touches store B's tracked request).
7. **In-process scheduler** — replace-per-store under concurrent `schedule` calls,
   cancel, fire-once per schedule, dead-scope rejection, virtual-time firing.
8. **Event contract** — field-level assertions on every `DrainSchedulerEvent` type;
   `DrainActivationCancelled` only on true cancellation (not replacement); non-blocking
   emission; drop-oldest under pressure.
9. **Restart replay** — enqueue → close → reopen over the same `journalStorage` →
   launch pass drains and schedules; matches `MutationRestartWalkingTest` fixture style.

`mutations-drain-meeseeks`:

1. **jvmTest against real Meeseeks (Quartz)** — the §8.3 item-1 verifications (a)–(f),
   each a named test; schedule→activation→worker→coordinator round trip;
   replace-via-`reschedule` only for verified-pending ids; tracked-id staleness refresh
   (`getTaskStatus` null/terminal → schedule new); recovery scan predicate (pending only,
   running adopted, terminal ignored, undeserializable skipped); concurrent `schedule`
   single-winner under the adapter lock; `Unavailable` → Transient grace → later
   registration recovers.
2. **Mapping unit tests** — §7.2 tables as pure-function tests (request mapping, result
   mapping), commonTest.
3. **Android/iOS** — compile + BCV/klib dumps + apple-tests lane for iosSimulatorArm64
   unit tests of mapping code. End-to-end WorkManager/BGTask execution is exercised by
   the sample app manually (BGTask via the documented `_simulateLaunchTask` LLDB flow) and
   is explicitly not CI-gated — stated in the README as the platform-reality boundary, the
   same posture Meeseeks itself takes.

Docs snippets compile in tests per the repo's `*DocsSnippet.kt` convention (README code
comes from modules CI compiles).

## 13. Build, CI, and release wiring

| Item | `mutations-drain` | `mutations-drain-meeseeks` |
|---|---|---|
| settings.gradle | `include ':mutations-drain'` (+ `:mutations-drain-sample`) | `include ':mutations-drain-meeseeks'` |
| Convention plugin | `store6.multiplatform` (full 12) | `store6.multiplatform.subset` (Meeseeks matrix) |
| gradle.properties | `POM_ARTIFACT_ID=mutations-drain` | `POM_ARTIFACT_ID=mutations-drain-meeseeks` |
| Version catalog | — | `meeseeks-runtime = { module = "dev.mattramotar.meeseeks:runtime", version = "1.1.0" }` + kotlin-serialization plugin |
| API dumps | `api/jvm`, `api/android`, `api/*.klib.api` committed | same |
| store6.yml edits (hard-coded lists) | linux-build module list; core-internal ban list; apple-tests matrix; klib publication modules; JS canary | same, plus publication-check suffix exclusions for its absent targets (wasmJs/macos/watchos/tvos/linux/mingw) |
| STABILITY.md | §3 artifact table gains both rows (experimental) | — |
| Sample | `mutations-drain-sample` (JVM, in-process scheduler; `mutations-quickstart` pattern) | wiring snippets in jvmTest/docs-snippet form |
| Docs | module README (realtime README as template); quickstart cross-link from the mutations drain section | module README incl. Meeseeks platform-setup pointers |

Kotlin floor 2.3 (repo-wide), Android minSdk 24 / compileSdk 36 (convention plugin), iOS
floor for the adapter follows Meeseeks' BGTaskScheduler requirement (iOS 13+; verified in
plan against the convention plugin's deployment targets).

## 14. Exit gates

The feature is shippable in an alpha when:

1. Every §6.2, §6.4, and §9 table row has a named passing test in `mutations-drain`
   commonTest (the §12.2 busy-loop, post-ack-stall, and fast-path-churn regressions
   explicitly included).
2. The §6.6 alignment guard passes against the real engine.
3. The Meeseeks jvm suite passes with all six §8.3 item-1 verifications green, or the
   failed verification's upstream Meeseeks change has shipped and the suite passes against
   the bumped version.
4. BCV JVM + klib dumps committed for both artifacts; core-internal ban list green;
   apple-tests lane green; klib publication check green with the adapter's target
   exclusions.
5. Both READMEs exist with compiled snippets; the in-memory-journal caveat, the iOS
   force-quit/foreground-reconnect caveats, the Doze caveat, and the multi-process
   non-support are stated.
6. The sample runs: offline enqueue → restart → launch pass → drain → confirmed, on JVM
   with the in-process scheduler.
7. #677 can be closed with links to the named conformance tests (repo release rule:
   "closes at least one community issue with a link to the named guarantee").

## 15. Follow-ups this design deliberately defers

Named here so the zero-mutations-diff constraint is a decision, not an oversight. Each
would sharpen an approximation §6.4 makes from public API:

1. **Structured drain report from `mutations`** — per-identity head states, retry-at
   timestamps (from the non-public `lastAttemptAt` + window), and outstanding
   retirement-checkpoint lag. Replaces head-derivation heuristics, the checkpoint-event
   observation, and the unconditional launch pass with engine truth. Candidate for a
   mutations API review after this ships and real usage exists.
2. **Meeseeks unique-key scheduling, cancellation propagation, successor-on-completion,
   wasmJs** (§8.3 items 2–5).
3. **Expedited/priority dispatch knob** on `DrainPolicy` mapped to Meeseeks
   priority/expedited once demand shows (v1 deliberately requests none).
4. **A public `RecordingDrainScheduler`** in a testing artifact if adapter authors appear
   (the contract-kit precedent is `mutations-testing`).
5. **macOS/watchOS/tvOS backends** on `NSBackgroundActivityScheduler` /
   `WKApplicationRefreshBackgroundTask`.

## 16. Open questions (with defaults, so review can veto rather than stall)

1. **Give-up policy.** Should `DrainPolicy` cap scheduler-driven retries (stop rescheduling
   after N activations until the next enqueue/launch)? Default: no cap in v1 — the 1h
   `maxDelay` bounds battery cost, escalation bounds hot paths, and parks handle terminal
   failures; revisit with beta telemetry.
2. **Unmetered-network constraint.** WorkManager supports `NetworkType.UNMETERED`;
   Meeseeks and BGTaskScheduler do not express it. Default: out of v1 (two-knob
   constraints); add if Meeseeks grows it.
3. **`watchAll()` convenience.** One collector spanning all registrations vs per-store
   `watch(name)`. Default: per-store only in v1; trivial for hosts to compose.
4. **Artifact naming.** `mutations-drain` vs `mutations-scheduling`. Default:
   `mutations-drain`, matching the seam's vocabulary (`drain`, journal) and the
   `mutations-*` family shape.
