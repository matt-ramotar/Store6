# Store 6 alpha01 Revision Plan — store6 branch (supersedes the main-tree draft)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Discharge the 18 majors (8 new + 10 carried) from the 2026-08-23 adversarial review so 6.0.0-alpha01 can be tagged from `store6` with every promise true and every waiver user-visible.

**Architecture:** Work is organized into **lanes** (A–K), each owning a disjoint set of files on the **`store6` branch** — the default branch and release line. Every lane branches from `origin/store6` (the checkout at the repo root is **stale `main` — never execute there**; use a worktree, e.g. the existing `.claude/worktrees/store6-audit` pattern). Lanes run in parallel; tasks within a lane are sequential. Wave 1 needs no rulings; Wave 2 is ruling-gated.

**Tech Stack:** Kotlin Multiplatform, kotlinx-coroutines 1.8.1, Ktor 3.5.2, Room 3.0.0, SQLDelight 2.x, Gradle conventions in `tooling/`, GitHub Actions, Codecov/Kover.

**Source spec:** [docs/superpowers/reviews/2026-08-23-store6-alpha-adversarial-review.md](../reviews/2026-08-23-store6-alpha-adversarial-review.md). §2 = new store6 findings, §3 = carried findings (C-M2…C-M10, minors), §4 = forbidden fixes. **Every subagent reads its finding and review §4 before writing code.** All paths below are store6-tree paths (unprefixed module dirs).

## Global Constraints

1. **Branch discipline:** all work branches from and merges to `store6`. The repo-root checkout is stale `main`; do not edit or audit it (its disposition is ruling R9).
2. **Never rerun** preserved CI evidence: main runs 32667414639/32667414624, store6 run 31972447780; soak-lane runs are never rerun-to-green.
3. **Engine CE classification is FS-1-ratified** — never reclassify fetcher-thrown CancellationException in core (pinned by `FetcherContractTest`). Adapter-side rewrap is permitted.
4. **C-M2 shield shape is binding:** outer merged frame `withContext(NonCancellable + HeldDriverAccess(...))` after cancellable admission. An inner NonCancellable block does NOT fix it.
5. **C-M8:** exclude from projection replay on **adoption-committed**, never `phase == ACKED` (`ack_neverReemitsOldBase` is pinned).
6. **C-M7:** capture the freshness token at **drain-push send**, not ack staging.
7. **Do not make `signalSink` lossy** — KeyEngine's projection consumer is per-key filtered; fix at the consumer.
8. **Do not touch llms.txt's 29 site-route links** (docs-site contract, sha-pinned in store-docs).
9. **Do not invent target releases** for the paging/opentelemetry STABILITY rows — R1 decides ship-vs-name-target.
10. **STABILITY.md:** single owner (Lane G), one PR, docs-sync-ack label + transform re-pin per `store6.yml`'s docs-sync-guard.
11. **Pinned tests that must stay green:** `MutationAckPathTest.ack_confirmFreshClearsDurableStaleness`, `ack_neverReemitsOldBase`, `FetcherContractTest.fetcherResultError_cancellationIsEquivalentToThrowingCancellation`, the hardened `RealtimeInvalidationTest.deleted_emitsLoadingThenRefetch`, `FileCancellationTest`.
12. **Public-API changes run `./gradlew apiDump`** and commit BCV/klib (and Swift, for core/mutations) diffs in the same PR. Merge order for generated dumps: Lane D before Lane E; Lane F re-runs apiDump after rebasing on both.
13. Verification before completion: each lane runs its module's full test task and pastes the command + tail in its completion note.

---

## Wave 0 — Rulings (Matt). Record each ruling inline here.

- [ ] **R1 — paging-androidx + opentelemetry inclusion trigger** (review §2.1): the green-for trigger is satisfied at head. (a) ship one/both in alpha01 (allowlist + BOM + STABILITY column in one change), or (b) name a target release for each. CHANGELOG follows the ruling.
- [ ] **R2 — ktor / file / mutations-conflicts roster** (§2.2): table rows or Promised entries with tier + target for each.
- [ ] **R3 — C-M7 disposition:** fix (token at push-send) or waive + document in `realtime/README.md`'s MutationStore section.
- [ ] **R4 — C-M8 disposition:** fix (adoption-committed exclusion) or waive + document in `MutationStore.stream` KDoc / STABILITY §9-equivalent.
- [ ] **R5 — C-M5 disposition:** pull the D3 discard door forward (`discardDeadLetter`) or waive with user-facing warnings. **Sharpened:** the branch now parks cross-namespace ack rejections too, so a backend bug can pin the journal with no remedy — weigh that in the ruling.
- [ ] **R6 — C-M9 paging teardown:** demand-tied fix or hardened-docs waive.
- [ ] **R7 — Register + serve-path race:** where does the classification register live on store6 (port main's docs/v6 register, or a new in-tree REGISTER.md), and what is the C-M1′ disposition (investigate the committed-stream signature to a mechanism ruling pre-tag, or waive user-visibly in the notes)? Also: the automation repoint target (Linear vs in-tree) for Lane E task E3.
- [ ] **R8 — POM developer identity** (§2.3): current maintainership values, or record acceptance of the Dropbox credit.
- [ ] **R9 — main branch disposition:** sync main to store6, archive it, or add a stale-branch banner — so the default-branch confusion cannot recur.
- [ ] **R10 — §5 closure issue** (§2.5): which community issue the alpha01 notes close (#570 → committed BCV/klib dump machinery; #534 → ROADMAP.md), or a user-visible waiver.

---

## Wave 1 — no rulings needed; lanes run in parallel

### Lane A — core engine (owns `core/src/**` except Lane F's Wave-2 seam leg)

**Files:** Modify `core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/internal/KeyEngine.kt` (four sites), `.../internal/InMemorySourceOfTruth.kt`, `.../seam/Bookkeeper.kt` (KDoc), `.../seam/Fetcher.kt` (KDoc), `.../Store.kt` (stream KDoc only). Tests in `core/src/commonTest/...`.

- [ ] **A1 (carried m3):** failing test first — a SoT whose `reader` completes normally must fail the pipeline fatally, not retry at 100ms forever. Fix: route the completed-normally `IllegalStateException` through the `RawObservationFailure` envelope so the retryWhen's `throw failure.engineFailure` branch terminates the pipeline (match the envelope's constructor in the file; site ≈ `:218-232`).
- [ ] **A2 (carried m4):** make `rawCommitResolution` reads coherent across lock domains — capture into the stateLock-held `ResidenceSnapshot` (preferred) or an atomic reference. Writes `:558`/`:1683`; lock-free readers via the `:1189-1208` helpers.
- [ ] **A3 (carried m5):** settlement-tail masking, branch-preserving:

```kotlin
fetchJob.invokeOnCompletion { failure ->
    if (failure != null) {
        ticket.outcome.cancel(
            if (failure is CancellationException) storeClosedCancellation()
            else CancellationException("Fetch settlement failed for key $keyId", failure)
        )
    }
}
```

(`"Store is closed."` is message-pinned by `StoreCloseLifecycleTest`/`StoreConformanceTest`; use the kotlinx `CancellationException(message, cause)` factory.)
- [ ] **A4 (carried m10, consumer-side per constraint 7):** in `runProjectionWriter` (~`:324-326`), decouple the overlay-trigger leg per subscriber — `configured.changes.filter { mine }.map { }.buffer(Channel.CONFLATED)` — so a hung projector cannot backpressure `mutate()`. Test: a projector blocking on one key must not stall `mutate()` on another. The mutations `signalSink` stays SUSPEND and untouched.
- [ ] **A5 (carried m7):** `InMemorySourceOfTruth` cell reclamation with the orphaned-reader race closed: remove null-row, zero-consumer cells under the mutex AND make the reader loop-validate the cell instance (re-fetch + re-subscribe if the map no longer holds it), or count handed-out cells. Add the delete-racing-fresh-reader interleaving test plus an internal `cellCountForTest()` assertion.
- [ ] **A6 (carried m1/m6, KDoc only):** `seam/Fetcher.kt` — warn that an escaping CancellationException is treated as fetch cancellation (streams/waiters cancelled, no `StoreResult.Error`); advise rewrapping CE-typed transport timeouts. `Store.stream` KDoc — add the FS-1 CE carve-out to the liveness sentence. `seam/Bookkeeper.kt` — `status` implementations must absorb storage failures and return null (both shipped adapters do; note the deliberate `kotlin.Error` pass-through they added).
- [ ] **A7:** `./gradlew :core:jvmTest :core:allTests` → PASS incl. conformance suites. Commit per fix.

### Lane B — mutations hygiene (owns `mutations/src/**` in Wave 1)

- [ ] **B1 (carried C-M6):** failing retention test (drive mutate→drain→ack→retire ×K on the in-memory journal; assert per-mutation bookkeeping does not grow with K after retirement, via `MutationInspection` or an internal accessor). Fix: at both durable retirement commit points, **after event emission**, remove the retired id from `durableExecutions`, `durableAttempts`, `durableAcks`, `effectSnapshots`, `durableEffectRows` (mirroring the existing `phases`/`completedAttempts` cleanup). Leave `ackEffectiveTargetsByIdempotencyKey` alone (not leaked). PARKED entries stay retained.
- [ ] **B2 (carried m8):** global `drain()` parity with keyed drain — retain the first non-retained post-ack failure (wrap raw rehome-continuation throws), finish the identity loop, `flushRetirementCheckpoint()`, then rethrow. Failing test: two identities, first fails post-ack non-retained; assert the second drains and the checkpoint flushes before the rethrow.
- [ ] **B3 (carried m9):** hoist `resolveForDrain`/`resolveTerminalKey` outside the identity/namespace leases (the file's own invariant + the DrainRehome cursor loop show the pattern); fallback = document the re-entry prohibition in `MutationKeyResolver` KDoc and say which option was taken.
- [ ] **B4:** `./gradlew :mutations:jvmTest` full suite → PASS (pruning, hydration, alias — note `MutationAliasFacadeTest` was revised on this branch for park-on-all-rejections; it must stay green as revised).

### Lane C — SqlDelight exception-atomicity (carried C-M2) (owns `sqldelight/src/**`)

**Files:** `sqldelight/src/commonMain/kotlin/org/mobilenativefoundation/store6/sqldelight/internal/DriverAccess.kt`, `.../SqlDelightSourceOfTruth.kt` (write/delete/deleteNamespace/deleteAll + `withTransaction`), `.../SqlDelightBookkeeper.kt` (maintenance ops). Test: extend the atomicity suite in `commonSqlTest`.

- [ ] **C1 (failing test):** external cancellation racing the commit boundary — launch `sot.write` on a multithreaded dispatcher, latch inside the transaction, `job.cancel()` from outside, ~100 iterations; assert never *threw-with-row-present* (fresh-reader verified). Expect flaky-FAIL pre-fix.
- [ ] **C2 (fix, constraint 4):** add `internal suspend fun <T> withMutationAccess(block: () -> T): T` whose admission (gate + `ensureActive`) stays cancellable, then `return withContext(NonCancellable + HeldDriverAccess(/* same token */)) { block() }` — the OUTER merged frame, mirroring `RoomSourceOfTruth`'s shield. Verify `HeldDriverAccess.owns()` job-identity bookkeeping and `withTransaction`'s `runNonSuspending` rebinding still pass for nested mutations under the NonCancellable job. Switch the four mutations, `withTransaction`, and Bookkeeper maintenance ops; reads unchanged.
- [ ] **C3:** `./gradlew :sqldelight:jvmTest` incl. the contract kits → PASS. Commit.

### Lane D — testing kits (carried C-M3/C-M4) (owns `testing/src/**`; merge after C, before E)

- [ ] **D1 (C-M4):** add to `BookkeeperContractKit`: `globalWatermark_coversNeverSeenKey_andLaterSuccessClears`; `forgetAllAndForgetNamespace_removeRecordsButPreserveWatermarks`; `successAndMarksDrawFromOneMonotoneSequence` (global watermark included). Model on the existing namespace-watermark tests and the unpublished `FakeBookkeeperAlgebraTest` assertions.
- [ ] **D2 (C-M3):** add to `SourceOfTruthContractKit`: `mutationCancelledExternally_isExceptionAtomic` (generalize C1 behind a kit hook); an optional fallible-mutation injector hook asserting throw-means-not-applied; a `TransactionalSourceOfTruth` extension asserting rollback publishes nothing **plus an external-cancel-at-commit variant** (pins C2 and the file adapter alike).
- [ ] **D3:** run every kit consumer: `./gradlew :testing:jvmTest :sqldelight:jvmTest :room:jvmTest :file:jvmTest :mutations:jvmTest` → PASS (green requires Lane C merged; a red elsewhere is a real adapter bug — report, don't weaken).
- [ ] **D4:** kit methods are public API — `./gradlew apiDump`, commit `testing/api/**` diffs. Merge before Lane E.

### Lane E — CI signal + targets (owns `.github/workflows/**`, `tooling/plugins/**`, `room/build.gradle.kts`, `paging-androidx/build.gradle.kts`, `coverage/`)

- [ ] **E1 (§2.7 soak short-circuit):** force execution in `store6-full-jvm.yml` — add `--no-build-cache` (or `--rerun-tasks`) to the `:mutations:jvmTest -Pstore6.fullJvmSuite` invocation, AND harden the census to fail on cache-restored XMLs (compare TEST-*.xml timestamps against run start).
- [ ] **E2 (carried m14, now runbook-covered):** derive the klib-check version — `version="$(grep -w 'VERSION_NAME' gradle.properties | cut -d'=' -f2)"` at `store6.yml:741` (root-only VERSION_NAME on this branch), and delete the corresponding manual bump from RELEASING.md step 2 (coordinate with Lane G's RELEASING edits — G owns RELEASING.md; hand G the sentence).
- [ ] **E3 (§2.8 classification automation, target per R7):** replace the unshielded `gh issue create` in `store6-full-jvm.yml` with the ruled mechanism (Linear CLI/API call, or append-to-register + open PR), and fix the duty text to cite the conduct doc that exists on this branch (per R7's register decision, with Lane K).
- [ ] **E4 (carried C-M10 targets):** verify first that room3-runtime 3.0.0 and paging-common 3.5.1 publish `watchosSimulatorArm64`/`tvosSimulatorArm64` variants (Gradle module metadata). Then add both targets to `Store6MultiplatformConventionPlugin.kt` (~`:20-21`) and to `room/build.gradle.kts` + `paging-androidx/build.gradle.kts` subset declarations (+ room's languageSettings list) — or, where an upstream lacks the variant, add klib-check case exclusions for that module and record the disposition. Add case exclusions for `devtools-inspector` (no watch/tv at all); add the simulator test tasks to apple-tests **only** for modules that declare the targets. `./gradlew apiDump` for the dump target-headers; **merge last in Wave 1** (constraint 12).
- [ ] **E5 (minor batch):** Codecov upload — add `!startsWith(github.ref, 'refs/tags/')` to the upload condition or set `fail_ci_if_error: false` on tag builds (keep hard enforcement on branch pushes/PRs). Add a scheduled/dispatch lane running `:mutations-drain-meeseeks:jvmTest -Pstore6.meeseeksJvmIntegration` (allowed-to-fail, reported) + a two-suite exclusion census. Record the coverage-instrumentation epoch (Kover attached to gate lanes since #74) in the register (Lane K) and optionally keep one uninstrumented JVM lane.

### Lane J — Room CE classification (carried m2) (owns `room/src/**`)

- [ ] **J1:** apply the RoomBookkeeper `ensureActive` classification idiom in `RoomSourceOfTruth`'s reader and transaction paths (rethrow genuine caller cancellation; otherwise surface as a storage failure the engine maps to `StoreError.Persistence`) — or, if declined, correct RoomBookkeeper's KDoc claim so the two stop contradicting each other. Note the branch's new `kotlin.Error` pass-through posture and keep it. `./gradlew :room:jvmTest` + hostTest → PASS.

### Lane K — Register, filings, and lineage (owns the new register file + `docs/superpowers/**` notes; coordinates with E3)

- [ ] **K1 (per R7):** create the classification register on store6 (port main's `docs/v6` register format or a new `docs/store6/REGISTER.md`). Seed entries: (a) the committed-stream serve-path signature (main's 034; one CI sighting, mechanism open — carries C-M1′'s disposition per R7); (b) the realtime clear-race lineage (four sightings → `fe94a0cc` test hardening; link the Linear follow-up id the codecov plan bullet references); (c) the coverage-instrumentation epoch; (d) the meeseeks known-red (two suites, upstream gap, no upstream issue yet — file one or link Linear).
- [ ] **K2 (audit debt):** register entries + Linear issues for the two uninvestigated gaps: CI workflow supply chain/secrets; Android consumer floors (minSdk/compileSdk/AGP documentation).
- [ ] **K3 (if R7 rules investigate):** run the C-M1′ investigation (committed-wait machinery vs `SourceOfTruthBindingConformanceTest` gate fixtures) to a mechanism ruling; deliverable = ruling package appended to the register entry.

---

## Wave 2 — ruling-gated

### Lane G — the docs/release-mechanics PR (owns STABILITY.md, CHANGELOG.md, README.md, RELEASING.md, `gradle.properties` POM lines, module READMEs, `opentelemetry/` comment fixes) — gated on R1, R2, R8, R10 (+ waiver texts per R3–R6)

One PR, docs-sync-ack label, single owner. Items:

- [ ] **G1 (R1):** execute the paging/opentelemetry ruling — either allowlist+BOM+STABILITY-column in one change, or named-target rows replacing the green-for clauses; mirror in CHANGELOG's artifact bullet (add opentelemetry's deferral, currently absent).
- [ ] **G2 (R2):** STABILITY rows/Promised entries for ktor, file, mutations-conflicts (tier + target).
- [ ] **G3 (R8):** POM developer block in root `gradle.properties` per the ruling.
- [ ] **G4 (§2.4):** flip the three status lines (README:17, llms.txt:9 — text only, never the links; quickstart.md:3-4 incl. replacing "as it stands on `main`" with the release branch/tag) **in the release PR**, and add the flip as an explicit RELEASING.md step.
- [ ] **G5 (R10/§2.5):** add the §5 closure line ("Closes #NNN" + the named conformance-test/machinery link) to the alpha01 notes, and the corresponding RELEASING.md checklist step — or the user-visible waiver.
- [ ] **G6 (§2.6):** RELEASING.md step for `INSTRUMENTATION_SCOPE_VERSION` (bump alongside VERSION_NAME); fix the stale comments in `opentelemetry/build.gradle.kts:50-53` and `InstrumentationScopeVersionTest` to the root-only model; (follow-up filed, not in this PR: generate the constant from the Gradle property).
- [ ] **G7 (minor batch):** CHANGELOG — separate `core` ("stable-track, not frozen until beta01") from the experimental artifacts; refresh the entry date at tag time (add to RELEASING). README badge → this line's Codecov (main already carries the corrected badge). Copy ktor's Maven-Local caveat into the five deferred-module README install sections. Reword compose's "same graduation" clause to its actual graduation evidence. Add STABILITY §7 note that mingwX64/iosX64 are compile-only-verified (or Lane E adds a Windows lane instead). Waiver texts per R3/R4/R5/R6 rulings land here for STABILITY/notes surfaces (module-file waiver texts land in Lane F/I, which own those files).
- [ ] **G8:** verify every touched claim against the tree before merge; docs-sync re-pin per the guard.

### Lane F — realtime×mutations + dead letters (owns `mutations/src/**` incl. `MutationStore.kt` + `storage/`, `mutations-sqldelight/src/**`, `realtime/README.md`, new `realtime/src/commonTest/.../RealtimeMutationsInterleavingTest.kt`; core seam leg after Lane A) — gated on R3/R4/R5; rebases on Lane B

- [ ] **F1 (carried m14 — may start in Wave 1):** promote the interleaving scenarios: (a) Upsert-invalidate rebases a pending optimistic overlay over the refetched base; (b) realtime Deleted preserves pending intents. `./gradlew :realtime:jvmTest` → PASS.
- [ ] **F2 (C-M7, if R3=fix):** capture the stale-epoch/bookkeeper token at **drain-push send**; `confirmFresh` clears staleness only up to the token (no-op when a stale mark postdates it). Seam change = token parameter on `StoreWriteHandle.confirmFresh` or an engine-internal guarded variant (core leg — after Lane A merges). `ack_confirmFreshClearsDurableStaleness` stays green. Add the invalidation-survives-ack conformance test.
- [ ] **F3 (C-M8, if R4=fix):** adoption-committed marker set (populated after `handle.apply` returns, cleared at retirement) excludes entries from `projectAll`'s replay. Test: non-idempotent append mutator; no frame ever shows the edit applied twice; `ack_neverReemitsOldBase` stays green.
- [ ] **F4 (C-M5, if R5=fix):** public `MutationStore.discardDeadLetter(mutationId)` (`@ExperimentalStoreApi`): PARKED→RETIRED edge in the storage validator (`storage/InMemoryMutationJournalStorage.kt` ~`:1001-1004`), the mutations-sqldelight storage, and hydration; prefix/checkpoint/prune advance past the park. D3 shape: explicit disposition, never in-place retry. Test: park (3 identical conflicts — or a cross-namespace rejection, now also a park on this branch), enqueue later mutations, discard, assert checkpoint+prune advance.
- [ ] **F5 (waive paths):** R3-waive → `realtime/README.md` MutationStore section documents the invalidation-erasure window; R4-waive → `MutationStore.stream` KDoc documents the transient double-projection frame; R5-waive → `deadLetters()` KDoc warns of permanent pinning (now including backend-caused cross-namespace parks). Register entries via Lane K for each waived item.
- [ ] **F6:** public API changed → `./gradlew apiDump` after rebasing on B/D/E; full `:mutations` `:realtime` (+`:core` if seam leg) suites → PASS.

### Lane I — paging teardown (carried C-M9) — gated on R6 (owns `paging-androidx/src/**` + its README)

- [ ] **I1 (fix):** cancel the generation when its PagingSource invalidates **or** its load-scope demand ends, preserving the existing later-frame self-invalidation; leak test (drop pager without `invalidate()`, assert collectors released). **(waive):** README lifecycle section with the `onCleared { factory.invalidate() }` example and the exact leak shape (watched keys that never emit again).

---

## Wave 3 — tag readiness (sequential, after all lanes merge)

- [ ] **T1:** version-bump PR: root `VERSION_NAME=6.0.0-alpha01`, `INSTRUMENTATION_SCOPE_VERSION` (until G6's follow-up automates it), CHANGELOG date. klib-check must go green (proves E2 composes). No other module properties change (root-only model).
- [ ] **T2:** confirm every R3–R7 waiver line landed in the notes; status lines flipped (G4).
- [ ] **T3:** fresh attempt-1 green pair (Store6 + CI) on the exact tag commit; any new red gets classified via the K1 register (never rerun-to-green).
- [ ] **T4:** tag `v6.0.0-alpha01`; the tag-gated publish lane runs; verify the version-match gate passed.
- [ ] **T5 post-tag:** resolve `org.mobilenativefoundation.store:bom:6.0.0-alpha01` from a scratch project; spot-check one POM on Central for description/developer (R8); flip anything G4 deferred to post-tag.

## Deferred (explicitly not in this plan)

- ktor timeout-classification pinning test + optional adapter-side CE rewrap (review §2 minors — good first alpha02 item, keeps constraint 3 intact).
- file adapter minors (surrogate-key validation, FileBookkeeper infallibility, README coords beyond G7's caveat).
- Bounding the default `InMemorySourceOfTruth` (B4's option (a) from round 1) — alpha-line work.
- Generating `INSTRUMENTATION_SCOPE_VERSION` from the Gradle property (G6 follow-up).
- Windows runner for mingwX64 (if G7 takes the doc note).
- drain-coordinator inspection-based checkpoint signal (alpha02, with the drain family's train).

## Parallelism summary

| Wave | Lanes | Gate |
|---|---|---|
| 0 | R1–R10 rulings | — |
| 1 | A, B, C, J, K, F1 ∥ then D (after C) ∥ then E merges last (repo-wide apiDump) | none |
| 2 | G (one PR) ∥ F (rebases on B, D, E; core leg after A) ∥ I | R1/R2/R8/R10 (G); R3/R4/R5 (F); R6 (I); R7 (K3/E3 mechanism) |
| 3 | T1–T5 sequential | all merged |

File-ownership exceptions (sequenced, not concurrent): F↔B on `mutations/**` (F rebases); F↔A on `core` seam leg (after A); E↔G on RELEASING.md sentence (G owns the file, E hands it text); generated `api/**` dumps ordered D → E-last → F-re-run; the K1 register is append-only with Lane K as sole owner in Wave 1.
