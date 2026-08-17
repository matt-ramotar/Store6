# Quickstart

> Store 6 is in development and **nothing is published yet**. This page is the shape of the API as
> it stands on `main`; the install coordinates land with 6.0.0-alpha01.

Store needs two things from you: a **key** that identifies what you want, and a **fetcher** that
knows how to go get it. Everything else — sharing one in-flight request across concurrent callers,
serving what is already resident, tracking staleness, bounding memory — is what Store does with
those two things.

Here is the whole idea in five lines.

<!-- display: store block verbatim from store6-quickstart/src/main/kotlin/org/mobilenativefoundation/store6/quickstart/Main.kt:49-51, dedent 8 (parity-checked); the stream and get lines are display forms, shapes from Main.kt:53-62, NOT parity-checked -->

```kotlin
val users = store<UserKey, User> {
    fetcher { key -> FakeApi.getUser(key.id) }
}

users.stream(UserKey("1")).collect { result -> render(result) }
val user = users.get(UserKey("2"))
```

The `store { }` block is verbatim from the executable
[`store6-quickstart`](../../store6-quickstart/) module. The last two lines are shown in their
simplest form so the shape is legible. The program below contains the real `stream` and `get` call
sites.

## The whole program

Use JDK 17 or newer. Gradle is supplied by the repository wrapper, so no separate Gradle
installation is needed. From a clean source checkout at the repository root, run:

```sh
./gradlew :store6-quickstart:run --console=plain
```

The program output is deterministic:

```text
Loading…
Data(name=User 1, origin=FETCHER)
get: User 2
```

The [`Store6` workflow](../../.github/workflows/store6.yml) includes this module's
`./gradlew :store6-quickstart:run --stacktrace` step for pushes and pull requests to `main`.
That is the workflow definition observed in this checkout, not evidence of a particular CI run.

If the command fails, confirm that `java -version` reports JDK 17 or newer, run it again from the
repository root, and retain Gradle's `--stacktrace` output when reporting an unresolved failure.
The source is
[`store6-quickstart/src/main/kotlin/org/mobilenativefoundation/store6/quickstart/Main.kt`](../../store6-quickstart/src/main/kotlin/org/mobilenativefoundation/store6/quickstart/Main.kt).

Supporting declarations — the key, the model, and a stand-in service:

<!-- verbatim: store6-quickstart/src/main/kotlin/org/mobilenativefoundation/store6/quickstart/Main.kt:1-39, dedent 0 (parity-checked) -->

```kotlin
package org.mobilenativefoundation.store6.quickstart

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store

/** Identifies a user by the stable identifier used by the example service. */
private class UserKey(
    /** The user identifier passed to the example service. */
    val id: String,
) : StoreKey {
    /** The namespace shared by user records in the example store. */
    override val namespace: StoreNamespace = StoreNamespace("users")

    /** Returns the service identifier used to distinguish this user from other users. */
    override fun canonicalId(): String = id
}

/** A user record returned by the example service. */
private class User(
    /** The stable identifier assigned to this user. */
    val id: String,

    /** The display name returned by the example service. */
    val name: String,
)

/** Provides deterministic user data for the executable example. */
private object FakeApi {
    /** Returns a user after simulating an asynchronous service call. */
    suspend fun getUser(id: String): User {
        delay(100)
        return User(id, "User $id")
    }
}
```

A `StoreKey` gives Store two things: a `namespace`, which groups related records so you can
invalidate or clear them together, and a `canonicalId()`, which distinguishes one record from
another inside that namespace. Key design is the one skill Store asks you to learn, and it has its
own guide: [Keys and Namespaces](key-design.md).

And `main`:

<!-- verbatim: store6-quickstart/src/main/kotlin/org/mobilenativefoundation/store6/quickstart/Main.kt:47-63, dedent 0 (parity-checked) -->

```kotlin
public fun main(): Unit =
    runBlocking {
        val users = store<UserKey, User> {
            fetcher { key -> FakeApi.getUser(key.id) }
        }

        users.stream(UserKey("1")).take(2).collect { result ->
            when (result) {
                is StoreResult.Loading -> println("Loading…")
                is StoreResult.Data -> println("Data(name=${result.value.name}, origin=${result.origin})")
                is StoreResult.Revalidated -> println("Revalidated(age=${result.age})")
                is StoreResult.Error -> println("Error(${result.error})")
            }
        }
        println("get: ${users.get(UserKey("2")).name}")
        users.close()
    }
```

## Reading the output

`stream` gives you a `StoreResult`, and there are exactly four kinds. Handle all four and there is
no fifth case waiting to surprise you:

- **`Loading`** — demand has been registered and no value is available yet.
- **`Data`** — a value, carrying an `origin` that tells you where it came from (`FETCHER`, `SOT`,
  `MEMORY`, `OVERLAY`) and whether it is stale or refreshing. The example prints the origin because
  attribution honesty is a contract, not a debugging aid.
- **`Revalidated`** — the server said nothing changed. You get one of these with the resident value's
  age, rather than a redundant `Data` frame.
- **`Error`** — the fetch failed. If a stale value was resident, you will have been served it first.

One detail worth naming so it does not read as magic: **`take(2)` is what ends this program.**
`stream` is an unbounded flow that stays live for as long as you collect it. The example takes the
first two frames — `Loading`, then `Data` — and stops. In an app you collect for the lifetime of the
screen instead, and `close()` the store when you are done with it.

Continue with [the read contract](/docs/store6/concepts/read-contract) for result and failure
semantics, then [freshness policies](/docs/store6/concepts/freshness) for choosing how each read
uses resident and fetched data. This completes the Core tutorial.

Mutation work is separate. Start with the
[`store6-mutations-quickstart` executable](../../store6-mutations-quickstart/src/main/kotlin/Main.kt).
Its default journal is in memory. Surviving process death or device restart requires an explicit
durable `MutationJournalStorage`; retaining an in-memory journal only supports reconstruction while
that collaborator remains alive.

---

*Last verified: 2026-08-10 · `main` @ `a6a156e9`, pre-6.0.0-alpha01*
