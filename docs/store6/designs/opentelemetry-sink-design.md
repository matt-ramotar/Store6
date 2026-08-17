# OpenTelemetry sink — technical design

Status: proposed, revision 2. Date: 2026-08-17. Deliverable: a new published experimental
artifact `org.mobilenativefoundation.store:opentelemetry` that implements the `StoreTelemetry`
seam over the OpenTelemetry API, so Store lifecycle facts land in the observability backend an
app already runs.

Revision 2 incorporates two independent adversarial reviews and an empirical build probe: a
skeleton of exactly this module was built inside this repository against Kotlin 2.3.20,
AGP 8.10.0, binary-compatibility-validator 0.17.0, and opentelemetry-java 1.65.0. Claims below
marked **[verified]** were observed in that probe; the full result table is in Appendix A.

The companion document is
[opentelemetry-sink-implementation-plan.md](./opentelemetry-sink-implementation-plan.md).

## 1. Summary

A single class, `OpenTelemetryStoreTelemetry`, implements
`org.mobilenativefoundation.store6.core.seam.StoreTelemetry` and translates the seam's six hooks
into OpenTelemetry **metrics** (always) and **spans** (opt-in). Values never cross the seam, so
they cannot leak. `StoreError` messages and causes never leave the sink. Key canonical ids are
excluded by default and opt-in on spans only. Namespace attribute cardinality is bounded by the
sink itself (§6.2) because the repository's own key-design guidance recommends dynamic
namespaces. The artifact depends on the stable `io.opentelemetry:opentelemetry-api` (1.65.0) and
ships the JVM-family target subset (`androidTarget`, `jvm`). The app owns the OpenTelemetry SDK,
exporters, sampling, and transport; this artifact defines only an instrumentation vocabulary,
versioned v0 in a `SIGNALS.md` that mirrors devtools' `EVENTS.md`, and it is explicitly **not**
the Store 6.1 wire format.

## 2. Background and constraints

### 2.1 The seam being consumed

`core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/StoreTelemetry.kt` is one
of the 13 freeze-candidate seam files (CI enforces the file list on every PR). Its contract, from
its KDoc:

- Handlers are non-suspending, must be non-blocking, and must not throw.
- The engine never invokes them while its state or write lock is held.
- When telemetry is unset the engine keeps a null reference and a null-guarded fast path; no
  fetch-duration mark is allocated.
- `onFetchStarted` runs at fetch-coroutine start. `onFetchSucceeded` / `onFetchFailed` run after
  commit or settlement and before the fetch ticket completes. **Superseded fetches have no
  terminal hook.**
- `onServe` runs for every public data emission and successful `get` return (for revalidations,
  only when the rendered projection retains a visible value).

Hooks may be invoked concurrently; the devtools vocabulary (`devtools/EVENTS.md`) documents
concurrent handler delivery, and `StoreTelemetryLoggerJvmConcurrencyTest` exists as the
repository's concurrency-test precedent for a sink.

The six hooks and their payloads:

| Hook | Payload |
|---|---|
| `onFetchStarted(key)` | `StoreKey` |
| `onFetchSucceeded(key, duration)` | `StoreKey`, `kotlin.time.Duration` |
| `onFetchFailed(key, error, duration)` | `StoreKey`, `StoreError`, `Duration` |
| `onServe(key, origin)` | `StoreKey`, `Origin` (`MEMORY`, `SOT`, `FETCHER`, `OVERLAY`) |
| `onInvalidated(key)` | `StoreKey` |
| `onCleared(key)` | `StoreKey` |

Identity is `StoreKey.namespace.value: String` plus `StoreKey.canonicalId(): String`. Values
never appear in any hook. `StoreError` is a sealed class whose variant set is **frozen for the
6.x major** (per its KDoc): `Fetch`, `Persistence`, `Conversion`, `FreshnessUnsatisfiable`,
`Conflict`, `Missing`. The interface is `@ExperimentalStoreApi` and
`@SubclassOptInRequired(DelicateStoreApi::class)`; implementations opt in with
`@OptIn(DelicateStoreApi::class)` (precedent: `StoreTelemetryLogger`).

**Namespace cardinality is not bounded by core.** `docs/store6/key-design.md` explicitly
recommends dynamic namespaces — its worked example is
`StoreNamespace("documents:$organizationId")`, one namespace per organization. Any design that
assumes "namespaces are a small fixed set" is wrong against the repository's own guidance. §6.2
is the consequence.

### 2.2 Precedents this design follows — and where it deliberately departs

- `devtools` (`StoreTelemetryLogger`, `StoreDevtoolsMonitor`, `CompositeStoreTelemetry`) is the
  existing sink family. It establishes: identities-and-lifecycle-facts-only telemetry; never
  values; never `StoreError` message/cause in emitted output; a versioned-but-experimental v0
  vocabulary (`EVENTS.md`) with the explicit statement that the Store 6.1 wire format is
  deliberately not decided.
- Exception handling: `StoreTelemetryLogger` guards only its `emit` callback with
  `catch (_: Throwable)`; `StoreDevtoolsMonitor` and `CompositeStoreTelemetry` add no guarding.
  This module guards **whole hook bodies** — a deliberate, stricter choice, not an established
  family pattern (§7). The rationale: every statement in this sink's hooks calls into an
  app-supplied OpenTelemetry implementation, which is the analog of the logger's app-supplied
  `emit` callback, not of devtools' own in-module code.
- `room` is the precedent for a target-subset module
  (`org.mobilenativefoundation.store.store6.multiplatform.subset` plugin) whose build file
  documents the exact supported subset and why.
- `realtime/sample` is the precedent for a runnable JVM sample:
  `runBlocking { withTimeout(SAMPLE_TIMEOUT_MILLIS) { runSample() } }`, `check()` assertions,
  close in `finally`, process exits on its own.
- The class name follows the `CompositeStoreTelemetry` shape (`<Qualifier>StoreTelemetry`). The
  other sinks are named `StoreTelemetryLogger` / `StoreDevtoolsMonitor`; there is no single
  family-wide pattern, and `OpenTelemetryStoreTelemetry` is chosen for greppable precision: a
  `StoreTelemetry` backed by OpenTelemetry.

### 2.3 Repository constraints that bind this module

- Experimental code ships in a **separate artifact**, every public symbol
  `@ExperimentalStoreApi`, tier stated in the STABILITY.md artifact table (STABILITY.md §2–3).
- Extension modules must not reference `InternalStoreApi` or
  `org.mobilenativefoundation.store6.core.internal` (CI grep gate).
- The TD-8 primitive whitelist bans `runBlocking`, `GlobalScope`, `atomicfu`, `Channel`, and
  `actor` in production sources of listed modules; the new module's `src/*Main` directories join
  that list. `java.util.concurrent` is not banned and is available because both targets are
  JVM-family. (Samples are not on the TD-8 list; `realtime/sample` uses `runBlocking`.)
- `explicitApi()`, binary-compatibility-validator with klib validation enabled, vanniktech
  publishing with `JavadocJar.Empty()`, Dokka, `jvmToolchain(11)`, Android `compileSdk 36` /
  `minSdk 24` all come from the shared convention plugin (`Store6Conventions.kt`).
- Kotlin floor is 2.3 (STABILITY.md §10); the repo builds with Kotlin 2.3.20.

## 3. Goals and non-goals

### Goals

1. One-line install: `telemetry(OpenTelemetryStoreTelemetry(openTelemetry))` inside `store { }`
   (plus the `ExperimentalStoreApi` opt-in and an app-constructed `OpenTelemetry` instance).
2. Metrics for all six hooks; spans for settled fetches, opt-in.
3. Identity-only telemetry: never stored values, never `StoreError.message` or `cause`; key
   canonical ids only when explicitly enabled and only on spans.
4. Bounded metric cardinality **enforced by the sink** (§6.2), not assumed.
5. Uphold the seam contract as far as a bridge can: non-throwing after construction, no
   cross-hook state, correct under concurrent delivery. The non-blocking obligation is
   necessarily transitive to the app's SDK configuration (§7).
6. Follow OpenTelemetry semantic-convention naming rules for instruments, units, and the
   `error.type` attribute.
7. Zero core diff: the module consumes public seams only (the bar `extension-probe` proves).

### Non-goals

1. **Deciding any wire format.** The OpenTelemetry SDK's exporter (typically OTLP) is the app's
   choice. This artifact's vocabulary is versioned v0 and is not the Store 6.1 devtools
   wire-format decision, which remains open exactly as `devtools/EVENTS.md` states.
2. **Mutations telemetry.** `MutationStore.events` is a separate `SharedFlow` vocabulary in the
   `mutations` artifact; core telemetry deliberately carries no mutation vocabulary. Bridging it
   would force a `mutations` dependency on every consumer of this artifact. Future work (§12).
3. **Crash-breadcrumb sinks.** Different consumers (crash SDKs), different shape (ring buffer
   attached to crash reports); separate design if pursued.
4. **Trace-context propagation into fetchers.** The seam is observational; no hook wraps the
   fetcher call, so no context can flow through it. Fetch spans are therefore standalone
   synthetic spans (§6.4). A fetcher-decorator that opens a live span is future work.
5. **Logs signal.** The six lifecycle facts are covered by metrics and spans; a `LogRecord`
   mapping adds surface without a named consumer.
6. **Targets beyond `androidTarget` and `jvm`** in this iteration (§4.1).
7. **Per-instrument enable/disable switches.** The OpenTelemetry SDK's view mechanism exists for
   exactly this (drop `store6.serves` with a view if it is too hot for a backend). Duplicating
   view capability as constructor flags grows API without adding capability; the README shows
   the view recipe instead. The known limitation — an app whose SDK is owned by the OTel Java
   agent may not control views — is accepted for v0 and recorded in the README.
8. **An Android-app consumer canary in CI.** No existing module verifies consumer-side dexing;
   the desugaring requirement is documented (§4.1) rather than CI-enforced. Recorded as a
   follow-up option, not a launch gate.

## 4. Decisions

### 4.1 D1 — Build on opentelemetry-java; ship the JVM-family subset

**Decision.** Depend on `io.opentelemetry:opentelemetry-api:1.65.0` (`api` scope — the
`OpenTelemetry` type appears in the public constructor). Targets: `androidTarget()` and `jvm()`
via the subset convention plugin (`androidTarget()` is mandatory under that plugin).

**Alternative rejected: opentelemetry-kotlin (KMP).** The official
[opentelemetry-kotlin](https://github.com/open-telemetry/opentelemetry-kotlin) project would
cover more Store6 targets, but as of 2026-08:

- Its core APIs are experimental — "subject to breaking changes without notice", every symbol
  behind `@OptIn(ExperimentalApi::class)` (its getting-started docs).
- It implements the Logging and Tracing APIs; **metrics — the primary signal of this design —
  are not available**.
- iOS/JS klib consumers must use a Kotlin version at least as new as the one it is built with
  (currently 2.4.0, per its release notes). Store6 pins Kotlin 2.3.20 with a published floor of
  2.3 (STABILITY.md §10), so the wider targets are unbuildable here today.
- It dropped `iosX64`, which is in Store6's canonical 12-target set.

Coupling an experimental Store artifact to an experimental third-party API multiplies breakage
across two release cadences. The JVM-family subset covers the platforms where OpenTelemetry
collection is production-real today. Widening is additive later (§12) and does not break this
artifact's consumers.

**Alternative rejected: expect/actual facade over both OTel stacks.** A common facade would
freeze a Store-owned abstraction over a moving third-party API — the wrong seam to invent while
both this artifact and opentelemetry-kotlin are experimental.

**Android consequences (documented, not enforced).** Per opentelemetry-java's VERSIONING.md,
Android support is API level 23+ **with core-library desugaring required** (stated unqualified
there). opentelemetry-android's README adds the practical detail for `minSdk < 26`: enable
`isCoreLibraryDesugaringEnabled`, depend on current `desugar_jdk_libs`, use AGP 8.3.0+, and set
`android.useFullClasspathForDexingTransform=true`. Store6's convention `minSdk` is 24, so the
README of this module must carry all of these consumer requirements, cited to both upstream
documents. This module's own code avoids `java.time` and uses the `(long, TimeUnit)` timestamp
overloads, but its `opentelemetry-api` dependency is what triggers the upstream requirement.

**Transitive surface.** `opentelemetry-api` brings `opentelemetry-context` (compile scope
**[verified]** in the probe's published POM). Consumers using an OpenTelemetry BOM should align
versions through their BOM; this artifact's pin is a minimum, not a mandate (README note). Both
this repository and opentelemetry-java are Apache-2.0; no NOTICE obligations beyond the POM
license fields.

### 4.2 D2 — New module `opentelemetry`, not an addition to `devtools`

**Decision.** New Gradle project `:opentelemetry` at `/opentelemetry`, artifact
`org.mobilenativefoundation.store:opentelemetry`, package
`org.mobilenativefoundation.store6.opentelemetry`, Android namespace the same. A runnable JVM
sample lives at `opentelemetry/sample` (`:opentelemetry-sample`), mirroring `realtime/sample`.

**Why not inside `devtools`:** `devtools` uses the full 12-target convention; adding a JVM-only
dependency would shrink its matrix or force an awkward internal split. A separate artifact keeps
the dependency optional and states the tier on the tin (STABILITY.md §2).

When a Store `bom` module materializes (STABILITY.md §3 lists the artifact; `settings.gradle`
has no `:bom` project yet), this artifact joins it like every other published module.

### 4.3 D3 — Source layout: a created `jvmAndroidMain` intermediate source set **[verified]**

The Kotlin documentation lists "JVM + Android targets" as an unsupported source-set-sharing
combination, and `commonMain` cannot resolve a Java-only dependency for metadata compilation.
Two candidate layouts were probed in this repository:

1. **One shared directory registered as `srcDir` of both platform source sets.** Compiles,
   passes BCV and `build`, but **fails Dokka**: "Pre-generation validity check failed: Source
   sets 'android' and 'jvm' have the common source roots … Every Kotlin source file should
   belong to only one source set" (Dokka issue 3701). The convention plugin applies Dokka to
   every module; shipping a module whose `dokkaGenerate` fails is not acceptable even though
   neither `build` nor publishing runs Dokka (publishing uses `JavadocJar.Empty()`).
2. **A created intermediate source set** `jvmAndroidMain` with `dependsOn(commonMain)` and both
   platform source sets depending on it. **Everything passes**: both platform compilations,
   `jvmTest`, lint, `apiDump`/`apiCheck`, `dokkaGenerate`, and `publishToMavenLocal`. The Kotlin
   Gradle Plugin **skips** metadata compilation for this source set
   (`compileJvmAndroidMainKotlinMetadata SKIPPED`), so the Java-only dependency is never
   resolved against metadata. The `api(libs.opentelemetry.api)` declaration on the intermediate
   source set propagates to both platform compile classpaths and into the published POMs at
   compile scope.

**Decision: layout 2.** Residual risk: the "unsupported" label means JetBrains does not promise
this shape keeps working (IDE analysis of the intermediate set may be degraded, and a future KGP
could change the skip behavior). The fallback is layout 1 plus a Dokka source-set suppression —
mechanical, no API change. Recorded in §11.

The complete module build file:

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
    // the drift guard test reads this property (see §8.6). findProperty is load-bearing:
    // the module's gradle.properties overrides the root's for project properties, while
    // providers.gradleProperty reads only the root's VERSION_NAME (5.1.0-SNAPSHOT) —
    // measured on this repository.
    systemProperty(
        "store6.opentelemetry.versionName",
        findProperty("VERSION_NAME") as String,
    )
}
```

**BCV output for this shape [verified]:** `apiDump` writes `api/jvm/opentelemetry.api`,
`api/android/opentelemetry.api`, and an **empty** `api/opentelemetry.klib.api`; `apiCheck`
(including `klibApiCheck`) passes with all three committed. STABILITY.md §7's sentence "Each
module commits a JVM `.api` dump and a `.klib.api` dump" therefore stays true as written — the
klib dump exists and is empty because the module has no klib-producing targets.

**Publication [verified]:** `publishToMavenLocal` produces exactly `opentelemetry` (jar),
`opentelemetry-jvm` (jar), and `opentelemetry-android` (aar) under
`org.mobilenativefoundation.store` at `VERSION_NAME` from the module's `gradle.properties`.
(The root `gradle.properties` still carries the Store 5 `5.1.0-SNAPSHOT`; omitting the module
file would publish the wrong version and fail the CI publication check.)

### 4.4 D4 — Signals: metrics always; spans opt-in; both from the same class

Metrics are the default because they are aggregate (no per-event export cost), cardinality-safe
after §6.2, and answer the questions the seam can answer (rates, latency distribution, origin
mix, error mix). Spans are opt-in (`emitSpans = false`) and honestly framed: each fetch span is
a **standalone, single-span trace**, synthesized after the fact. It does not join a caller's
trace, cannot parent the fetcher's own HTTP spans, and adds no causality information. What it
adds over the histogram: per-fetch inspection (exact placement in time of an individual slow or
failed fetch) and, with `keyAttributeOnSpans`, per-key drill-down that metrics must never carry.
Sampling and export batching remain the SDK's decision; the sink never buffers or samples.

### 4.5 D5 — No cross-hook state: terminal-time span synthesis

The seam guarantees no terminal hook for superseded fetches, and hooks carry no fetch id. Any
design that opens a span in `onFetchStarted` and ends it in a terminal hook leaks spans (and an
in-flight UpDownCounter would drift permanently). **Decision:** the sink holds no cross-hook
state. Terminal hooks carry the engine-measured `duration`, so the span is created complete at
terminal time (§6.4). `onFetchStarted` increments a counter.

Consequence, stated in `SIGNALS.md` rather than papered over: `store6.fetch.attempts` counts
starts; the `store6.fetch.duration` histogram counts settlements. **As recorded by one sink
instance, for engine-produced hooks recorded by a non-throwing SDK**, attempts minus
settlements equals superseded fetches plus fetches currently in flight. (A partially failing
SDK, or direct hook calls with invalid durations, can decouple the two instruments.) After
export, the arithmetic additionally survives only under cumulative temporality with aligned,
lossless collection; delta temporality, restarts, and per-instrument drops all break it.
`SIGNALS.md` documents the subtraction as a process-lifetime cumulative heuristic, not an
invariant.

### 4.6 D6 — Vocabulary: `store6.*` namespace, v0, documented in `SIGNALS.md`

Instrument and attribute names use the `store6.` namespace, not `store.`:

- It matches the repository's deliberate versioned naming
  (`org.mobilenativefoundation.store6.*` packages, the devtools default label token `store6`).
- Store 5 and Store 6 run side by side for the whole 6.x major (STABILITY.md §6); a versioned
  namespace keeps their telemetry distinguishable in one app.
- It cannot collide with a future OpenTelemetry semantic-conventions `store.` namespace;
  instrumentation-specific names should use their own namespace per semconv naming guidance.

`error.type` is the exception: it is the registered semconv attribute for exactly this purpose,
used with Store-defined values (§6.3). The attribute-key constant is declared locally; the
`io.opentelemetry.semconv:opentelemetry-semconv` artifact is not worth a dependency for one
string.

The vocabulary is versioned **v0** and documented in `opentelemetry/SIGNALS.md` with the same
change policy as `devtools/EVENTS.md`: names stable within v0, revisions recorded at alpha
boundaries, cross-alpha compatibility not guaranteed, and explicitly not the Store 6.1 wire
format. `SIGNALS.md` also carries the unit note that trips dashboard authors: this histogram
records **seconds** (semconv), while devtools v0 logger lines record `fetch_ms` milliseconds.

### 4.7 D7 — Instrumentation scope and version constant

Meter and tracer are created with instrumentation scope name
`org.mobilenativefoundation.store6.opentelemetry` and instrumentation version equal to the
artifact version, kept as an `internal const val` (`"6.0.0-SNAPSHOT"` today). Drift is guarded
by a `jvmTest` comparing the constant against the `store6.opentelemetry.versionName` system
property that the module build file forwards from the module's `VERSION_NAME` project property
via `findProperty` (§4.3 build file; `providers.gradleProperty` would silently read the root's
`5.1.0-SNAPSHOT` instead — measured). No file I/O, fail-closed if the property is absent.

## 5. Public API

The entire public surface (every symbol `@ExperimentalStoreApi`):

```kotlin
package org.mobilenativefoundation.store6.opentelemetry

@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class OpenTelemetryStoreTelemetry(
    openTelemetry: OpenTelemetry,
    private val emitSpans: Boolean = false,
    private val keyAttributeOnSpans: Boolean = false,
    private val maxNamespaces: Int = 512,
    private val extraAttributes: Attributes = Attributes.empty(),
) : StoreTelemetry {
    override fun onFetchStarted(key: StoreKey)
    override fun onFetchSucceeded(key: StoreKey, duration: Duration)
    override fun onFetchFailed(key: StoreKey, error: StoreError, duration: Duration)
    override fun onServe(key: StoreKey, origin: Origin)
    override fun onInvalidated(key: StoreKey)
    override fun onCleared(key: StoreKey)
}
```

Defaulted constructor parameters follow the sink-family precedent
(`StoreTelemetryLogger(label, timeSource, emit)`, `StoreDevtoolsMonitor(capacity, timeSource)`)
rather than a config type; the surface is experimental and may be reshaped in any release.

- `openTelemetry` is required, with no `GlobalOpenTelemetry` fallback. Explicit injection keeps
  construction order visible on Android and tests hermetic. (OpenTelemetry's own guidance
  reserves the global for java-agent-style auto-instrumentation; apps that use the agent can
  still pass `GlobalOpenTelemetry.get()` explicitly.) Two documented consequences:
  - Providers are **snapshotted at construction**. An SDK registered globally later is
    invisible; construct the sink after the app's SDK.
  - `OpenTelemetry.noop()` is legal and silent: construction succeeds, hooks succeed, nothing
    is exported. Construction throws only if the supplied providers throw while building the
    meter, instruments, or tracer — that (and invalid `maxNamespaces`) is the only install-time
    failure surface. There is no reliable "misconfiguration fails fast" guarantee beyond it.
- `emitSpans = false`: metrics only by default (§4.4).
- `keyAttributeOnSpans = false`: when true **and** `emitSpans` is true, fetch spans carry
  `store6.key` = `key.canonicalId()`. Inert without `emitSpans`; documented, not validated.
  Keys never appear on metrics regardless of configuration (§6.2).
- `maxNamespaces = 512` (`require(maxNamespaces > 0)`, the `StoreDevtoolsMonitor` capacity
  precedent): the cardinality bound, §6.2.
- `extraAttributes = Attributes.empty()`: constant attributes merged into every metric point
  and span at construction time (zero per-event cost). This is how an app distinguishes two
  stores or two sink installs that share namespace values (for example
  `Attributes.of(stringKey("store6.store"), "users")`). Collision rule, documented: an
  `extraAttributes` entry named like a sink-owned attribute is overwritten by the sink.
- Installation composes with devtools sinks through the existing
  `storeTelemetryOf(logger, monitor, otelSink)` from the `devtools` artifact; no dependency
  between the two artifacts in either direction.
- Instances are safe to share across several stores; all state is namespace-keyed, immutable
  after interning, and thread-safe.

## 6. Telemetry mapping (vocabulary v0)

### 6.1 Metrics

All instruments are created once, at construction.

| Instrument | Kind | Unit | Hook | Attributes |
|---|---|---|---|---|
| `store6.fetch.attempts` | Counter (Long) | `{attempt}` | `onFetchStarted` | `store6.namespace` |
| `store6.fetch.duration` | Histogram (Double) | `s` | `onFetchSucceeded`, `onFetchFailed` | `store6.namespace`; `error.type` on failure only |
| `store6.serves` | Counter (Long) | `{serve}` | `onServe` | `store6.namespace`, `store6.origin` |
| `store6.invalidations` | Counter (Long) | `{invalidation}` | `onInvalidated` | `store6.namespace` |
| `store6.clears` | Counter (Long) | `{clear}` | `onCleared` | `store6.namespace` |

Naming follows the semconv general rules: duration histograms named `{operation}.duration` with
unit `s`; counters of discrete countable events pluralized with `{annotation}` units;
`error.type` present only on failures (the `http.client.request.duration` pattern).
`store6.fetch.duration` sets explicit bucket boundaries **advice**
`[0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0]` — the
semconv HTTP client duration boundaries, appropriate for network-backed fetches.
`DoubleHistogramBuilder.setExplicitBucketBoundariesAdvice` is stable API since
opentelemetry-java 1.32.0 **[verified against 1.65.0]**; advice is a hint the SDK honors unless
a view overrides aggregation, and `SIGNALS.md` says so.

Attribute values:

- `store6.namespace` = `StoreKey.namespace.value` verbatim, subject to the §6.2 bound.
- `store6.origin` = the `Origin` enum name verbatim (`MEMORY`, `SOT`, `FETCHER`, `OVERLAY`) —
  one vocabulary with `EVENTS.md`, which logs `origin=FETCHER`.
- `error.type` = one of the six literal variant names `Fetch`, `Persistence`, `Conversion`,
  `FreshnessUnsatisfiable`, `Conflict`, `Missing` — identical to the v0 error names in
  `EVENTS.md`, and stable because the `StoreError` variant set is frozen for the 6.x major.
  semconv permits domain-defined low-cardinality `error.type` values.
- Every point additionally carries `extraAttributes` (§5).

### 6.2 Cardinality bound

Because the key-design guidance recommends per-entity namespaces (§2.1), the sink bounds
namespace cardinality itself instead of assuming it:

- Attribute bundles are interned per namespace in a `ConcurrentHashMap`. Once the map holds
  `maxNamespaces` distinct namespaces, every further namespace records under the single
  coalesced value `store6.namespace = "overflow"` — memory and time-series growth both stop.
  This mirrors the overflow-attribute-set behavior of the SDK's own per-instrument cardinality
  limits, which remain the backstop for total series count.
- First-come-first-interned; no eviction. An app that legitimately needs more distinct
  namespaces raises `maxNamespaces` deliberately.
- **`store6.key` never appears on a metric attribute** — canonical ids are unbounded (user ids,
  document ids) and belong only on opt-in spans.
- Span **names** are fixed (`store6.fetch`, §6.4); namespaces appear on spans as attributes
  only, so span-name cardinality is 1. Span attributes use the same interned bundles, so the
  overflow coalescing applies to spans identically.
- `SIGNALS.md` documents all of this, including the guidance that per-entity analysis belongs
  on spans (opt-in keys), not metrics.

### 6.3 What is deliberately absent

`StoreError.message` and `StoreError.cause` never reach any attribute, span status description,
or recorded exception. This is stricter than `StoreDevtoolsMonitor` (which retains the
structured error in an in-process snapshot) because everything this sink emits leaves the
process through exporters. Same posture as devtools logger lines: "messages are review-gated
diagnostics."

### 6.4 Spans (only when `emitSpans = true`)

One span per settled fetch, created complete at terminal-hook time:

| Field | Value |
|---|---|
| Name | `store6.fetch` — constant; the namespace is an attribute (§6.2) |
| Kind | `INTERNAL` (the network call belongs to the fetcher and its own instrumentation) |
| Parent | none — `setNoParent()`. Terminal hooks run on engine-controlled threads; inheriting whatever `Context` is current there would parent fetch spans under unrelated spans. |
| Start / end | `end = System.currentTimeMillis()` converted to epoch nanos; `start = end − duration`, clamped at 0; both set explicitly, so the recorded span duration equals the engine-measured `duration` exactly whenever the clamp does not engage — and the finite non-negative durations the engine produces cannot engage it **[verified by probe test]** |
| Attributes | interned bundle (`store6.namespace` + `extraAttributes`; plus `error.type` on failure); plus `store6.key` only when `keyAttributeOnSpans` |
| Status | unset on success; `ERROR` **without description** on failure — semconv recommends against duplicating `error.type` in the status description |

Rules the implementation must follow:

- Non-finite or negative durations are dropped before recording (histogram and span). The
  engine only produces finite non-negative monotonic-elapsed durations, but the hooks are
  public API; `Duration.INFINITE.inWholeNanoseconds` saturates to `Long.MAX_VALUE` and would
  produce garbage timestamps. Histogram conversion is `duration.toDouble(DurationUnit.SECONDS)`.
- `span.end(...)` is called in a `finally` after `setStatus`, so a throwing SDK cannot cause an
  unfinished span to accumulate in the tracer.
- Attributes are set on the builder before `startSpan()`, so samplers see them.
- Wall-clock placement is millisecond-precision and subject to wall-clock steps during the
  fetch; the span *duration* is exact because both timestamps derive from the same base.

`onFetchStarted`, `onServe`, `onInvalidated`, and `onCleared` produce no spans: they have no
duration, and their facts are fully carried by the counters.

## 7. Implementation mechanics

Representative hook (full code in the implementation plan; the complete sink was compiled and
exercised in the probe):

```kotlin
override fun onServe(key: StoreKey, origin: Origin) {
    try {
        serves.add(1, namespaceAttributes(key).byOrigin.getValue(origin))
    } catch (_: Throwable) {
        // Telemetry observers cannot participate in Store correctness.
    }
}
```

- **Threading.** Hooks may run concurrently. The sink's only mutable state is the
  `ConcurrentHashMap` intern table; a racing first touch of a namespace can build the bundle
  twice and one wins — both are immutable and equivalent. OpenTelemetry meters, tracers, and
  instruments are specified as safe for concurrent use.
- **Interned bundles.** Per namespace: one base `Attributes` (namespace + `extraAttributes`),
  one per `Origin` (built from `Origin.entries` at runtime, so an enum grown in a future core
  is covered without recompiling this module), one per error type. Twelve small immutable maps
  per namespace, built once.
- **Per-event cost** (stated structurally in the README, devtools-style): one
  `ConcurrentHashMap` read after first touch, one instrument `add`/`record` into the app's SDK,
  plus — for spans — one builder and two timestamp computations. `canonicalId()` is invoked
  only when `keyAttributeOnSpans` is enabled.
- **Non-throwing after construction.** Every override wraps its whole body in
  `try { … } catch (_: Throwable) { }` with the comment
  `// Telemetry observers cannot participate in Store correctness.` in each catch (the
  logger's comment, reused verbatim; whole-body guarding itself is this module's stricter
  choice, §2.2).
- **Non-blocking is transitive.** The swallow guards against throwing, not blocking. The seam's
  non-blocking obligation extends to the app's SDK configuration: samplers run inside span
  start and span processors inside span end, synchronously on the calling thread, and metric
  recording writes synchronously into the SDK's aggregation storage (bounded work; reader
  collection and export run on the reader's own schedule). The README states the requirement
  plainly: use batching span processors and prompt samplers; a synchronous exporter in a span
  processor stalls engine threads through this sink. This is the same class of transitive
  obligation the devtools logger places on its `emit` callback ("must return promptly").
- **TD-8 compliance.** No `runBlocking`, `GlobalScope`, `atomicfu`, `Channel`, or `actor`
  anywhere in production sources **[verified — probe passed the repo build with the gate's
  regex clean]**.
- **Error-name mapping** is an exhaustive `when` over sealed `StoreError` — six literals,
  intentionally identical to devtools' internal `storeErrorV0Name` (not reusable without
  widening devtools' API; duplicating six literals is cheaper than coupling the artifacts).

## 8. Testing

All tests in `jvmTest`, file-private 25-second `runTest` shadow convention. The probe already
validated the two hardest cases end to end (Appendix A); the full suite:

1. **Metrics unit tests.** Drive hooks directly with a local `TestKey` against
   `InMemoryMetricReader`: instrument names, units, attribute sets on every instrument
   (including invalidations and clears), counter values, histogram count/sum, `error.type`
   present only on failures, `extraAttributes` merged, overflow coalescing at `maxNamespaces`,
   instrumentation-scope name and version on the exported data.
2. **All six error variants.** `TestStoreResults` factories from `:testing` (`fetchError`,
   `persistenceError`, `conversionError`, `freshnessUnsatisfiable`, `conflict`, `missing`) —
   the `StoreTelemetryLoggerTest` mechanism, since `StoreError` constructors are internal.
   Assert the six `error.type` literals; assert diagnostic message strings appear **nowhere**
   in exported metric or span data (the devtools "no diagnostic payloads" pattern).
3. **Span unit tests.** `InMemorySpanExporter`: name `store6.fetch`, `INTERNAL`, no parent,
   `endEpochNanos − startEpochNanos == duration.inWholeNanoseconds` exactly for normal
   durations; `Duration.INFINITE` and negative durations produce no span **and no histogram
   point** (asserted against a reader on the same SDK); status `ERROR` without description on
   failure, `UNSET` on success; `store6.key` present only when both flags are set;
   `extraAttributes` and overflow coalescing apply to span attributes; scope name and version
   on the span data.
4. **Robustness — three separated fixtures.** (a) A throwing meter with a non-throwing tracer:
   every hook swallows instrument failures. (b) A throwing tracer with a non-throwing meter
   and `emitSpans = true`: terminal hooks swallow span-path failures (a combined
   throwing-everything fixture would never reach the tracer, because the histogram throws
   first). (c) A tracer whose `setStatus` throws after `startSpan`: the started span is still
   ended (the `finally`). Test wrappers must override every fluent builder method the sink
   calls to return the wrapper — interface delegation alone lets fluent methods return the
   delegate and silently escape the wrapper. (d) `OpenTelemetry.noop()`: hooks complete,
   nothing exported, nothing thrown.
5. **Concurrency.** The `StoreTelemetryLoggerJvmConcurrencyTest` precedent: N threads × M
   events across overlapping namespaces; exact counter totals, no lost updates, at most the
   benign duplicate-bundle race.
6. **Version drift guard.** The constant equals the `store6.opentelemetry.versionName` system
   property (§4.3, §4.7); fails if the property is missing.
7. **Integration.** Real `store { fetcher { … }; telemetry(sink) }` (the
   `StoreDevtoolsMonitorIntegrationTest` pattern): `get` → attempts=1, duration count=1,
   serves{origin=FETCHER}=1 **[verified in probe]**; failing fetcher (the engine retries zero
   times by default, so one throwing fetch settles immediately) → duration point with
   `error.type=Fetch`; `invalidate` / `clear` counters; `store.close()` in `finally`.

## 9. Repository integration (complete checklist)

| Surface | Change |
|---|---|
| `settings.gradle` | `include ':opentelemetry'`; `include ':opentelemetry-sample'` + `project(':opentelemetry-sample').projectDir = file('opentelemetry/sample')` |
| `gradle/libs.versions.toml` | Version `opentelemetry = "1.65.0"`; libraries `opentelemetry-api`, `opentelemetry-sdk`, `opentelemetry-sdk-testing` |
| `opentelemetry/` | `build.gradle.kts` (§4.3, complete), `gradle.properties` (`VERSION_NAME=6.0.0-SNAPSHOT`, `POM_NAME=opentelemetry`, `POM_ARTIFACT_ID=opentelemetry`), `src/androidMain/AndroidManifest.xml` (`<manifest />`), sources under `src/jvmAndroidMain/kotlin/`, tests under `src/jvmTest/kotlin/`, `README.md`, `SIGNALS.md`, committed dumps `api/jvm/opentelemetry.api`, `api/android/opentelemetry.api`, and the empty `api/opentelemetry.klib.api` |
| `opentelemetry/sample/` | JVM `application` module (the `realtime/sample` shape): builds an `OpenTelemetrySdk` with `InMemoryMetricReader` and `InMemorySpanExporter` (the sdk-testing artifact keeps the run self-contained and the exports machine-checkable), installs the sink with `emitSpans = true`, runs fetch/invalidate/refetch/clear against one store, `check()`s the five exported instruments and the two fetch spans, prints the exported data, closes the store and the SDK in `finally`, whole run inside `runBlocking { withTimeout(20_000) { … } }` so the CI `run` task terminates |
| `.github/workflows/store6.yml` — linux-build-test | Add build step `./gradlew :opentelemetry:build …` and run step `./gradlew :opentelemetry-sample:run --stacktrace`; add `opentelemetry opentelemetry/sample` to the core-internal-access module list (filesystem paths, not Gradle names); add `opentelemetry/src/*Main` to TD-8 `production_source_dirs` |
| `.github/workflows/store6.yml` — klib-publication-check | **Two coupled edits:** append `:opentelemetry:publishToMavenLocal` to the publish command, and add `opentelemetry` to `modules=(…)` with a `case` arm excluding the ten non-JVM-family suffixes (`-iosarm64`, `-iossimulatorarm64`, `-iosx64`, `-js`, `-linuxx64`, `-macosarm64`, `-mingwx64`, `-tvosarm64`, `-wasm-js`, `-watchosarm64`), commented like the `room` arm |
| `.github/workflows/store6.yml` — other jobs | **No** additions to apple-tests, the JS lock canary, native-stress, or swift jobs (no such targets; the task names would fail) |
| `ci.yml` | No edit — `./gradlew clean build` picks up newly included projects |
| `STABILITY.md` §3 table | Row: `opentelemetry` — Experimental (`@ExperimentalStoreApi`); joins the line in the first release it is green for (the `paging-androidx` phrasing). §7 needs **no** amendment (empty klib dump is committed, §4.3). **STABILITY.md is in `.github/docs-sync-sources.txt`, so the implementation PR needs the `docs-sync-ack` label or its `docs-sync-guard` job fails.** |
| Benchmarks | None required; README states costs structurally, as `devtools/README.md` does. A benchmark case against `OpenTelemetry.noop()` is optional follow-up; no numeric claims ship without it. |

## 10. Documentation deliverables

- `opentelemetry/README.md` — `devtools/README.md` section shape: what it is; install
  (dependency + opt-in + one builder line + composition via `storeTelemetryOf`); targets
  (JVM-family subset and why; the module's own Android `minSdk 24`; the Android consumer
  requirements from §4.1 with citations; the OTel BOM alignment note; R8 note: this artifact
  is API-only and ships no keep rules — the app's SDK carries its own); construction-order and
  `noop()` semantics (§5); a signals summary table linking `SIGNALS.md`; privacy posture
  (§6.3); installed cost and the zero-cost boundary (unset telemetry keeps the engine's null
  fast path untouched); the transitive non-blocking requirement on SDK configuration (§7,
  stated per signal: samplers/span processors synchronous, metric recording synchronous into
  aggregation storage, reader export on its own schedule); a concrete SDK view recipe for
  dropping `store6.serves`; the wire-format deferral sentence.
- `opentelemetry/SIGNALS.md` — vocabulary v0: §6 tables; attribute value definitions; the
  cardinality bound and overflow value; the attempts-vs-settlements heuristic with its
  temporality caveats; seconds-vs-`fetch_ms` unit note; advice-is-a-hint note; the `EVENTS.md`
  change policy shape verbatim: names stable within v0, changes recorded at alpha boundaries,
  "v0 is not the Store 6.1 wire format; that decision remains open."

## 11. Risks

| Risk | Mitigation |
|---|---|
| The `jvmAndroidMain` created source set relies on behavior the Kotlin docs call unsupported (no metadata compilation is attempted for it today) | Empirically green across every repo gate on Kotlin 2.3.20 (Appendix A). Fallback if a toolchain bump breaks it: shared `srcDir` into both platform source sets (also verified green everywhere except Dokka) plus a Dokka source-set suppression. No API change either way. |
| IDE analysis of the intermediate source set may degrade (e.g. unresolved OTel symbols in-editor) | Platform compilations are the authority; CI builds both. Accepted for an experimental artifact; revisit if contributor friction is real. |
| A blocking sampler/processor/reader in the app's SDK stalls engine threads through the sink | Cannot be fixed inside a synchronous bridge without buffering (which would break the zero-state design and add drop semantics). Stated as a transitive contract in README + SIGNALS.md; batch processors recommended; same obligation class as the logger's `emit` contract. |
| `store6.*` never becomes a registered semconv namespace | Acceptable: instrumentation-specific namespaces are the documented convention; the artifact is experimental and v0 may be revised at an alpha boundary. |
| Dynamic namespaces exceed `maxNamespaces` and telemetry coalesces to `overflow` | Deliberate (§6.2): bounded and visible beats unbounded and silent. The limit is a constructor parameter; `SIGNALS.md` documents the behavior and the SDK's own cardinality limits remain the backstop. |
| opentelemetry-java's monthly minors drift ahead of the 1.65.0 pin | `api`-scoped dependency on a stable-tier API with a compatibility guarantee; Renovate manages the catalog; consumers align via their OTel BOM. |
| Version constant drifts from `gradle.properties` | Drift-guard test via forwarded Gradle property (§4.7); fail-closed. |
| Dashboard authors misread `attempts − settlements` | `SIGNALS.md` documents exact semantics and the temporality caveats (§4.5). |
| Consumer at `minSdk` 24–25 ships without desugaring and crashes on-device | Documented consumer requirement with upstream citations (§4.1); README carries the full recipe. CI enforcement (an Android app canary) recorded as follow-up, matching current repo practice of not building consumer apps in CI. |

## 12. Future work (explicitly out of scope)

1. **Wider targets** once opentelemetry-kotlin ships stable metrics and its Kotlin floor is
   compatible with Store6's; the `SIGNALS.md` vocabulary is target-independent and carries over.
2. **Mutations bridge**: a separate artifact (or source set) collecting `MutationStore.events`
   into `store6.mutation.*` instruments, keeping the `mutations` dependency optional.
3. **Fetcher decorator for live spans** with real context propagation, making fetch spans
   parents of the fetcher's own HTTP client spans and members of caller traces.
4. **Crash-breadcrumb sink** as its own design.
5. **Benchmark case** for the sink against `OpenTelemetry.noop()` in `:benchmarks`.
6. **Android consumer canary** proving the desugaring recipe at `minSdk 24` end to end.

## Appendix A — build-probe evidence (2026-08-17)

A full skeleton of this module (complete sink implementation, two tests, module build file,
`gradle.properties`, manifest, `settings.gradle` include, catalog entries) was built in this
repository and then reverted. Environment: Kotlin 2.3.20, AGP 8.10.0, BCV 0.17.0, Dokka from
the repo catalog, opentelemetry-java 1.65.0, JDK 21 host with the toolchain-provisioned JDK 11,
Android SDK platform 36.

| Check | Layout: shared `srcDir` in both platform source sets | Layout: created `jvmAndroidMain` source set |
|---|---|---|
| `:opentelemetry:compileKotlinJvm` + `:opentelemetry:compileReleaseKotlinAndroid` | pass | pass |
| `:opentelemetry:apiDump` | pass — writes `api/jvm/`, `api/android/`, empty `api/opentelemetry.klib.api` | pass — same three files |
| `:opentelemetry:apiCheck` (incl. `klibApiCheck`) | pass with all three dumps committed | pass |
| `:opentelemetry:build` (tests, lint, apiCheck) | pass; `jvmTest` 2/2 | pass |
| `:opentelemetry:dokkaGenerate` | **fail** — "Source sets 'android' and 'jvm' have the common source roots" (Dokka issue 3701) | **pass** |
| `:opentelemetry:publishToMavenLocal` | not run | pass — `opentelemetry` jar, `opentelemetry-jvm` jar, `opentelemetry-android` aar at 6.0.0-SNAPSHOT; POM carries `opentelemetry-api` 1.65.0 at compile scope |
| Metadata compilation of the shared set | n/a (no such source set) | `compileJvmAndroidMainKotlinMetadata` **SKIPPED** |

Probe tests that passed (against the real engine and real SDK):

- `realStoreFetchProducesAttemptDurationAndServeMetrics`: `store { fetcher { … }; telemetry(sink) }`,
  one `get` → exactly `store6.fetch.attempts` = 1, `store6.fetch.duration` count = 1 with
  `store6.namespace = "users"`, `store6.serves` = 1, and one finished `store6.fetch` span.
- `directHooksRecordSpanWithExactDuration`: `onFetchSucceeded(key, 123.milliseconds)` →
  `endEpochNanos − startEpochNanos == 123_000_000` exactly, and `store6.key` present with
  `keyAttributeOnSpans = true`.

OpenTelemetry API surface exercised by the compiled probe: `meterBuilder`/`tracerBuilder` with
`setInstrumentationVersion`, `counterBuilder`/`histogramBuilder` with units and descriptions,
`setExplicitBucketBoundariesAdvice(List<Double>)`, `Attributes.builder().putAll(...)`,
`SpanBuilder.setNoParent()`, `setSpanKind(INTERNAL)`, `setStartTimestamp(long, TimeUnit)`,
`setAllAttributes(Attributes)`, `Span.setStatus(StatusCode.ERROR)`, `Span.end(long, TimeUnit)`.
