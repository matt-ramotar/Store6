# Store 6 alpha01 adversarial review — 2026-08-23 (store6 branch, definitive)

**Verdict: NO BLOCKERS on the release line.** The `store6` default branch at head `9d68f4db` is
green on both gate workflows, has a real tag-gated publish lane, and has already discharged five
of the six blocker-class defects an earlier pass found on the stale `main` branch. What remains
before a defensible 6.0.0-alpha01 tag is **8 new majors + 10 carried majors**, all in the
"fix or explicitly, user-visibly waive" class — the largest cluster is one docs/release-mechanics
PR; the rest are engine/adapter fixes and CI-signal repairs, several ruling-gated.

## Scope correction and method

Round 1 of this audit (48 agents, workflow `wf_1c6492a9-b17`) ran against the repo's **stale
`main` branch** — a scoping error: the default branch and release line is **`store6`**
(`git remote show origin` → HEAD branch: store6). Round 2 (22 agents, workflow `wf_a99299f8-0f6`,
7 dimensions + per-finding adversarial refuters) audited the store6 tree at `9d68f4db`
("Codecov integration for the store6 module family (#74)") in the worktree
`.claude/worktrees/store6-audit`, covering the surface round 1 never saw. Round 1's deep engine
findings were then re-grounded file-by-file against the real cross-branch diff (the trees are
NOT byte-identical — an earlier "identical" conclusion came from a broken diff invocation;
production deltas exist in core, mutations, realtime, room, sqldelight, testing, compose).

The store6 tree: 40 modules under **unprefixed directories** (`core/`, `mutations/`,
`testing/`, …; packages remain `org.mobilenativefoundation.store6.*`), including six modules
round 1 never audited: `ktor` (fetcher kit), `file` (file-backed SoT+Bookkeeper),
`opentelemetry` (telemetry sink), `mutations-conflicts`, `mutations-drain`,
`mutations-drain-meeseeks`, plus `bom` and `coverage`.

**Severity bar** (STABILITY.md's own alpha01 contract): blocker = must fix before the tag;
major = fix or explicitly, user-visibly waive; minor = rides to alpha02. Experimental-tier API
churn is not a finding. Artifacts outside the alpha01 publish allowlist calibrate down.

**Round-2 caveat:** two agents died on a session limit — one duplicate verifier (its finding is
covered by two other verified findings) and the round-2 gap critic (no second gap sweep ran;
disclosed in §6).

---

## 1. What the release line already fixed (round-1 findings discharged on store6)

The `fe94a0cc` "Alpha01 revisions" commit and the branch's release engineering discharged most of
round 1's headline findings. Recorded so nobody re-fixes them:

- **R1-B1 (head red on both gates) — gone.** store6 head `9d68f4db` is green on both workflows
  (runs 32658851425 / 32658851443, verified from run data; all gate steps executed).
- **R1-B2 (clear()/Loading race) — RATIFIED.** The branch took the ratify path:
  [Store.kt](../../core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/Store.kt)'s
  `clear` KDoc now documents the carve-out ("While the call is in flight, an active collector may
  receive one queued duplicate Data frame; under a fast refetch that frame may carry the
  refetched value and interleave with the Loading transition"), `RealtimeMessage.Deleted` carries
  the same text, and `RealtimeInvalidationTest.deleted_emitsLoadingThenRefetch` was hardened to
  terminalize the contract — its comment pre-classifies any future failure as a real defect. The
  four historical sightings (two on main CI, one on store6 CI run 31972447780, one local under
  Kover) all predate the hardening. Residual: lineage hygiene only (§3, register item).
- **R1-B3 (no publish path) — gone.** ci.yml on store6 is tag-gated with tag↔VERSION_NAME match,
  snapshot refusal, signing wiring, and an explicit allowlist
  `(core testing sqldelight room compose graphql realtime mutations mutations-testing
  mutations-sqldelight bom)` that exactly equals the BOM constraints and STABILITY's alpha01
  column. RELEASING.md is rewritten for Store 6 (root-only VERSION_NAME, CI-only publication).
  KMMBridge is classpath-only, never applied. Verified end-to-end by round 2.
- **R1-B4 (bounded-memory promise false) — gone.** README:14 says the zero-config in-memory
  persistence "is unbounded by design — install a …", and `StoreBuilder`'s `sourceOfTruth`/
  `bookkeeper` KDocs now state per-key lifetime retention, not bounded by `maxIdleKeys`.
- **R1-B5 (tier table omissions) — superseded.** The rewritten table covers 16 artifacts
  including graphql, realtime, mutations-sqldelight, mutations-testing, bom, and named alpha02
  targets for devtools×2, mutations-drain×2, opentelemetry. Residue = §2 items 1–2 (narrower).
- **R1-M11 (POM says Store5) — half fixed.** `POM_DESCRIPTION` now says Store6. The developer
  block survives as §2 item 3.
- **R1-M12 (no CHANGELOG entry) — entry exists** (dated 2026-08-21). Residue = §2 items 4–5.
- **R1-M13 (FetcherResult unannotated) — resolved by annotating.** The branch overrode the old
  008 posture: `FetcherResult` now carries `@ExperimentalStoreApi`. STABILITY's seam blanket
  sentence is TRUE on this tree. (Round 1's "do not annotate" guidance is obsolete.)
- **R1 §5 cross-namespace ruling — INVERTED by the branch.** `MutationEngine` now **parks every
  alias-admission rejection** including cross-namespace (`HALTED` outcome and
  `recordDurableProtocolFailure` deleted; comment: "retrying cannot change the server's answer …
  instead of looping INFLIGHT→READY under backoff forever"). Round 1's "never park
  cross-namespace" is obsolete — but note the sharpened interplay with M5 below.
- **R1-m12 (llms.txt dead links) — REFUTED.** The 29 rooted links are docs-site routes; all
  target pages exist in the store-docs repo, pinned by a contract test; the sync transform
  rewrites the repo-relative links at publication. Do not "fix" llms.txt's link scheme.
- Other store6-only hardening observed: `invalidateResident` no longer emits telemetry/events on
  a no-op; joined-fetch-failure now serves a newer committed value under tolerant freshness; a
  thrown `stales` function parks durably instead of silently blocking the FIFO; codec-wedge
  adoption emits a recurring advisory; Room gained a cross-database nesting guard; both
  bookkeepers now propagate `kotlin.Error`.

---

## 2. New findings on the store6 surface (round 2, refuter-verified)

### Majors — docs / release mechanics (one PR can clear items 1–6)

1. **The "joins the line in the first release it is green for" trigger is already satisfied for
   paging-androidx and opentelemetry — and neither ships.** STABILITY.md:78 (paging, plus the
   no-silent-drop rule) and :62 (opentelemetry) state a self-satisfying condition; head
   `9d68f4db` is green with both modules built, tested, and klib-checked, yet both are absent
   from the publish allowlist and BOM, and no record names a later target. CHANGELOG repeats the
   promise for paging and omits opentelemetry entirely. *Adjusted-major.* **Fix:** ship them
   (allowlist+BOM+column in one change, per the documented sync rule) or name targets.
2. **ktor, file, and mutations-conflicts have no STABILITY row, no target, zero mention.** All
   three are publishable-shaped (POM coordinates, klib-checked, tier-marked READMEs pointing at
   STABILITY.md) and deferred — exactly the category the table's alpha02-target rows exist for.
   The `fe94a0cc` table rewrite postdates all three and enumerated every *other* deferred
   artifact. *Adjusted-major (two dimensions + one unverified duplicate).* **Fix:** three rows or
   Promised-paragraph entries with targets.
3. **Every artifact still publishes POM developer `dropbox`/`Dropbox`.** Immutable on Central
   once released; the description half was fixed, the developer block never adjudicated.
   *Confirmed major.* **Fix:** set to current maintainership (safe on this branch — no Store 5
   modules publish from it) or record explicit acceptance.
4. **No release step flips the "Nothing is published yet." lines.** README:17, llms.txt:9, and
   quickstart.md:3-4 (which also says the API is "as it stands on `main`" — the wrong branch)
   all deny the release exists; RELEASING.md's 7-step checklist never touches them. Currently
   true, false at the tag. *Adjusted blocker→major (prospective; post-tag retro-fixable on live
   surfaces).* **Fix:** flip in the release PR + add the checklist step.
5. **STABILITY §5's per-alpha release-notes commitment is unmet.** "Each alpha closes at least
   one community issue with a link to the named guarantee … a conformance test, not a changelog
   line" — the alpha01 entry is linkless; RELEASING.md reproduces only the target-month half.
   The resolving machinery exists (#570 → committed BCV/klib dumps per §7; #534 → ROADMAP.md).
   *Confirmed major.* **Fix:** add the closure link(s) to the notes + the checklist step, or
   waive user-visibly in the notes.
6. **The opentelemetry `INSTRUMENTATION_SCOPE_VERSION` constant deterministically reds the
   version-bump PR.** The constant is hardcoded `"6.0.0-SNAPSHOT"`; `InstrumentationScopeVersionTest`
   asserts it equals root `VERSION_NAME`; the test runs in the root build that gates the release
   PR and the tag's publish job; RELEASING.md documents the analogous store6.yml bump but not
   this one, and re-triggers on the post-release bump. *Confirmed major.* **Fix:** runbook step
   now; generate the constant from the Gradle property later. *(Related minors: the build-file
   and test comments describe a module-level VERSION_NAME override that no longer exists and
   RELEASING.md forbids; the otel README install snippet names a never-published coordinate.)*

### Majors — CI signal quality

7. **The daily full-JVM soak lane silently short-circuits via Gradle build cache.** The only
   lane that ever runs the Lincheck suite frequently restores `:mutations:jvmTest` FROM-CACHE
   (5 of 16 scheduled runs since Aug 8, incl. 2 of the last 4 at the release head: 59–67s
   "full suite" greens executing zero tests), and the "no suite may be lost" census passes on
   cache-restored XMLs — structurally blind to non-execution. Soak evidence cited by mutations'
   graduation criteria is materially overstated. *Confirmed major.* **Fix:** `--no-build-cache`/
   `--rerun-tasks` in that lane, or census on fresh execution timestamps.
8. **Red-day classification automation is deterministically broken on the release repo.** The
   scheduled workflow's failure step runs an unshielded `gh issue create` against
   matt-ramotar/Store6, which has **issues disabled** (`has_issues:false`), and the duty text
   cites `docs/v6` conduct — a directory that **does not exist on the store6 branch**. The
   never-rerun/always-classify discipline has no working mechanism and no in-tree register on
   the release line. *Confirmed major.* **Fix:** repoint the mechanism (Linear / in-tree
   register committed via PR) and port or re-home the conduct/register docs.

### Round-2 minors (selection; full list in the workflow output)

| Finding | Location | Note |
|---|---|---|
| ktor timeout-vs-cancellation classification is **unpinned** | `ktor/.../KtorFetcher.kt:151` | Correct today only because Ktor 3.5.2's `HttpRequestTimeoutException` is an `IOException` and `HttpStatement.execute` unwraps the internal cancellation (binary-verified). Ktor 2.x pins or a hierarchy regression would silently kill streams per FS-1. Add the HttpTimeout MockEngine test + optionally an adapter-side CE rewrap (permitted; engine-side reclassification stays forbidden) |
| file: lossy surrogate key encoding can alias two keys onto one file | `file/.../internal/Base32.kt:19` | validate with `throwOnInvalidSequence = true` |
| file: `FileBookkeeper` throws IAE through the seam's operationally-infallible ops | `file/.../FileBookkeeper.kt:104` | engine calls `recordSuccess` unguarded post-commit |
| meeseeks known-red gate is prose-only | `mutations-drain-meeseeks/build.gradle.kts:44` | excluded suites run in **zero** CI lanes; README's "track upstream" pointer dangles (no upstream issue, fork issues disabled). Disclosed in STABILITY:61 and outside the allowlist → minor for this tag; a property-gated lane + real tracking entry due before any alpha02 train |
| drain coordinator rides the lossy advisory event bus | `mutations-drain/.../MutationDrainCoordinator.kt:233` | a missed emission can cancel the persisted safety activation; liveness-only |
| realtime race register hygiene | `realtime/.../RealtimeInvalidationTest.kt:109` | race closed by test hardening; sighting→hardening lineage and the "follow-up task filed" pointer are unverifiable from the repo — link the Linear id |
| Codecov upload with `fail_ci_if_error: true` gates tag publication | `ci.yml:90` | a Codecov outage blocks the release; skip/soften on tag refs |
| CHANGELOG calls `core` "experimental-track" | `CHANGELOG.md:14` | STABILITY says stable-track; reword |
| 5 deferred-module READMEs give never-published install coordinates | e.g. `file/README.md:21` | copy ktor's Maven-Local caveat |
| README codecov badge points at upstream Store-5 main | `README.md:5` | main's README already carries the correct fork/store6 badge |
| Coverage instrumentation now runs in both gate lanes | `Store6Conventions.kt:38` | flake-baseline epoch; one recorded agent-coincident unmasking; keep one uninstrumented lane or record the epoch |

### Round-2 refuted (do not "fix")

- **opentelemetry's "first release it is green for" row is not a false promise** — in this repo's
  idiom (fixed by CHANGELOG's identical paging usage) the conditional means *deferred,
  unscheduled*; the row is design-ratified verbatim. The *trigger-already-satisfied* tension is
  what survives (major #1). Inventing an "alpha02 (target)" without a ruling would fabricate a
  commitment.
- **llms.txt's 29 site-route links are not dead** (see §1) — trimming them would desync the
  docs-repo's sha-pinned 36-link contract.

---

## 3. Carried-over findings (round 1, re-anchored to store6 paths)

Round 1's engine/adapter findings were made against code the store6 branch shares with targeted
diffs; each below was checked against the actual cross-branch delta and **survives**. Original
refuter analyses are in the round-1 output (workflow `wf_1c6492a9-b17`); severities unchanged
unless noted.

### Majors

- **C-M2 — SqlDelight SoT (and Bookkeeper maintenance ops) can durably commit then throw
  CancellationException.** `sqldelight/.../internal/DriverAccess.kt` unchanged on store6: plain
  `withContext(HeldDriverAccess)` around the synchronous committing transaction; prompt
  cancellation throws CE at the boundary *after* COMMIT + afterCommit signals — violating the
  seam's exception-atomicity clause the engine's commit path relies on
  (`core/.../internal/KeyEngine.kt` terminalizes as Cancelled, skips `recordSuccess`). Room
  shields exactly this window (`room/.../RoomSourceOfTruth.kt` NonCancellable frame);
  reproduction was compiled and run against coroutines 1.8.1 in round 1. Applies to
  write/delete/deleteNamespace/deleteAll **and `withTransaction`**. **Fix shape (binding):** the
  shield must be the OUTER merged frame — `withContext(NonCancellable + HeldDriverAccess(...))`
  after cancellable admission; an inner `NonCancellable` does NOT close the demonstrated window.
- **C-M3 — `SourceOfTruthContractKit` has zero exception-atomicity/cancellation cases** (kits
  unchanged on store6); the graduation gate certifies the C-M2 violator today.
- **C-M4 — `BookkeeperContractKit` never exercises `advanceGlobalStaleWatermark`,
  `forgetNamespace`, `forgetAll`** or the never-reset-watermark / shared-sequence clauses.
- **C-M5 — A parked dead letter permanently pins journal prune + server checkpoint; no discard
  API — now SHARPENED:** the branch's park-every-alias-rejection change (§1) means a *backend's*
  cross-namespace bug now lands in the same terminal-park bucket, so the missing discard door
  gates recovery from server-side bugs too. The deferral (021 D3) is still invisible to users
  (STABILITY §8, README, `deadLetters()` KDoc silent).
- **C-M6 — MutationEngine retains five per-mutation in-memory maps forever** (retirement rewrites
  instead of removing; sites untouched by the branch diff). Constraints: leave
  `ackEffectiveTargetsByIdempotencyKey` alone; sequence removal after event emission.
- **C-M7 — Drain-ack `confirmFresh` erases realtime invalidations in the residual window** (ack
  path adoption sites unchanged). Binding fix constraint: capture the freshness token at
  **drain-push send**; `MutationAckPathTest.ack_confirmFreshClearsDurableStaleness` stays green.
- **C-M8 — ACKED intent transiently double-projected over its own adopted echo**
  (`projectAll`/`replayableEntries` unchanged). Binding constraint: exclude on
  **adoption-committed**, not `phase == ACKED` (pinned `ack_neverReemitsOldBase`).
- **C-M9 — Abandoned pager leaks the final generation's watcher scope + collectors**
  (`paging-androidx` src identical). Fix teardown-on-demand or waive with hardened docs.
- **C-M10 — watchOS/tvOS ship device-only klibs** — no simulator variants in the convention
  plugin (re-verified on store6 tooling) nor in `room`/`paging-androidx`'s subset declarations;
  simulator consumers fail resolution; the families have never executed a test. Scoping caveats
  from round-1 critique apply (subset modules, devtools-inspector exclusions, verify upstream
  simulator variants first).
- **C-M1′ — the committed-stream serve-path race (main's "issue 034") is unregistered here.**
  The KeyEngine committed-wait machinery is shared; main's register (docs/v6) does not exist on
  store6 (§2 item 8). The one CI sighting remains unexplained; the conformance test is publicly
  held out as documentation of the guarantee. Investigate to a mechanism ruling or waive
  user-visibly in the notes, and give the signature a register home on this branch.

### Minors carried (store6 paths)

`core/.../KeyEngine.kt`: reader-completes-normally becomes an infinite 100ms retry loop instead
of the documented fatal defect (~:218); `rawCommitResolution` read outside stateLock;
settlement-tail failures masked as "Store is closed." (branch-corrected snippet: preserve the
CE-branch, use `CancellationException(message, cause)`); mutations `signalSink` hang — fix at the
**consumer** (per-key-filtered collect in `runProjectionWriter`; a lossy sink can drop a key's
only wake). `core/.../InMemorySourceOfTruth.kt`: cells never removed (fix must close the
orphaned-reader race). Seam KDocs: `Bookkeeper.status` absorb guidance; `Fetcher`/`Store.stream`
CE-trap carve-out (FS-1 stands; ktor finding above makes this concrete). `mutations/`: global
drain skips checkpoint flush on non-retained post-ack failure; resolver runs under drain leases.
`room/`: Room-internal-CE classification exists in the Bookkeeper but not the SoT (KDoc claim
retained on store6; latent amplification verified, no demonstrated Room 3.0.0 trigger).
STABILITY: compose's "same graduation" clause names a kit that cannot apply to it. CI: mingwX64 +
iosX64 publish with zero test execution anywhere. Tests: the blessed realtime-on-MutationStore
composition still has no same-key interleaving coverage in-repo (the round-1 scratch scenarios
that exposed C-M7/C-M8 remain unpromoted).

---

## 4. Forbidden fixes (updated for store6 — supersedes round 1 §5)

1. **Never rerun** preserved classification evidence: main runs 32667414639/32667414624, store6
   run 31972447780, and the soak-lane's no-rerun conduct generally.
2. **Do not reclassify fetcher-thrown CancellationException in the engine** (FS-1-ratified,
   pinned by `FetcherContractTest`). Adapter-side rewrap (e.g. in the ktor kit) is permitted.
3. **Do not fix C-M8 by excluding `phase == ACKED`** — key on adoption-committed
   (`ack_neverReemitsOldBase` is pinned).
4. **Do not capture C-M7's freshness token at ack staging** — capture at drain-push send.
5. **C-M2's shield must be the outer merged `NonCancellable + HeldDriverAccess` frame** — an
   inner NonCancellable block does not close the boundary-return window.
6. **Do not "fix" llms.txt's 29 site-route links or trim them** — docs-site contract, sha-pinned.
7. **Do not invent target releases for opentelemetry/paging rows without a ruling** — the
   green-for idiom is design-ratified; the *decision* (ship vs name a target) is Matt's.
8. **Do not make `signalSink` lossy** — the projection consumer is per-key filtered.
9. Obsolete round-1 items (do not resurrect): the cross-namespace no-park rule (branch now parks
   all alias rejections); FetcherResult-must-stay-unannotated (branch annotated it).

## 5. Positive assurance (verified clean on store6)

- **Release lane:** allowlist == BOM == STABILITY alpha01 column, exactly; tag↔version bind;
  snapshot refusal; signing wiring; root-only VERSION_NAME (no module shadows);
  RELEASING.md walk produces a correct release modulo §2 items; swift-dumps lanes renamed and in
  sync; `coverage` correctly unpublished.
- **ktor kit:** timeout taxonomy correct in the pinned Ktor 3.5.2 on all targets
  (binary-verified `IOException` hierarchy + `unwrapCancellationException`); cannot emit
  `Error(cause=CE)` by construction; status taxonomy, etag/304 discipline, conditional-header
  hygiene, client lifecycle, tier annotations, strong transport-matrix tests — all clean.
- **file adapter:** "best-engineered adapter audited" — outer-NonCancellable Room pattern on
  every mutation/maintenance op (pinned by `FileCancellationTest`), temp-file + atomic rename
  with CRC32 envelope, corruption → quarantine → absent → refetch (no key poisoning), honest
  README on fsync limits, runs both official contract kits.
- **opentelemetry sink:** every handler wrapped (throwing meters/tracers tested), spans
  synthesized-complete at terminal time (no leak via cancellation), bounded interned namespaces
  (512 + overflow coalescing), engine call sites verified outside locks — containment is
  load-bearing and present.
- **mutations extensions:** conflict strategies cannot violate engine invariants (purity by
  signature; park bound engine-owned; merge-throw park verified); drain upholds one-pass/no-retry
  + backoff + no lease re-entrancy; the b2c9e4a8 deflake is genuine synchronization fixes, not
  weakened assertions; Meeseeks "bounded retry" claim verified against upstream 1.1.1 defaults.
- **CI:** all 20 library modules' tests genuinely executed at head across jvm/js/linux/wasm/
  android + apple lanes; every gate step ran; no `@Ignore` anywhere; exclusion debt limited to
  two disclosed sites.

## 6. Audit debt

- **CI workflow supply chain & secrets** (action pinning, token scopes, injection): still
  unaudited in both rounds.
- **Android consumer floors** (minSdk/compileSdk/AGP of shipped AARs): still unaudited.
- **Round-2 gap critic never ran** (session limit) — no second completeness sweep over the store6
  surface; candidates it would have probed: samples/demos building, devtools on this branch,
  benchmarks lane, store6-swift facade.
- **main branch is stale and diverging** — round 1's plan targeted it; a disposition (sync,
  archive, or banner) needs a ruling so the next audit doesn't repeat the scoping error.

## 7. Coverage map

| Round | Dimension | Result |
|---|---|---|
| 1 (main; code shared modulo diffs) | core-concurrency, mutations-engine, api-surface, platform, adapters, error-cancellation, resource-lifecycle | engine findings carried in §3; per-file re-grounded against the branch diff |
| 2 (store6) | release-eng | 6 findings; lane verified end-to-end |
| 2 | docs-claims | 6 findings (1 refuted); line-by-line truth audit |
| 2 | ktor-kit | 1 minor; CE question resolved at binary level |
| 2 | file-adapter | 3 minors; exception-atomicity clean |
| 2 | otel-sink | 4 findings (1 refuted); containment clean |
| 2 | mutations-extensions | 3 findings; invariants clean |
| 2 | ci-quality | 5 findings; head-green semantics audited |

---
*Round 1: workflow `wf_1c6492a9-b17` (48 agents, vs stale main; raw output
`/private/tmp/claude-501/.../tasks/wsqjz6vm2.output`). Round 2: workflow `wf_a99299f8-0f6`
(22 agents, vs store6 @ 9d68f4db; raw output `/private/tmp/claude-501/.../tasks/wsf3c1uhi.output`).
Companion plan: [2026-08-23-store6-alpha-revision-plan.md](../plans/2026-08-23-store6-alpha-revision-plan.md).*
