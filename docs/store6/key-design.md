# Keys and namespaces

Key design is the one thing Store asks you to get right. Everything else has a sensible default. A
key does not, because only you know how your data is shaped.

It is worth the attention because a `StoreKey` is doing two jobs at once, and they have different
consequences when you get them wrong.

## The two jobs

<!-- provenance: store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/StoreKey.kt:9-19 — the landed public interface, declarations only (KDoc elided; not a copy-paste recipe) -->

```kotlin
public interface StoreKey {
    public val namespace: StoreNamespace
    public fun canonicalId(): String
}
```

**`canonicalId()` is identity.** Two keys with the same namespace value and the same canonical id
are the same key: they share one in-flight fetch, one resident value, one stale mark. Two keys with
different canonical ids share nothing. This is the lever that controls deduplication. Get it too
narrow and you fetch the same thing twice under two names. Get it too wide and two different things
collide on one cache entry.

Core keeps `namespace.value` and `canonicalId()` as two separate identity components. It does not
derive identity by joining them with a delimiter. Both components use exact, case-sensitive string
comparison without Unicode normalization.

**`namespace` is the unit of bulk operations.** It is what `invalidateNamespace` and `clearNamespace`
act on, and the durable watermark it carries covers keys the store has never even seen. This is the
lever that controls how much you can invalidate in one call.

Because identity is a `String`, the rule is simple: **the canonical id must be stable for the
lifetime of the key, and it must contain everything that makes the result different.** If two
requests would return different bytes, their canonical ids must differ.

## The smallest correct key

<!-- recipe: shapes from store6-quickstart/src/main/kotlin/org/mobilenativefoundation/store6/quickstart/Main.kt:11-21 (the landed key implementation CI compiles and runs) -->

```kotlin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

class UserKey(val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = id
}
```

One namespace per record type, the record's own identifier as the canonical id. Start here. Most
keys never need to be more than this.

## When the id needs more than an identifier

If the same record can come back differently depending on the request, the difference belongs in the
canonical id. A user record fetched with expanded relationships is not the same value as the same
user fetched without them, and it must not overwrite it.

<!-- recipe: derived from the same StoreKey contract as above; the framed composite-id shape follows canonicalId()'s "unique within the key's namespace" contract in StoreKey.kt -->

```kotlin
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

private const val HEX = "0123456789abcdef"

private fun canonicalFieldsV1(vararg fields: String): String = buildString {
    append("s6k1")
    for (field in fields) {
        val utf8 = field.encodeToByteArray()
        append(':')
        append(utf8.size)
        append(':')
        for (byte in utf8) {
            val unsigned = byte.toInt() and 0xff
            append(HEX[unsigned ushr 4])
            append(HEX[unsigned and 0x0f])
        }
    }
}

class UserKey(
    val tenantId: String,
    val id: String,
    val representation: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")

    override fun canonicalId(): String = canonicalFieldsV1(tenantId, id, representation)
}
```

The format starts with the `s6k1` version marker. Each field then contributes
`:<UTF-8 byte count>:<lowercase hexadecimal UTF-8 bytes>`. The encoded sequence has these exact
properties:

- No fields encode as `s6k1`; one empty field encodes as `s6k1:0:`.
- Field order is significant.
- Case is preserved. The encoder does not case-fold or normalize Unicode, so `é` and `e` followed by
  a combining acute accent remain different fields.
- Delimiters and control characters, including NUL, are encoded as bytes rather than interpreted as
  framing.
- A format change requires a new version marker.

Within this format, different ordered sequences of UTF-8 field bytes produce different canonical
ids. The encoder does not decide which fields belong in identity. Include every field that changes
the returned value, and keep their order and preprocessing stable.

Two things to avoid here. Do not put anything in the canonical id that changes between two requests
you *want* deduplicated, such as a timestamp, a request id, or a nonce. And do not put a secret in
it, because the canonical id is a cache key and durable implementations can persist it.

## Choosing namespaces

Namespaces are cheap. Use one per record type as the default, and split further when you want a
smaller blast radius for bulk invalidation.

The question to ask is: *what do I want to invalidate together?* A pull-to-refresh on a user's
profile screen should invalidate the user, not everything. A sign-out should clear everything. A
"this organization's data changed" push notification is exactly the case for a per-organization
namespace, because it lets one call invalidate the right subset instead of all of it.

<!-- recipe: shapes from store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/Store.kt:84-147 (the landed invalidate/clear signatures); namespace-watermark behavior per StoreInvalidationConformanceTest and StoreDurableMaintenanceConformanceTest -->

```kotlin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

class Document(val id: String, val title: String)

class DocumentKey(
    val organizationId: String,
    val documentId: String,
) : StoreKey {
    override val namespace: StoreNamespace =
        StoreNamespace(canonicalFieldsV1("documents", organizationId))

    override fun canonicalId(): String = documentId
}

suspend fun onOrganizationChanged(
    store: Store<DocumentKey, Document>,
    organizationId: String,
) {
    store.invalidateNamespace(
        StoreNamespace(canonicalFieldsV1("documents", organizationId)),
    )
}
```

A tenant identifier must participate in key identity whenever the same record identifier can occur
in more than one tenant. Put it in the canonical id when all tenants should share one maintenance
group. Put it in the namespace, as in the example above, when namespace invalidation and clearing
must be tenant-scoped. Keep the namespace and canonical id as separate components in either design.

A namespace is only a maintenance grouping. Matching a tenant-scoped namespace does not authorize a
read or write, and Store does not replace authorization checks in the fetcher or source of truth.

## The payoff

Once keys are right, the namespace-level operations become the tool you reach for:

- `invalidate(key)` and `invalidateNamespace(namespace)` mark values stale without removing them.
  Active streams are signaled on return and observe refetched data, and the resident value keeps
  serving in the meantime.
- `clear(key)`, `clearNamespace(namespace)`, and `clearAll()` destructively remove values.
- The namespace and global watermarks are **durable**, so they cover keys that are not currently
  resident and survive process restart. Invalidating a namespace before a key has ever been fetched
  still makes that key's first read honest.

Which of invalidate and clear you want is its own decision, and it has its own guide:
[Invalidate or Clear](invalidate-vs-clear.md).

## Namespace equality

`StoreNamespace` has value equality and a matching value-based hash code. Independently constructed
instances with the same `.value` compare equal and are interchangeable as map or set keys. Equality
uses the exact string: it is case-sensitive and does not normalize Unicode.

Compare complete Store identities component by component: namespace value with namespace value, and
canonical id with canonical id. Do not flatten the pair into one delimiter-joined string. These are
cache identifiers rather than secrets or authorization decisions.

## Changing a key format

Changing a field, field order, preprocessing rule, namespace value, or encoder version creates a
different Store identity. Plan that change as a data migration:

1. Either migrate or dual-read the old identity before writing the new one, or purge durable data
   under the old identity before rollout.
2. If the namespace changes, clear or purge the old namespace explicitly. Maintenance on the new
   namespace does not reach records or watermarks under the old value. Store namespace clears
   preserve durable stale watermarks by contract, so removing an obsolete watermark requires a
   backend migration or retention policy.
3. If only the canonical format changes, a per-key clear using the new id cannot reach old ids. A
   namespace clear removes old and new ids in that maintenance group, so coordinate the resulting
   refetches.
4. Do not fall back from a tenant-qualified identity to an unqualified or different tenant identity.
   Migration does not weaken authorization boundaries.

Without migration or purge, durable rows under old identities become unreachable from new keys.
They can remain until backend retention removes them, consume storage, and retain stale or sensitive
cached data. An obsolete namespace watermark can remain too. Record the format version outside the
opaque id as well when the backing store needs to enumerate or migrate versions.

## One store or many

Namespaces partition the maintenance blast radius within one store: use them when records share a
typed Store boundary but need separate `invalidateNamespace` or `clearNamespace` scopes.

Freshness is not a store-topology choice. Each `stream` or `get` call selects its own `Freshness`
policy, so callers using one store can make different read decisions.

Use separate stores when domains need independent typed value, failure, and lifecycle boundaries.
This separation does not make operations across stores atomic; no cross-store transaction is part
of the `Store` contract.

---

*Last verified: 2026-08-16 · `5a8c956b`, pre-6.0.0-alpha01*
