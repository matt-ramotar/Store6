# Mutation reviewer cross-challenge: acknowledgement, realtime, and projection

Reviewed release snapshot `/private/tmp/store6-alpha-review-2026-09-05`, commit `b123c95a373f3629c23e797cb97e2bca18bb260a`. Paths are relative to that snapshot. No Gradle execution or new runtime reproduction. This is a source challenge of the adapter review and the older C-M7/C-M8 candidates.

## X-1 · P1 · A two-call adoption can attach one writer's ETag to another writer's value

**Verdict: the adapter review's candidate survives source refutation. Source-proven legal interleaving. No current execution claim.**

Primary ownership: `core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/internal/KeyEngine.kt:1622-1644`. Callers: `realtime/src/commonMain/kotlin/org/mobilenativefoundation/store6/realtime/RealtimeBinding.kt:72-76` and `mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:3031-3038`.

Exact legal schedule:

1. Fetch F starts and holds a response containing `v1`, ETag `e1`.
2. An adopting realtime Upsert applies `v2`. Hold its SourceOfTruth write before it returns so it owns the key's write lock.
3. Release F's network response while that write is held. F reaches `commitFetch` and waits for the same write lock.
4. Release Upsert's write. Its `apply` commits `v2` and releases the lock. The waiting fetch commits `v1/e1` before Upsert's separate `confirmFresh(e2)` acquires the lock.
5. `confirmFresh` reads the current residence, keeps its value `v1`, substitutes metadata `e2`, advances its stale epoch to the current epoch, and persists success with `e2`.

The final local pair is `v1/e2`. If the backend's current `e2` describes `v2`, a later conditional fetch can legitimately return NotModified for `e2`, preserving the wrong `v1` locally. A raw stale fetch value winning a later commit is separately documented policy; the mismatched value/ETag pair is the defect.

Why core attribution does not refute it: `core/.../Transitions.kt:145-169` permits F's CommitFetch while the original fetch slot is active and its clear epoch is unchanged. `ApplyWrite` at `199-212` installs synthetic attribution but leaves that fetch slot and clear epoch intact. `KeyEngine.applyWrite` and `commitFetch` each own the write lock for their own operation only. `RealStoreRuntime.kt:26-37` calls `applyWrite` and `confirmFresh` separately. The successful-write attribution machinery decides which committed value is current; `confirmFresh` accepts no attribution token and deliberately copies whichever value is current.

Counter-evidence: realtime KDoc explicitly says an in-flight fetch is not cancelled and that a fetch committed after apply is later source-of-truth authority. This supports the schedule but does not authorize applying the Upsert's ETag to that fetch. `RealtimeAdoptionTest` covers Upsert's ETag reaching a subsequent conditional fetch (`79-95`) and its apply/confirm order (`101-114`); neither inserts a competing writer between those calls. Per-binding serialization only protects callers sharing that binding, not the core fetch path or another binding.

Narrow direction: bind metadata confirmation to the exact applied writer token, or add a core operation that commits the value and its metadata under one per-key commit lease. A guarded confirmation must skip when another writer replaced the applied value. Do not change the accepted policy that a later fetch commit may be authority. A combined value/metadata operation does not require making the mutation journal and source of truth one transaction.

Deterministic acceptance: drive the schedule above with SourceOfTruth and fetch barriers; assert `v1/e1` if F is the later writer, or `v2/e2` if Upsert is the later writer, never `v1/e2`. Make the next conditional fetch return NotModified only for the supplied ETag and verify that no mismatched value can be entrenched. Run the same competing-writer schedule through a mutation acknowledgement because it calls the same two methods.

## X-2 · P2 · A delayed mutation acknowledgement clears invalidations received after its push began

**Verdict: prior C-M7 survives as a distinct freshness problem. Source-proven epoch overwrite; no current runtime reproduction.**

Ownership: `mutations/.../MutationEngine.kt:1968-1983` (send) and `3037-3038` (adoption), plus core `KeyEngine.kt:1631-1643` (freshness confirmation).

Schedule: start a mutation push at freshness epoch E. The server accepts value v1, but hold its response. Another actor changes the server to v2 and the host receives a realtime Changed event. Invalidate the local key, advancing its stale epoch and durable stale state. Use no active collector so this does not race an automatic fetch. Deliver the old acknowledgement for v1. Adoption writes v1, and unguarded `confirmFresh` stamps the now-current epoch and calls `recordSuccess`, clearing the newer invalidation. A later CachedOrFetch can return v1 while the server is v2.

This is not X-1: it does not need a competing local value write, and binding confirmation only to the local apply token would not solve it. The freshness evidence predates the remote response; the acknowledgement cannot disprove a change signalled after that evidence began.

Counter-evidence: `MutationAckPathTest.kt:83-117` requires an acknowledgement to clear durable staleness that existed before mutate/drain. That requirement should survive. It does not cover invalidation after push send. The standalone StoreWriteHandle `confirmFresh` contract intentionally clears current staleness, so a fix belongs in the caller's use of richer confirmation evidence, not a blanket removal of freshness confirmation. No reviewed alpha two-step-ack ruling waives lost later invalidations.

Narrow direction: capture freshness evidence no later than the push send boundary and confirm only through that evidence. A token captured when staging or adopting the acknowledgement is too late. Token lifetime across retry, aliases, key eviction, and restart requires an explicit design choice. If restart cannot reconstruct safe evidence, conservative stale adoption is safer than inventing a token that clears unseen invalidations. Preserve the accepted two-step journal/source-of-truth boundary.

Acceptance: retain the pre-drain-staleness test, then add key, namespace, and global invalidation between send and response; each must remain stale after adoption until a successful fetch based on later authority. Add the alias target and resumed ACKED cases once their evidence lifetime is specified.

## X-3 · P2 · The acknowledged echo can be optimistically transformed a second time

**Verdict: prior C-M8 remains unresolved and must not be silently waived. Source-proven double projection calculation; exact emitted-frame schedule needs current execution.**

Ownership: `mutations/.../MutationEngine.kt:982-997`, `1286-1313`, `3037-3051`, and normal retirement `3510-3540`.

Schedule: confirmed counter is 0, one pure mutation increments its base to 1, and the server acknowledges 1. `resumeDurableAck` calls core apply with 1 while the journal still contains the increment. Pause its subsequent confirmation/effects/retirement work, for example at the retained bookkeeper's success barrier. An active projection writer receives the committed base 1. `projectAll` still iterates that journal entry because membership remains until retirement and `replayableEntries` filters only tombstone watermarks. The same pure increment therefore projects 2 even though only one increment was requested and acknowledged.

The false value can last while post-ack work is suspended or failing, rather than merely for a CPU instruction. The engine still needs the intent for durable resumption, so deleting its durable row early is not a remedy.

Counter-evidence: `MutationAckPathTest.ack_neverReemitsOldBase` (`164-207`) tests a replacement-style mutation; reapplying that projection does not reveal a numerical double increment. It correctly pins that ACKED alone is too early to remove an optimistic projection: the authoritative value has not necessarily committed yet. README's accepted two-step posture is about crash replay/idempotent endpoints. The quickstart warns that existing collectors need not converge across the boundary, but this is not an explicit user ruling that falsely doubled values are acceptable. No exact waiver was found in the reviewed public contract or provided user instruction.

Narrow direction: associate optimistic membership with the source adoption commit, keeping durable ACKED/EFFECTS_PENDING rows for recovery. Exclude the already-adopted prefix only for source observations that prove that adoption committed. Do not simply filter phase ACKED. A process-local flag written after `apply` returns can itself miss an earlier source observation, so core writer attribution or a coordinated publication fence may be required. This is a design/implementation question distinct from transactional journal acknowledgement.

Acceptance: use a non-idempotent but pure increment or append mutator, hold confirmation and effect completion after source adoption, and assert no frame projects the acknowledged intent twice. The old-base regression test must remain green. Add queued suffixes so the source echo excludes exactly the acknowledged prefix while still showing later optimistic edits. Reopen ACKED and EFFECTS_PENDING journals to preserve conservative durable recovery.

## Prior candidate reconciliation

| Prior candidate | Current status | Required next action |
| --- | --- | --- |
| C-M5 parked high-water pin | Accepted runtime rule. Missing public operational disclosure still open. | Documentation owner should name that a parked early sequence pins retirement checkpoints/pruning and that no discard/requeue door exists. Do not invent a discard API in a wording fix. |
| C-M6 retained completion caches | Source-verified P2 in mutation report M-2. | Add deterministic cache-retention test and completion cleanup with event/alias ownership safeguards. |
| C-M7 acknowledgement clears later invalidation | Source-verified P2 X-2. | Specify send-time evidence lifetime, then add the delayed-response invalidation tests before implementing. |
| C-M8 acknowledged echo reprojected | Source-verified calculation, runtime publication unexecuted, P2 X-3. No explicit waiver established. | Reproduce the increment schedule and implement adoption-aware membership or obtain a concrete user-visible disposition. |

The initial mutation report's statement treating C-M8 as accepted was an inference from the acknowledgement/quickstart wording and is superseded by this explicit unresolved disposition. Experimental tier alone waives none of these claims.
