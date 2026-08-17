# Store6 Room

`store6-room` connects Store6 to an existing Room database. Your DAO remains the
source of truth for application values. The adapter adds two sidecar tables for
freshness metadata and durable invalidation.

The Store6 seam is a **freeze candidate, not frozen** — see [STABILITY.md](../STABILITY.md).

## Install from this checkout

The [sample](sample/src/main/kotlin/org/mobilenativefoundation/store6/room/sample/Main.kt)
uses project dependencies and is the verified source-checkout path:

```shell
./gradlew :store6-room-sample:run
```

For an unpublished Maven Local consumer, publish `store6-core` and `store6-room` from the same
checkout, then constrain Maven Local so it cannot shadow unrelated dependencies:

```kotlin
repositories {
    mavenLocal {
        content { includeGroup("org.mobilenativefoundation.store") }
    }
    mavenCentral()
}
```

Maven Local is a local distribution mechanism, not evidence that this snapshot is remotely
published. Use the dependency and source-set opt-in below with the local version you published.
The sample is the copy-complete fixture: it contains the domain types, imports, database
declarations, migration, Store construction, and expected restart assertions in one file.

## Existing-database walkthrough

The runnable sample starts with a version-1 database that contains only a
`users` table. It seeds a row and closes the database before Store6 is added.

### 1. Add the dependencies

Use the same Store6 version for `store6-core`, `store6-room`, and
`store6-testing`. Replace `<version>` with the release you consume:

```kotlin
plugins {
    kotlin("jvm") version "2.3.20"
    id("com.google.devtools.ksp") version "2.3.10"
    id("androidx.room3") version "3.0.0"
}

kotlin {
    sourceSets {
        getByName("main")
            .languageSettings
            .optIn("org.mobilenativefoundation.store6.core.ExperimentalStoreApi")
    }
}

dependencies {
    implementation("org.mobilenativefoundation.store:store6-core:<version>")
    implementation("org.mobilenativefoundation.store:store6-room:<version>")
    implementation("androidx.room3:room3-runtime:3.0.0")
    implementation("androidx.sqlite:sqlite-bundled:2.7.0")
    ksp("androidx.room3:room3-compiler:3.0.0")
}
```

The `androidx.room3` Gradle plugin registers its extension as `room3` (not Room 2's `room`),
so a schema directory is declared with `room3 { schemaDirectory(...) }`. This release line also
requires Kotlin ≥2.3 and, on Android, AGP ≥8.10.

Use the corresponding source set if your database is in a multiplatform
module. This source-set opt-in also covers Room's generated DAO implementation;
a file-level opt-in covers only the file that declares it.

The sample module uses project dependencies, so it is runnable directly from
this checkout.

### 2. Make the three-declaration database diff

Keep every existing entity and DAO. Add the two adapter entities and one DAO
accessor:

```kotlin
@Database(
    entities = [
        UserEntity::class,
        Store6BookkeepingEntity::class,
        Store6WatermarkEntity::class,
    ],
    version = 2,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun store6BookkeeperDao(): Store6BookkeeperDao
}
```

This is the precise schema claim: Store6 changes no columns or constraints in
your tables. It adds two sidecar tables, which requires one database-version
bump and one migration.

### 3. Add the migration

```kotlin
val addStore6Tables =
    object : Migration(1, 2) {
        // androidx.room3 Migration.migrate is suspend. Store6RoomSchema.createTables is not:
        // androidx.sqlite 2.7.0's execSQL is synchronous, so it is called directly.
        override suspend fun migrate(connection: SQLiteConnection) =
            Store6RoomSchema.createTables(connection)
    }

Room.databaseBuilder<AppDatabase>(name = databasePath)
    .addMigrations(addStore6Tables)
```

`Store6RoomSchema.createTables` creates `store6_bookkeeping` and
`store6_watermarks`. It does not alter `users`.

### 4. Wire Store6 to your DAO

```kotlin
@file:OptIn(ExperimentalStoreApi::class)

val dao = database.userDao()
val users =
    store<UserKey, User> {
        fetcher { key -> api.getUser(key.id) }
        persistence(
            RoomSourceOfTruth(
                database = database,
                rowReader = { key ->
                    dao.user(key.id).map { row -> row?.toUser() }
                },
                rowWriter = { _, user -> dao.upsert(user.toEntity()) },
                rowDeleter = { key -> dao.delete(key.id) },
                namespaceDeleter = { _ -> dao.deleteAll() },
                allDeleter = { dao.deleteAll() },
            ),
        )
        bookkeeper(
            RoomBookkeeper(
                database = database,
                dao = database.store6BookkeeperDao(),
            ),
        )
    }
```

The adapter needs the same `RoomDatabase` for the source of truth and
bookkeeper so their operations use the same database lifecycle.

### 5. Run and inspect the walkthrough

```shell
./gradlew :store6-room-sample:run
```

The program checks four observable behaviors and exits nonzero if any check
fails:

1. The legacy row is served from the source of truth with no fetch.
2. A cold key emits `Loading`, then `Data` from the fetcher.
3. A new Store and Room instance over the same file serves that row fresh and
   non-refreshing from the source of truth, without another fetch, because its
   metadata is durable.
4. Namespace invalidation survives another rebuild and forces a refetch.

Durability lives in your Room rows plus the adapter sidecar tables, not in retained Store engines.
Quiescent engine residency is bounded by `maxIdleKeys` (128 by default). The application owns both
resources: stop UI collectors, call `store.close()`, then call `database.close()` in `finally`.
`close()` is synchronous and idempotent; an operation started after close fails immediately with
`IllegalStateException("Store is closed.")`. Do not close the Room database before the Store.

Direct writes through another Room database handle can wake an active Room query. If they race a
Store write for the same key, the adapter can coalesce the external change into the Store echo or
the next re-query. Treat a cancelled mutation at the commit boundary as outcome-unknown: read the
row again, then retry only an idempotent operation if its intended durable state is absent.

## Testing your wiring

Add `org.mobilenativefoundation.store:store6-testing:<version>` to the test
source set. Extend `SourceOfTruthContractKit<K, V>` for your
`RoomSourceOfTruth` fixture and `BookkeeperContractKit` for your
`RoomBookkeeper` fixture. Return a fresh database-backed adapter from each
factory method.

The inherited suites cover 15 source-of-truth contracts and 6 bookkeeping
contracts on every target where you execute them. Their close and lifecycle
semantics are final.

## Compatibility statement

store6-room targets **androidx.room3 3.0.0** (Room's KMP-first line),
pinned with Kotlin **2.3.20** and androidx.sqlite **2.7.0**. These versions are
KLIB-ABI locked and Renovate-frozen; they move only with the repository's
toolchain.

Supported targets:

- android
- jvm
- iosArm64
- iosSimulatorArm64
- macosArm64
- watchosArm64
- tvosArm64
- linuxX64

**Gaps vs `store6-core`'s 12 targets: js, wasmJs, mingwX64, and iosX64.**
`androidx.room3` publishes no iosX64 variant. js and wasmJs *are* supported by that release line and are planned
as a follow-up — they require a suspend schema API and async SQLite integration.
Use `store6-sqldelight` or a custom source of truth on those targets meanwhile.

Android consumers need **compileSdk 34+** — the floor room3's AAR metadata
declares — and **minSdk 24+**. This repository builds the AAR at compileSdk 36.
An AAR built at minSdk 24 cannot be consumed by an application with a lower
minimum.

**Room 2.x is not supported by this artifact.** store6-room launched on the `androidx.room3` line;
it never shipped a Room 2 release. That line uses new coordinates, packages, and
type identity, and its native KLIBs require Kotlin ≥2.3 with AGP ≥8.10 — it is
gated on both a toolchain move and a migration, not one or the other. Room 2
applications should migrate the app database to `androidx.room3` (Google documents 2.x/3.x
coexistence) or use `store6-sqldelight`.

Tests execute on jvm, linuxX64, iosSimulatorArm64, and macosArm64. Android,
iosArm64, watchosArm64, and tvosArm64 are compile-only in this repository. CI
also exercises cross-module Room code generation, where user databases reference
adapter entities and DAO types from a dependency KLIB.

At the exact transaction commit boundary, cancellation can leave the caller
without a durable-outcome confirmation. Read the row again before retrying;
only retry an idempotent intended state. `RoomSourceOfTruth` documents the
underlying transaction behavior in its KDoc.

## Support axes

- **Publish:** Maven Local is supported for a locally published snapshot; no remote publication is
  asserted here.
- **Compile:** the artifact targets the platforms listed above, subject to the Room 3 toolchain and
  Android SDK floors.
- **Runtime:** execution depends on a supported Room/SQLite runtime for that platform.
- **Sample:** `store6-room-sample` is a JVM fixture and checks legacy migration, cold fetch,
  restart durability, invalidation, and close order.
