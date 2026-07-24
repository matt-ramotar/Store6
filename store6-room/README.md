# Store6 Room

`store6-room` connects Store6 to an existing Room database. Your DAO remains the
source of truth for application values. The adapter adds two sidecar tables for
freshness metadata and durable invalidation.

## 15-minute existing-database walkthrough

The runnable sample starts with a version-1 database that contains only a
`users` table. It seeds a row and closes the database before Store6 is added.

### 1. Add the dependencies (2 minutes)

Use the same Store6 version for `store6-core`, `store6-room`, and
`store6-testing`. Replace `<version>` with the release you consume:

```kotlin
plugins {
    kotlin("jvm") version "2.2.21"
    id("com.google.devtools.ksp") version "2.2.21-2.0.5"
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
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.sqlite:sqlite-bundled:2.6.2")
    ksp("androidx.room:room-compiler:2.8.4")
}
```

Use the corresponding source set if your database is in a multiplatform
module. This source-set opt-in also covers Room's generated DAO implementation;
a file-level opt-in covers only the file that declares it.

The sample module uses project dependencies, so it is runnable directly from
this checkout.

### 2. Make the three-declaration database diff (3 minutes)

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

### 3. Add the migration (2 minutes)

```kotlin
val addStore6Tables =
    object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) =
            Store6RoomSchema.createTables(connection)
    }

Room.databaseBuilder<AppDatabase>(name = databasePath)
    .addMigrations(addStore6Tables)
```

`Store6RoomSchema.createTables` creates `store6_bookkeeping` and
`store6_watermarks`. It does not alter `users`.

### 4. Wire Store6 to your DAO (5 minutes)

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

### 5. Run and inspect the walkthrough (3 minutes)

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

Durability lives in your Room rows plus the adapter sidecar tables, not in
retained Store engines. Quiescent engine residency is bounded by `maxIdleKeys`
(128 by default). `close()` is synchronous and idempotent, cancels active Store
work, and releases engine residence. An operation started after close fails
immediately with `IllegalStateException("Store is closed.")`.

### Honest timing checklist

Run this against a real existing Room project before calling the 15-minute
claim verified:

- [ ] Start a timer before editing.
- [ ] By 2:00, add the five dependency lines and source-set opt-in.
- [ ] By 5:00, add the two entities and DAO accessor.
- [ ] By 7:00, add and register the migration.
- [ ] By 12:00, map the existing DAO into `RoomSourceOfTruth` and add
      `RoomBookkeeper`.
- [ ] By 15:00, run one legacy read, one cold fetch, one rebuild, and one
      invalidation/rebuild.
- [ ] Record the date, machine, starting project, elapsed time, and any
      corrections: `________________________________________`.

## Testing your wiring

Add `org.mobilenativefoundation.store:store6-testing:<version>` to the test
source set. Extend `SourceOfTruthContractKit<K, V>` for your
`RoomSourceOfTruth` fixture and `BookkeeperContractKit` for your
`RoomBookkeeper` fixture. Return a fresh database-backed adapter from each
factory method.

The inherited suites cover 15 source-of-truth contracts and 6 bookkeeping
contracts on every target where you execute them. Their close and lifecycle
semantics are final. No provisional issue-007 caveats remain.

## Compatibility statement

This release targets Room 2.8.4 and Kotlin **2.2.21**. The versions are
KLIB-ABI locked and Renovate-frozen, so upgrades require one verified toolchain
change.

The adapter supports Room's nine-target KMP subset:

- android
- jvm
- iosX64
- iosArm64
- iosSimulatorArm64
- macosArm64
- watchosArm64
- tvosArm64
- linuxX64

The gaps against `store6-core`'s 12 targets are **js, wasmJs, and mingwX64**.
Use `store6-sqldelight` or a custom source of truth on those targets.

Android consumers need compileSdk 36 and **minSdk 24+**. This repository builds
the AAR with compileSdk 36 and minSdk 24. An AAR built at minSdk 24 cannot be
consumed by an application with a lower minimum.

**Room 3 tracking:** Room 3 uses new `androidx.room3` coordinates. It is
KSP-only and driver-only, adds js/wasmJs, and coexists with Room 2.x.
`store6-room` stays on Room 2.8.x. A Room 3 adapter is a separate follow-up
because it requires a coordinate and API migration, not because of the
repository's Kotlin floor.

Tests execute on jvm, linuxX64, iosSimulatorArm64, and macosArm64. Android,
iosX64, iosArm64, watchosArm64, and tvosArm64 are compile-only in this
repository. CI also exercises cross-module Room code generation, where user
databases reference adapter entities and DAO types from a dependency KLIB.

One conservative edge remains at the exact transaction commit boundary:
cancellation can commit a Room mutation while surfacing cancellation to the
caller. `RoomSourceOfTruth` documents the recovery behavior in its KDoc.
