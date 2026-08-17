# Store6 SQLDelight adapter

`store6-sqldelight` persists Store6 values and durable freshness metadata in one SQLDelight database. The adapter leaves your generated schema unchanged and creates its own four `store6_meta*` sidecar tables when it is constructed.

The Store6 seam is a **freeze candidate, not frozen** — see [STABILITY.md](../STABILITY.md).

## Install from this checkout

The [sample](sample/src/main/kotlin/org/mobilenativefoundation/store6/sqldelight/sample/Main.kt)
uses project dependencies and is the verified source-checkout path:

```shell
./gradlew :store6-sqldelight-sample:run --args=--reset
./gradlew :store6-sqldelight-sample:run
```

## Existing-schema walkthrough

### 0. Prerequisites

Use Kotlin 2.3, SQLDelight 2.1.0, a JDK supported by your build, and a synchronous SQLDelight
driver. This repository's executable sample uses JDK 11 bytecode and the JDBC SQLite driver. No
remote publication is asserted. For an unpublished Maven Local consumer, publish `store6-core`
and `store6-sqldelight` from this checkout:

```shell
./gradlew :store6-core:publishToMavenLocal :store6-sqldelight:publishToMavenLocal
```

On Linux, native executables also require the SQLite development package and `pkg-config`; the module resolves SQLite's host library directory through `pkg-config` when linking its native-driver tests.

### 1. Add the adapter

Keep the SQLDelight plugin and the driver for your platform, then add the Store6 adapter:

```kotlin
repositories {
    mavenLocal {
        content { includeGroup("org.mobilenativefoundation.store") }
    }
    mavenCentral()
}

dependencies {
    implementation("org.mobilenativefoundation.store:store6-sqldelight:6.0.0-SNAPSHOT")
    implementation("app.cash.sqldelight:sqlite-driver:2.1.0") // JVM sample
}
```

Opt in in the consuming source set:

```kotlin
kotlin.sourceSets.named("main") {
    languageSettings.optIn("org.mobilenativefoundation.store6.core.ExperimentalStoreApi")
}
```

### 2. Keep your existing schema

No Store6 columns, queries, or migrations belong in your `.sq` files. The sample starts with this ordinary user table:

```sql
CREATE TABLE user (
  id TEXT NOT NULL PRIMARY KEY,
  name TEXT NOT NULL,
  email TEXT NOT NULL
);

selectById:
SELECT * FROM user WHERE id = ?;

upsert:
INSERT INTO user(id, name, email)
VALUES (?, ?, ?)
ON CONFLICT(id) DO UPDATE SET
  name = excluded.name,
  email = excluded.email;

deleteById:
DELETE FROM user WHERE id = ?;

deleteAll:
DELETE FROM user;
```

The conflict-update form preserves rows that reference `user`; SQLite's `INSERT OR REPLACE` is
delete-then-insert and can trigger foreign-key cascades. SQLDelight's default SQLite 3.18 dialect
does not parse this syntax, so select SQLite 3.24 or newer in the consumer build:

```kotlin
sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("example.db")
            dialect("app.cash.sqldelight:sqlite-3-24-dialect:2.1.0")
        }
    }
}
```

Create or migrate that schema as usual, then construct the adapter. It idempotently creates `store6_meta_schema`, `store6_meta_sequence`, `store6_meta`, and `store6_meta_watermark` itself. The executable sample probes `sqlite_master` and calls `SampleDatabase.Schema.create(driver)` only for a new database.

### 3. Wire generated queries to Store6

For this schema, `UserKey` implements `StoreKey`, uses `StoreNamespace("users")`, and returns its `id` from `canonicalId()`. `fakeApi` represents your network source and `fetches` is only a counter for the restart demonstration.

```kotlin
val sot = SqlDelightSourceOfTruth<UserKey, User>(
    driver = driver, transacter = db,
    readQuery = { key -> db.userQueries.selectById(key.id) { id, name, email -> User(id, name, email) } },
    writeRow = { _, user -> db.userQueries.upsert(user.id, user.name, user.email) },
    deleteRow = { key -> db.userQueries.deleteById(key.id) },
    deleteNamespaceRows = { ns -> if (ns.value == "users") db.userQueries.deleteAll() },
    deleteAllRows = { db.userQueries.deleteAll() },
)
val store = store<UserKey, User> {
    fetcher { key -> fakeApi.user(key.id).also { fetches++ } }
    persistence(sot)
    bookkeeper(SqlDelightBookkeeper(driver, db))
}
```

Three rules keep the boundary sound:

1. **Round trip:** after `writeRow(key, value)` returns, `readQuery(key)` must return the equivalent `value`.
2. **One driver:** `driver`, `transacter`, every generated query, every mutation callback, and `SqlDelightBookkeeper` must address the same database through the same `SqlDriver`. That is what makes each value-and-meta update atomic. Construct both adapters before exposing the driver to concurrent work because sidecar schema setup is synchronous; afterward, adapter reads and transactions sharing that driver are serialized.
3. **Synchronous transactions:** `withTransaction` is for same-database statements that complete without suspension and remain on the calling thread. Do network, delays, dispatcher changes, and other asynchronous work before or after it. A block that genuinely suspends throws `IllegalStateException`, cancels its child job, and rolls the transaction back; non-cooperative suspension is unsupported.

Adapter-owned writes and deletes notify matching active readers after commit, including equal-value rewrites. Reader signals are instance-scoped: direct SQL inside `withTransaction` and commits made through another adapter instance do not wake an already-active reader. A new collection still reads those external changes from the database.

For an external write that must refresh an already-active Store reader, perform it through this
adapter, then re-collect or invalidate the Store key. The application owns resource shutdown:
stop collectors, call `store.close()`, then close the SQLDelight driver. Do not close the driver
while Store work can still use it. A suspended `withTransaction` block fails and rolls back; after
any cancellation or storage failure, read the durable row before retrying only an idempotent
operation.

Use one logical Store per database and namespace set. Instances sharing a database also share the sidecar's monotone sequence and watermarks.

### 4. Run twice

The included sample stores its database at `build/sample.db`. Reset it for the first process, then run the same binary again without resetting:

```shell
./gradlew :store6-sqldelight-sample:run --args=--reset
./gradlew :store6-sqldelight-sample:run
```

The first process fetches once and atomically persists the user plus durable metadata. The second process includes:

```text
served from SQLDelight without a refetch (durable meta survived the restart): fetches=0
```

Call `close()` when the Store is no longer needed. It is synchronous and idempotent, cancels active collectors, and makes every later operation fail immediately with `IllegalStateException("Store is closed.")`. A Store retains at most `maxIdleKeys` quiescent key engines (default 128); active collectors and in-flight work remain resident until they become quiescent. The sample uses one key.

## Drivers and current limitations

| Platform | SQLDelight dependency | Typical driver | Status |
| --- | --- | --- | --- |
| Android | `app.cash.sqldelight:android-driver:2.1.0` | `AndroidSqliteDriver` | Supported |
| Apple, Linux, Windows | `app.cash.sqldelight:native-driver:2.1.0` | native driver / `inMemoryDriver` | Supported |
| JVM desktop/server | `app.cash.sqldelight:sqlite-driver:2.1.0` | `JdbcSqliteDriver` | Supported |
| JS and Wasm | `app.cash.sqldelight:web-worker-driver:2.1.0` | web-worker driver | Not yet supported; this adapter currently requires synchronous drivers |

The published KMP artifact covers the canonical Store6 targets, but driver-backed execution is limited to targets for which SQLDelight provides a synchronous driver. JS and Wasm remain compile-only for this adapter today. Each source-of-truth write stamps the sidecar's write time and clears its stored ETag. If Store later needs to fetch, the conditional request therefore carries no ETag until a later successful fetch records one.

## Support axes

- **Publish:** Maven Local works after the explicit publish command above; no remote publication is
  asserted here.
- **Compile:** the adapter's KMP artifact follows Store6 targets; JS and Wasm compile but do not
  support this synchronous-driver adapter at runtime.
- **Runtime:** use the platform driver in the table above and one shared `SqlDriver`.
- **Sample:** `store6-sqldelight-sample` is a JVM/JDBC fixture; it proves first fetch, durable
  restart, and conflict-update behavior.

## Timing

No end-to-end consumer-edit timing claim is made. The source-checkout sample is an executable
fixture; measure the Maven Local path in the target project's environment before setting a
time expectation.
