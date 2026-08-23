# Store6 Codecov Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire JVM code coverage (Kover → Codecov) for the store6 module family in the `matt-ramotar/Store6` fork, with the same wiring upstream-ready for the `MobileNativeFoundation/Store` `store6` branch.

**Architecture:** Kover is applied to every store6 library module via `Store6Conventions` (mirroring the existing Dokka pattern: tooling `compileOnly` + root buildscript `classpath`). A new non-published `store6-coverage` aggregator module merges the per-module reports into one JaCoCo-compatible XML via `kover(project(...))` dependencies. Root `ci.yml` — which already runs every store6 `jvmTest` and the bare `koverXmlReport` task — gains a second Codecov upload step under a dedicated `store6` flag, with the repo gate widened to include the fork. The pre-existing v5 upload is fixed as part of this (its report path has been broken since the Kover DSL migration).

**Tech Stack:** Kover 0.9.0-RC (already in `gradle/libs.versions.toml`), codecov-action@v4 (matches existing step), GitHub Actions, Gradle 8.11.1.

## Global Constraints

- Kover version stays `0.9.0-RC` (catalog `kover`, `gradle/libs.versions.toml:27`) — do NOT bump it in this work.
- Codecov action stays `codecov/codecov-action@v4` — matches the existing v5 step; upgrading is out of scope.
- Coverage is **JVM-target only** (Kover instruments JVM bytecode). Native/JS/wasm targets are uncovered; this is a documented limitation, not a gap to fix here.
- ci.yml edits are **pure additions or in-place single-line fixes** — never reorder or remove existing steps (repo convention: union-safe edits).
- The Kover agent must NOT attach to `jvmTest` when `-Pstore6.fullJvmSuite` is set — Lincheck does its own bytecode transformation (conflict risk) and that hosted lane already runs 2h40m–3h13m (`store6-full-jvm.yml:35`).
- `gh pr create` MUST pass `--repo matt-ramotar/Store6` (a bare call targets upstream — standing repo rule).
- Never rerun a red CI run to make it green; a red gets classified, not retried.
- The `store6-coverage` module must not be published: no publishing plugin, not added to any publish allowlist, no BCV.
- CI merge ordering: **Task 1 (HITL Codecov activation) must complete before the Task 5 branch merges** — the widened gate uploads on push to `main`, and an absent `CODECOV_TOKEN` with `fail_ci_if_error: true` would turn main red.
- Coverage generation/upload lives ONLY in root `ci.yml`. `store6.yml`, `store6-full-jvm.yml`, and `store6-benchmarks.yml` get zero coverage steps (single source of truth; the store6.yml linux lane would only duplicate ci.yml's jvmTest coverage).

## Background facts (verified 2026-08-23)

An executor needs these; do not re-derive them:

- `ci.yml`'s `build-and-test` job runs `./gradlew clean build koverXmlReport` on ubuntu — this already executes every store6 module's `jvmTest` (the census step at `ci.yml:56` proves store6-mutations results exist). The bare `koverXmlReport` invocation runs the task in **every project that has it**, so no build-step command change is needed when Kover appears in more modules.
- The existing Codecov upload (`ci.yml:76-87`) is gated `github.repository == 'MobileNativeFoundation/Store'` → never runs on the fork. Its `files: build/reports/kover/coverage.xml` points at a file nothing produces.
- `store/build.gradle.kts:41` has `xmlFile.set(file("${layout.buildDirectory}/reports/kover/coverage.xml"))` — string-interpolating a `DirectoryProperty` stringifies the provider, so the XML lands in a literal directory named `property(org.gradle.api.file.Directory, fixed(...))` under `store/`. This is the probable cause of the retired INFRA-CODECOV red class (baseline-30d.md row, 07-18).
- Store6 module → convention map: full variant (`org.mobilenativefoundation.store.store6.multiplatform`, declares `jvm()` itself): store6-core, store6-testing, store6-sqldelight, store6-compose, store6-graphql, store6-realtime, store6-mutations, store6-mutations-sqldelight, store6-mutations-testing, store6-devtools. Subset variant (module declares own targets, all three declare `jvm()`): store6-room, store6-paging-androidx, store6-devtools-inspector. Both variants funnel through `configureStore6Module()` in `Store6Conventions.kt`, so one edit covers all 13.
- Not aggregated (exercisers/harnesses, not library surface): store6-quickstart, store6-mutations-quickstart, all `*-sample` modules, store6-compose-demo, store6-devtools-demo, store6-benchmarks, store6-extension-probe, store6-swift-dumps-\* (`store6-swift/` is not a Gradle module at all — no `include` in settings.gradle).
- Tooling composite build (`tooling/settings.gradle.kts`) imports the root version catalog, so `libs.kover.gradle.plugin` (catalog line 63) resolves there.
- Root `build.gradle.kts` `subprojects` block early-returns for `name.startsWith("store6")` — the aggregator inherits that exemption (no ktlint/spotless) automatically.
- `settings.gradle` has `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")` — `kover(projects.store6Core)` accessors work.
- Upstream state: `refs/heads/store6` = `f45df778` = upstream `main` (2026-07-21 "Build modernization: Gradle 9.5, AGP 9.2, Kotlin 2.3"). **No store6 modules exist on that branch.** The stale mirror `refs/heads/matt-ramotar/store6/main` (`b6aa4d09`, 2025-02-05) is not an ancestor of fork main. Fork main diverged from upstream before the modernization commit.
- Upstream has a working `CODECOV_TOKEN` history (README badge token, INFRA-CODECOV red proves the step executes there).

---

### Task 1: HITL — Activate Codecov for the fork (Matt, not the agent)

**Files:** none (external configuration).

**Interfaces:**
- Consumes: nothing.
- Produces: `CODECOV_TOKEN` repository secret on `matt-ramotar/Store6`; the fork activated on codecov.io. Task 5's widened gate and Task 7's end-to-end verification depend on this existing.

These steps involve credentials and account actions, so Matt performs them directly. The agent's only role is to verify completion afterward.

- [ ] **Step 1 (Matt): Activate the repo on Codecov**

Log in at https://app.codecov.io with GitHub, open the `matt-ramotar` owner page, find `Store6`, and activate it. Copy the repository upload token from Settings → General.

- [ ] **Step 2 (Matt): Set the repo secret**

```bash
gh secret set CODECOV_TOKEN --repo matt-ramotar/Store6
```

(Paste the token when prompted — do not put it on the command line or in chat.)

- [ ] **Step 3 (Matt, optional but recommended): Install the Codecov GitHub App on the fork**

https://github.com/apps/codecov → Configure → select `matt-ramotar/Store6`. This enables PR comments/status checks (the `comment: layout: diff, files` block in `.codecov.yml` needs it).

- [ ] **Step 4 (agent): Verify the secret exists**

Run: `gh secret list --repo matt-ramotar/Store6`
Expected: a row named `CODECOV_TOKEN`.

No commit — nothing in-tree changed.

---

### Task 2: Fix the v5 Kover report path (unblocks the existing upstream upload)

**Files:**
- Modify: `store/build.gradle.kts:41`
- Modify: `.github/workflows/ci.yml:83` (the existing v5 upload's `files:` line)

**Interfaces:**
- Consumes: nothing.
- Produces: `store/build/reports/kover/coverage.xml` — the path the v5 upload step references from Task 5 onward.

- [ ] **Step 1: Demonstrate the failure (red)**

```bash
./gradlew :store:koverXmlReport --stacktrace
find store -name "coverage.xml" -not -path "*/node_modules/*"
```

Expected: BUILD SUCCESSFUL, but the XML path printed contains a literal directory named `property(org.gradle.api.file.Directory, fixed(...)` — NOT `store/build/reports/kover/coverage.xml`.

- [ ] **Step 2: Fix the property usage**

In `store/build.gradle.kts`, replace:

```kotlin
                xmlFile.set(file("${layout.buildDirectory}/reports/kover/coverage.xml"))
```

with:

```kotlin
                xmlFile.set(layout.buildDirectory.file("reports/kover/coverage.xml"))
```

- [ ] **Step 3: Point the upload at the real path**

In `.github/workflows/ci.yml`, in the `Upload Coverage to Codecov` step, replace:

```yaml
          files: build/reports/kover/coverage.xml
```

with:

```yaml
          files: store/build/reports/kover/coverage.xml
```

- [ ] **Step 4: Verify (green)**

```bash
find store -maxdepth 1 -name "property*" -exec rm -rf {} +
./gradlew :store:koverXmlReport
test -f store/build/reports/kover/coverage.xml && echo PATH-OK
find store -maxdepth 1 -name "property*" | wc -l
```

Expected: `PATH-OK`, and the final count prints `0` (no resurrected garbage directory).

- [ ] **Step 5: Commit**

```bash
git add store/build.gradle.kts .github/workflows/ci.yml
git commit -m "fix(coverage): resolve Kover XML path via layout.buildDirectory.file and align the Codecov files param"
```

---

### Task 3: Apply Kover to every store6 library module via Store6Conventions

**Files:**
- Modify: `tooling/plugins/build.gradle.kts` (dependencies block)
- Modify: `build.gradle.kts` (root buildscript dependencies block, ~line 24-34)
- Modify: `tooling/plugins/src/main/kotlin/org/mobilenativefoundation/store/tooling/plugins/Store6Conventions.kt`

**Interfaces:**
- Consumes: catalog entries `libs.kover.gradle.plugin` (toml line 63) and plugin id `org.jetbrains.kotlinx.kover`.
- Produces: every module applying either store6 convention plugin gains the Kover plugin → a `koverXmlReport` task and eligibility as a `kover(project(...))` dependency in Task 4. Instrumentation of `jvmTest` is disabled when `store6.fullJvmSuite` is set.

- [ ] **Step 1: Demonstrate absence (red)**

Run: `./gradlew :store6-core:koverXmlReport`
Expected: FAILS with `Cannot locate tasks that match ':store6-core:koverXmlReport'`.

- [ ] **Step 2: Put Kover on the tooling compile classpath**

In `tooling/plugins/build.gradle.kts`, in the `dependencies` block, after `compileOnly(libs.dokka.gradle.plugin)` add:

```kotlin
    compileOnly(libs.kover.gradle.plugin)
```

- [ ] **Step 3: Put Kover on the root build runtime classpath (Dokka pattern)**

In root `build.gradle.kts`, in the `buildscript { dependencies { ... } }` block, after `classpath(libs.dokka.gradle.plugin)` add:

```kotlin
        classpath(libs.kover.gradle.plugin)
```

(This is why `compileOnly` suffices in tooling — same mechanism Dokka already uses. The `store` module's `alias(libs.plugins.kover)` keeps working: same version, parent classloader wins.)

- [ ] **Step 4: Apply and configure Kover in `configureStore6Module()`**

In `Store6Conventions.kt`, extend the `with(pluginManager)` block:

```kotlin
    with(pluginManager) {
        apply("org.jetbrains.kotlin.multiplatform")
        apply("com.android.library")
        apply("com.vanniktech.maven.publish")
        apply("org.jetbrains.dokka")
        apply("org.jetbrains.kotlinx.binary-compatibility-validator")
        apply("org.jetbrains.kotlinx.kover")
    }
```

Immediately after that block (before the `ApiValidationExtension` configure), add:

```kotlin
    // Kover's on-the-fly agent must not attach to the scheduled full mutations JVM
    // suite: Lincheck performs its own bytecode transformation (agent-conflict risk)
    // and that hosted lane already runs ~3h. Only store6-full-jvm.yml passes
    // -Pstore6.fullJvmSuite; every other lane keeps coverage instrumentation.
    if (providers.gradleProperty("store6.fullJvmSuite").isPresent) {
        extensions.configure<KoverProjectExtension> {
            currentProject {
                instrumentation {
                    disabledForTestTasks.add("jvmTest")
                }
            }
        }
    }
```

Add the import:

```kotlin
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
```

If the 0.9.0-RC DSL rejects `disabledForTestTasks.add(...)`, open `KoverProjectExtension` sources from the IDE/dependency jar and use the 0.9 spelling of the same intent (the invariant to preserve: no Kover agent on `jvmTest` when `store6.fullJvmSuite` is present) — do not silently drop the guard.

- [ ] **Step 5: Verify tooling still compiles and its tests pass**

Run: `./gradlew -p tooling :plugins:build`
Expected: BUILD SUCCESSFUL (includes `SwiftDumpTasksPluginTest`).

- [ ] **Step 6: Verify a full-variant and a subset-variant module produce reports (green)**

```bash
./gradlew :store6-core:koverXmlReport :store6-room:koverXmlReport --stacktrace
test -f store6-core/build/reports/kover/report.xml && test -f store6-room/build/reports/kover/report.xml && echo REPORTS-OK
grep -m1 '<counter type="LINE"' store6-core/build/reports/kover/report.xml
```

Expected: `REPORTS-OK`, and a LINE counter element with `covered="N"` where N > 0.

- [ ] **Step 7: Verify the Lincheck guard wires up without breaking configuration**

Run: `./gradlew :store6-mutations:help -Pstore6.fullJvmSuite`
Expected: BUILD SUCCESSFUL (configuration-time proof; the scheduled lane is the runtime proof).

- [ ] **Step 8: Commit**

```bash
git add tooling/plugins/build.gradle.kts build.gradle.kts tooling/plugins/src/main/kotlin/org/mobilenativefoundation/store/tooling/plugins/Store6Conventions.kt
git commit -m "feat(coverage): apply Kover to store6 modules via conventions, agent off under fullJvmSuite"
```

---

### Task 4: `store6-coverage` aggregator module

**Files:**
- Modify: `settings.gradle` (after `include ':store6-benchmarks'`, line 52)
- Create: `store6-coverage/build.gradle.kts`

**Interfaces:**
- Consumes: Kover applied in the 13 library modules (Task 3); typesafe project accessors.
- Produces: `store6-coverage/build/reports/kover/report.xml` (Kover's default path — deliberately not customized, avoiding the Task-2 bug class). Task 5's upload step and Task 7's verification reference exactly this path.

- [ ] **Step 1: Register the module**

In `settings.gradle`, after `include ':store6-benchmarks'`, add:

```groovy
include ':store6-coverage'
```

- [ ] **Step 2: Create the aggregator build script**

Create `store6-coverage/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kover)
}

// Merges JVM coverage across the store6 production library modules into one
// JaCoCo-compatible XML (build/reports/kover/report.xml) for the Codecov upload
// in ci.yml (flag: store6). Quickstarts, samples, demos, benchmarks, the
// extension probe, and swift dumps are exercisers, not library surface, and are
// deliberately absent. Kover covers JVM-target tests only; native/JS runs are
// exercised by CI but contribute no coverage data. This module publishes
// nothing and must stay out of every publish allowlist.
dependencies {
    kover(projects.store6Core)
    kover(projects.store6Testing)
    kover(projects.store6Sqldelight)
    kover(projects.store6Compose)
    kover(projects.store6Room)
    kover(projects.store6PagingAndroidx)
    kover(projects.store6Graphql)
    kover(projects.store6Realtime)
    kover(projects.store6Mutations)
    kover(projects.store6MutationsSqldelight)
    kover(projects.store6MutationsTesting)
    kover(projects.store6Devtools)
    kover(projects.store6DevtoolsInspector)
}
```

(store6-testing and store6-mutations-testing are included on purpose: they are published library surface. Their fake-heavy paths are ignored Codecov-side by the existing `**/fake` rule.)

- [ ] **Step 3: Verify the merged report (green)**

```bash
./gradlew :store6-coverage:koverXmlReport --stacktrace
test -f store6-coverage/build/reports/kover/report.xml && echo MERGED-OK
grep -o '<package name="[^"]*"' store6-coverage/build/reports/kover/report.xml | sort -u | head -20
```

Expected: `MERGED-OK`; the package list spans multiple modules (at minimum packages under `org/mobilenativefoundation/store6/...` from core AND mutations AND at least one integration module). If only one module's packages appear, the aggregation is broken — stop and fix before proceeding.

Note: this run executes `jvmTest` for all 13 modules (Lincheck excluded by the default filter) — expect several minutes.

- [ ] **Step 4: Verify the module is invisible to publishing**

Run: `./gradlew :store6-coverage:tasks --all | grep -ci publish || true`
Expected: `0`.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle store6-coverage/build.gradle.kts
git commit -m "feat(coverage): store6-coverage aggregator merging Kover XML across store6 library modules"
```

---

### Task 5: CI wiring — store6 upload step, fork-widened gate, store6 branch triggers

**Files:**
- Modify: `.github/workflows/ci.yml` (triggers at lines 3-7; the v5 upload's `if:`/comment at lines 76-79; new step appended after line 87)

**Interfaces:**
- Consumes: `store6-coverage/build/reports/kover/report.xml` (Task 4); `CODECOV_TOKEN` secret (Task 1 on the fork; pre-existing upstream).
- Produces: Codecov uploads under flags `unittests` (v5) and `store6` on pushes and same-repo PRs, in both repos. The `store6` branch trigger makes the identical file upstream-ready.

No gradle-command change: the existing `./gradlew clean build koverXmlReport` already runs the aggregator's `koverXmlReport` (bare task name matches in all projects).

- [ ] **Step 1: Add `store6` to the workflow triggers**

Replace:

```yaml
on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]
```

with:

```yaml
on:
  push:
    branches: [ main, store6 ]
  pull_request:
    branches: [ main, store6 ]
```

(Inert on the fork until a `store6` branch exists; activates root CI for the upstream `store6` branch the moment this tree lands there.)

- [ ] **Step 2: Widen the v5 upload gate to the fork and refresh its comment**

Replace (ci.yml lines 77-79):

```yaml
        # The development fork has no Codecov token. Keep coverage generation required there,
        # while enforcing uploads for same-repo PRs and pushes in the upstream repository.
        if: ${{ github.repository == 'MobileNativeFoundation/Store' && (github.event.pull_request.head.repo.full_name == github.repository || github.event_name != 'pull_request') }}
```

with:

```yaml
        # Both the upstream repo and the matt-ramotar/Store6 development fork carry a
        # CODECOV_TOKEN secret. Fork-of-fork PRs cannot read secrets, so uploads are
        # enforced only for same-repo PRs and pushes.
        if: ${{ (github.repository == 'MobileNativeFoundation/Store' || github.repository == 'matt-ramotar/Store6') && (github.event.pull_request.head.repo.full_name == github.repository || github.event_name != 'pull_request') }}
```

- [ ] **Step 3: Append the store6 upload step**

Immediately after the existing `Upload Coverage to Codecov` step (after its `verbose: true` line), append:

```yaml
      - name: Upload Store6 Coverage to Codecov
        # Same gate as the v5 upload above. Flag `store6` keeps the store6 library
        # family separate from the v5 `unittests` flag in Codecov's UI and statuses.
        if: ${{ (github.repository == 'MobileNativeFoundation/Store' || github.repository == 'matt-ramotar/Store6') && (github.event.pull_request.head.repo.full_name == github.repository || github.event_name != 'pull_request') }}
        uses: codecov/codecov-action@v4
        with:
          token: ${{ secrets.CODECOV_TOKEN }}
          files: store6-coverage/build/reports/kover/report.xml
          flags: store6
          name: store6-coverage
          fail_ci_if_error: true
          verbose: true
```

- [ ] **Step 4: Verify workflow syntax**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml')); print('YAML-OK')"`
Expected: `YAML-OK`. (Real validation is the CI run in Task 7.)

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci(coverage): upload store6 aggregate to Codecov under flag store6; widen gate to fork; trigger on store6 branch"
```

---

### Task 6: `.codecov.yml` — flags with carryforward, store6 path hygiene

**Files:**
- Modify: `.codecov.yml`

**Interfaces:**
- Consumes: flags `unittests` and `store6` (Task 5).
- Produces: carryforward semantics (a PR touching only store6 doesn't tank the v5 flag's status, and vice versa) plus ignore rules keeping non-library store6 paths out of ratios.

- [ ] **Step 1: Rewrite `.codecov.yml`**

Replace the full file contents with:

```yaml
coverage:
  range: 70..80
  round: down
  precision: 2

comment:
  layout: diff, files

# Two upload flags exist: `unittests` (Store v5, store/ module) and `store6`
# (store6 library family, aggregated by :store6-coverage). Carryforward keeps
# each flag's last-known coverage when a commit uploads only the other flag.
flag_management:
  default_rules:
    carryforward: true

ignore:
  - "**/fake"
  - "**/commonTest"
  - "**/androidTest"
  - "**/iOSTest"
  - "**/jsTest"
  - "**/jvmTest"
  - "store6-quickstart/**"
  - "store6-mutations-quickstart/**"
  - "store6-benchmarks/**"
  - "store6-extension-probe/**"
  - "store6-swift-dumps/**"
  - "store6-compose-demo/**"
  - "store6-devtools-demo/**"
  - "**/sample/**"
```

(The store6 ignores are belt-and-braces — those modules never enter the aggregate — but they also shield the diff-coverage view when a PR touches sample code.)

- [ ] **Step 2: Validate against Codecov's validator (red would be a schema error)**

Run: `curl -sf --data-binary @.codecov.yml https://codecov.io/validate`
Expected: output beginning `Valid!` followed by the echoed config. Any error → fix before committing.

- [ ] **Step 3: Commit**

```bash
git add .codecov.yml
git commit -m "chore(codecov): flag carryforward and store6 path ignores"
```

---

### Task 7: Fork end-to-end verification (PR → CI → Codecov UI)

**Files:** none (verification + PR).

**Interfaces:**
- Consumes: everything above; `CODECOV_TOKEN` secret (Task 1 — **hard prerequisite for merging**).
- Produces: a merged PR on `matt-ramotar/Store6` main and a verified Codecov baseline with both flags.

- [ ] **Step 1: Push the branch and open the PR**

```bash
git push -u origin HEAD
gh pr create --repo matt-ramotar/Store6 --title "Codecov integration for the store6 module family" --body "$(cat <<'EOF'
Wires JVM coverage (Kover -> Codecov) for the store6 library family and fixes the long-broken v5 report path.

- Fix: store/ Kover XML landed in a literal `property(...)` directory (stringified DirectoryProperty); upload `files:` never matched -> probable root cause of the retired INFRA-CODECOV red class.
- Kover applied to all 13 store6 library modules via Store6Conventions (Dokka classpath pattern); agent disabled for jvmTest under -Pstore6.fullJvmSuite (Lincheck does its own instrumentation).
- New non-published `store6-coverage` aggregator merges per-module Kover XML.
- ci.yml: second upload under flag `store6`; gate widened to matt-ramotar/Store6; triggers include the `store6` branch (upstream-ready).
- .codecov.yml: flag carryforward + store6 path ignores.

Coverage is JVM-target only (Kover limitation). No changes to store6.yml / store6-full-jvm.yml lanes.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 2: Watch CI**

Run: `gh pr checks --repo matt-ramotar/Store6 --watch`
Expected: `build-and-test` green, including BOTH upload steps (check the run log: each codecov step prints a resulting report URL). If an upload step is red: classify (token? path? YAML?), fix forward — do not rerun.

- [ ] **Step 3: Verify flags via the Codecov API**

```bash
curl -s "https://api.codecov.io/api/v2/github/matt-ramotar/repos/Store6/flags/" | python3 -m json.tool
```

Expected: entries for `store6` and `unittests`.

- [ ] **Step 4: Verify paths resolve in the UI (Matt or agent via browser)**

Open `https://app.codecov.io/gh/matt-ramotar/Store6` → file tree shows `store6-core/src/commonMain/...` etc. with nonzero coverage. If paths show as unresolved package names instead of repo paths, add a `fixes` section to `.codecov.yml` in a follow-up commit — but Kover's JaCoCo-style `sourcefile` output normally resolves without it.

- [ ] **Step 5: Merge per repo convention, then confirm the main-push upload**

After review/approval, merge the PR. Then:

```bash
gh run list --repo matt-ramotar/Store6 --workflow ci.yml --branch main --limit 1
```

Expected: the post-merge run green with both uploads → main baseline established (carryforward now has a base for both flags).

- [ ] **Step 6 (Matt, optional): Linear close-out**

Record the merge on the Store 6 Roadmap project (linear.app/wanderinginc/project/store-6-roadmap-19a3596d74f6) per the merge/milestone convention.

---

### Task 8: Upstream — MobileNativeFoundation/Store `store6` branch

**Files:** none new — the wiring from Tasks 2–6 IS the upstream integration; it rides with the fork tree.

**Interfaces:**
- Consumes: merged fork main (Task 7); upstream `CODECOV_TOKEN` (pre-existing).
- Produces: working Codecov on the upstream `store6` branch at the moment the fork tree lands there, plus an optional standalone v5 fix PR to upstream `main`.

Reality check an executor must internalize: upstream's `store6` branch currently equals upstream `main` and contains **no store6 modules**. There is nothing to integrate against there today — a Codecov-only PR to that branch would wire coverage for code that doesn't exist on it. The correct integration is (a) everything in this plan being in-tree and gate-correct for `github.repository == 'MobileNativeFoundation/Store'`, which Tasks 5–6 guarantee, and (b) the deltas below.

- [ ] **Step 1: Verify upstream-readiness invariants in the merged tree (agent, on fork main)**

```bash
grep -c "github.repository == 'MobileNativeFoundation/Store'" .github/workflows/ci.yml
grep -c "branches: \[ main, store6 \]" .github/workflows/ci.yml
```

Expected: `3` (two upload gates + the untouched publish gate) and `2` (push + pull_request triggers). Both upload gates must name BOTH repos; the publish gate must still name only upstream.

- [ ] **Step 2 (Matt → upstream maintainers): Confirm upstream Codecov health**

Ask a MobileNativeFoundation/Store maintainer to confirm `CODECOV_TOKEN` is still a valid secret (the badge token in README.md:5 suggests yes) and that the Codecov GitHub App is installed on the org repo. No new Codecov configuration is needed for a non-default branch: uploads from `store6` appear under that branch in the UI, and PRs targeting `store6` get status/comment against the carried-forward base once the first baseline upload lands there.

- [ ] **Step 3 (HITL — rides the upstreaming cadence, not this plan's execution): Deliver the tree**

When the fork tree is next synced upstream (refresh of the `matt-ramotar/store6/main` mirror branch, currently stale at `b6aa4d09`/2025-02-05, or a PR into `store6` — whichever the upstreaming flow uses), the Codecov wiring travels with it. The first push to upstream `store6` after landing triggers root CI (Task 5 Step 1's trigger) and produces the baseline upload. **Any push to upstream branches is Matt-confirmed, never agent-initiated.**

- [ ] **Step 4 (optional, separable): Standalone v5 fix PR to upstream `main`**

Task 2's fix benefits upstream `main` today, independent of store6. If Matt wants it now:

```bash
git fetch upstream main
git switch -c fix/v5-kover-report-path FETCH_HEAD
```

Then **re-read `store/build.gradle.kts` and `.github/workflows/ci.yml` on that branch before editing** — upstream main is build-modernized (Gradle 9.5/AGP 9.2/Kotlin 2.3) and the files differ from the fork's; verify the `${layout.buildDirectory}` bug is still present there, apply the equivalent of Task 2 Steps 2–3, run `./gradlew :store:koverXmlReport` to verify, push the branch to `origin`, and open the PR with Matt's explicit go-ahead:

```bash
gh pr create --repo MobileNativeFoundation/Store --base main --head matt-ramotar:fix/v5-kover-report-path --title "Fix Kover XML report path feeding the Codecov upload" --body "$(cat <<'EOF'
String-interpolating `layout.buildDirectory` in store/build.gradle.kts stringifies the DirectoryProperty, so the Kover XML lands in a literal directory named `property(org.gradle.api.file.Directory, fixed(...))` instead of `store/build/reports/kover/coverage.xml` — and the Codecov upload's `files:` param (`build/reports/kover/coverage.xml`) matches nothing either way. This resolves the provider properly and points the upload at the real report, so uploads stop failing on a missing file.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

If the bug is already fixed on upstream main, skip and note it.

---

## Out of scope / follow-ups

- README coverage badge for the fork (would create upstream merge noise; the existing badge points at MobileNativeFoundation/Store and becomes accurate once uploads work there).
- Coverage thresholds/status gates (`status:` blocks, `target:`) — establish a baseline first; ratchets are a separate decision.
- codecov-action v5 upgrade.
- Non-JVM coverage (Kotlin/Native has no Kover support).
- Coverage in `store6.yml` lanes (deliberate single-lane decision — revisit only if ci.yml's ubuntu lane ever stops running store6 jvmTest).

---

## Execution erratum (2026-08-23, appended — never inserted)

The plan's "Background facts" were derived from the fork's `main` branch (`5a8c956b`). Matt corrected mid-execution: **the fork's development branch is `store6`** (GitHub default branch; PRs #68–#73 merged there), and `main` is stale. The first execution (commits `d45c6f8a..e297f04e`, based on `main`) was discarded and the work re-executed on `origin/store6` (`fe94a0cc`, "Alpha01 revisions"). Corrections relative to the plan text:

- **Baseline tree:** on `store6`, v5 is excised (`store`, `cache`, `multicast`, `rx2` removed; `rootProject.name = "Store6"`), and store6 modules drop the `store6-` prefix (`core`, `mutations`, …). Task 2 (v5 Kover path fix) is **moot on this branch** — it survives only as Task 8 Step 4's optional upstream-main PR, where the bug is still live.
- **Aggregator:** named `coverage` (no prefix convention), covering **19** convention-applying library modules — the plan's 13 plus `ktor`, `file`, `opentelemetry`, `mutations-drain`, `mutations-drain-meeseeks`, `mutations-conflicts`. Inclusion criterion sharpened to: applies the store6 conventions ⇒ aggregated. `bom` (constraints only), `store6-swift` (SPM facade), quickstarts, samples, demos, benchmarks, extension-probe, swift-dumps stay out.
- **ci.yml on `store6`:** already triggers on `[ main, store6 ]` (Task 5 Step 1 unnecessary); has **no** Codecov steps at all (the v5 upload left with v5), so only ONE upload step is added (flag `store6`) and the build step gains `koverXmlReport`. The publish job is tag/dispatch-gated with a module allowlist (PR #73) — `coverage` has no publish tasks and stays out of the allowlist.
- **Version-less plugin application (discovered in execution round 1):** with Kover on the root buildscript classpath, a versioned `plugins { alias(libs.plugins.kover) }` fails with "already on the classpath with an unknown version" — the aggregator applies the plugin by version-less id.
- **.codecov.yml ignores** use the unprefixed paths (`quickstart/**`, `benchmarks/**`, …).
- **Codecov default branch (new HITL step):** the Codecov API showed the repo tracking `branch: main`; with development on `store6`, Matt should set Default Branch = `store6` in Codecov → Settings → General for the dashboard to reflect the baseline.
- **Flake sighting during round-1 verification (registered, out of scope):** `RealtimeInvalidationTest.deleted_emitsLoadingThenRefetch[jvm]` failed once with a value-shaped ComparisonFailure under the newly-attached Kover agent, passed on re-execution; likely agent-as-unmasker of the fenced-clear race the test documents. Follow-up task filed. Round 2 (on `store6`) ran the same suite green.
