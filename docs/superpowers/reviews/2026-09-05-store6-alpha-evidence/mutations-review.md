# Store6 mutation review, 2026-09-05

Source authority: release branch `store6`, `b123c95a373f3629c23e797cb97e2bca18bb260a`, read from `/private/tmp/store6-alpha-review-2026-09-05`. Paths below are relative to that exact snapshot. The earlier local-main inspection was discarded when the orchestrator established the release branch. No source, tests, build files, or existing review documents were edited. No Gradle task or runtime reproducer was executed by this reviewer.

## Findings

### M-1 · P2 · A newly accepted intent with an unencodable projected value repeatedly aborts global drain

**Evidence: source-proven control flow. Not executed.**

Primary location: `mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:2078-2088` and `2176-2180`. Related paths: the same file `2139-2154` and `763-778`; public contract at `mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:255-266`.

Trigger:

1. Configure a deterministic value codec whose `encode` throws for a particular value, such as a polymorphic value whose serializer is unavailable, while ordinary values encode successfully. Its argument codec still works.
2. Accept an upsert intent at identity A that projects that value. Enqueue a valid intent behind it and another valid intent in namespace B.
3. Call global `drain()` with A first in the captured journal order.

`prepareDurableAttempt` calls `buildDurableAttempt` before the READY transaction, and the latter invokes the consumer value codec without containment. The exception escapes the global loop, which catches only `RetryablePostAckFailure`. The intent stays UNPREPARED, produces no CODEC dead letter, and is selected again on subsequent global drains. A valid suffix and later namespaces make no progress through this trigger. Restart does not fix it: there is no prepared value blob for hydration to classify, so it rebuilds the same unprepared intent. With a precondition selector installed, `copiedPresence` can fail even earlier while constructing the candidate outside the selector's try block.

This contradicts the facade's terminal pre-transport codec-failure parking contract. It is a fault-containment defect, not a claim that malformed application values should become valid. The codec purity/determinism contract does not imply that serialization is total; `MutationCodec.decode` explicitly allows illegal bytes/version failures.

Counter-evidence: `MutationDrainParkingTest.kt:78-111` seeds a READY row with value codec version 99 and proves hydration-based parking. The adjacent argument-version tests also begin with stored bad rows. They do not exercise encoding a newly projected value before the first attempt exists. The release branch already catches and parks a throwing `stales` function at engine `2059-2075`, demonstrating the corresponding preparation containment pattern.

Narrow fix: contain initial value serialization and precondition-candidate copy failures at the UNPREPARED preparation boundary, preserve CancellationException, park non-cancellation failures as CODEC using the existing durable park handoff, and continue the captured pass. Do not broadly catch storage or programming failures as CODEC. Audit the equivalent copy boundaries in conflict retry separately because REFRESH_REQUIRED has different parking rules.

Deterministic acceptance: enqueue a bad upsert, a valid same-key suffix, and a valid different-namespace intent; one global drain must park exactly the bad intent with normalized CODEC evidence, perform zero pushes for it, and complete the eligible valid work. Repeat with a precondition selector installed. Reopen the journal and assert the same dead letter without re-encoding the parked value. A codec-thrown CancellationException must still propagate without parking or inventing completed attempts.

### M-2 · P2 · Successful mutation payloads remain strongly retained after checkpoint pruning

**Evidence: source-proven retention. Heap size and latency impact not measured.**

Primary location: `mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:872-880`. Ownership and writes: same file `178-190`, `2120-2124`, `2938-2942`, `3721-3731`; owner enumeration `1444-1453`.

Trigger: keep one MutationStore alive, repeatedly upsert the same key with distinct substantial values, acknowledge each push, and confirm every retirement prefix. This requires no growing key cardinality, no parked intents, no aliases, and no transport failures.

Storage prune deletes confirmed history, but the engine never removes the corresponding entries from `durableAttempts`, `durableAcks`, `durableExecutions`, `durableEffectRows`, or `effectSnapshots`. Successful retirement removes only `phases` and `completedAttempts` and rewrites `durableExecutions` to RETIRED. Each retained attempt owns base/mine ByteArrays, each acknowledgement owns another authoritative ByteArray, and the maps live as long as the store. `durableNamespaceOwners` also rebuilds an index over all retained attempt rows and scans all retained executions on later drains.

Consequence: process memory grows with completed mutation history even when the database journal is empty and the application mutates one key. Repeated map copy/index operations also grow with that history. The README's warning about zero-configuration in-memory persistence and unbounded key cardinality does not describe this engine-owned retention, and installing SQLDelight does not remove it.

Counter-evidence: the pruning and retirement tests inspect durable rows and retirement prefixes, which correctly disappear or advance. They do not assert process-cache removal. Reopening creates a new engine from the pruned journal and releases the problem when the old engine is collected, but keeping a live store is the ordinary usage that exposes it. The prior local review's C-M6 independently identified this candidate; this report rechecked the live release source and all removal sites before retaining it.

Narrow fix: remove completed per-mutation caches after all retirement/effect events needing them have been built or emitted, using a single completion helper for normal, alias, and ServerWins retirement. Preserve durable aliases, active tombstones, unconfirmed retired sequence accounting, parked evidence, and process-local acknowledgement target pins until their own legal release points. Alternatively synchronize cache pruning to confirmed storage pruning, while guarding concurrent event publication and continuation.

Deterministic acceptance: execute a bounded number of large distinct upserts on one key with successful checkpoints, then assert empty pending/dead-letter lists, no eligible durable history, and zero retained completed mutation entries/blob references in an internal test snapshot. Include alias and ServerWins completion plus a deliberately held ACKED owner to prove active replay data survives. Assert lifecycle event payloads remain intact. This can prove retention without a flaky GC or heap-size threshold.

### M-3 · P3 · Concurrent Meeseeks scheduling can leave an untracked pending activation

**Evidence: source-proven interleaving under a scheduler/manager that accepts concurrent calls. Not executed against Meeseeks. Outside the alpha01 allowlist, so this is not an alpha01 blocker.**

Primary location: `mutations-drain-meeseeks/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/drain/meeseeks/MeeseeksDrainScheduler.kt:54-88`, `102-118`, `129-136`; counter-test `mutations-drain-meeseeks/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/drain/meeseeks/MeeseeksDrainSchedulerUnitTest.kt:150-163`.

Trigger: two schedule calls for one store name both read an empty `tracked` map and both complete their recovery scan before either manager schedule call publishes a task. Each creates a Pending task. Both then call `putTracked`, so only the last ID remains tracked. `cancel(storeName)` consults that single ID and leaves the other Pending task runnable. The coordinator can create this overlap because `runPass` persists safety activations before taking its per-registration pass mutex.

Counter-evidence: `concurrentSchedulesConverge` launches two Default-dispatcher calls but asserts only that the tracked map has one name and that there are at most two schedule calls. That passes the orphan-task schedule above. Atomic publication of a single map entry protects the map, not the external schedule/check/replace operation. The underlying manager could independently coalesce tasks, but the adapter creates requests without a per-store unique identity and the local fake accepts distinct tasks. Therefore runtime amplification is conditional on manager behavior; source does not establish a compensating uniqueness guarantee.

Narrow fix: serialize the manager read/scan/schedule/reschedule/cancel operations for each registration name, or add a manager-supported stable unique scheduling key and prove its replacement semantics. Keep running tasks intact, as required by the adapter contract.

Deterministic acceptance: use a manager fixture with barriers that lets both calls finish an empty recovery scan before proceeding. After both return, assert one Pending task, not merely one tracked-map entry. Cancel the name and assert zero Pending tasks. Add an overlap with cancel and verify no untracked task survives.

## Coverage inventory

| Surface | Inspection performed | Limits |
| --- | --- | --- |
| `mutations` public API | MutationStore, factory/builder, MutationProtocol, journal storage contract, registry signatures, event/inspection routing | No ABI regeneration or consumer compilation |
| Durable mutation engine | Hydration, enqueue, namespace and identity scheduling, prepare/send, transport failures, conflict receipts/retries, parking, ack receipt/adoption, effect routing, normal/alias/ServerWins retirement, checkpoint/prune, projection and defensive-copy helpers | Legacy codec-less engine path was sampled. No exhaustive linearizability proof |
| Journal and alias cache | StorageBackedMutationJournal append/rehome/hydrate/retirement publication, alias admission/routing | Alias-cycle and lower-sequence causal validation were source-traced but not exhaustively rederived |
| `mutations-sqldelight` | Transaction boundary, record read/write map, execution transitions, ack/effect persistence, prune, schema v2 and migration/quiescence gate | Driver-specific runtime behavior and all final-state validator branches not executed |
| `mutations-testing` | Storage contract API inventory, kill-point wrapper/classification, purity-kit surface; related contract-test ownership | Did not execute the contract suite or line-review every fixture/negative cell |
| Mutation tests | Targeted inspection of parking, pruning, retirement, restart, ack, conflict, namespace scheduling and cancellation/replay test owners | Tests are counter-evidence from source only; no passing-run claim |
| `mutations-quickstart` | Entire Main.kt and its factory, codec, server, and stream usage | Example was not run. Its toy server does not establish a production concurrency or multi-client backend contract |
| `mutations-conflicts` | Merge vocabulary, complete terminal/LWW/three-way factory paths and field builder; fields/merge integration test ownership | No execution or exhaustive generated input matrix |
| `mutations-drain` | Complete coordinator, registry, in-process scheduler, follow-up delay derivation; lifecycle/watch/activation test owners | Real-thread unregister/reschedule races remain a hypothesis requiring a dedicated schedule |
| `mutations-drain-meeseeks` | Complete scheduler and worker, request/payload surface and unit scheduling/recovery tests | Upstream platform manager semantics and excluded integration suites unverified |

## Accepted limits, refutations, and uncertainty

- No P0 or P1 established in this bounded review. This is not a release approval.
- The README explicitly accepts the alpha two-step durable acknowledgement posture and idempotent replay requirement. No finding here asks to make it atomic. The quickstart's warning that an already-active collector need not converge is not an explicit waiver of double projection. Prior C-M8 remains unresolved, with a source-verified calculation and required current runtime reproduction in the companion cross-review X-3.
- `clientId = "client-0"` is a real collision risk outside a suitably scoped backend, but canonical private decision `docs/v6/decisions/023-mutations-drain-conflicts.md:64-65` explicitly retains that internal installation posture. It is not presented as an unapproved implementation defect here.
- Canonical decision Q-8 at that same document `62` accepts PARKED work pinning the retirement high-water. The absence of a discard/requeue API is therefore not relitigated as a runtime bug. Public disclosure of the operational consequence remains worth the documentation owner's review.
- A throwing `stales` function is parked in the release branch. The stale-main hypothesis was rejected.
- Cross-namespace alias acknowledgements are parked by this release branch. The older private no-park posture is superseded by current source; no request to restore it is made.
- Post-ack missing-codec hydration emits a recurring blocked-adoption event and keeps durable ACKED ownership. Old decoders remain a documented consumer obligation. The new M-1 finding is specifically the pre-attempt live serialization boundary.
- The drain coordinator observes checkpoint failure through a lossy advisory event flow and a best-effort yield. A missed event can allow it to cancel its safety activation despite outstanding retirement-checkpoint work. This is the existing non-alpha scheduler risk, not independently executed here. Periodic explicit reconciliation reduces that risk but does not prove wakeup durability.
- Source establishes possible cache growth and exact missing containment. It does not establish a production OOM threshold, performance regression magnitude, platform-specific failure rate, or an actual lost user write.
- The companion `/private/tmp/store6-alpha-2026-09-05-cross-review.md` carries the independent realtime/mutation acknowledgement challenge: P1 cross-writer value/ETag mismatch, P2 C-M7 later-invalidation loss, and P2 C-M8 adopted-echo double projection. It also records every prior mutation candidate's current status and next action. Its cross-boundary findings are additional to this initial bounded module report.

## Verification record

Read-only inventory and targeted source/test searches completed. Exact path and line anchors were checked on the release snapshot. Findings received separate accuracy, warrant, and reader-utility passes. No runtime tests executed. No build cache, daemon, lock, sandbox, or network probing was attempted.

Memory was used only for historical workflow orientation (`MEMORY.md:390-430`, rollout `019fbe5f-b483-7271-8199-50848ea30d1b`). Current source and explicitly named decisions govern the findings, not remembered green runs.
