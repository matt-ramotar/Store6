# Conflict-strategy pack — technical design

Status: reviewed draft (revision 2, after two adversarial model reviews) · Target artifact:
`mutations-conflicts` (new) · Base branch: `store6`

This document is the written-before-work design required by roadmap principle 5 ("Gates are
written down before the work starts"). Its companion,
[implementation-plan.md](./implementation-plan.md), decomposes the build into orchestrated
sub-agent tasks.

## 1. Summary

A new experimental extension artifact, `mutations-conflicts`, that ships canned conflict merge
policies — server-wins, client-wins, last-write-wins, and three-way merge (value-level and
selected-fields) — as one-line registrations inside the existing `conflicts { }` door of
`mutationStore`. The mutations engine already owns the entire conflict pipeline: receipt, durable
`REFRESH_REQUIRED` phase, freshness barrier, recapture, bounded retry, park, and retire. Today
only bespoke lambdas fit the doors. The pack supplies named policies with exact, tested presence
matrices, so the deletion/creation cells and termination behavior are decided once and
documented instead of re-derived per app.

**Demand evidence, scoped honestly.**
[MobileNativeFoundation/Store#678](https://github.com/MobileNativeFoundation/Store/issues/678)
asks to "enable configuration of conflict resolution strategies, such as logging, retrying,
canceling" — and its lead sentence asks not to "pull immediately after failing to push local
changes." This pack delivers the named-strategy vocabulary: retrying maps to the `Retry`
resolutions produced by client-wins / last-write-wins / three-way merge, and canceling maps to
`ServerWins` (retire the intent; authoritative state stands). It does **not** deliver the
"don't pull" part: the engine unconditionally runs a `MustBeFresh` freshness barrier before any
merge policy executes, and this design's zero-`mutations`-diff constraint (§3) keeps that
immovable. A resolve-without-refetch capability would be a `mutations` engine proposal, not a
policy pack. Logging is likewise not a policy: the engine already emits advisory conflict events
(§7), and a pure, re-executable merge is the wrong place for a side effect. The pack therefore
answers #678's strategy-configuration ask within the engine's existing semantics, and this
design says so rather than claiming to close the issue.

## 2. The doors and the pipeline as built (context, verified against source)

Everything below is existing behavior in `mutations`; the pack changes none of it.

**Registration.** `MutationStoreBuilder.conflicts(configure: MutationConflictBuilder<K, V>.() -> Unit)`
(`mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt`).
`MutationConflictBuilder` exposes exactly two doors, each registering at most once per block:

- `precondition(select: (MutationPreconditionCandidate<K, V>) -> StoreMeta?)` — pure metadata
  selector, run once per newly prepared semantic generation, never on a transport retry.
  Returning `null` selects an existence/value precondition without metadata; it never removes the
  base precondition. Unset, the engine selects the candidate's captured metadata.
- `merge(merge: (base: MutationPresence<V>, mine: MutationPresence<V>, theirs: MutationPresence<V>) -> MutationConflictResolution<V>)`
  — pure policy consulted on a server-signaled conflict. Returns
  `MutationConflictResolution.Retry(value: MutationPresence<V>)` or
  `MutationConflictResolution.ServerWins`. Without a registered merge, server-wins is the
  non-removable terminal.

**Conflict signal.** A conflict exists only as a thrown exception from `MutationServer.push`,
built through the public core doors:

```kotlin
throw StoreResults.exception(StoreResults.conflict(serverMeta, message), cause)
```

where `serverMeta: StoreMeta?` carries at most `writtenAtEpochMillis: Long` and `etag: String?`.

**Receipt and the unchanged-conflict bound.** On receipt the engine durably records the conflict
on the current generation's immutable attempt row and advances the intent to `REFRESH_REQUIRED`
— unless the trailing run of conflict-bearing attempt rows for this client sequence (one row per
generation, newest backward) all carry an identical `(conflictWrittenAt, conflictEtag)` pair and
that run has reached `CONFLICT_UNCHANGED_BOUND = 3`, in which case the intent parks atomically
with a normalized `CONFLICT` failure (`detail = "conflict-unchanged-bound"`). The bound counts
**across generations**: a retrying policy contributes one receipt per generation, so
"three consecutive conflicted generations with the same server timestamp/etag pair" is the
precise trigger. Receipts whose `serverMeta` is null record a `(null, null)` pair and compare
identical to each other. Conflict message text and metadata object identity never participate.

**Resume.** Resuming `REFRESH_REQUIRED` runs a `MustBeFresh` read as a completion barrier only,
recaptures `theirs` by the same ordered `status -> LocalOnly` capture loop used for bases
(`StoreError.Missing` means authoritative `Absent`), decodes the attempt's frozen `base` and
`mine` from durable blobs, and invokes the merge. `Retry(value)` persists generation `g + 1` —
with `base` = the recapture, `mine = value`, a fresh precondition selection, and a new
generation idempotency key — before any transmission. `ServerWins` retires the intent without
another push and terminally `SKIPPED`s its pending invalidation effects
(`MutationEffectSkipped`). A merge or selector that throws parks the intent with kind `CONFLICT`
and detail `"merge-failed"` / `"selector-failed"`.

**Inputs are copies, by two mechanisms.** `base` and `mine` are decoded fresh from the durable
attempt's blobs through the value codec's defensive-copy boundary (`decodeCopied`); `theirs`
passes through the engine's `copiedPresence` (a codec encode/decode round trip). A policy
therefore cannot corrupt engine state by mutating its inputs. Purity is still required for a
different reason: `REFRESH_REQUIRED` is a durable phase. A crash or a rolled-back retry
transaction re-resumes it, so a merge can run more than once for one conflict receipt and must
be deterministic over equal inputs.

**Returned values must round-trip the codec.** The engine copies a `Retry` value through
`copiedPresence` **outside** the merge's catch block, then encodes it into the durable attempt.
A returned value the registered codec cannot encode/decode does not park the intent: the failure
propagates out of the drain call and the intent remains in `REFRESH_REQUIRED` (retryable). The
pack documents this contract and tests it (§8).

**Scheduling.** `READY` and `REFRESH_REQUIRED` share the global drain's backoff eligibility, so
after a conflict receipt the merge normally runs on a later eligible global pass; an explicit
keyed `drain(key)` bypasses backoff and resumes immediately.

**What the merge does not receive.** The door's signature carries three presences and nothing
else — no `StoreMeta`, no key, no generation. The conflict receipt's `serverMeta` is recorded in
the journal and surfaced on `MutationConflictObserved`, but it is not a merge input, and
`theirs` is the post-barrier recapture of local authoritative truth — never the conflict
response's payload. This shapes the last-write-wins design (§5.3): ordering information must
travel inside `V`.

## 3. Goals and non-goals

**Goals**

1. Canned merge policies for the four named strategies, each with a complete, documented,
   deterministic presence matrix (deletion and creation conflicts included).
2. Zero source diff to `core` and `mutations`. The pack consumes only public
   `@ExperimentalStoreApi` surface. (Repository registration files — `settings.gradle`,
   `.github/workflows/store6.yml` — are wiring, not library diff.)
3. Policy decisions unit-testable without a store, and the full pipeline behavior
   (retry generation, retire, park, bound) integration-tested through a real `mutationStore`.
4. The artifact clears every gate an existing extension clears (§10) on the full 12-target
   convention.

**Non-goals**

- No new engine capability: no new resolution kinds, no suspend or effectful merge hooks, no
  new failure kinds or details, no changes to the unchanged-conflict bound, and no way to skip
  or defer the pre-merge freshness barrier (§1).
- No transport-level conflict payloads. `theirs` is the post-barrier recapture of local
  authoritative truth; a server that wants its copy considered must serve it to the fetcher.
- No logging/observation hook inside policies (§7).
- No retry-count or backoff policies. The merge door is a pure stateless function of
  `(base, mine, theirs)`; "retry n times then give up" needs durable attempt state the door
  deliberately does not expose. Drain cadence and backoff belong to the engine.
- No park-for-manual-resolution ("escalate") policy — cut during review; §12 records why.
- No precondition selector helpers in the initial scope (§12).
- No devtools or event-vocabulary changes.

## 4. Artifact and packaging

| Decision | Value |
|---|---|
| Module / artifact | `mutations-conflicts` · `org.mobilenativefoundation.store:mutations-conflicts:6.0.0-SNAPSHOT` |
| Package | `org.mobilenativefoundation.store6.mutations.conflicts` |
| Tier | Experimental; every public declaration carries `@ExperimentalStoreApi` (matches `mutations-testing` practice and STABILITY.md §2) |
| Targets | The full 12-target Store6 convention via `org.mobilenativefoundation.store.store6.multiplatform` |
| Production dependencies | `api(projects.mutations)` only. No new third-party dependencies; no kotlinx-datetime (not in the version catalog; epoch-millis `Long`s suffice) |
| Test dependencies (`commonTest`) | `implementation(projects.testing)` (for `TestWallClock`), `implementation(libs.kotlinx.coroutines.test)`; `implementation(libs.turbine)` only if event assertions need it (same set `mutations` itself uses) |
| Android namespace | `org.mobilenativefoundation.store6.mutations.conflicts` |
| Swift dumps | None. The Swift dump/facade lanes cover `core`, `mutations`, and the `store6-swift` facade; no extension artifact participates |

Why a separate artifact rather than shipping inside `mutations`: the satellite pattern is already
established (`mutations-testing`, `mutations-sqldelight`); it keeps the engine artifact minimal
while policy vocabulary iterates on its own rhythm; and building strictly through the public
doors is itself extension-contract evidence for the mutations graduation review.

## 5. Public API

All declarations below are `public`, carry `@ExperimentalStoreApi`, and live in
`org.mobilenativefoundation.store6.mutations.conflicts`. The surface has two layers: **factories**
on a `MutationMerges` namespace object (precedent: core's `StoreResults`) that return the door's
exact function type and are directly unit-testable, and **builder extensions** on
`MutationConflictBuilder<K, V>` that register the corresponding factory result via the existing
`merge(...)` door. Extension names are DSL verb phrases (`threeWayMerge`, `mergeFields`) while
factory names are nouns on the namespace object (`threeWay`, `fields`); the pairing is stated in
KDoc. Extensions never register a precondition selector, so any canned policy composes with a
caller's own `precondition { }` in the same block. Registering two merge policies in one block
fails while the `conflicts { }` block executes during store construction, with the door's own
message (`"conflicts { } already registered a merge policy."`) — fail-fast behavior inherited
from the door, not reimplemented.

```kotlin
/**
 * The merge function shape accepted by MutationConflictBuilder.merge. Kotlin typealiases are
 * transparent, so values of this type install directly. Parameter names are documentation only.
 */
public typealias MutationMergeFunction<V> =
    (
        base: MutationPresence<V>,
        mine: MutationPresence<V>,
        theirs: MutationPresence<V>,
    ) -> MutationConflictResolution<V>

/**
 * Which side a policy prefers when a rule must pick one. MINE is this client's projected
 * outcome; THEIRS is the post-barrier recapture of local authoritative truth — not the conflict
 * response's payload and not StoreError.Conflict.serverMeta.
 */
public enum class MutationConflictBias { MINE, THEIRS }

public object MutationMerges {
    public fun <V : Any> serverWins(): MutationMergeFunction<V>

    public fun <V : Any> clientWins(): MutationMergeFunction<V>

    public fun <V : Any> lastWriteWins(
        onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        writtenAt: (V) -> Long,
    ): MutationMergeFunction<V>

    public fun <V : Any> threeWay(
        onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        merge: (base: V?, mine: V, theirs: V) -> V,
    ): MutationMergeFunction<V>

    public fun <V : Any> fields(
        onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
        onBothChanged: MutationConflictBias = MutationConflictBias.THEIRS,
        configure: MutationFieldMergeBuilder<V>.() -> Unit,
    ): MutationMergeFunction<V>
}

// Builder sugar; each delegates to the factory of the corresponding name.
public fun <K : StoreKey, V : Any> MutationConflictBuilder<K, V>.serverWins()
public fun <K : StoreKey, V : Any> MutationConflictBuilder<K, V>.clientWins()
public fun <K : StoreKey, V : Any> MutationConflictBuilder<K, V>.lastWriteWins(
    onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    writtenAt: (V) -> Long,
)
public fun <K : StoreKey, V : Any> MutationConflictBuilder<K, V>.threeWayMerge(
    onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    merge: (base: V?, mine: V, theirs: V) -> V,
)
public fun <K : StoreKey, V : Any> MutationConflictBuilder<K, V>.mergeFields(
    onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    onBothChanged: MutationConflictBias = MutationConflictBias.THEIRS,
    configure: MutationFieldMergeBuilder<V>.() -> Unit,
)
```

Usage:

```kotlin
mutationStore(registry, server, keyResolver, valueCodecVersion = 1, valueCodec = codec) {
    fetcher(api::load)
    conflicts {
        lastWriteWins { note -> note.updatedAtEpochMillis }
    }
}
```

`writtenAt` stays the last parameter of `lastWriteWins` so the trailing-lambda call above
compiles with the enum defaults in place.

### 5.1 `serverWins()`

Always returns `MutationConflictResolution.ServerWins`. Behaviorally identical to registering no
merge; it exists so the choice is explicit in code review, pairs with a caller-supplied
`precondition { }` in the same block, and names the default in the pack's vocabulary. The KDoc
states the equivalence.

### 5.2 `clientWins()`

Always returns `Retry(mine)`. Because a retry generation's `mine` is the previous resolution's
value, client-wins re-asserts the caller's projected outcome across conflict rounds — including
`Retry(Absent)` when the intent was a deletion.

Termination, stated precisely rather than optimistically: a retry converges when generation
`g + 1`'s precondition — built from the post-barrier recapture — matches server state at the
next push. When the barrier fetch adopts the server's conflicting value, that is the common
case. When it does not (or the server keeps moving), the engine's unchanged-conflict bound parks
the intent after three consecutive conflicted generations with an identical server
timestamp/etag pair (§2) — and a server that mints a fresh `serverMeta` on every rejection never
trips that bound, so client-wins retries indefinitely at drain cadence against such a server.
The KDoc carries this paragraph.

### 5.3 `lastWriteWins(onMineAbsent, onTheirsAbsent, writtenAt)`

`writtenAt` extracts a totally ordered write stamp from a value — epoch milliseconds or any
monotonic logical/hybrid clock the application maintains. The stamp must live inside `V`: the
merge door receives no `StoreMeta` (§2), so the bookkeeping stamp Store already maintains is not
available to a policy, and `theirs` is the freshest authoritative value by recapture.

**Correctness precondition, stated first in KDoc and README:** the mutator's projector must
assign a fresh stamp to every local write. A projector that copies the base's stamp (the
routine `copy(title = ...)` shape) produces ties, and ties lose to the server — the policy
silently degrades to server-wins. The README example shows a projector bumping the stamp.

Decision table (P = `Present`, A = `Absent`):

| mine | theirs | Resolution |
|---|---|---|
| P | P | `writtenAt(mine) > writtenAt(theirs)` → `Retry(mine)`; otherwise → `ServerWins`. Ties go to the server. |
| A | P | `onMineAbsent`: `THEIRS` → `ServerWins`; `MINE` → `Retry(Absent)` (re-push the deletion) |
| P | A | `onTheirsAbsent`: `THEIRS` → `ServerWins` (stay deleted); `MINE` → `Retry(mine)` (recreate) |
| A | A | `ServerWins` (nothing to contend) |

`base` does not participate in LWW decisions. A deletion carries no `V` to stamp, so true LWW
over deletes is impossible at this door; the absent cells are explicit bias knobs instead, and
the defaults are `THEIRS` because that invents no state. Consequence, documented verbatim in
KDoc: **a bare `lastWriteWins { ... }` call is last-write-wins when both sides are present and
server-wins otherwise.** Wall-clock stamps are skew-sensitive — prefer server-assigned or
hybrid stamps. A `writtenAt` that throws parks the intent (`CONFLICT` / `"merge-failed"`).
A comparator overload (`compare: (V, V) -> Int`) was considered and deferred: `Long` covers the
stated stamp shapes, and the experimental tier permits adding an overload compatibly later.

### 5.4 `threeWay(onMineAbsent, onTheirsAbsent, merge)`

Value-level three-way merge. The presence matrix is pack-owned; the value merge is caller-owned.

| base | mine | theirs | Resolution |
|---|---|---|---|
| any | P | P | `Retry(Present(merge(baseOrNull, mine, theirs)))` where `baseOrNull` is the base value, or `null` when base is `Absent` (both sides created independently) |
| any | A | P | `onMineAbsent` as in §5.3 |
| any | P | A | `onTheirsAbsent` as in §5.3 |
| any | A | A | `ServerWins` (nothing to contend; see §6.4 for the effect consequence) |

The pack never converts an affirmative `Retry` into `ServerWins` on value equality: even if
`merge(...) == theirs`, the resolution is `Retry`, because a successful push runs the mutator's
declared invalidation effects and adoption, while `ServerWins` terminally skips them. Collapsing
the two on `equals` would silently change effect semantics. (The `A`/`A` cell is different in
kind: there is no write left to make, and pushing a delete-of-an-absent-entity generation solely
to trigger effects would use the transport as an effects scheduler — rejected in §12.)

### 5.5 `fields(onMineAbsent, onTheirsAbsent, onBothChanged, configure)` / `mergeFields`

Selected-fields three-way merge. The name of the game, stated before anything else: **this
policy merges the fields you register and resolves every unregistered field to `theirs`.** The
builder cannot enumerate a type's properties in common Kotlin, so it cannot detect an unregistered
locally-mutated field; forgetting one silently surrenders that field's local edit. Register every
field the app mutates locally. This warning leads the KDoc and the README section.

```kotlin
public class MutationFieldMergeBuilder<V : Any> internal constructor() {
    /** Registers one field; contested values resolve by the policy's onBothChanged bias. */
    public fun <F> field(get: (V) -> F, set: (V, F) -> V)

    /** Registers one field with a caller-owned combiner for contested values. */
    public fun <F> field(
        get: (V) -> F,
        set: (V, F) -> V,
        combine: (base: V?, mine: F, theirs: F) -> F,
    )
}
```

Property references satisfy `get` (`field(Note::title, { v, x -> v.copy(title = x) })`); no
reflection dependency is involved. The two overloads are distinguished by arity.

Contracts, all of which the implementation enforces or the KDoc states:

- **Construction.** At least one registered field is required; an empty block throws
  `IllegalArgumentException` from the factory. Registrations are snapshotted into an immutable
  list when the factory returns; calling `field(...)` on an escaped builder reference afterward
  throws `IllegalStateException`.
- **Equality.** Field values are compared with `==` (`Any.equals`). `Array`/`ByteArray` compare
  by identity, not content — callers needing content equality wrap the field type or supply a
  `combine`. `Double.NaN != NaN` applies.
- **Order.** Fields apply in registration order over a single mutable canvas (a local variable
  initialized to the `theirs` value; the pack makes no additional copy). A later registration's
  `set` sees earlier registrations' results; registering overlapping lenses means the later one
  wins where they collide.
- **Algorithm**, in the both-present cell of §5.4's matrix, per registered field with
  `m = get(mine)`, `t = get(theirs)`, and `b = get(base)` when base is `Present`:
  - base `Present`: `m == b` → keep `t` (only theirs changed, or neither); `m != b && t == b` →
    `set(canvas, m)` (only mine changed); `m != b && t != b && m == t` → keep `t` (identical
    change); `m != b && t != b && m != t` → **contested**.
  - base `Absent` (both created): `m == t` → keep `t`; `m != t` → **contested**.
  - Contested: the field's `combine` when registered, else `onBothChanged` (`THEIRS` → keep `t`,
    `MINE` → `set(canvas, m)`).
- **Result.** `Retry(Present(canvas))`.
- **Purity.** `get`/`set`/`combine` must be pure; a throw parks the intent. `combine` receives
  the whole base value as `V?` rather than `F?` so a nullable field type cannot conflate "no
  baseline entity" with "baseline field was null".

## 6. Semantics that hold for every policy

1. **Purity and re-execution.** Policies and every caller-supplied lambda (`writtenAt`, `merge`,
   `get`, `set`, `combine`) are pure and deterministic. The engine may re-invoke the merge for
   the same conflict after a crash or rolled-back retry transaction (§2). Policies hold no state
   across invocations — which is also why retry-counting policies are out of scope (§3).
2. **Round evolution.** On round *n* the merge receives: `base` = the capture frozen when round
   *n*'s generation was prepared (round 1: the initial ordered capture; round *n* > 1: the
   recapture taken when round *n − 1* resolved to `Retry`), `mine` = round *n − 1*'s resolution
   value (round 1: the projector's outcome), `theirs` = the fresh post-barrier recapture.
3. **Bound interplay.** Any `Retry`-producing policy is subject to the engine's
   unchanged-conflict park after three consecutive conflicted generations carrying an identical
   server timestamp/etag pair — including servers that always signal `serverMeta = null`, whose
   receipts compare identical to each other. A server whose conflict metadata changes every time
   never trips the bound (§5.2). `ServerWins`-producing cells never re-push, so they never
   contribute to it.
4. **`ServerWins` terminally skips declared effects.** Retiring through server-wins marks the
   intent's pending invalidation effects `SKIPPED` (`MutationEffectSkipped`) — engine semantics,
   identical to the no-merge default. Apps that key downstream invalidation on a mutation's
   declared stale-sets must treat a server-wins outcome as "effects did not run".
5. **No value-equality shortcuts** (§5.4) — an affirmative `Retry` is a `Retry` even when the
   merged value equals `theirs`.
6. **Thrown policy ⇒ parked intent**, kind `CONFLICT`, detail `"merge-failed"`, message = the
   thrown exception's first line, control-stripped and truncated to the engine's 1024-UTF-8-byte
   bound. Policies are non-suspending and must never catch `CancellationException`; the pack's
   own code contains no `try`/`catch`.
7. **Returned `Retry` values must round-trip the registered value codec.** A value the codec
   rejects propagates out of the drain (the intent stays `REFRESH_REQUIRED`, retryable) rather
   than parking (§2). Policies that synthesize values (`threeWay`, `fields`, `combine`) inherit
   this obligation from the caller's lambdas.
8. **No mutation of inputs or outputs.** Inputs are engine-copied (§2); returned values are
   encoded into the durable attempt, and later decoded copies must be equivalent.

## 7. Observability and the logging ask

`MutationConflictObserved(mutationId, identity, occurredAtEpochMillis, generation, serverMeta)`
fires on every durable conflict receipt, and `MutationParked` / `MutationRetired` /
`MutationEffectSkipped` narrate the resolution outcome. These events are advisory and
best-effort — emitted after the corresponding durable transition, may drop under buffer
pressure, replay nothing to new collectors, and replay nothing after restart (the `MutationEvent`
KDoc owns those numbers). For durable audit, the journal inspection surfaces
(`pendingWrites()`, `deadLetters()`, and the journal storage rows) are the record.

That split is why the pack adds no `onConflict` callback: a side effect inside a merge would
re-fire on crash-replay re-execution (§6.1) and fire before the durable transition it claims to
report, making it strictly worse than the existing advisory events on both axes. The README
shows the `store.events` collection pattern instead.

## 8. Testing strategy

Four layers, all in the pack (the mutations module's conflict fixtures live in its unpublished
`commonTest` source set and cannot be reused; the pack builds its own fixture file modeled on
`mutations/src/commonTest/.../MutationsTestFixtures.kt` — key type, string codecs, a fake
`MutationServer` with scriptable push behavior):

1. **Unit matrices** against factory outputs as plain functions: every cell of every decision
   table in §5, tie-breaking, bias knobs, contested-field routing, registration-order and
   overlapping-lens behavior, empty-`fields` rejection, escaped-builder rejection, and
   throw-propagation from caller lambdas. No store, no coroutines.
2. **Integration through a real `mutationStore`** with the pack's fake server signaling
   conflicts via `throw StoreResults.exception(StoreResults.conflict(meta, message), cause)`:
   - retry persists generation `g + 1` with `base` = the recapture and the policy's merged
     `mine`, under a fresh idempotency key;
   - server-wins retires without a second push and skips effects;
   - client-wins parks via the unchanged bound on the third identically-stamped conflicted
     generation;
   - canned policies compose with a caller `precondition { }` in the same block;
   - a policy-returned value the codec rejects propagates out of `drain(key)` and leaves the
     intent `REFRESHING`.
   Observability note: generations, idempotency keys, and base/mine blobs are asserted through
   the public journal-storage transaction API (`attempts(clientId)` etc.) and events —
   `PendingIntent` exposes no generation. The transaction API reads rows by client id and offers
   no client enumeration; the engine's default id is engine-internal (`"client-0"` today). The
   fixture therefore captures the id from a recorded `MutationPush.clientId` (every push carries
   it) and verifies a matching journal client row exists, so the suite fails loudly if the
   default moves. The fixture's fake server must also leave retirement unconfirmed
   (`confirmedThroughSequence = 0`) so retired rows survive pruning for assertion.
3. **Restart determinism**: reuse one `InMemoryMutationJournalStorage` instance across
   `store.close()` and a fresh `mutationStore` (the established restart pattern in
   `MutationConflictTest`); a store closed at `REFRESH_REQUIRED` re-runs the merge after reopen
   and lands the same outcome as an uninterrupted control run — same terminal phase, same
   generation rows, same pushed values — one case per policy family (serverWins, clientWins,
   lastWriteWins, threeWay).
4. **Compiled docs snippets**: `*DocsSnippet.kt` files in `commonTest` carrying
   `docs:snippet:mutations-conflicts-pack-*` markers — the `-pack-` segment avoids colliding
   with the existing `mutations-conflicts-policy` snippet id in the mutations module.

Test conventions inherited from the repository: `kotlin.test` assertions; the 25-second
`runTest` shadow-timeout shim (and its byte-identical Turbine-deadline comment variant if
Turbine is used); `TestWallClock` from `testing` for clock control. No Lincheck lane: the pack
is stateless pure functions, and the repository's only Lincheck suite covers
`InMemoryMutationJournalStorage` transactions, which the pack does not touch.

## 9. Compatibility and constraints

- **Zero core/mutations source diff** is a hard gate, mechanically checked in review by
  `git diff --stat` against `core/` and `mutations/`.
- The pack's sources must pass the existing extension gates in `store6.yml`: no
  `InternalStoreApi` or `store6.core.internal` reference; no banned concurrency primitive
  (`runBlocking`, `GlobalScope`, `atomicfu`, `Channel`, `actor`) — satisfied by pure functions.
- `explicitApi()` strict (convention plugin); BCV JVM/Android `.api` + `.klib.api` dumps
  committed; klib publication matrix green for all 12 targets.
- Experimental tier means the pack may change shape in any release; SemVer guarantees are not
  claimed (STABILITY.md §2).
- Source comments and KDoc in the pack follow AGENTS.md: no issue-tracker IDs or internal
  shorthand in Kotlin sources; the README and this design may cite upstream issues.

## 10. Repository integration (wiring inventory)

Files outside the new module that change:

| File | Change |
|---|---|
| `settings.gradle` | `include ':mutations-conflicts'` |
| `.github/workflows/store6.yml` · `linux-build-test` | Add `:mutations-conflicts:build` to the mutation-support build step; add `mutations-conflicts` to the internal-access module list; add `mutations-conflicts/src/*Main` to the TD-8 `production_source_dirs`; add `:mutations-conflicts:jsNodeTest` to the JS canary |
| `.github/workflows/store6.yml` · `apple-tests` | Add `:mutations-conflicts:iosSimulatorArm64Test` and `:mutations-conflicts:macosArm64Test` |
| `.github/workflows/store6.yml` · `klib-publication-check` | Add `:mutations-conflicts:publishToMavenLocal`; add `mutations-conflicts` to `modules=(…)` |

Gates that need **no** file edit but bind the module anyway: `ci.yml` runs `./gradlew clean
build` over the whole settings graph, so inclusion in `settings.gradle` alone enrolls the module
there (including `apiCheck`). The `linux-build-test` census step counts `mutations/src` test
files only — it is unaffected by a new module and must not be edited.

New module files: `mutations-conflicts/build.gradle.kts` (convention plugin,
`api(projects.mutations)`, plus the `commonTest` dependencies from §4),
`mutations-conflicts/gradle.properties` (`VERSION_NAME=6.0.0-SNAPSHOT`, `POM_NAME=mutations-conflicts`,
`POM_ARTIFACT_ID=mutations-conflicts`), empty `src/androidMain/AndroidManifest.xml`, sources,
tests, committed `api/` dumps, `README.md`.

Deliberately untouched, consistent with `realtime`/`graphql` precedent: `CHANGELOG.md` (no Store 6
sections exist yet), `STABILITY.md` §3 artifact table (frozen "as of 6.0.0-alpha01"), `llms.txt`,
`.github/docs-sync-sources.txt`, the BOM (no module exists), `extension-probe`, Swift dump lanes.

## 11. Documentation deliverables

`mutations-conflicts/README.md` in the established extension shape (lede stating the promise and
the experimental tier with a STABILITY.md link → Install → per-policy sections with the decision
tables from §5 → semantics shared by every policy (§6) → observability pattern (§7)). The README
carries the two loud warnings verbatim: the LWW stamp-bumping precondition (§5.3) and the
unregistered-fields rule (§5.5), each with an anti-example. Code blocks mirror compiled docs
snippets. No dokka `Module.md` (only `core` and `mutations` carry one). All prose passes the
repository documentation-discipline three-pass review.

## 12. Alternatives considered and rejected

- **An `escalate(message)` policy** (park the conflict for manual resolution) — cut during
  adversarial review. Parking is engineered as a failure terminal, not a strategy: a parked
  sequence never re-enters the executable FIFO, there is no unpark/requeue API, and — decisive —
  the retirement checkpoint "never advances across parked or active work", so every escalated
  conflict would permanently pin the client's contiguous retirement prefix and with it the
  backend's idempotency-receipt cleanup. Routine parking also makes deliberate decisions
  indistinguishable from policy crashes (`CONFLICT` / `"merge-failed"`). The manual-resolution
  pattern that works today without new API: resolve with `serverWins()` (or the default), watch
  `MutationConflictObserved`, and let the app re-issue a fresh mutation with the user's chosen
  value.
- **Widening the merge door to carry `StoreMeta` / key / generation.** Requires a `mutations`
  diff against a reviewed surface; value-carried stamps plus post-barrier recapture cover the
  LWW need. If real adopters need receipt metadata in merges, that is a `mutations` proposal.
- **A named `MutationMergePolicy` fun interface instead of the door's function type.** The
  door's own vocabulary is a function type; a parallel nominal type adds an adapter seam and
  SAM-ambiguity risk inside `conflicts { }` for no capability.
- **Reflection- or serialization-driven generic field merge.** No kotlinx-serialization
  dependency in the Store6 conventions, `copy` is not reflectively reachable in common code, and
  explicit lenses are testable and total.
- **Policies inside the `mutations` artifact.** Rejected in favor of the satellite pattern (§4).
- **An `onConflict` observation hook.** Rejected (§7).
- **Value-equality `Retry`→`ServerWins` optimization.** Rejected (§5.4).
- **`Retry(Absent)` in the both-Absent cell to force effects.** Rejected: it spends a network
  round trip pushing a delete of an already-absent entity purely to schedule invalidation
  effects; §6.4 documents the skip instead.
- **Precondition selector helpers.** The engine default (captured metadata) is correct for
  metadata-capable backends; `precondition { null }` is already a one-liner; and an "etag-only"
  selector cannot drop `writtenAtEpochMillis` without inventing a value, because `StoreMeta`
  requires it. Nothing canned to add yet.

## 13. Cut lines and open questions

Ordered cut list if scope must shrink (roadmap principle 1 — cut scope, never cadence):

1. `fields` / `mergeFields` (§5.5) — the largest single work item; `threeWay` remains the
   canned three-way answer.
2. Builder extension sugar — factories alone remain usable as `merge(MutationMerges.clientWins())`.

Open questions for review:

1. Is `onBothChanged = THEIRS` the right contested-field default, or should contested fields
   with no combiner fail policy construction (forcing an explicit choice per field)?
2. Should the absent-cell biases on `lastWriteWins` lose their defaults (forcing every caller to
   spell delete behavior), at the cost of making the one-line call impossible?

Resolved during review: keep `MutationConflictBias.MINE`/`THEIRS` (they name the door's own
`mine`/`theirs` vocabulary; `CLIENT`/`SERVER` would misdescribe the recapture, and the enum KDoc
now defines the terms); keep the `MutationMergeFunction` typealias (transparent to the door's
type; costs only dump surface); `escalate` cut (§12).

## 14. Exit gates

The feature is done when all of the following hold, in CI on the PR:

1. `./gradlew :mutations-conflicts:build` green (includes `apiCheck`, JVM/Android/JS/Wasm/native
   compilation, and default test suites) with committed `api/` dumps.
2. `linux-build-test`, `apple-tests` (`iosSimulatorArm64Test`, `macosArm64Test`), the JS canary
   (`jsNodeTest`), and `klib-publication-check` green with the module wired in (§10).
3. Every §5 decision-table cell has a named unit test; every §8.2 integration behavior has a
   named test against a real `mutationStore`; the §8.3 restart test passes.
4. `git diff` shows no change under `core/` or `mutations/`.
5. README complete per §11 and snippet-backed; KDoc on every public declaration; documentation
   three-pass review recorded in the PR description.
6. Package internal-access and TD-8 grep gates pass with the module enrolled.
