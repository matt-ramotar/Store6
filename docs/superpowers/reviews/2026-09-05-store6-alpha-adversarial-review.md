# Store6 initial alpha adversarial review

Reviewed 2026-09-05. **Recommendation: hold the alpha tag while the four P1 findings below remain open.** The hosted gates pass, but publication is not gated on the complete matrix, the latest scheduled stress result is cached, and source review establishes two persistence/freshness races in shipping adapters. The P2 items need correction or an explicit, accurately documented disposition before their affected capability ships. No P0 was established. This review is not release authorization.

## Source and evidence boundary

The reviewed release branch is [`matt-ramotar/Store6:store6` at `b123c95a373f3629c23e797cb97e2bca18bb260a`](https://github.com/matt-ramotar/Store6/tree/b123c95a373f3629c23e797cb97e2bca18bb260a). GitHub repository metadata and a live branch lookup confirmed the branch and SHA during this review. An isolated export is at `/private/tmp/store6-alpha-review-2026-09-05`. All **792 tracked file blobs** were compared with the Git tree: **zero mismatches**. The settings file declares **41 Gradle projects**. These counts establish source identity and inventory, not exhaustive line coverage.

The user workspace remains on stale `main` at `5a8c956bc1dbd6ad838ea9da3b34c7d76c703a71`. Initial exploration and one JVM test invocation occurred there before the release-branch discrepancy was found. That test passed, but is excluded from release evidence. No finding below relies on an unverified stale-main behavior. The earlier 2026-08-23 review and revision plan were preserved. Only the Codecov configuration, README badge, and Codecov plan differ between that review's release SHA `9d68f4db` and this source. Historical findings remain candidates until rechecked; historical test claims are not fresh execution.

Every source path below is relative to the isolated **release snapshot**, not the stale root checkout. Exact inventories, specialist notes, hosted logs, and source hashes are in [the evidence directory](2026-09-05-store6-alpha-evidence/README.md).

Evidence classes used here:

- **Executed observation:** a command or archived hosted log inspected during this review.
- **Source-proven:** an explicit path, interleaving, or configuration contradiction demonstrated from current source; a proposed regression has not been run.
- **Open hypothesis/contract decision:** a plausible risk or ambiguous requirement whose disposition is not established. These are not counted as confirmed defects.

P1 means correct before releasing the affected alpha capability. P2 means a substantive defect, contract inconsistency, or evidence gap requiring a fix or explicit disposition. Deferred modules do not expand the alpha floor merely because this audit inspected them.

## Dynamic review method and coverage

One orchestrator and three workers ran in parallel. Initial assignments were core/testing, mutation engine/storage, and adapters. The orchestrator inspected release engineering, distribution, documentation, file/Ktor integrations, telemetry, and the module census. After the initial findings, workers received new bounded assignments: Swift exception bridging, realtime/mutation freshness cross-review, and independent challenge of the release findings. This second wave was selected from emerging risks rather than a fixed checklist. Final integration challenges the evidence class, accepted-limit boundary, and ownership of each revision task.

| Area | Review performed | Explicit limit |
| --- | --- | --- |
| `core`, `testing`, `extension-probe` | Public contracts; request lifecycle; fetch/reader/clear/confirmation paths; registry, delivery, maintenance, fakes and contract-kit inventory; relevant tests | The 5,183-line engine's full projection-authorization state space was not re-proven. Platform clocks and every helper were not line-reviewed. |
| `mutations`, `mutations-testing`, `mutations-sqldelight`, `mutations-quickstart` | Preparation, push, codec failures, hydration/restart, ack adoption, conflicts, aliases, effects, retirement/checkpoints, schema and tests | No linearizability proof or executed crash matrix. Legacy codec-less path sampled. |
| `sqldelight`, `room`, `compose`, `paging-androidx`, `graphql`, `realtime` | Common production paths and relevant cancellation, identity, concurrency, lifecycle, sample and test coverage | Platform leaf implementations and all sample interactions were not exhaustively reviewed or run. |
| `file`, `ktor` | File naming/recovery/mutation boundaries; HTTP validators, status mapping and cancellation; tests inspected selectively | No filesystem fault injection or transport integration executed. Both absent from alpha Maven allowlist. |
| `mutations-conflicts`, `mutations-drain`, `mutations-drain-meeseeks` | Merge factories, coordinator/registry/scheduler, recovery and cancellation | Real Meeseeks manager semantics and excluded integration suites unverified. |
| `devtools`, `devtools-inspector`, `opentelemetry` | Retention/identity contracts, callback containment, event projection, metrics and version wiring | No live Compose UI, performance measurement, or exporter exercise. |
| `store6-swift`, four `swift-dumps-*` projects, root Swift package | Swift API and Kotlin bridge errors, committed header evidence, package boundary, hosted job results | No local Swift runtime execution or new generated-dump comparison. |
| `bom`, `coverage`, `tooling`, workflows and publication properties | Allowlist/BOM agreement, target declarations, version/signing gates, CI provenance, failure reporting | No Maven upload, release dispatch, signing exercise, dependency vulnerability or license audit. |
| Remaining quickstarts, demos, benchmarks and adapter samples | Settings/build census and hosted step evidence; selected source examples | No visual acceptance, Android device run, fresh benchmark, or complete sample source audit. |
| README, STABILITY, ROADMAP, RELEASING, CHANGELOG, local guides, `llms.txt`, Store plugin | Release/contract consistency, consumer prerequisites and selected source references | External docs site and every plugin snippet/route were not audited. No site deployment claim. |

## Verification results

| Evidence | Result and meaning |
| --- | --- |
| [Store6 push run 33262505687](https://github.com/matt-ramotar/Store6/actions/runs/33262505687) | Original exact-SHA run succeeded: Linux, Apple, Swift dumps, Swift facade, KLIB publication check and native stress jobs. Job/step metadata archived. This review did not establish fresh task execution for every green step. |
| [CI push run 33262505690](https://github.com/matt-ramotar/Store6/actions/runs/33262505690) | Exact-SHA build-and-test succeeded; publishing was skipped on the fork push. No released-artifact proof. |
| [Scheduled run 33956908960](https://github.com/matt-ramotar/Store6/actions/runs/33956908960) | Exact-SHA success on 2026-09-05, but `:mutations:jvmTest FROM-CACHE`. The run's `expected=34 executed=34` is a report-file census, not execution. F02. |
| Local release `./gradlew :core:jvmTest --stacktrace` | Wrapper failed on its ZIP lock with `Operation not permitted` before Gradle task startup. **Zero release tests executed locally.** Log retained. No cache/daemon/path workaround or rerun was attempted. |
| Snapshot integrity and document checks | 792 Git blobs matched; final artifact paths, finding/task coverage and source references checked. These checks do not substitute for runtime regression tests. |

## Findings affecting the current alpha line

### F01 · P1 · Maven release can precede failure of the complete validation matrix

**Source-proven.** `.github/workflows/ci.yml:93-100,140-159` makes publication depend only on `build-and-test`. `.github/workflows/store6.yml:3-9` independently starts the broader matrix on the same tag. A valid upstream tag can release artifacts while Apple tests, Swift dumps, KLIB checks, or native stress remain running or fail. `RELEASING.md:64-66` says the matrix also runs, but provides no dependency or explicit exact-SHA prepublication wait.

The repository guard, snapshot refusal, and tag/version match are useful and correct. They do not close this gate. No failed publication was triggered or observed; the fork cannot publish through this guarded job. Make complete same-SHA validation for the shipping roster a required predecessor to upstream publication, with an explicit policy for the full-suite evidence. Do not automatically require deferred capabilities to ship. Test missing, failed, pending, cancelled, and wrong-SHA evidence using a harmless publication stub; every case must refuse release. A green result from another revision must not satisfy the gate. Owner: L1.

### F02 · P1 · The scheduled full mutations suite can pass without executing tests

**Executed observation plus source-proven cause.** `.github/workflows/store6-full-jvm.yml:34-44` permits cached `jvmTest` output and counts XML files. The archived 2026-09-05 log reports `:mutations:jvmTest FROM-CACHE` at line 363, `BUILD SUCCESSFUL in 25s` at line 373, and `expected=34 executed=34` at line 392.

This result may reuse legitimate historical passing tests; it proves no fresh stress execution on that run. The daily lane therefore cannot provide the scheduled concurrency evidence its name and census imply. Force the stress test task to execute, verify the actual task outcome and current-run test identifiers, and retain failure evidence. Two successive unchanged-source executions must both run test bodies. Compilation may remain cached. Owner: L1.

### F03 · P1 · SQLDelight can commit and then throw cancellation

**Source-proven; regression not executed here.** `sqldelight/src/commonMain/kotlin/org/mobilenativefoundation/store6/sqldelight/internal/DriverAccess.kt:22-51` wraps an admitted synchronous transaction in a cancellable `withContext(HeldDriverAccess(...))`. The row callback can change the database, cancel the captured caller Job without throwing, then return. The transaction commits and notifies readers; coroutine completion can subsequently throw cancellation. `SqlDelightSourceOfTruth.kt:101-148` uses this shared boundary for mutations.

That violates `core/.../seam/SourceOfTruth.kt:21-31`: a throwing mutation must not have applied. The core can then classify cancellation and skip committed bookkeeping. The no-suspension callback rule does not remove completion cancellation. Existing `SqlDelightAtomicityTest.kt:180-196` throws *inside* the transaction and proves rollback of a different path. The reviewer checked the pinned [coroutines 1.8.1 completion implementation](https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.8.1/kotlinx-coroutines-core/common/src/intrinsics/Undispatched.kt).

Use one outer `NonCancellable + HeldDriverAccess` committing frame after cancellable admission. An inner shield returning through the old outer frame is insufficient. Preserve explicit callback-exception rollback and cancellation while queued. Test write, deletes, maintenance, and transaction commit callbacks: never observe a thrown operation whose row change committed. Owner: L2.

### F04 · P1 · Realtime can attach a pushed ETag to an unrelated fetched value

**Source-proven, independently cross-reviewed; not executed.** `realtime/.../RealtimeBinding.kt:72-76` performs `apply(v2)` and `confirmFresh(e2)` separately. Core `Transitions.kt:199-211` preserves an existing fetch; its commit is still accepted at `145-168` when the clear epoch is unchanged. The old fetch can commit `v1/e1` between those two calls. `KeyEngine.kt:1622-1645` then stamps current value `v1` with pushed tag `e2`.

The binding mutex protects other calls through that binding, not engine fetches or other bindings. A later conditional request using `e2` can return NotModified and validate the wrong content. The documented rule that a later fetch may win does not authorize mismatched content and metadata. Existing realtime tests check mocked pair serialization or uncontended ETag forwarding.

Bind confirmation to the exact adoption identity, or apply value and metadata under one engine operation. Preserve the intended later-writer ordering; do not infer that all fetches should be cancelled. A real-engine test must force a fetch between the two calls, then prove a coherent value/tag pair through conditional revalidation. Include two bindings and intervening invalidation. Public seam implications make this serial core/integration work. Owners: L0 decision, L3 implementation, L4 caller integration.

### F05 · P2 · Initial mutation encoding failure can repeatedly abort global drain

**Source-proven.** `mutations/.../MutationEngine.kt:2078-2088,2176-2180` calls the consumer value codec before the READY transaction without the terminal codec-failure containment promised by `MutationStore.kt:255-266`. Global drain (`763-778`) does not catch that failure. An accepted but unencodable intent remains UNPREPARED and repeatedly prevents later eligible identities from draining. The precondition-candidate copying path (`2139-2154`) has the related boundary.

Hydration parking tests begin with an already stored bad READY row; they do not test initial encode. The release's throwing-`stales` fix also does not cover it. Contain serialization failures at their actual codec boundaries, preserve cancellation and distinguish storage errors. A single pass with a bad intent, a same-key suffix, and a valid second namespace must park only the bad intent, never push it, and progress eligible work. Repeat after restart and with a selector. Owner: L5.

### F06 · P2 · Completed mutation blobs remain retained after durable history is pruned

**Source-proven; magnitude unmeasured.** `mutations/.../MutationEngine.kt:178-190,872-880,2120-2124,2938-2942,3721-3731` retains completed entries in `durableAttempts`, `durableAcks`, `durableExecutions`, `durableEffectRows`, and `effectSnapshots`. Storage prune removes durable history, but these engine maps retain attempt/ack payloads for the Store lifetime. Owner enumeration at `1444-1453` also scans historical entries.

Repeated successful writes to **one key** are sufficient. This differs from the explicitly accepted per-distinct-key retention of the default read persistence; SQLDelight does not fix it. Existing tests check disk rows/checkpoints. Add a shared completed-entry cleanup boundary after required events and continuations, preserving pending ACKED work, parked evidence, aliases and legitimate pins. Verify internal entry/blob ownership reaches zero after confirmed pruning, while held active entries and emitted lifecycle payloads survive. No flaky heap threshold is needed. Owner: L5.

### F07 · P2 · Store.close misses get requests suspended before a fetch ticket

**Source-proven.** [KeyEngine.get:4674-4684](https://github.com/matt-ramotar/Store6/blob/b123c95a373f3629c23e797cb97e2bca18bb260a/core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/internal/KeyEngine.kt#L4674-L4684) executes in caller context, checks closure at entry, then can suspend in [bookkeeping:2203-2214](https://github.com/matt-ramotar/Store6/blob/b123c95a373f3629c23e797cb97e2bca18bb260a/core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/internal/KeyEngine.kt#L2203-L2214), hydration or lock admission (`2151-2157`). Close cancels the Store job, not that caller request. A seeded LocalOnly read paused in a cancellable `Bookkeeper.status` gate survives close; releasing the gate can return its cached value after closure.

[Store.close:170-175](https://github.com/matt-ramotar/Store6/blob/b123c95a373f3629c23e797cb97e2bca18bb260a/core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/Store.kt#L170-L175) promises cancellation of value requests waiting on in-flight work. Existing close tests wait until fetching has started, when the engine-owned deferred provides cancellation; stream separately registers close handling. Bind the complete request to caller cancellation and Store closure without cancelling the caller's surrounding job or changing noncancellable commits. A closed status gate must remain closed while close cancels the request and runs cleanup. Retain exact-message and shared-fetch tests. Owner: L3.

### F08 · P2 · GraphQL numeric equality, hashing and key identity disagree

**Source-proven; platform regressions not executed.** `graphql/.../GraphQlValue.kt:56-62`, `Canonicalization.kt:24-25`, and `GraphQlOperationKey.kt:45-57` combine primitive Double equality, Double hashing, and rendered canonical text. Positive and negative zero compare equal but have different hashes and JVM canonical strings. NaN is admitted but breaks reflexive value equality. On JS, whole Float rendering can also collide with Int rendering despite distinct value types.

Existing tests check integer/string hash cases and Int/Float object inequality without canonical-key equality. Choose a consistent finite-number identity rule and apply it to equality, hashing and rendering. Test signed zero through variables, operation keys and hash sets, reject or explicitly handle nonfinite input, and test integer/float canonical identity on JVM and JS. This is an observable key contract decision, not a cosmetic serializer rewrite. Owners: L0, L6.

### F09 · P2 · The reusable contract kits omit failure and watermark obligations

**Source-proven coverage gap, not a claim that all adapters fail.** `testing/.../SourceOfTruthContractKit.kt:53-315` contains 15 success/liveness cases but no externally cancelled commit or fallible-mutation atomicity case. `BookkeeperContractKit.kt:34-145` has six cases and never invokes global watermark advancement, `forgetNamespace`, or `forgetAll`. The seam requires broader algebra; the SQL defect in F03 is a concrete example not detected by the advertised kit.

Add fault-injection/transactional extensions where appropriate and cases for never-seen keys under global marks, shared monotone sequencing, and preservation of marks through forgetting. Keep existing local adapter tests. Test each kit consumer, with failure cases that actually fail the flawed implementation. Added public kit APIs require generated ABI review. Owner: L7, after L2 and any L0 status decision.

### F10 · P2 · Release roster and public guidance need one source-bound contract

**Source-proven contradictions and preparation gaps.** The current publish allowlist (`ci.yml:152`), BOM (`bom/build.gradle.kts:18-27`) and alpha table (`STABILITY.md:47-57`) agree on ten libraries plus BOM. Preserve this positive result. However, file, Ktor, mutations-conflicts and the Swift distribution have no explicit roster status; paging and OpenTelemetry have an unbounded “first green release” condition (`STABILITY.md:62,77-79`). Their hosted jobs pass at this SHA, which does not itself authorize adding artifacts.

`docs/store6/quickstart.md:7-9` still says the default configuration bounds memory while README:14 and the default SourceOfTruth expressly state unbounded persistence. `room/README.md:219-221` describes an old cancellation defect contradicted by its current shield and KDoc. Consumer prerequisites need one verified entry point: the repository configures minSdk 24 and builds with compileSdk 36, while Room's README:202-205 says compileSdk 34+. The repository build SDK alone does not establish a consumer minimum; inspect publication AAR metadata and an external consumer before changing that claim. STABILITY does name the Kotlin floor. Deferred install examples need the same local-only qualification Ktor supplies. Prepublication status is correct today and needs a coordinated change when artifacts actually exist.

Record explicit ship/defer choices and targets, reconcile claims against source and a real external consumer, and keep allowlist/BOM/table equality. Document parked-work operational limits and only the waivers actually accepted. Do not silently broaden API guarantees or change runtime through a wording pass. Owners: L0, L8; L1 owns workflow edits.

### F11 · P2 · Publishing has no enforced GitHub release record, and notes omit policy evidence

**Source-proven.** The workflow inventory contains no GitHub Release creation/verification after Maven publication. `RELEASING.md:32,68-73` leaves notes manual. `STABILITY.md:105-107` requires a community issue tied to a named guarantee; the alpha entry at `CHANGELOG.md:5-57` has none. It also calls core experimental-track despite the stable-track table, and contains prewritten release/date-cadence values.

Make a release record and verified artifact receipt an explicit, inspectable completion requirement. Validate notes before publishing; create or verify the GitHub Release afterward. A postpublication notes failure must be a partial release requiring record repair, not a reason to upload the same Maven version again. Select only an issue supported by an actual test or documented guarantee; this review does not authorize posting or closing it. Owners: L1 and L8.

### F12 · P2 · The configured target set exceeds the demonstrated consumer experience

**Source-proven target/evidence limit.** `Store6MultiplatformConventionPlugin.kt:14-28`, `room/build.gradle.kts:13-20`, and `paging-androidx/build.gradle.kts:14-23` declare watchOS/tvOS device targets but no corresponding simulator targets. The checked Apple runtime lane exercises iOS simulator and macOS Arm64. MinGW and Intel iOS have no executed runtime lane in these workflows.

Do not infer broken device artifacts from this. Decide whether to add verified simulator variants or explicitly document device-only and compile-only support. Check dependency metadata before changing subset modules. Use external consumers to resolve each promised target and execute each claimed tested target. KLIB file existence and API dumps prove different things. Owners: L0, L1, L8.

## Additional findings for modules outside the alpha allowlist

### F13 · P2 · Default Paging refresh skips the anchored page

**Source-proven.** `paging-androidx/.../StorePagingBuilder.kt:18-21,65-72` chooses an adjacent page key. For a forward-only first page with `prevKey=null`, `nextKey=2`, an anchor in page 1 refreshes from page 2 and cannot prepend the lost first page. The source passes that key unchanged. The existing test asserts a neighbor key, not preservation of anchored content.

Default safely to an initial restart or require an explicit application refresh mapping; generic cursor inversion cannot be guessed. Test a PagingState with a first-page item anchor through refresh and a real presenter access/invalidate cycle. The separate watcher lifetime is already documented: callers must invalidate the factory or close the Store on abandonment. Add a disposal example; do not relabel that as a newly discovered leak. Owner: L9, conditional on shipping Paging or as follow-up.

### F14 · P1 before Swift distribution · Maintenance errors escape the declared bridge exception set

**Source-proven unhandled-exception path; no Swift failure executed. Deferred from the current Maven alpha floor.** [StoreClient.swift:65-86](https://github.com/matt-ramotar/Store6/blob/b123c95a373f3629c23e797cb97e2bca18bb260a/store6-swift/swift/Sources/Store6/StoreClient.swift#L65-L86) directly invokes unannotated Kotlin maintenance methods. `core/.../Store.kt` has KDoc throws clauses but no Kotlin `@Throws` declarations on them. In contrast, [SwiftInterop.kt:141-151](https://github.com/matt-ramotar/Store6/blob/b123c95a373f3629c23e797cb97e2bca18bb260a/store6-swift/src/commonMain/kotlin/org/mobilenativefoundation/store6/swift/SwiftInterop.kt#L141-L151) deliberately wraps get in `@Throws(StoreException, CancellationException)` to avoid fatal Objective-C bridging. That wrapper omits the IllegalStateException raised by closed-store calls; constructing states after close has a related undeclared exception path.

The specialist checked committed `store6-swift/api/swift/skie/Store6Kotlin.h:582-623,1285-1289` and pinned SKIE 0.10.13 [exception-list generation](https://github.com/touchlab/SKIE/blob/0.10.13/SKIE/kotlin-compiler/linker-plugin/src/main/kotlin/co/touchlab/skie/phases/features/suspend/kotlin/SuspendKotlinBridgeCheckedExceptionsGenerator.kt) and [runtime handling](https://github.com/touchlab/SKIE/blob/0.10.13/SKIE/runtime/kotlin/src/commonMain/kotlin/co/touchlab/skie/runtime/coroutines/suspend/Skie_SuspendHandler.kt): undeclared non-cancellation exceptions are rethrown into Kotlin rather than returned as catchable Swift errors. Add explicit bridge wrappers and a consistent closed-store error contract. Exercise every maintenance failure, get-after-close and states-after-close from Swift, asserting representable failure and process survival. Do not infer that an async Swift `throws` signature covers every Kotlin throwable. The package's local debug XCFramework is explicitly prerelease-only, so this is not a claim that alpha Maven users consume the facade. Owner: L9 or a dedicated Swift worker when the module ships.

### F15 · P2 · File key encoding can collapse distinct malformed strings

**Source-based candidate requiring runtime regression.** `file/.../internal/Base32.kt:17-19` UTF-8 encodes with replacement before deriving a filename. Distinct unpaired surrogate strings can therefore collapse to the same filename while remaining distinct Store identities. Length checks alone do not reject them. Reject malformed sequences before any mutation; test collision pairs and valid Unicode round trips in both file persistence components. This has no current alpha Maven exposure because file is outside the allowlist. Owner: L9.

## Open decisions and carried risks

These entries are deliberately not counted among the four P1 findings. They remain visible so a narrower fresh review does not silently discard old risks.

| ID | Evidence and disposition needed | Revision owner |
| --- | --- | --- |
| C01 | `Bookkeeper.status` exceptions escape core's typed retrieval channels (`KeyEngine.kt:2203-2205,2239-2247,4674-4678`). The seam explicitly names other infallible methods but leaves status unspecified. Room and SQLDelight absorb ordinary status failures; file cold recovery can throw. Decide conservative status behavior and enforce it across core/implementations; do not silently treat read failure as proof of freshness. | L0 then L3/L7; file follow-up L9 |
| C02 | Source review confirms delayed mutation-ack confirmation clears invalidation received after push began (`MutationEngine.kt:1968-1983,3037-3038`; `KeyEngine.kt:1631-1643`). Freshness evidence must be captured no later than push send; capturing it at acknowledgement/adoption is too late. Combining apply+metadata fixes F04's writer mismatch but does not alone protect these newer invalidations. Specify token lifetime across retries, aliases and restart while preserving successful-ack clearing of earlier staleness. Runtime regression remains unexecuted. | L0, L3, L4 |
| C03 | Prior ACKED intent may be projected again over its already adopted server echo. Accepted two-step crash/replay policy is not by itself a waiver for wrong visible values. Pin the schedule and determine adoption-committed fencing while preserving overlays before actual adoption. Never exclude merely on `phase == ACKED`. | L0, L4 |
| C04 | PARKED work pins retirement high-water and no discard/requeue API exists. Existing private decision accepts the pin; no new discard API is mandated. Public docs must explain retained history, recovery options, and checkpoint consequences. | L8; API expansion only by separate decision |
| C05 | Old committed-stream race, normally completing SoT retry loop, rawCommitResolution locking, settlement-tail error masking, and projection backpressure were not independently reproduced in this bounded review. Preserve their original evidence and investigate before declaring them resolved. Normal reader completion violates the SoT seam, so separate defensive handling from supported-use bugs. | L10 evidence triage; no automatic source rewrites |
| C06 | Meeseeks concurrent scheduling can create two pending tasks while tracking one; dependent manager semantics remain unexecuted. The coordinator's advisory event dependency and excluded integration suites also lack fresh verification. These modules remain deferred. | L9, before their release |
| C07 | Room-internal cancellation classification and pinned Ktor timeout behavior lack a newly demonstrated failing transport/driver trigger. Add focused contract probes if these hypotheses matter to shipping; do not globally reclassify cancellation. | L10 |

## Refutations and preserved alpha limits

- Default in-memory row/bookkeeping retention is explicitly unbounded. F06 concerns a different, engine-owned completed-write history. The read default is not reintroduced as a runtime blocker.
- Alpha's nontransactional acknowledgement posture and idempotent remote replay remain accepted. This review does not pull full transactional acknowledgement into the alpha.
- The clear queued-frame carve-out, Room reader backpressure, SQLDelight instance-scoped notifications, and Paging explicit teardown are documented boundaries. They are not defects merely because another design is possible.
- Cross-namespace acknowledgement rejection and throwing-`stales` parking are already fixed in this source. No task restores the old behavior.
- Store5 and Store6 coexistence was not declared broken: the old core artifact is `core5`, while the new one is `core`. Directory renaming is not coordinate-collision evidence.
- The Codecov badge is corrected at this revision. Informational coverage thresholds and Kover's JVM-only scope are explicit choices, not failed release tests.
- Compose restart/equivalence handling, Ktor default status mapping, file cancellation shielding, and telemetry callback containment produced no additional demonstrated alpha defect in this pass. That is bounded review evidence, not a correctness certification.

## Release decision

The executable follow-up is [the parallel revision plan](../plans/2026-09-05-store6-alpha-revision-plan.md). Its first gate freezes the reviewed contract choices and source revision. It gives each code surface one writer and routes fresh discoveries back through reproduction and review. Completion requires exact-revision evidence for the resulting release candidate, an explicit record for every open finding, consumer/publication checks, and release-owner approval of the actual cut. No implementation, commit, push, merge, tag, publication, issue update, or announcement was performed by this review.
