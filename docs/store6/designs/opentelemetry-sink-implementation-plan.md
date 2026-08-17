# OpenTelemetry sink — implementation plan

> **For agentic workers:** this plan is executed by one orchestrator agent and one worker
> sub-agent per task. Workers see only their own task plus the "Global constraints" and
> "Shared context" sections, which the orchestrator must include in every dispatch. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** ship the `org.mobilenativefoundation.store:opentelemetry` experimental artifact — an
OpenTelemetry sink over the `StoreTelemetry` seam — exactly as specified in
[opentelemetry-sink-design.md](./opentelemetry-sink-design.md), with tests, a runnable sample,
CI wiring, and documentation.

**Architecture:** one new KMP module `:opentelemetry` (targets `androidTarget` + `jvm`, created
`jvmAndroidMain` intermediate source set), one public class `OpenTelemetryStoreTelemetry`
mapping the six telemetry hooks to five metrics and an opt-in synthesized fetch span; one JVM
sample module `:opentelemetry-sample`. The design document is authoritative for every
behavioral question; this plan is authoritative for file contents and sequencing.

**Tech stack:** Kotlin 2.3.20, Gradle convention plugin
`org.mobilenativefoundation.store.store6.multiplatform.subset`, AGP 8.10.0, opentelemetry-java
1.65.0 (`opentelemetry-api`; SDK + in-memory exporters in tests and sample only),
binary-compatibility-validator 0.17.0, kotlinx-coroutines-test.

**Evidence base:** a complete skeleton of Tasks 1–2 plus two of the tests was already built and
run green inside this repository (design doc, Appendix A). Steps marked **[probe-verified]**
reproduce that result; a worker hitting a different outcome on those steps should suspect its
own diff first.

---

## 0. Orchestration: how to run this plan

### 0.1 Roles

- **Orchestrator (Fable):** dispatches one worker per task, enforces budgets, runs the review
  gate after every task, owns the final PR. The orchestrator never writes feature code itself;
  it may make single-line fixes during a review gate (typos, a missed import) rather than
  re-dispatching.
- **Workers (Sol 5.6 and Cursor Grok 4.6):** execute exactly one task per dispatch, in a fresh
  context, with the dispatch package from §0.5.

Model assignment guidance: Tasks 1–9 are code-and-build tasks — either model; alternate to
balance load. Tasks 10–11 are documentation-heavy — prefer Sol 5.6, and the dispatch must
instruct the worker to read and follow the repository skills
`plugins/internal/documentation/skills/documentation-discipline/SKILL.md` and
`plugins/internal/documentation/skills/code-documentation/SKILL.md` before writing prose.

### 0.2 Time budgets

Budgets are agent wall-clock minutes per task, measured from dispatch to the worker's final
report, including command runtime. They assume a warmed Gradle daemon after Task 1 (Task 1's
budget absorbs first-run dependency downloads).

| Task | Budget (min) | Depends on |
|---|---|---|
| 0 — Preflight | 10 (+ clock-paused SDK bootstrap if needed) | — |
| 1 — Module skeleton builds green | 25 | 0 |
| 2 — Sink implementation + API dumps | 30 | 1 |
| 3 — Metrics unit tests | 35 | 2 |
| 4 — Error mapping + privacy tests | 30 | 3 |
| 5 — Span + robustness tests | 45 | 4 |
| 6 — Concurrency + version-drift tests | 30 | 5 |
| 7 — Engine integration tests | 30 | 6 |
| 8 — Sample module | 30 | 2 |
| 9 — CI workflow wiring | 25 | 8 |
| 10 — README + SIGNALS | 45 | 2 |
| 11 — Stability table + final sweep | 25 | all |
| **Total worker budget** | **360** | |

Orchestrator review gates: 10 minutes of review work each, **excluding Gradle command
runtime**, 120 minutes total. Whole-effort budget: 480 agent-minutes plus command runtime.

**Budget rules (orchestrator enforces):**

1. At 100% of a task budget the worker must stop starting new work and report: done / remaining
   / blocker.
2. At 150% the orchestrator terminates the dispatch and triages: (a) re-dispatch the remainder
   as a narrower task to a fresh worker, (b) split the task, or (c) apply a descope marker
   (below). Never let a worker grind past 150%, but never kill a running Gradle invocation —
   apply triage after the command returns.
3. **Descope markers.** Steps tagged `[descope-allowed]` may be cut by the orchestrator at
   triage time and recorded as follow-ups in the PR description. No other step may be cut.
4. The clock pauses for environment bootstrap (Task 0 SDK install) and for repository-wide
   breakage unrelated to this work; the orchestrator verifies the latter by running the same
   command on an untouched checkout.

### 0.3 Execution order and parallelism

Sequential spine: 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 11. After Task 2 lands, two parallel lanes
may run concurrently with the spine, because they touch disjoint files:

- Lane B: Task 8 → Task 9 (sample + its `settings.gradle` include; then CI wiring, which
  references the sample).
- Lane C: Task 10 (README + SIGNALS; module docs only).

If running lanes in parallel, all lanes commit to the same feature branch; the orchestrator
serializes pushes (each worker starts from the fetched branch tip and rebases before pushing).
If that coordination overhead is not worth it, run everything sequentially — the spine order
already interleaves cheap and expensive tasks.

### 0.4 Review gate (orchestrator, after every task)

1. Re-run the task's verification commands verbatim; all must pass.
2. Diff review against the task's "Files" list — no files outside the list, no scope creep, no
   commented-out code, no TODOs. Files listed as "Create (via `apiDump`)" are expected
   generated outputs and count as in-list.
3. Check every new public declaration **in `opentelemetry/src/*Main` production sources**
   carries `@ExperimentalStoreApi` and appears in the regenerated API dumps. Tests and the
   sample are exempt (the sample's `public fun main()` matches the `realtime/sample`
   precedent).
4. For documentation tasks: run the three-pass review (accuracy of protected tokens; warrant
   for every claim; reader utility) required by the repository's documentation skills.
5. Commit and push if the worker did not.

### 0.5 Dispatch package (include in every worker prompt)

- The task text from this plan, verbatim, including its budget.
- The "Global constraints" and "Shared context" sections below, verbatim.
- The design document path: `docs/store6/designs/opentelemetry-sink-design.md` (workers should
  read §5–§8 minimum).
- The branch name, the expected predecessor commit (subject line or SHA), and the sync
  preamble: `git fetch origin <branch> && git checkout <branch> && git pull --rebase origin
  <branch>`, then verify the predecessor commit is present before starting.
- The environment line: `export ANDROID_HOME=<value reported by Task 0>` (shell exports do not
  survive across workers).
- The instruction: "Do not modify any file outside your task's Files list. If a step fails in a
  way this plan does not predict, stop and report rather than improvising around it."

### 0.6 Failure playbook (known signatures → first checks)

| Symptom | First check | Fix |
|---|---|---|
| `Namespace not specified … :opentelemetry` from AGP | `android { namespace = … }` block missing | Restore the block from Task 1's build file |
| Dokka: "Source sets 'android' and 'jvm' have the common source roots" | Sources laid out via duplicated `srcDir` instead of the `jvmAndroidMain` source set | Use the Task 1 build file exactly; the layout is load-bearing |
| `apiCheck` fails for project opentelemetry | Dumps not regenerated after an API change | `./gradlew :opentelemetry:apiDump` and commit all three files, including the empty `api/opentelemetry.klib.api` |
| klib-publication-check misses an artifact | Module missing from the publish command or `modules=(…)` list; or `opentelemetry/gradle.properties` missing (version falls back to the root's `5.1.0-SNAPSHOT`); or the publish itself failed — read the job log | Task 9's two coupled edits; Task 1 owns `gradle.properties` |
| Version-drift test fails: expected `6.0.0-SNAPSHOT`, actual `5.1.0-SNAPSHOT` | Build file used `providers.gradleProperty` (reads only the root's properties) | Use `findProperty("VERSION_NAME") as String` exactly as Task 1 specifies |
| Version-drift test fails with a null property | `tasks.withType<Test>` systemProperty block missing | Restore it from Task 1's build file |
| `docs-sync-guard` job fails on the PR | `STABILITY.md` is in `.github/docs-sync-sources.txt` | Add the `docs-sync-ack` label to the PR (Task 11) |
| `:opentelemetry-sample:run` never exits in CI | `sdk.close()` or `store.close()` unreached in `finally`; then check for other non-daemon threads | Sample must match Task 8 exactly (`withTimeout` + both closes) |
| Unresolved OTel symbols only in the IDE, Gradle green | Known degraded IDE analysis of the `jvmAndroidMain` source set | Ignore for this effort; Gradle is the authority (design §11) |

### 0.7 Branch, commits, PR

One feature branch off `store6`, named per the executing environment's branch policy. One
commit per task with the message given in the task. The PR body: link the design doc, list any
descoped steps as follow-ups, and note that the `docs-sync-ack` label is required (Task 11).
Check for a PR template before creating the PR.

---

## Global constraints

Copy into every dispatch.

1. Packages: `org.mobilenativefoundation.store6.opentelemetry` (module) and
   `org.mobilenativefoundation.store6.opentelemetry.sample` (sample). Android namespace:
   `org.mobilenativefoundation.store6.opentelemetry`.
2. Every public declaration **in `opentelemetry/src/*Main` production sources** carries
   `@ExperimentalStoreApi`. Implementing `StoreTelemetry` requires
   `@OptIn(DelicateStoreApi::class)` at the class or file level. Tests and the sample are
   exempt from the marker.
3. Telemetry hooks must never throw after construction: every override body is wrapped in
   `try { … } catch (_: Throwable) { }` and each catch contains exactly the comment
   `// Telemetry observers cannot participate in Store correctness.`
4. Never emit `StoreError.message`, `StoreError.cause`, or any stored value. Key canonical ids
   only on spans and only behind `keyAttributeOnSpans`.
5. Banned in `opentelemetry/src/*Main` production sources (CI-enforced): `runBlocking`,
   `GlobalScope`, `atomicfu`, `Channel`, `actor`. (`runBlocking` is fine in the sample.)
6. No references to `InternalStoreApi` or `org.mobilenativefoundation.store6.core.internal`
   anywhere in the module or sample (CI-enforced).
7. The v0 vocabulary is fixed by the design §6: instruments `store6.fetch.attempts`
   (`{attempt}`), `store6.fetch.duration` (`s`), `store6.serves` (`{serve}`),
   `store6.invalidations` (`{invalidation}`), `store6.clears` (`{clear}`); attributes
   `store6.namespace`, `store6.origin`, `store6.key`, `error.type`; span name `store6.fetch`;
   scope name `org.mobilenativefoundation.store6.opentelemetry`; overflow namespace value
   `overflow`. Do not rename anything.
8. Tests: `jvmTest` only; every coroutine test uses the file-private shadow
   `private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
   coroutineRunTest(timeout = 25.seconds, testBody = testBody)` with the import alias
   `import kotlinx.coroutines.test.runTest as coroutineRunTest`.
9. The module's main sources compile under `explicitApi()`: public API needs explicit
   visibility modifiers and explicit return types. Tests and the sample are outside
   `explicitApi()`.
10. Run all Gradle commands from the repository root with `ANDROID_HOME` set (the dispatch
    package carries the value).

## Shared context

- Seam: `core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/StoreTelemetry.kt`
  (six hooks; read its KDoc). Types: `StoreKey` (`namespace.value`, `canonicalId()`), `Origin`
  (`MEMORY`, `SOT`, `FETCHER`, `OVERLAY`), sealed `StoreError` (`Fetch`, `Persistence`,
  `Conversion`, `FreshnessUnsatisfiable`, `Conflict`, `Missing` — frozen set).
- Store construction in tests: `store<K, V> { fetcher { … }; telemetry(sink) }`, then
  `store.get(key)` / `store.stream(key, Freshness.MustBeFresh)` / `store.invalidate(key)` /
  `store.clear(key)` / `store.close()`. The builder's `fetcher` lambda is `suspend (K) -> V`;
  both `fetcher { "value" }` and `fetcher { key -> … }` are the same overload. The engine
  performs zero fetch retries by default, maps a thrown fetcher into `StoreError.Fetch`
  (embedding the throwable's message into `StoreError.message` — which this sink must never
  export), and superseded fetches have no terminal hook.
- `StoreResult.Error` exposes `val error: StoreError`. `StoreResult.Data<V>` exposes `value`
  and `origin`.
- Error factories for tests: `org.mobilenativefoundation.store6.testing.TestStoreResults`
  (`fetchError(message, cause = null)`, `persistenceError(message)`,
  `conversionError(message)`, `freshnessUnsatisfiable(message)`, `conflict(serverMeta,
  message)`, `missing(key, message)`).

---

## Task 0 — Preflight

**Budget: 10 minutes; the SDK install, if needed, pauses the clock.** Worker: any.

**Files:** none (read-only).

- [ ] **Step 0.1** Verify the toolchain from the repository root:

```bash
git status --short            # expect: clean (or only this plan's docs)
echo $ANDROID_HOME            # expect: a path; if empty, install per below
./gradlew --version           # expect: wrapper resolves; JDK 17+ as launcher JVM
```

If `ANDROID_HOME` is empty, install command-line tools (clock paused; the exact recipe that
worked in the probe environment; the subshell keeps the working directory at the repo root):

```bash
(
  mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools &&
  curl -sL -o tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" &&
  unzip -q tools.zip && rm tools.zip && mv cmdline-tools latest &&
  yes | latest/bin/sdkmanager --licenses &&
  latest/bin/sdkmanager --install "platforms;android-36" "build-tools;35.0.0" "platform-tools"
)
export ANDROID_HOME=~/android-sdk
```

- [ ] **Step 0.2** Sanity-compile an existing module to warm the daemon and prove the
  environment: `./gradlew :devtools:compileKotlinJvm --console=plain`. Expected: BUILD
  SUCCESSFUL.
- [ ] **Step 0.3** Report: toolchain versions, the exact `ANDROID_HOME` value (the
  orchestrator puts it into every later dispatch), whether the SDK was installed, elapsed
  time.

---

## Task 1 — Module skeleton builds green

**Budget: 25 minutes** (absorbs first-run dependency downloads). Worker: any.

**Files:**
- Modify: `settings.gradle` (one include line)
- Modify: `gradle/libs.versions.toml` (one version, three libraries)
- Create: `opentelemetry/build.gradle.kts`
- Create: `opentelemetry/gradle.properties`
- Create: `opentelemetry/src/androidMain/AndroidManifest.xml`
- Create: `opentelemetry/src/jvmAndroidMain/kotlin/org/mobilenativefoundation/store6/opentelemetry/OpenTelemetryStoreTelemetry.kt` (placeholder — replaced in Task 2)
- Create (via `apiDump`): `opentelemetry/api/jvm/opentelemetry.api`,
  `opentelemetry/api/android/opentelemetry.api`, `opentelemetry/api/opentelemetry.klib.api`
  (the third is empty by design — commit it; `klibApiCheck` requires it)

**Interfaces produced:** a configuring, compiling `:opentelemetry` project every later task
builds on; catalog aliases `libs.opentelemetry.api`, `libs.opentelemetry.sdk`,
`libs.opentelemetry.sdk.testing`.

- [ ] **Step 1.1** In `settings.gradle`, insert immediately after the
  `project(':realtime-sample').projectDir = file('realtime/sample')` line:

```groovy
include ':opentelemetry'
```

(The `:opentelemetry-sample` include is added in Task 8 together with the sample itself.)

- [ ] **Step 1.2** In `gradle/libs.versions.toml`: under `[versions]` add

```toml
opentelemetry = "1.65.0"
```

and under `[libraries]` add

```toml
opentelemetry-api = { group = "io.opentelemetry", name = "opentelemetry-api", version.ref = "opentelemetry" }
opentelemetry-sdk = { group = "io.opentelemetry", name = "opentelemetry-sdk", version.ref = "opentelemetry" }
opentelemetry-sdk-testing = { group = "io.opentelemetry", name = "opentelemetry-sdk-testing", version.ref = "opentelemetry" }
```

- [ ] **Step 1.3** Create `opentelemetry/gradle.properties` (load-bearing: without it, the
  module publishes as the root's `5.1.0-SNAPSHOT`):

```properties
VERSION_NAME=6.0.0-SNAPSHOT
POM_NAME=opentelemetry
POM_ARTIFACT_ID=opentelemetry
```

- [ ] **Step 1.4** Create `opentelemetry/src/androidMain/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 1.5** Create `opentelemetry/build.gradle.kts` exactly: **[probe-verified except
  the `tasks.withType<Test>` block; its `findProperty` choice is separately measured — the
  module's `gradle.properties` overrides the root's for project properties, while
  `providers.gradleProperty` reads only the root's `VERSION_NAME` (5.1.0-SNAPSHOT)]**

```kotlin
plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform.subset")
}

kotlin {
    // JVM-family subset: this module builds on opentelemetry-java, which publishes JVM
    // bytecode only. androidTarget() is mandatory under the subset plugin. The remaining
    // Store6 targets are additive later via a multiplatform OpenTelemetry API once one is
    // stable; see README "Targets".
    androidTarget()
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core)
            }
        }
        // Kotlin's hierarchy template has no JVM+Android intermediate; this created source
        // set is compiled per target and gets no metadata compilation, which is what lets it
        // hold a Java-only dependency.
        val jvmAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                api(libs.opentelemetry.api)
            }
        }
        val jvmMain by getting {
            dependsOn(jvmAndroidMain)
        }
        val androidMain by getting {
            dependsOn(jvmAndroidMain)
        }
        val jvmTest by getting {
            dependencies {
                implementation(projects.testing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.opentelemetry.sdk.testing)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.opentelemetry"
}

tasks.withType<Test>().configureEach {
    // The instrumentation-scope version constant must match this module's published version;
    // InstrumentationScopeVersionTest reads this property. findProperty is load-bearing: the
    // module's gradle.properties overrides the root's for project properties, while
    // providers.gradleProperty would read only the root's VERSION_NAME.
    systemProperty(
        "store6.opentelemetry.versionName",
        findProperty("VERSION_NAME") as String,
    )
}
```

- [ ] **Step 1.6** Create the placeholder source file (Task 2 replaces it) at
  `opentelemetry/src/jvmAndroidMain/kotlin/org/mobilenativefoundation/store6/opentelemetry/OpenTelemetryStoreTelemetry.kt`:

```kotlin
package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry

/** Placeholder; replaced by the full sink in the next change. */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class OpenTelemetryStoreTelemetry(
    @Suppress("unused") private val openTelemetry: OpenTelemetry,
) : StoreTelemetry
```

- [ ] **Step 1.7** Build and dump: `./gradlew :opentelemetry:build :opentelemetry:apiDump
  --console=plain`. Expected: BUILD SUCCESSFUL; the three files listed under "Create (via
  `apiDump`)" exist. **[probe-verified]**
- [ ] **Step 1.8** Dokka must pass on this layout: `./gradlew :opentelemetry:dokkaGenerate
  --console=plain`. Expected: BUILD SUCCESSFUL. If it fails with "common source roots", the
  layout was changed — see the failure playbook. **[probe-verified]**
- [ ] **Step 1.9** Commit (including the `api/` files):
  `Scaffold the opentelemetry extension module`

---

## Task 2 — Sink implementation + API dumps

**Budget: 30 minutes.** Worker: any.

**Files:**
- Replace: `opentelemetry/src/jvmAndroidMain/kotlin/org/mobilenativefoundation/store6/opentelemetry/OpenTelemetryStoreTelemetry.kt`
- Regenerate (via `apiDump`): the three files under `opentelemetry/api/`

**Interfaces produced (later tasks rely on these exact names):** public class
`OpenTelemetryStoreTelemetry(openTelemetry: OpenTelemetry, emitSpans: Boolean = false,
keyAttributeOnSpans: Boolean = false, maxNamespaces: Int = 512, extraAttributes: Attributes =
Attributes.empty())`; internal `storeErrorType(error: StoreError): String`; internal constants
`INSTRUMENTATION_SCOPE_NAME`, `INSTRUMENTATION_SCOPE_VERSION`, `SPAN_NAME`,
`OVERFLOW_NAMESPACE`.

- [ ] **Step 2.1** Replace the placeholder file with the full implementation:
  **[probe-verified: compiled on both targets and exercised against the real SDK]**

```kotlin
package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * OpenTelemetry sink over the [StoreTelemetry] seam (a freeze candidate).
 *
 * One builder line installs it: `telemetry(OpenTelemetryStoreTelemetry(openTelemetry))`.
 * Metrics are always recorded; fetch spans are opt-in via [emitSpans]. The instrument names,
 * units, attributes, and span shape are the v0 vocabulary in `SIGNALS.md` (versioned but
 * experimental, and not the Store 6.1 wire format).
 *
 * Identity only: stored values never cross the telemetry seam, and this sink never emits
 * [StoreError] messages or causes. Key canonical ids appear only on spans and only when
 * [keyAttributeOnSpans] is enabled. Namespace attribute cardinality is bounded by
 * [maxNamespaces]; once the bound is reached, further namespaces record under the coalesced
 * value `overflow`.
 *
 * Providers are read from [openTelemetry] once, at construction, so the sink must be
 * constructed after the app's SDK; `OpenTelemetry.noop()` is a legal argument and records
 * nothing. After construction, handlers never throw: failures inside the OpenTelemetry
 * implementation are discarded so they cannot escape a telemetry handler. Handlers stay
 * non-blocking only if the supplied SDK configuration is non-blocking (batching span
 * processors, prompt samplers); that obligation transfers to the app the same way a logger
 * emit callback's promptness does. When telemetry is unset, the engine's null fast path
 * remains untouched.
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class OpenTelemetryStoreTelemetry(
    openTelemetry: OpenTelemetry,
    private val emitSpans: Boolean = false,
    private val keyAttributeOnSpans: Boolean = false,
    private val maxNamespaces: Int = 512,
    private val extraAttributes: Attributes = Attributes.empty(),
) : StoreTelemetry {
    init {
        require(maxNamespaces > 0) { "maxNamespaces must be greater than zero, was $maxNamespaces." }
    }

    private val meter = openTelemetry.meterProvider
        .meterBuilder(INSTRUMENTATION_SCOPE_NAME)
        .setInstrumentationVersion(INSTRUMENTATION_SCOPE_VERSION)
        .build()

    private val fetchAttempts = meter.counterBuilder("store6.fetch.attempts")
        .setUnit("{attempt}")
        .setDescription("Fetch attempts observed at fetch-coroutine start.")
        .build()

    private val fetchDuration = meter.histogramBuilder("store6.fetch.duration")
        .setUnit("s")
        .setDescription("Duration of settled fetches.")
        .setExplicitBucketBoundariesAdvice(EXPLICIT_BUCKET_BOUNDARIES)
        .build()

    private val serves = meter.counterBuilder("store6.serves")
        .setUnit("{serve}")
        .setDescription("Successful public serves.")
        .build()

    private val invalidations = meter.counterBuilder("store6.invalidations")
        .setUnit("{invalidation}")
        .setDescription("Successful invalidations.")
        .build()

    private val clears = meter.counterBuilder("store6.clears")
        .setUnit("{clear}")
        .setDescription("Successful clears.")
        .build()

    private val tracer: Tracer? = if (emitSpans) {
        openTelemetry.tracerProvider
            .tracerBuilder(INSTRUMENTATION_SCOPE_NAME)
            .setInstrumentationVersion(INSTRUMENTATION_SCOPE_VERSION)
            .build()
    } else {
        null
    }

    private val attributesByNamespace = ConcurrentHashMap<String, NamespaceAttributes>()
    private val overflowAttributes = NamespaceAttributes(OVERFLOW_NAMESPACE, extraAttributes)

    override fun onFetchStarted(key: StoreKey) {
        try {
            fetchAttempts.add(1, namespaceAttributes(key).base)
        } catch (_: Throwable) {
            // Telemetry observers cannot participate in Store correctness.
        }
    }

    override fun onFetchSucceeded(
        key: StoreKey,
        duration: Duration,
    ) {
        try {
            if (!duration.isFinite() || duration.isNegative()) return
            val interned = namespaceAttributes(key)
            fetchDuration.record(duration.toDouble(DurationUnit.SECONDS), interned.base)
            recordSpan(key, interned, duration, errorType = null)
        } catch (_: Throwable) {
            // Telemetry observers cannot participate in Store correctness.
        }
    }

    override fun onFetchFailed(
        key: StoreKey,
        error: StoreError,
        duration: Duration,
    ) {
        try {
            if (!duration.isFinite() || duration.isNegative()) return
            val interned = namespaceAttributes(key)
            val errorType = storeErrorType(error)
            fetchDuration.record(
                duration.toDouble(DurationUnit.SECONDS),
                interned.byErrorType.getValue(errorType),
            )
            recordSpan(key, interned, duration, errorType)
        } catch (_: Throwable) {
            // Telemetry observers cannot participate in Store correctness.
        }
    }

    override fun onServe(
        key: StoreKey,
        origin: Origin,
    ) {
        try {
            serves.add(1, namespaceAttributes(key).byOrigin.getValue(origin))
        } catch (_: Throwable) {
            // Telemetry observers cannot participate in Store correctness.
        }
    }

    override fun onInvalidated(key: StoreKey) {
        try {
            invalidations.add(1, namespaceAttributes(key).base)
        } catch (_: Throwable) {
            // Telemetry observers cannot participate in Store correctness.
        }
    }

    override fun onCleared(key: StoreKey) {
        try {
            clears.add(1, namespaceAttributes(key).base)
        } catch (_: Throwable) {
            // Telemetry observers cannot participate in Store correctness.
        }
    }

    private fun namespaceAttributes(key: StoreKey): NamespaceAttributes {
        val namespace = key.namespace.value
        val cached = attributesByNamespace[namespace]
        if (cached != null) return cached
        // Approximate bound: concurrent first observations of distinct namespaces can each
        // pass this check, so the table can exceed maxNamespaces by the number of racing
        // namespaces. It is a memory and cardinality guard, not an exact quota.
        if (attributesByNamespace.size >= maxNamespaces) return overflowAttributes
        return attributesByNamespace.computeIfAbsent(namespace) {
            NamespaceAttributes(it, extraAttributes)
        }
    }

    private fun recordSpan(
        key: StoreKey,
        interned: NamespaceAttributes,
        duration: Duration,
        errorType: String?,
    ) {
        val tracer = tracer ?: return
        val endEpochNanos = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
        val startEpochNanos = (endEpochNanos - duration.inWholeNanoseconds).coerceAtLeast(0L)
        val builder = tracer.spanBuilder(SPAN_NAME)
            .setNoParent()
            .setSpanKind(SpanKind.INTERNAL)
            .setStartTimestamp(startEpochNanos, TimeUnit.NANOSECONDS)
            .setAllAttributes(
                if (errorType == null) interned.base else interned.byErrorType.getValue(errorType),
            )
        if (keyAttributeOnSpans) {
            builder.setAttribute(STORE_KEY_ATTRIBUTE, key.canonicalId())
        }
        val span = builder.startSpan()
        try {
            if (errorType != null) span.setStatus(StatusCode.ERROR)
        } finally {
            span.end(endEpochNanos, TimeUnit.NANOSECONDS)
        }
    }
}

private class NamespaceAttributes(
    namespace: String,
    extraAttributes: Attributes,
) {
    val base: Attributes = Attributes.builder()
        .putAll(extraAttributes)
        .put(STORE_NAMESPACE_ATTRIBUTE, namespace)
        .build()

    // Built from the runtime enum, so an Origin grown in a future core version is covered
    // without recompiling this module.
    val byOrigin: Map<Origin, Attributes> = Origin.entries.associateWith { origin ->
        Attributes.builder()
            .putAll(base)
            .put(STORE_ORIGIN_ATTRIBUTE, origin.name)
            .build()
    }

    val byErrorType: Map<String, Attributes> = ERROR_TYPES.associateWith { errorType ->
        Attributes.builder()
            .putAll(base)
            .put(ERROR_TYPE_ATTRIBUTE, errorType)
            .build()
    }
}

// The six literals are the StoreError variant names, frozen for the 6.x major, and identical
// to the v0 error names in devtools/EVENTS.md.
internal fun storeErrorType(error: StoreError): String =
    when (error) {
        is StoreError.Fetch -> "Fetch"
        is StoreError.Persistence -> "Persistence"
        is StoreError.Conversion -> "Conversion"
        is StoreError.FreshnessUnsatisfiable -> "FreshnessUnsatisfiable"
        is StoreError.Conflict -> "Conflict"
        is StoreError.Missing -> "Missing"
    }

internal const val INSTRUMENTATION_SCOPE_NAME: String =
    "org.mobilenativefoundation.store6.opentelemetry"
internal const val INSTRUMENTATION_SCOPE_VERSION: String = "6.0.0-SNAPSHOT"
internal const val SPAN_NAME: String = "store6.fetch"
internal const val OVERFLOW_NAMESPACE: String = "overflow"

private val STORE_NAMESPACE_ATTRIBUTE: AttributeKey<String> =
    AttributeKey.stringKey("store6.namespace")
private val STORE_ORIGIN_ATTRIBUTE: AttributeKey<String> =
    AttributeKey.stringKey("store6.origin")
private val STORE_KEY_ATTRIBUTE: AttributeKey<String> =
    AttributeKey.stringKey("store6.key")
private val ERROR_TYPE_ATTRIBUTE: AttributeKey<String> =
    AttributeKey.stringKey("error.type")

private val ERROR_TYPES: List<String> = listOf(
    "Fetch",
    "Persistence",
    "Conversion",
    "FreshnessUnsatisfiable",
    "Conflict",
    "Missing",
)

// The semconv HTTP client duration boundaries; advice the SDK honors under the default
// aggregation, and a registered view overrides.
private val EXPLICIT_BUCKET_BOUNDARIES: List<Double> =
    listOf(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0)
```

- [ ] **Step 2.2** `./gradlew :opentelemetry:apiDump :opentelemetry:build --console=plain`.
  Expected: BUILD SUCCESSFUL; `opentelemetry/api/jvm/opentelemetry.api` now contains the
  class with its five-argument constructor and six overrides. **[probe-verified]**
- [ ] **Step 2.3** `./gradlew :opentelemetry:dokkaGenerate --console=plain`. Expected: BUILD
  SUCCESSFUL.
- [ ] **Step 2.4** Commit: `OpenTelemetry sink over the StoreTelemetry seam`

---

## Task 3 — Metrics unit tests

**Budget: 35 minutes.** Worker: any.

**Files:**
- Create: `opentelemetry/src/jvmTest/kotlin/org/mobilenativefoundation/store6/opentelemetry/OpenTelemetryStoreTelemetryMetricsTest.kt`

**Interfaces consumed:** the Task 2 class and constants. Each test file declares its own
`TestKey` — test files stay self-contained.

- [ ] **Step 3.1** Write the test file:

```kotlin
@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class OpenTelemetryStoreTelemetryMetricsTest {
    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    private class Harness {
        val metricReader: InMemoryMetricReader = InMemoryMetricReader.create()
        val sdk: OpenTelemetrySdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .build()

        fun metrics(): Map<String, MetricData> =
            metricReader.collectAllMetrics().associateBy { it.name }

        fun close(): Unit = sdk.close()
    }

    private val namespaceKey = AttributeKey.stringKey("store6.namespace")
    private val originKey = AttributeKey.stringKey("store6.origin")
    private val errorTypeKey = AttributeKey.stringKey("error.type")

    @Test
    fun countersRecordUnderTheV0NamesUnitsAndNamespaceAttribute() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk)
        val key = TestKey("users", "user-1")

        sink.onFetchStarted(key)
        sink.onFetchStarted(key)
        sink.onInvalidated(key)
        sink.onCleared(key)

        val metrics = harness.metrics()
        val attempts = metrics.getValue("store6.fetch.attempts")
        assertEquals("{attempt}", attempts.unit)
        val attemptsPoint = attempts.longSumData.points.single()
        assertEquals(2L, attemptsPoint.value)
        assertEquals("users", attemptsPoint.attributes.get(namespaceKey))
        val invalidations = metrics.getValue("store6.invalidations")
        assertEquals("{invalidation}", invalidations.unit)
        val invalidationsPoint = invalidations.longSumData.points.single()
        assertEquals(1L, invalidationsPoint.value)
        assertEquals("users", invalidationsPoint.attributes.get(namespaceKey))
        val clears = metrics.getValue("store6.clears")
        assertEquals("{clear}", clears.unit)
        val clearsPoint = clears.longSumData.points.single()
        assertEquals(1L, clearsPoint.value)
        assertEquals("users", clearsPoint.attributes.get(namespaceKey))
        assertEquals(INSTRUMENTATION_SCOPE_NAME, attempts.instrumentationScopeInfo.name)
        assertEquals(INSTRUMENTATION_SCOPE_VERSION, attempts.instrumentationScopeInfo.version)
        harness.close()
    }

    @Test
    fun servesRecordOneSeriesPerOrigin() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk)
        val key = TestKey("users", "user-1")

        Origin.entries.forEach { origin -> sink.onServe(key, origin) }
        sink.onServe(key, Origin.FETCHER)

        val serves = harness.metrics().getValue("store6.serves")
        assertEquals("{serve}", serves.unit)
        val byOrigin = serves.longSumData.points.associateBy { it.attributes.get(originKey) }
        assertEquals(Origin.entries.map { it.name }.toSet(), byOrigin.keys)
        assertEquals(2L, byOrigin.getValue("FETCHER").value)
        assertEquals(1L, byOrigin.getValue("MEMORY").value)
        harness.close()
    }

    @Test
    fun successDurationRecordsSecondsWithoutErrorTypeAndWithAdvisedBuckets() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk)

        sink.onFetchSucceeded(TestKey("users", "user-1"), 250.milliseconds)

        val duration = harness.metrics().getValue("store6.fetch.duration")
        assertEquals("s", duration.unit)
        val point = duration.histogramData.points.single()
        assertEquals(1L, point.count)
        assertEquals(0.25, point.sum, 1e-9)
        assertEquals("users", point.attributes.get(namespaceKey))
        assertNull(point.attributes.get(errorTypeKey))
        assertEquals(
            listOf(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0),
            point.boundaries,
        )
        harness.close()
    }

    @Test
    fun extraAttributesMergeIntoEveryPointAndSinkOwnedKeysWin() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(
            harness.sdk,
            extraAttributes = Attributes.builder()
                .put(AttributeKey.stringKey("store6.store"), "users-store")
                .put(namespaceKey, "attacker-controlled")
                .build(),
        )
        val key = TestKey("users", "user-1")

        sink.onFetchStarted(key)
        sink.onInvalidated(key)

        val metrics = harness.metrics()
        val storeKey = AttributeKey.stringKey("store6.store")
        listOf("store6.fetch.attempts", "store6.invalidations").forEach { name ->
            val point = metrics.getValue(name).longSumData.points.single()
            assertEquals("users-store", point.attributes.get(storeKey))
            assertEquals("users", point.attributes.get(namespaceKey))
        }
        harness.close()
    }

    @Test
    fun namespacesBeyondMaxNamespacesCoalesceToOverflow() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk, maxNamespaces = 2)

        sink.onFetchStarted(TestKey("ns-a", "k"))
        sink.onFetchStarted(TestKey("ns-b", "k"))
        sink.onFetchStarted(TestKey("ns-c", "k"))
        sink.onFetchStarted(TestKey("ns-d", "k"))

        val points = harness.metrics()
            .getValue("store6.fetch.attempts").longSumData.points
        val byNamespace = points.associate { it.attributes.get(namespaceKey) to it.value }
        assertEquals(mapOf("ns-a" to 1L, "ns-b" to 1L, "overflow" to 2L), byNamespace)
        harness.close()
    }

    @Test
    fun noopOpenTelemetryIsLegalAndRecordsNothing() {
        val sink = OpenTelemetryStoreTelemetry(OpenTelemetry.noop(), emitSpans = true)
        val key = TestKey("users", "user-1")

        sink.onFetchStarted(key)
        sink.onFetchSucceeded(key, 10.milliseconds)
        sink.onServe(key, Origin.FETCHER)
        sink.onInvalidated(key)
        sink.onCleared(key)
        // Completing without an exception is the assertion; noop exports nothing.
    }
}
```

- [ ] **Step 3.2** Run: `./gradlew :opentelemetry:jvmTest --console=plain`. Expected: BUILD
  SUCCESSFUL, 6 new tests pass. If the `boundaries` assertion fails, report the actual
  boundaries — do not delete the assertion (it validates the advice is honored by the default
  aggregation).
- [ ] **Step 3.3** Commit: `Cover the v0 metric vocabulary with unit tests`

---

## Task 4 — Error mapping + privacy tests

**Budget: 30 minutes.** Worker: any.

**Files:**
- Create: `opentelemetry/src/jvmTest/kotlin/org/mobilenativefoundation/store6/opentelemetry/OpenTelemetryStoreTelemetryErrorMappingTest.kt`

- [ ] **Step 4.1** Write the test file. Metrics are collected **once** into a snapshot; the
  privacy sweep walks exported attribute values structurally and also checks the rendered
  strings as a belt:

```kotlin
@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

class OpenTelemetryStoreTelemetryErrorMappingTest {
    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    private class ErrorCase(
        val name: String,
        val error: StoreError,
    )

    @Test
    fun allSixVariantsMapToTheirLiteralErrorTypeAndLeakNoDiagnostics() {
        val key = TestKey("users", "user-1")
        val diagnostics = listOf(
            "fetch diagnostic",
            "fetch cause diagnostic",
            "persistence diagnostic",
            "conversion diagnostic",
            "freshness diagnostic",
            "conflict diagnostic",
            "missing diagnostic",
        )
        val cases = listOf(
            ErrorCase(
                "Fetch",
                TestStoreResults.fetchError(diagnostics[0], IllegalStateException(diagnostics[1])),
            ),
            ErrorCase("Persistence", TestStoreResults.persistenceError(diagnostics[2])),
            ErrorCase("Conversion", TestStoreResults.conversionError(diagnostics[3])),
            ErrorCase("FreshnessUnsatisfiable", TestStoreResults.freshnessUnsatisfiable(diagnostics[4])),
            ErrorCase("Conflict", TestStoreResults.conflict(null, diagnostics[5])),
            ErrorCase("Missing", TestStoreResults.missing(key, diagnostics[6])),
        )
        assertEquals(cases.map { it.name }, cases.map { storeErrorType(it.error) })

        val metricReader = InMemoryMetricReader.create()
        val spanExporter = InMemorySpanExporter.create()
        val sdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build(),
            )
            .build()
        val sink = OpenTelemetryStoreTelemetry(sdk, emitSpans = true)

        cases.forEachIndexed { index, case ->
            sink.onFetchFailed(key, case.error, (index + 1).milliseconds)
        }

        // Single snapshot: assert against one collection so a temporality change can never
        // make later reads empty.
        val metrics = metricReader.collectAllMetrics()
        val spans = spanExporter.finishedSpanItems

        val errorTypeKey = AttributeKey.stringKey("error.type")
        val points = metrics.single { it.name == "store6.fetch.duration" }.histogramData.points
        assertEquals(
            cases.map { it.name }.toSet(),
            points.map { it.attributes.get(errorTypeKey) }.toSet(),
        )
        points.forEach { point -> assertEquals(1L, point.count) }

        assertEquals(cases.size, spans.size)
        spans.forEach { span ->
            assertEquals(StatusCode.ERROR, span.status.statusCode)
            assertEquals("", span.status.description)
        }
        assertEquals(
            cases.map { it.name }.toSet(),
            spans.map { it.attributes.get(errorTypeKey) }.toSet(),
        )

        // Structured sweep of everything exported, plus rendered strings as a belt.
        val exportedValues = buildList {
            metrics.forEach { metric ->
                metric.longSumData.points.forEach { point ->
                    point.attributes.forEach { _, value -> add(value.toString()) }
                }
                metric.histogramData.points.forEach { point ->
                    point.attributes.forEach { _, value -> add(value.toString()) }
                }
            }
            spans.forEach { span ->
                add(span.name)
                add(span.status.description)
                span.attributes.forEach { _, value -> add(value.toString()) }
            }
        }
        val renderedExport = metrics.toString() + spans.toString()
        diagnostics.forEach { diagnostic ->
            assertFalse(exportedValues.any { diagnostic in it }, "diagnostic leaked: $diagnostic")
            assertFalse(diagnostic in renderedExport, "diagnostic leaked in rendering: $diagnostic")
        }
        sdk.close()
    }
}
```

- [ ] **Step 4.2** Run: `./gradlew :opentelemetry:jvmTest --console=plain`. Expected: pass.
- [ ] **Step 4.3** Commit: `Pin the six error.type literals and the no-diagnostics posture`

---

## Task 5 — Span + robustness tests

**Budget: 45 minutes.** Worker: any.

**Files:**
- Create: `opentelemetry/src/jvmTest/kotlin/org/mobilenativefoundation/store6/opentelemetry/OpenTelemetryStoreTelemetrySpanTest.kt`
- Create: `opentelemetry/src/jvmTest/kotlin/org/mobilenativefoundation/store6/opentelemetry/OpenTelemetryStoreTelemetryRobustnessTest.kt`

- [ ] **Step 5.1** Write the span test file:

```kotlin
@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class OpenTelemetryStoreTelemetrySpanTest {
    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    private class Harness {
        val metricReader: InMemoryMetricReader = InMemoryMetricReader.create()
        val spanExporter: InMemorySpanExporter = InMemorySpanExporter.create()
        val sdk: OpenTelemetrySdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build(),
            )
            .build()

        fun close(): Unit = sdk.close()
    }

    @Test
    fun spansAreDisabledByDefault() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk)

        sink.onFetchSucceeded(TestKey("users", "user-1"), 10.milliseconds)

        assertTrue(harness.spanExporter.finishedSpanItems.isEmpty())
        harness.close()
    }

    @Test
    fun spanCarriesExactDurationInternalKindNoParentUnsetStatusAndNoKeyByDefault() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk, emitSpans = true)

        sink.onFetchSucceeded(TestKey("users", "user-1"), 123.milliseconds)

        val span = harness.spanExporter.finishedSpanItems.single()
        assertEquals("store6.fetch", span.name)
        assertEquals(SpanKind.INTERNAL, span.kind)
        assertFalse(span.parentSpanContext.isValid)
        assertEquals(StatusCode.UNSET, span.status.statusCode)
        assertEquals(123.milliseconds.inWholeNanoseconds, span.endEpochNanos - span.startEpochNanos)
        assertEquals("users", span.attributes.get(AttributeKey.stringKey("store6.namespace")))
        assertNull(span.attributes.get(AttributeKey.stringKey("store6.key")))
        assertEquals(INSTRUMENTATION_SCOPE_NAME, span.instrumentationScopeInfo.name)
        assertEquals(INSTRUMENTATION_SCOPE_VERSION, span.instrumentationScopeInfo.version)
        harness.close()
    }

    @Test
    fun keyAttributeAppearsOnlyWhenOptedIn() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(
            harness.sdk,
            emitSpans = true,
            keyAttributeOnSpans = true,
        )

        sink.onFetchSucceeded(TestKey("users", "user-1"), 5.milliseconds)

        val span = harness.spanExporter.finishedSpanItems.single()
        assertEquals("user-1", span.attributes.get(AttributeKey.stringKey("store6.key")))
        harness.close()
    }

    @Test
    fun extraAttributesAndOverflowCoalescingApplyToSpans() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(
            harness.sdk,
            emitSpans = true,
            maxNamespaces = 1,
            extraAttributes = Attributes.of(AttributeKey.stringKey("store6.store"), "users-store"),
        )

        sink.onFetchSucceeded(TestKey("ns-a", "k"), 5.milliseconds)
        sink.onFetchSucceeded(TestKey("ns-b", "k"), 5.milliseconds)

        val namespaceKey = AttributeKey.stringKey("store6.namespace")
        val spans = harness.spanExporter.finishedSpanItems
        assertEquals(
            listOf("ns-a", "overflow"),
            spans.map { it.attributes.get(namespaceKey) },
        )
        spans.forEach { span ->
            assertEquals("users-store", span.attributes.get(AttributeKey.stringKey("store6.store")))
        }
        harness.close()
    }

    @Test
    fun nonFiniteAndNegativeDurationsProduceNoSpanAndNoHistogramPoint() {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk, emitSpans = true)
        val key = TestKey("users", "user-1")

        sink.onFetchSucceeded(key, Duration.INFINITE)
        sink.onFetchSucceeded(key, (-5).milliseconds)

        assertTrue(harness.spanExporter.finishedSpanItems.isEmpty())
        assertTrue(
            harness.metricReader.collectAllMetrics().none { it.name == "store6.fetch.duration" },
        )
        harness.close()
    }
}
```

- [ ] **Step 5.2** Write the robustness test file. Three separated fixtures (design §8.4): a
  throwing meter cannot double as a tracer test, because terminal hooks throw at the
  histogram before ever reaching the span path. The wrappers use Kotlin interface delegation
  (`by`) for everything except the members that must throw — **plus explicit overrides of
  every fluent method the sink calls, returning the wrapper**, because a delegated fluent
  method returns the delegate and silently escapes the wrapper:

```kotlin
@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.DoubleHistogramBuilder
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongCounterBuilder
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.MeterBuilder
import io.opentelemetry.api.metrics.MeterProvider
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.util.concurrent.TimeUnit
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.testing.TestStoreResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class OpenTelemetryStoreTelemetryRobustnessTest {
    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    /** Meter whose instruments throw on use; the tracer side stays the functioning no-op. */
    private class ThrowingMeterOpenTelemetry(
        private val delegate: OpenTelemetry = OpenTelemetry.noop(),
    ) : OpenTelemetry by delegate {
        override fun getMeterProvider(): MeterProvider = object : MeterProvider {
            override fun meterBuilder(instrumentationScopeName: String): MeterBuilder =
                object : MeterBuilder by delegate.meterProvider.meterBuilder(instrumentationScopeName) {
                    override fun setInstrumentationVersion(version: String): MeterBuilder = this

                    override fun setSchemaUrl(schemaUrl: String): MeterBuilder = this

                    override fun build(): Meter =
                        throwingMeter(delegate.meterProvider.get(instrumentationScopeName))
                }
        }

        private fun throwingMeter(delegate: Meter): Meter = object : Meter by delegate {
            override fun counterBuilder(name: String): LongCounterBuilder =
                object : LongCounterBuilder by delegate.counterBuilder(name) {
                    override fun setUnit(unit: String): LongCounterBuilder = this

                    override fun setDescription(description: String): LongCounterBuilder = this

                    override fun build(): LongCounter = object : LongCounter {
                        override fun add(value: Long): Unit = error("counter failure")

                        override fun add(value: Long, attributes: Attributes): Unit =
                            error("counter failure")

                        override fun add(value: Long, attributes: Attributes, context: Context): Unit =
                            error("counter failure")
                    }
                }

            override fun histogramBuilder(name: String): DoubleHistogramBuilder =
                object : DoubleHistogramBuilder by delegate.histogramBuilder(name) {
                    override fun setUnit(unit: String): DoubleHistogramBuilder = this

                    override fun setDescription(description: String): DoubleHistogramBuilder = this

                    override fun setExplicitBucketBoundariesAdvice(
                        bucketBoundaries: List<Double>,
                    ): DoubleHistogramBuilder = this

                    override fun build(): DoubleHistogram = object : DoubleHistogram {
                        override fun record(value: Double): Unit = error("histogram failure")

                        override fun record(value: Double, attributes: Attributes): Unit =
                            error("histogram failure")

                        override fun record(value: Double, attributes: Attributes, context: Context): Unit =
                            error("histogram failure")
                    }
                }
        }
    }

    @Test
    fun everyHookSwallowsThrowingInstruments() {
        val sink = OpenTelemetryStoreTelemetry(ThrowingMeterOpenTelemetry())
        val key = TestKey("users", "user-1")

        sink.onFetchStarted(key)
        sink.onFetchSucceeded(key, 10.milliseconds)
        sink.onFetchFailed(key, TestStoreResults.fetchError("diagnostic"), 10.milliseconds)
        sink.onServe(key, Origin.FETCHER)
        sink.onInvalidated(key)
        sink.onCleared(key)
        // Completing without an exception is the assertion.
    }

    /** Tracer whose spanBuilder throws; the meter side stays the functioning no-op. */
    private class ThrowingTracerOpenTelemetry(
        private val delegate: OpenTelemetry = OpenTelemetry.noop(),
    ) : OpenTelemetry by delegate {
        override fun getTracerProvider(): TracerProvider = object : TracerProvider {
            override fun get(instrumentationScopeName: String): Tracer =
                Tracer { error("spanBuilder failure") }

            override fun get(
                instrumentationScopeName: String,
                instrumentationScopeVersion: String,
            ): Tracer = get(instrumentationScopeName)
        }
    }

    @Test
    fun terminalHooksSwallowAThrowingSpanPath() {
        val sink = OpenTelemetryStoreTelemetry(ThrowingTracerOpenTelemetry(), emitSpans = true)
        val key = TestKey("users", "user-1")

        sink.onFetchSucceeded(key, 10.milliseconds)
        sink.onFetchFailed(key, TestStoreResults.fetchError("diagnostic"), 10.milliseconds)
        // Completing without an exception is the assertion; the no-op meter recorded fine.
    }

    /**
     * Wraps a real SDK tracer so setStatus throws after startSpan. Every fluent method the
     * sink calls is overridden to return this wrapper; delegation alone would let each
     * fluent call return the delegate and escape the wrapper before startSpan.
     */
    private class StatusThrowingOpenTelemetry(
        private val delegate: OpenTelemetry,
    ) : OpenTelemetry by delegate {
        override fun getTracerProvider(): TracerProvider = object : TracerProvider {
            override fun get(instrumentationScopeName: String): Tracer =
                wrap(delegate.tracerProvider.get(instrumentationScopeName))

            override fun get(
                instrumentationScopeName: String,
                instrumentationScopeVersion: String,
            ): Tracer = wrap(
                delegate.tracerProvider.get(instrumentationScopeName, instrumentationScopeVersion),
            )

            private fun wrap(tracer: Tracer): Tracer =
                Tracer { spanName -> ChainPreservingBuilder(tracer.spanBuilder(spanName)) }
        }

        private class ChainPreservingBuilder(
            private val delegate: SpanBuilder,
        ) : SpanBuilder by delegate {
            override fun setNoParent(): SpanBuilder {
                delegate.setNoParent()
                return this
            }

            override fun setSpanKind(spanKind: SpanKind): SpanBuilder {
                delegate.setSpanKind(spanKind)
                return this
            }

            override fun setStartTimestamp(startTimestamp: Long, unit: TimeUnit): SpanBuilder {
                delegate.setStartTimestamp(startTimestamp, unit)
                return this
            }

            override fun setAllAttributes(attributes: Attributes): SpanBuilder {
                delegate.setAllAttributes(attributes)
                return this
            }

            override fun <T> setAttribute(key: AttributeKey<T>, value: T): SpanBuilder {
                delegate.setAttribute(key, value)
                return this
            }

            override fun startSpan(): Span = StatusThrowingSpan(delegate.startSpan())
        }

        private class StatusThrowingSpan(
            private val delegate: Span,
        ) : Span by delegate {
            override fun setStatus(statusCode: StatusCode): Span = error("status failure")

            override fun setStatus(statusCode: StatusCode, description: String): Span =
                error("status failure")
        }
    }

    @Test
    fun aThrowingSetStatusStillEndsTheSpan() {
        val spanExporter = InMemorySpanExporter.create()
        val realSdk = OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build(),
            )
            .build()
        val sink = OpenTelemetryStoreTelemetry(StatusThrowingOpenTelemetry(realSdk), emitSpans = true)

        sink.onFetchFailed(
            TestKey("users", "user-1"),
            TestStoreResults.fetchError("diagnostic"),
            10.milliseconds,
        )

        // The wrapper's startSpan started a real span; the finally must have ended it even
        // though setStatus threw. UNSET status proves the throwing setStatus really ran
        // instead of the delegate's.
        val span = spanExporter.finishedSpanItems.single()
        assertEquals(StatusCode.UNSET, span.status.statusCode)
        realSdk.close()
    }
}
```

Worker notes: `Tracer` is a functional interface (single abstract `spanBuilder`), so
`Tracer { … }` compiles. `MeterProvider.get(String)` is a default method, so overriding only
`meterBuilder` satisfies the interface. If any delegated interface has gained an abstract
member that breaks `by` delegation, implement it by manual delegation and report it. If the
compiler rejects the `<T>` bound on `setAttribute`, match the IDE-suggested override
signature. `[descope-allowed]` applies to the `aThrowingSetStatusStillEndsTheSpan` test and
its `StatusThrowingOpenTelemetry` fixture only: if they fight the compiler beyond the
remaining budget, drop them, keep everything else, and record the gap as a follow-up.

- [ ] **Step 5.3** Run: `./gradlew :opentelemetry:jvmTest --console=plain`. Expected: pass.
- [ ] **Step 5.4** Commit: `Cover span synthesis edges and hostile-SDK robustness`

---

## Task 6 — Concurrency + version-drift tests

**Budget: 30 minutes.** Worker: any.

**Files:**
- Create: `opentelemetry/src/jvmTest/kotlin/org/mobilenativefoundation/store6/opentelemetry/OpenTelemetryStoreTelemetryConcurrencyTest.kt`
- Create: `opentelemetry/src/jvmTest/kotlin/org/mobilenativefoundation/store6/opentelemetry/InstrumentationScopeVersionTest.kt`

- [ ] **Step 6.1** Concurrency test (precedent:
  `devtools/src/jvmTest/.../StoreTelemetryLoggerJvmConcurrencyTest.kt`; a single completion
  latch keeps the failure path fast):

```kotlin
@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

class OpenTelemetryStoreTelemetryConcurrencyTest {
    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    @Test
    fun concurrentHooksAcrossNamespacesLoseNoCounts() {
        val metricReader = InMemoryMetricReader.create()
        val sdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .build()
        val sink = OpenTelemetryStoreTelemetry(sdk)
        val namespaces = listOf("ns-a", "ns-b")
        val threadsPerNamespace = 4
        val eventsPerThread = 500
        val start = CountDownLatch(1)
        val done = CountDownLatch(namespaces.size * threadsPerNamespace)
        val workers = namespaces.flatMap { namespace ->
            (1..threadsPerNamespace).map { workerIndex ->
                thread(isDaemon = true, name = "otel-$namespace-$workerIndex") {
                    val key = TestKey(namespace, "key-$workerIndex")
                    start.await()
                    repeat(eventsPerThread) { sink.onServe(key, Origin.MEMORY) }
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue(done.await(25, TimeUnit.SECONDS), "workers did not finish in time")
        workers.forEach { worker -> worker.join(TimeUnit.SECONDS.toMillis(5)) }

        val namespaceKey = AttributeKey.stringKey("store6.namespace")
        val points = metricReader.collectAllMetrics()
            .single { it.name == "store6.serves" }
            .longSumData.points
        val byNamespace = points.associate { it.attributes.get(namespaceKey) to it.value }
        val expected = (threadsPerNamespace * eventsPerThread).toLong()
        assertEquals(mapOf("ns-a" to expected, "ns-b" to expected), byNamespace)
        sdk.close()
    }
}
```

- [ ] **Step 6.2** Version-drift test:

```kotlin
package org.mobilenativefoundation.store6.opentelemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class InstrumentationScopeVersionTest {
    @Test
    fun scopeVersionConstantMatchesTheModuleVersion() {
        // Forwarded by the module build file from the module's VERSION_NAME project property;
        // a missing property fails the test rather than silently passing. An actual value of
        // 5.1.0-SNAPSHOT means the build file read the root's property instead of the
        // module's (see the failure playbook).
        val versionName = System.getProperty("store6.opentelemetry.versionName")
        assertNotNull(versionName, "store6.opentelemetry.versionName system property is not set")
        assertEquals(versionName, INSTRUMENTATION_SCOPE_VERSION)
    }
}
```

- [ ] **Step 6.3** Run: `./gradlew :opentelemetry:jvmTest --console=plain`. Expected: pass.
- [ ] **Step 6.4** Commit: `Guard concurrency and the instrumentation-scope version`

---

## Task 7 — Engine integration tests

**Budget: 30 minutes.** Worker: any.

**Files:**
- Create: `opentelemetry/src/jvmTest/kotlin/org/mobilenativefoundation/store6/opentelemetry/OpenTelemetryStoreTelemetryIntegrationTest.kt`

- [ ] **Step 7.1** Write the test file (pattern:
  `devtools/src/commonTest/.../StoreDevtoolsMonitorIntegrationTest.kt`; the success path is
  **[probe-verified]**; metrics are collected once per test):

```kotlin
@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest as coroutineRunTest

class OpenTelemetryStoreTelemetryIntegrationTest {
    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    private class Harness {
        val metricReader: InMemoryMetricReader = InMemoryMetricReader.create()
        val spanExporter: InMemorySpanExporter = InMemorySpanExporter.create()
        val sdk: OpenTelemetrySdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build(),
            )
            .build()

        fun close(): Unit = sdk.close()
    }

    @Test
    fun fetchInvalidateRefetchAndClearProduceTheExpectedSeries(): TestResult = runTest {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk, emitSpans = true)
        var fetches = 0
        val store = store<TestKey, String> {
            fetcher { "value-${++fetches}" }
            telemetry(sink)
        }
        val key = TestKey("users", "user-1")

        try {
            assertEquals("value-1", store.get(key))
            store.invalidate(key)
            val refetched = store.stream(key, Freshness.MustBeFresh).first {
                it is StoreResult.Data<*>
            }
            assertEquals("value-2", assertIs<StoreResult.Data<String>>(refetched).value)
            store.clear(key)
        } finally {
            store.close()
        }

        val metrics = harness.metricReader.collectAllMetrics().associateBy { it.name }
        assertEquals(2L, metrics.getValue("store6.fetch.attempts").longSumData.points.single().value)
        assertEquals(2L, metrics.getValue("store6.fetch.duration").histogramData.points.single().count)
        assertEquals(1L, metrics.getValue("store6.invalidations").longSumData.points.single().value)
        assertEquals(1L, metrics.getValue("store6.clears").longSumData.points.single().value)
        val originKey = AttributeKey.stringKey("store6.origin")
        val serveOrigins = metrics.getValue("store6.serves").longSumData.points
            .associate { it.attributes.get(originKey) to it.value }
        assertTrue((serveOrigins["FETCHER"] ?: 0L) >= 2L, "serves=$serveOrigins")
        assertEquals(2, harness.spanExporter.finishedSpanItems.size)
        harness.close()
    }

    @Test
    fun aFailingFetchSettlesIntoAnErrorTypedDurationPoint(): TestResult = runTest {
        val harness = Harness()
        val sink = OpenTelemetryStoreTelemetry(harness.sdk)
        val store = store<TestKey, String> {
            fetcher { error("review-gated diagnostic") }
            telemetry(sink)
        }
        val key = TestKey("users", "user-1")

        try {
            val result = store.stream(key, Freshness.MustBeFresh).first { it is StoreResult.Error }
            assertIs<StoreError.Fetch>(assertIs<StoreResult.Error>(result).error)
        } finally {
            store.close()
        }

        val metrics = harness.metricReader.collectAllMetrics()
        val errorTypeKey = AttributeKey.stringKey("error.type")
        val point = metrics.single { it.name == "store6.fetch.duration" }
            .histogramData.points.single()
        assertEquals("Fetch", point.attributes.get(errorTypeKey))
        assertTrue("review-gated diagnostic" !in metrics.toString())
        harness.close()
    }
}

// One file-private 25s runTest shadow, no nested wall-clock waits.
private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
```

Note: the serve-count assertion is deliberately `>= 2` for `FETCHER` rather than an exact
total — `get` plus a `MustBeFresh` stream can serve additional frames; exact serve counts are
engine-behavior assertions that belong to core's conformance suite, not this module.

- [ ] **Step 7.2** Run: `./gradlew :opentelemetry:jvmTest --console=plain`. Expected: pass.
  If `StoreResult.Error` never arrives in the failure test, print the observed results and
  report — do not loosen the assertion.
- [ ] **Step 7.3** Run the whole module gate once: `./gradlew :opentelemetry:build
  --console=plain`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 7.4** Commit: `Integration-test the sink against the real engine`

---

## Task 8 — Sample module

**Budget: 30 minutes.** Worker: any. Depends on Task 2 only (may run parallel to Tasks 3–7).

**Files:**
- Modify: `settings.gradle` (sample include + projectDir mapping)
- Create: `opentelemetry/sample/build.gradle.kts`
- Create: `opentelemetry/sample/src/main/kotlin/org/mobilenativefoundation/store6/opentelemetry/sample/Main.kt`

**Interfaces produced:** `:opentelemetry-sample:run` terminates on its own with exit code 0
and machine-checks the exported telemetry (Task 9's CI step depends on this).

- [ ] **Step 8.1** In `settings.gradle`, insert immediately after the
  `include ':opentelemetry'` line:

```groovy
include ':opentelemetry-sample'
project(':opentelemetry-sample').projectDir = file('opentelemetry/sample')
```

- [ ] **Step 8.2** `opentelemetry/sample/build.gradle.kts` (the `realtime/sample` shape):

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(11) }

dependencies {
    implementation(projects.opentelemetry)
    implementation(libs.opentelemetry.sdk)
    // In-memory reader and exporter keep the sample self-contained and its exports
    // machine-checkable in CI.
    implementation(libs.opentelemetry.sdk.testing)
}

application { mainClass.set("org.mobilenativefoundation.store6.opentelemetry.sample.MainKt") }
```

- [ ] **Step 8.3** `Main.kt`:

```kotlin
@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.opentelemetry.sample

import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.opentelemetry.OpenTelemetryStoreTelemetry

public fun main(): Unit =
    runBlocking {
        withTimeout(SAMPLE_TIMEOUT_MILLIS) {
            runSample()
        }
    }

private suspend fun runSample() {
    // The app owns the SDK: exporters, processors, sampling. This sample uses in-memory
    // exporters so it can assert on what was exported and stay dependency-free; a production
    // setup would use OTLP exporters behind batching processors (see the module README).
    val metricReader = InMemoryMetricReader.create()
    val spanExporter = InMemorySpanExporter.create()
    val sdk = OpenTelemetrySdk.builder()
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
        .setTracerProvider(
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build(),
        )
        .build()

    var fetches = 0
    val store = store<ArticleKey, Article> {
        fetcher { key -> Article(key.id, "Article ${key.id} v${++fetches}") }
        telemetry(OpenTelemetryStoreTelemetry(sdk, emitSpans = true))
    }
    val key = ArticleKey("42")

    try {
        val first = store.get(key)
        check(first.title == "Article 42 v1") { "unexpected first fetch: ${first.title}" }

        val resident = store.get(key)
        check(resident.title == "Article 42 v1") { "unexpected resident serve: ${resident.title}" }

        store.invalidate(key)
        val refetched = store.stream(key, Freshness.MustBeFresh).first {
            it is StoreResult.Data<*>
        }
        val refetchedArticle = (refetched as StoreResult.Data<*>).value as Article
        check(refetchedArticle.title == "Article 42 v2") {
            "unexpected refetch: ${refetchedArticle.title}"
        }

        store.clear(key)

        val metrics = metricReader.collectAllMetrics().associateBy { it.name }
        check(
            metrics.keys == setOf(
                "store6.fetch.attempts",
                "store6.fetch.duration",
                "store6.serves",
                "store6.invalidations",
                "store6.clears",
            ),
        ) { "unexpected instruments: ${metrics.keys}" }
        check(metrics.getValue("store6.fetch.attempts").longSumData.points.single().value == 2L)
        check(metrics.getValue("store6.fetch.duration").histogramData.points.single().count == 2L)
        val spans = spanExporter.finishedSpanItems
        check(spans.size == 2 && spans.all { it.name == "store6.fetch" }) {
            "unexpected spans: $spans"
        }

        println("store6-opentelemetry sample: five instruments and ${spans.size} fetch spans exported")
        metrics.values.forEach(::println)
        spans.forEach(::println)
    } finally {
        store.close()
        // Shuts the providers down and stops their threads so the process can exit; without
        // it the Gradle run task would hang.
        sdk.close()
    }
}

private class ArticleKey(
    val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("articles")

    override fun canonicalId(): String = id
}

private class Article(
    val id: String,
    val title: String,
)

private const val SAMPLE_TIMEOUT_MILLIS: Long = 20_000L
```

- [ ] **Step 8.4** Run: `./gradlew :opentelemetry-sample:run --console=plain`. Expected: all
  `check`s pass, the summary line plus the five metric and two span renderings print, BUILD
  SUCCESSFUL, **and the command returns on its own**. If it hangs, `sdk.close()` is not being
  reached.
- [ ] **Step 8.5** Commit: `Runnable opentelemetry sample`

---

## Task 9 — CI workflow wiring

**Budget: 25 minutes.** Worker: any. Depends on Task 8.

**Files:**
- Modify: `.github/workflows/store6.yml` (four locations; no other workflow files)

- [ ] **Step 9.1** In the `linux-build-test` job, immediately after the
  `Run Store6 realtime sample` step, insert (matching the sibling steps' 6-space `- name` /
  8-space `run` indentation and `run: >` folding):

```yaml
      - name: Build Store6 opentelemetry
        run: >
          ./gradlew :opentelemetry:build
          -Pkotlin.native.enableKlibsCrossCompilation=true
          -Pkotlin.apple.xcodeCompatibility.nowarn=true
          --stacktrace

      - name: Run Store6 opentelemetry sample
        run: ./gradlew :opentelemetry-sample:run --stacktrace
```

- [ ] **Step 9.2** In the `Reject core-internal access from extension modules` step, extend
  the `for module in …` list: append `opentelemetry opentelemetry/sample` (filesystem paths,
  before the final `; do`).
- [ ] **Step 9.3** In the `Enforce the TD-8 primitive whitelist…` step, extend
  `production_source_dirs=(…)`: append `opentelemetry/src/*Main` inside the parentheses.
- [ ] **Step 9.4** In the `klib-publication-check` job — **two coupled edits**:
  1. Append `:opentelemetry:publishToMavenLocal` to the `Publish Store6 core to Maven local
     without signing` Gradle command.
  2. In `modules=(core testing … realtime)`, append `opentelemetry`; then add a `case` arm
     directly after the `devtools-inspector:*` arm, matching its indentation:

```bash
                opentelemetry:-iosarm64|opentelemetry:-iossimulatorarm64|opentelemetry:-iosx64|opentelemetry:-js|opentelemetry:-linuxx64|opentelemetry:-macosarm64|opentelemetry:-mingwx64|opentelemetry:-tvosarm64|opentelemetry:-wasm-js|opentelemetry:-watchosarm64)
                  # opentelemetry ships the JVM-family subset; opentelemetry-java publishes
                  # JVM bytecode only, so there are no Kotlin/Native or web variants.
                  continue
                  ;;
```

- [ ] **Step 9.5** Make **no** changes to `apple-tests`, the JS lock canary, `native-stress`,
  `swift-dumps`, `swift-facade`, `ci.yml`, or `benchmarks.yml` — the module has no targets in
  those lanes, and `ci.yml`'s `clean build` picks up included projects automatically.
- [ ] **Step 9.6** Validate the YAML parses:
  `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/store6.yml'))"`. Expected:
  no output. Then simulate the publication check locally:

```bash
./gradlew :opentelemetry:publishToMavenLocal -Pkotlin.native.enableKlibsCrossCompilation=true --stacktrace
ls ~/.m2/repository/org/mobilenativefoundation/store/opentelemetry/6.0.0-SNAPSHOT/opentelemetry-6.0.0-SNAPSHOT.jar
ls ~/.m2/repository/org/mobilenativefoundation/store/opentelemetry-jvm/6.0.0-SNAPSHOT/opentelemetry-jvm-6.0.0-SNAPSHOT.jar
ls ~/.m2/repository/org/mobilenativefoundation/store/opentelemetry-android/6.0.0-SNAPSHOT/opentelemetry-android-6.0.0-SNAPSHOT.aar
```

Expected: all three artifacts exist. **[probe-verified]**

- [ ] **Step 9.7** Commit: `Wire the opentelemetry module into Store6 CI`

---

## Task 10 — README + SIGNALS

**Budget: 45 minutes.** Worker: **Sol 5.6 preferred.** Depends on Task 2 (parallel-safe with
Tasks 3–9).

**Files:**
- Create: `opentelemetry/README.md`
- Create: `opentelemetry/SIGNALS.md`

The dispatch must instruct the worker to read
`plugins/internal/documentation/skills/documentation-discipline/SKILL.md` and
`plugins/internal/documentation/skills/code-documentation/SKILL.md` first, and to treat the
design document §6 and §10 as the content authority. The two files below are complete drafts;
the worker's job is to land them verbatim, then run the three-pass review and fix only factual
defects it can evidence against the Task 2 source (reporting each fix).

- [ ] **Step 10.1** Create `opentelemetry/README.md`:

````markdown
# opentelemetry

An OpenTelemetry sink for Store6 over the Store telemetry seam. The single public class,
`OpenTelemetryStoreTelemetry`, records metrics for every telemetry hook and, opt-in,
synthesizes one span per settled fetch. Every public entry point is `@ExperimentalStoreApi`.
The seam is a freeze candidate, not frozen.

## Install

```kotlin
dependencies {
    implementation("org.mobilenativefoundation.store:opentelemetry:6.0.0-SNAPSHOT")
}
```

Opt in to `ExperimentalStoreApi`, construct the sink from the app's `OpenTelemetry`, and add
one builder line:

```kotlin
import org.mobilenativefoundation.store6.opentelemetry.OpenTelemetryStoreTelemetry

val users = store<UserKey, User> {
    fetcher(userFetcher)
    telemetry(OpenTelemetryStoreTelemetry(openTelemetry))
}
```

To install it beside the devtools sinks, compose with `storeTelemetryOf` from the `devtools`
artifact: `telemetry(storeTelemetryOf(logger, monitor, otelSink))`. Neither artifact depends
on the other.

Construction reads the meter and tracer providers from the passed `OpenTelemetry` once:
construct the sink after the app's SDK is built. `OpenTelemetry.noop()` is accepted and
records nothing; there is no global lookup and no install-time detection of a missing SDK.

## Targets

`androidTarget` (minSdk 24, the Store6 convention) and `jvm` only: the sink builds on
`opentelemetry-java`, which publishes JVM bytecode. The other Store6 targets keep the
telemetry seam and the devtools sinks; they gain an OpenTelemetry bridge when a multiplatform
OpenTelemetry API with stable metrics exists.

Android consumers inherit opentelemetry-java's requirements: API level 23+ with core-library
desugaring (opentelemetry-java `VERSIONING.md`). For `minSdk` below 26, opentelemetry-android's
guidance additionally applies: enable `isCoreLibraryDesugaringEnabled`, depend on a current
`desugar_jdk_libs`, use AGP 8.3.0+, and set
`android.useFullClasspathForDexingTransform=true` in `gradle.properties`. This artifact is
API-only and ships no R8 keep rules; the app's OpenTelemetry SDK carries its own.

`opentelemetry-api` (Apache-2.0, like Store) arrives at `api` scope together with its
`opentelemetry-context` dependency. Apps using an OpenTelemetry BOM should align versions
through the BOM; this artifact's pin is a floor, not a mandate.

## Signals

[SIGNALS.md](SIGNALS.md) defines the v0 vocabulary in full. Summary:

| Signal | Name | Recorded on |
| --- | --- | --- |
| Counter `{attempt}` | `store6.fetch.attempts` | fetch start |
| Histogram `s` | `store6.fetch.duration` | fetch success/failure (`error.type` on failure) |
| Counter `{serve}` | `store6.serves` | every public serve (`store6.origin`) |
| Counter `{invalidation}` | `store6.invalidations` | invalidation |
| Counter `{clear}` | `store6.clears` | clear |
| Span (opt-in) | `store6.fetch` | fetch success/failure |

The histogram unit is seconds; the devtools v0 logger logs `fetch_ms` milliseconds — do not
mix them in one dashboard query.

Fetch spans are standalone, synthesized after the fetch settles: they never join a caller
trace and never parent the fetcher's own HTTP spans. Enable them with `emitSpans = true` for
per-fetch inspection; add `keyAttributeOnSpans = true` to attach `store6.key`. Keys never
appear on metrics.

## Identity, values, and diagnostics

Stored values never cross the telemetry seam. This sink additionally never emits
`StoreError` messages or causes — failures carry only the variant name in `error.type` — and
never records exceptions on spans. Namespaces and (opt-in) key canonical ids are the only
identity that leaves the process; if namespaces or keys embed tenant or user identifiers,
treat span export accordingly.

## Installed cost and zero-cost boundary

Each hook performs one concurrent-map read (after a namespace's first event), one instrument
`add`/`record` into the app's SDK, and — for spans — one span build with two timestamps.
Attribute sets are interned per namespace, bounded by `maxNamespaces` (default 512; beyond
it, further namespaces coalesce into `store6.namespace = "overflow"`). After construction,
hooks never throw; failures inside the OpenTelemetry implementation are discarded.

Hooks stay non-blocking only if the SDK configuration is. Samplers run inside span start and
span processors inside span end, synchronously on the calling thread — use batching span
processors and prompt samplers; a synchronous exporter inside a span processor stalls Store
engine threads. Metric recording writes synchronously into the SDK's aggregation storage
(bounded work); metric collection and export run on the reader's own schedule. To silence one
instrument (for example `store6.serves` on a hot store), register a view when building the
meter provider:

```kotlin
SdkMeterProvider.builder()
    .registerView(
        InstrumentSelector.builder().setName("store6.serves").build(),
        View.builder().setAggregation(Aggregation.drop()).build(),
    )
```

Leaving `telemetry` unset preserves the core engine's null fast path.
````

- [ ] **Step 10.2** Create `opentelemetry/SIGNALS.md`:

````markdown
# Store6 OpenTelemetry vocabulary v0

This vocabulary is versioned but **EXPERIMENTAL**. Its names are stable within v0. It is an
instrumentation vocabulary for the OpenTelemetry API; the app's SDK owns exporters, sampling,
temporality, and transport. It is **not** the Store 6.1 wire format; that decision remains
open, exactly as `devtools/EVENTS.md` states for the logger vocabulary.

The sink observes identities and lifecycle facts only. Stored values never cross the
telemetry seam. `StoreError` messages and causes are never exported; failures carry only the
variant name.

## Instruments

| Instrument | Kind | Unit | Recorded on | Attributes |
| --- | --- | --- | --- | --- |
| `store6.fetch.attempts` | Counter | `{attempt}` | fetch-coroutine start | `store6.namespace` |
| `store6.fetch.duration` | Histogram | `s` | fetch success or terminal failure | `store6.namespace`; `error.type` on failure only |
| `store6.serves` | Counter | `{serve}` | every successful public serve | `store6.namespace`, `store6.origin` |
| `store6.invalidations` | Counter | `{invalidation}` | successful invalidation | `store6.namespace` |
| `store6.clears` | Counter | `{clear}` | successful clear | `store6.namespace` |

The histogram sets explicit bucket boundaries advice
`[0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0]` (the
semantic conventions' HTTP client duration boundaries). Advice is a hint: the SDK honors it
under the default aggregation, and a registered view overrides it.

The histogram unit is **seconds**. The devtools v0 logger emits `fetch_ms` in milliseconds.

## Attributes

| Attribute | Values |
| --- | --- |
| `store6.namespace` | `StoreKey.namespace.value`, verbatim, subject to the cardinality bound below. `overflow` is a reserved value. |
| `store6.origin` | `MEMORY`, `SOT`, `FETCHER`, `OVERLAY` — the `Origin` enum names, matching `EVENTS.md`. |
| `error.type` | Exactly one of `Fetch`, `Persistence`, `Conversion`, `FreshnessUnsatisfiable`, `Conflict`, `Missing` — the `StoreError` variant names, frozen for the 6.x major and identical to the v0 error names in `EVENTS.md`. Present only on failures. |
| `store6.key` | `StoreKey.canonicalId()`. Spans only, only with `keyAttributeOnSpans = true`. Never on metrics. |

Constructor `extraAttributes` are merged into every metric point and span; an entry that
collides with a sink-owned attribute name is overwritten by the sink.

## Cardinality bound

Store's key-design guidance allows dynamic namespaces (one per organization, for example), so
the sink bounds namespace cardinality itself. Attribute sets are interned per namespace; once
`maxNamespaces` (default 512) distinct namespaces have been interned, later namespaces record
under `store6.namespace = "overflow"`. Under concurrent first observations the interned count
can exceed the bound by the number of racing namespaces — it is a memory and cardinality
guard, not an exact quota. There is no eviction. Per-entity analysis belongs on spans with
`keyAttributeOnSpans`, not on metrics. The SDK's own per-instrument cardinality limits remain
the backstop.

## Fetch span (opt-in, `emitSpans = true`)

| Field | Value |
| --- | --- |
| Name | `store6.fetch` (constant; the namespace is an attribute) |
| Kind | `INTERNAL` |
| Parent | none; the span never joins a caller trace |
| Timestamps | end = wall clock at the terminal hook; start = end − engine-measured duration, clamped at zero; the span duration equals the engine's measured duration exactly whenever the clamp does not engage, and engine-produced durations cannot engage it |
| Attributes | `store6.namespace`; `error.type` on failure; `store6.key` when opted in; plus `extraAttributes` |
| Status | unset on success; `ERROR` without description on failure (`error.type` carries the variant) |

One span per settled fetch. Superseded fetches have no terminal hook and therefore no span
and no `store6.fetch.duration` point — see the next section. Started-then-abandoned spans
cannot occur: the span is created complete at terminal time. Non-finite or negative durations
(impossible from the engine, possible through direct hook calls) are dropped entirely, from
both the histogram and the span.

## Attempts versus settlements

`store6.fetch.attempts` counts fetch starts; the `store6.fetch.duration` histogram counts
settlements. As recorded by one sink instance over its lifetime — for engine-produced hooks
recorded by a non-throwing SDK — attempts minus settlements equals superseded fetches plus
fetches currently in flight. A partially failing SDK or direct hook calls with invalid
durations can decouple the two instruments. After export, the arithmetic additionally
survives only under cumulative temporality with lossless collection; delta temporality,
process restarts, and dropped points all break it. Treat the subtraction as a
process-lifetime heuristic, not an invariant.

## Instrumentation scope

Meter and tracer use the scope name `org.mobilenativefoundation.store6.opentelemetry` with
the artifact version as the scope version. A unit test pins the version constant to the
module's Gradle `VERSION_NAME`.

## Change policy

As an experimental vocabulary, v0 may still change at an alpha boundary. Any v0 instrument,
attribute, value, or span-shape change must be recorded in the alpha notes. Cross-alpha
compatibility for dashboards is not guaranteed. v0 is not the Store 6.1 wire format; that
decision remains open.
````

- [ ] **Step 10.3** Three-pass review (accuracy / warrant / reader utility) against the design
  doc and the Task 2 source file. Every instrument name, unit, attribute, literal, and default
  in the docs must match the code exactly. Verify the README view-recipe snippet against
  opentelemetry-java 1.65.0 (`InstrumentSelector`, `View`, `Aggregation.drop()` are SDK
  classes in `io.opentelemetry.sdk.metrics`); if any symbol is wrong, fix the snippet and
  report it.
- [ ] **Step 10.4** Commit: `Document the opentelemetry artifact (README + SIGNALS v0)`

---

## Task 11 — Stability table + final sweep + PR

**Budget: 25 minutes.** Worker: Sol 5.6 preferred (touches a policy document). Depends on all
other tasks.

**Files:**
- Modify: `STABILITY.md` (one table row)

- [ ] **Step 11.1** In the STABILITY.md §3 artifact table, after the `devtools-inspector`
  row, add:

```markdown
| `opentelemetry` | Experimental (`@ExperimentalStoreApi`). | Joins the line in the first release it is green for. |
```

Change nothing else in the file. §7 needs no amendment: this module commits a `.klib.api`
dump (empty, because it has no klib-producing targets).

- [ ] **Step 11.2** Final verification sweep, from the repository root:

```bash
./gradlew :opentelemetry:build :opentelemetry:apiCheck :opentelemetry:dokkaGenerate --console=plain
./gradlew :opentelemetry-sample:run --console=plain
grep -rnE 'InternalStoreApi|org[.]mobilenativefoundation[.]store6[.]core[.]internal' opentelemetry/src opentelemetry/sample/src && echo LEAK || echo CLEAN
grep -rnE '(^|[^[:alnum:]_])(runBlocking|GlobalScope|atomicfu|Channel|actor)([^[:alnum:]_]|$)' opentelemetry/src/jvmAndroidMain && echo BANNED || echo CLEAN
git status --short   # expect exactly: " M STABILITY.md" (this task's uncommitted edit)
```

Expected: two BUILD SUCCESSFUL runs, sample exits by itself, both greps print CLEAN, and the
only uncommitted change is this task's STABILITY.md edit.

- [ ] **Step 11.3** Commit: `Add the opentelemetry artifact to the stability table`. Then
  `git status --short` must be empty.
- [ ] **Step 11.4** Orchestrator: push, open the PR against `store6` (check for a PR
  template), body links the design doc and lists any descoped steps as follow-ups, **apply
  the `docs-sync-ack` label** (STABILITY.md is a docs-sync source; the guard job fails
  without the label). Confirm all `store6.yml` jobs go green; the failure playbook (§0.6)
  covers the known red paths.

---

## Plan self-review record

- Spec coverage: design §5 (API) → Task 2; §6.1–6.2 (metrics, cardinality) → Tasks 2–3; §6.3
  (privacy) → Task 4; §6.4 (spans) → Tasks 2, 5; §7 (mechanics, robustness) → Tasks 2, 5, 6;
  §8 (testing, incl. the three separated robustness fixtures and the span/histogram drop
  rule) → Tasks 3–7; §9 (repo integration) → Tasks 1, 8, 9, 11; §10 (docs, incl. the signals
  table and view recipe) → Task 10; sample with machine-checked exports → Task 8.
- Placeholder scan: no TBD/TODO; every code step carries complete code; the deliberate
  flexibility points (Step 5.2 delegation and generic-override notes, Step 10.3 view-recipe
  verification) name their exact alternatives; the single `[descope-allowed]` marker lives in
  Task 5's prose, not in source.
- Type consistency: constructor parameters, constants, and instrument names are identical
  across Tasks 2, 3–8, 10, and the design doc (`OpenTelemetryStoreTelemetry`, `emitSpans`,
  `keyAttributeOnSpans`, `maxNamespaces`, `extraAttributes`, `INSTRUMENTATION_SCOPE_NAME`,
  `INSTRUMENTATION_SCOPE_VERSION`, `SPAN_NAME`, `OVERFLOW_NAMESPACE`).
