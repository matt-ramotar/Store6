# Invalidate or clear

`invalidate` and `clear` express different trust decisions. Invalidation retains a value and marks
it stale. Clear removes a value from one Store. Neither operation is an application authorization
boundary.

The one-line version:

- **`invalidate` marks stale and retains the value.** Whether a read serves it or fetches depends on
  that read's `Freshness`.
- **`clear` removes from one Store.** Active or later demand can fetch and populate the key again.

## What invalidate does

`invalidate(key)` marks the value stale without removing it. The result is freshness-specific:

- `Freshness.CachedOrFetch` serves the retained value as stale and starts a background refresh.
- `Freshness.MustBeFresh` withholds the retained stale value while it fetches.
- `Freshness.LocalOnly` serves locally available data without invoking the fetcher, even after
  invalidation.
- `Freshness.MaxAge` and `Freshness.StaleIfError` apply their own documented stale-data rules.

Three properties are worth knowing because they are what make it safe to call:

- **The stale mark follows the configured bookkeeper's lifetime.** A durable bookkeeper can retain
  it across process death. The default bookkeeper and source of truth are in memory, so the default
  configuration does not.
- **It is level-triggered monotone state**, so a signal issued during any race window is never lost.
  You do not have to reason about whether a fetch was in flight when you called it.
- **Compatible active demand is signaled.** A collector whose freshness permits fetching can
  observe a refresh without the retained value being deleted. `LocalOnly` never fetches.

Use invalidation when the old value is imperfect but still safe to retain. The exact UI transition
comes from the caller's freshness policy, not from invalidation alone.

<!-- recipe: shapes from store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/Store.kt:70-98 (invalidate/invalidateNamespace signatures and their contracts); behavior per StoreInvalidationConformanceTest.invalidate_activeStream_observesRefetchedData -->

```kotlin
import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult

class User(val id: String, val name: String)

class UserKey(val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = id
}

// Mark the value stale. The observing read's Freshness controls whether it stays visible.
suspend fun onPullToRefresh(
    store: Store<UserKey, User>,
    key: UserKey,
) {
    store.invalidate(key)
}

// CachedOrFetch gives this collector the stale-while-revalidate shape.
fun observeUser(
    store: Store<UserKey, User>,
    key: UserKey,
): Flow<StoreResult<User>> = store.stream(key)
```

## What clear does

`clear(key)` destructively removes the value from one Store. On return, its resident value,
configured source-of-truth row, and freshness bookkeeping are gone. Active fetch-capable demand can
observe the absent transition and then refetch.

Three properties matter here:

- **An in-flight fetch that started before the clear can no longer commit.** Its waiters observe
  `StoreError.Missing`. A clear racing a fetch cannot resurrect the discarded value.
- **A post-clear stream never replays pre-clear data.** It starts absent or loading. This is a
  per-Store guarantee, not a timing accident.
- **Clear does not reserve future absence.** Active or later demand can invoke the fetcher and
  populate the key again.

Use clear when the retained value is wrong to serve. For session replacement, first stop new demand
and shut down old-session work. Calling clear while old demand remains active can immediately start
a new fetch.

<!-- recipe: shapes from store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/Store.kt:113-164 (clear/clearNamespace/clearAll signatures and their contracts); behavior per StoreInvalidationConformanceTest.clear_thenNewStreamEmitsLoadingNeverStaleReplay -->

```kotlin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

class User(val id: String, val name: String)

class UserKey(val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = id
}

class Document(val id: String, val title: String)

class DocumentKey(
    val tenantId: String,
    val documentId: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("documents")

    override fun canonicalId(): String = "$tenantId:$documentId"
}

// This clears one old-session Store. Application-level session teardown must gate demand,
// stop workers, clear every old Store, and close old resources before publishing a replacement.
suspend fun clearOneOldSessionStore(store: Store<UserKey, User>) {
    store.clearAll()
}

// A record the server reported as deleted: remove it rather than refreshing it.
suspend fun onRecordDeleted(
    store: Store<UserKey, User>,
    key: UserKey,
) {
    store.clear(key)
}

// A namespace is a bulk-maintenance scope inside this Store, not an authorization check.
suspend fun clearDocuments(
    store: Store<DocumentKey, Document>,
) {
    store.clearNamespace(StoreNamespace("documents"))
}
```

## Choosing

| You want to say | Use | The user sees |
|---|---|---|
| "This might be out of date" | `invalidate` | Determined by the read's `Freshness` |
| "This key is no longer valid to retain" | `clear` | It becomes absent; demand may refetch |
| "This maintenance group is stale" | `invalidateNamespace` | Affected reads apply their freshness policies |
| "Remove this maintenance group from one Store" | `clearNamespace` | Affected keys become absent; demand may refetch |
| "Remove everything from one Store" | `clearAll` | That Store becomes empty; demand may refetch |

The deciding question is whether the retained value is still safe to serve. If it is safe but
possibly outdated, invalidate it. If it is unsafe to retain, stop unsafe demand and clear it.

## Read-policy consequences

With the default `Freshness.CachedOrFetch`, invalidation retains and serves the stale value while a
background refresh runs. A successful fetch produces fresh `Data`; `NotModified` produces
`Revalidated`; and a failure reports an error while the stale value remains available.

`Freshness.MustBeFresh` does not have that stale-while-revalidate shape. It withholds residence and
waits for a fresh fetch. `Freshness.LocalOnly` takes the opposite posture: it can serve the retained
local value after invalidation but never invokes the fetcher.

After `clear`, there is nothing to serve. A fetch-capable stream starts from `Loading`, and `get`
blocks for the fetch result. `Freshness.LocalOnly` reports `StoreError.Missing` without fetching.

## Remote deletion is cycle-local

`FetcherResult.Deleted` clears the key and reports `StoreError.Missing` for that fetch cycle. It does
not install a permanent tombstone. It schedules no automatic refetch in the same cycle, but later
demand can invoke the fetcher again and repopulate the key.

## Replacing an authenticated session

A Store can fence commits only within that Store. It cannot make teardown across multiple Stores or
storage systems atomic, and `StoreNamespace` is not an authorization boundary. Put tenant identity
in key identity and partition storage by tenant or session. Use separate Store and storage instances
for separate authenticated sessions.

Apply this order at the application boundary:

1. Close an application gate so no new old-session demand can reach any old Store.
2. Cancel and join every old-session collector and worker.
3. Revoke the old credentials.
4. Quarantine the old session's pending mutation journal in its external storage partition.
5. Call `clearAll()` on every old Store. Attempt every Store even if one cleanup fails.
6. Close every old Store and its storage resources.
7. Publish replacement credentials and new Store instances only after every cleanup step succeeds.

Fail closed: any gate, join, journal, clear, or close failure prevents publication of the replacement
session. Still close old resources after a clear failure. `close()` is idempotent. After close,
Store operations fail with `IllegalStateException` and the message `Store is closed.`

This ordering assumes all Store access passes through the application gate and local collectors,
workers, and fetchers cooperate with coroutine cancellation. Cancellation does not prove that a
remote side effect was cancelled or reversed. Core also has no mutation-journal purge operation;
journal quarantine is an application storage responsibility. The per-Store clear fence remains the
last defense against a pre-clear fetch ticket returning late and committing.

---

*Last verified: 2026-08-16 · `5a8c956bc1dbd6ad838ea9da3b34c7d76c703a71`,
pre-6.0.0-alpha01*
