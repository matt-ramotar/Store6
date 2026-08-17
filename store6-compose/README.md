# store6-compose

Compose Multiplatform integration for Store v6. Everything here is `@ExperimentalStoreApi`.
The seam it consumes is a freeze candidate, not frozen — see [STABILITY.md](../STABILITY.md).

## Install from this checkout

The repository is the executable source-checkout path: depend on `projects.store6Compose`
from a module included in this build. No remote Store6 artifact is claimed here. For an
unpublished Maven Local consumer, publish the adapter and core first, then scope Maven Local
to Store's group so it cannot shadow unrelated dependencies:

```shell
./gradlew :store6-core:publishToMavenLocal :store6-compose:publishToMavenLocal
```

```kotlin
repositories {
    mavenLocal {
        content { includeGroup("org.mobilenativefoundation.store") }
    }
    mavenCentral()
}

dependencies {
    implementation("org.mobilenativefoundation.store:store6-core:<local-version>")
    implementation("org.mobilenativefoundation.store:store6-compose:<local-version>")
    implementation("org.jetbrains.compose.runtime:runtime:<your-compose-version>")
}
```

Opt in in the consuming source set:

```kotlin
kotlin.sourceSets.named("commonMain") {
    languageSettings.optIn("org.mobilenativefoundation.store6.core.ExperimentalStoreApi")
}
```

## Entry points

- `Store.collectAsState(key, freshness)` — `State<StoreResult<V>>`, starts at `Loading`,
  restarts only on structural identity change (namespace/canonicalId/freshness), all targets.
- `Flow<StoreResult<V>>.collectAsStoreState(initial)` — the flow-level variant.
- `Store.collectAsStateWithLifecycle(...)` / `collectAsStoreStateWithLifecycle(...)` —
  lifecycle-gated via `repeatOnLifecycle`, on all targets. These need a `LifecycleOwner`; on
  targets with no UI host that populates `LocalLifecycleOwner`, pass one explicitly.
- `skipEqualData()` / `storeResultMutationPolicy()` — structural skipping for stateIn/ViewModel
  flows and custom state holders.

## Minimal accessible screen

`UserKey` is your `StoreKey` implementation and `User` is your domain type. This pattern handles
the first load, current data, a stale badge, and errors while retaining the last data for an
error frame. It uses the lifecycle-gated entry point; pass an explicit `LifecycleOwner` when the
host does not provide `LocalLifecycleOwner`.

```kotlin
@file:OptIn(ExperimentalStoreApi::class)

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.mobilenativefoundation.store6.compose.collectAsStateWithLifecycle
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreResult

data class User(val name: String)

@Composable
fun UserResult(store: Store<UserKey, User>, key: UserKey) {
    val result by store.collectAsStateWithLifecycle(key)
    var lastData by remember { mutableStateOf<StoreResult.Data<User>?>(null) }
    if (result is StoreResult.Data) SideEffect { lastData = result }

    when (val frame = result) {
        is StoreResult.Loading -> CircularProgressIndicator(
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        is StoreResult.Data -> {
            Text(frame.value.name)
            if (frame.isStale) Text("Showing stale data")
        }
        is StoreResult.Error -> {
            lastData?.let { Text(it.value.name) }
            Text("Could not refresh data", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        is StoreResult.Revalidated -> Unit
    }
}
```

`Revalidated` and `Error` can be event-shaped rather than durable UI state. Collect
`store.stream(key)` in a `LaunchedEffect` for one-shot analytics, announcements, or retry
commands; do not make effects depend only on a state value that can be superseded. The runnable
[desktop demo](../store6-compose-demo/src/main/kotlin/org/mobilenativefoundation/store6/composedemo/DemoScreen.kt)
exercises loading, data, stale content, and a refresh failure; run
`./gradlew :store6-compose-demo:run`.

## Support axes

- **Publish:** Maven Local is usable after locally publishing the snapshot; no remote publication
  is asserted here.
- **Compile:** this Compose Multiplatform module compiles for Store6 targets with the matching
  Compose and lifecycle dependencies.
- **Runtime:** lifecycle collection needs a host-provided or explicit `LifecycleOwner`; plain
  `collectAsState` does not.
- **Sample:** `store6-compose-demo` is a JVM desktop demo and is linked above; it is intentionally
  outside this integration module's source tree.

## Recomposition discipline

`StoreResult` types deliberately have identity equality. This module skips recomposition by
structural comparison of `Data`'s value/origin/isStale/refreshing — `age` is excluded (it
advances every emission). Results are never merged across kinds. That mirrors the engine's
`conflateLatestData` rule (same-kind latest-wins; never merged across
kinds): "Revalidated is a lifecycle signal: `conflateLatestData` never conflates it away in
favor of another kind; for a blocked collector a newer `Revalidated` supersedes an older queued
one, so the kind itself is never lost." This module is stricter still — `Loading`/`Revalidated`/
`Error` always pass; only structurally-equal consecutive `Data` frames are dropped. Event-shaped
consumption of `Revalidated`/`Error` should collect the Flow, not a State.

## Stability configuration for consumers

Strong skipping (default since Kotlin 2.0.20) compares unstable parameters by instance; this
module's state holders keep instances stable across equal frames, so skipping works out of the
box. To make store types compare as stable values instead — which is what lets the compiler skip
on equal *content* rather than equal *instance* — add the shipped snippet
(`stability/store6-stability.conf`, reproduced below) to your app module:

    composeCompiler {
        stabilityConfigurationFiles.add(
            layout.projectDirectory.file("store6-stability.conf"),
        )
    }

    // store6-stability.conf  (mirror of the shipped file)
    org.mobilenativefoundation.store6.core.*
    org.mobilenativefoundation.store6.core.seam.*

CI verifies this exact snippet against a tiered probe of core public types on every PR. With the
snippet applied, every probed core type — including the interface-typed ones (`StoreResult`,
`Freshness`, `StoreKey`, `StoreMeta`, `StoreError`) and the generic `StoreResult.Data<V>` —
resolves as **stable**; without it, they resolve as `unstable` and the CI gate fails.

Note that `composeCompiler.stabilityConfigurationFiles` is not registered as a Gradle task input
by the Compose compiler plugin, and the emitted stability reports are an undeclared output. This
module's build scripts compensate (`inputs.file(...)` plus opting the demo compilations out of
the build cache) so that editing the conf always re-emits a matching report; consumers relying on
their own report-based checks should do the same.

## Demo

`./gradlew :store6-compose-demo:run` — refreshing spinner-over-content, STALE badge, and
error-with-stale-data against a fake fetcher with toggleable latency and failure.
