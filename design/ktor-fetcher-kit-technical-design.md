# Ktor fetcher kit — technical design

Status: draft, revised after two adversarial reviews (GPT-5.6 Sol, Cursor Grok 4.6). Target
artifact: `org.mobilenativefoundation.store:ktor:6.0.0-SNAPSHOT` (module path `:ktor`, package
`org.mobilenativefoundation.store6.ktor`). Tier: experimental (`@ExperimentalStoreApi`), like every
other Store 6 extension.

This is the design of record for the extension the Store 6 beta-features shortlist calls the "Ktor
fetcher kit": a published adapter that builds a `Store` fetcher on a Ktor `HttpClient`, maps HTTP
conditional revalidation (ETag / `If-None-Match` and Last-Modified / `If-Modified-Since`) onto the
core's `FetchPlan.Conditional` seam, and maps HTTP outcomes onto `FetcherResult` with typed errors.

Every behavioral claim about core is cited to a file and line range at the `store6` branch. Claims
about Ktor runtime behavior are labeled as such and are gated by the spike in
[§13](#13-dependency-alignment-and-httpclient-compatibility). Unsettled points are in
[§16 Open decisions](#16-open-decisions).

Revision note: the first draft understated three things the reviews corrected and this version
fixes — the cold-304 outcome ([§3.4](#34-the-not-modified-path)), the fact that conditional
revalidation is residence-lifetime-scoped ([§3.6](#36-validator-lifetime-a-core-constraint-the-kit-inherits)),
and the interaction with Ktor's `HttpCache` plugin ([§13.2](#132-httpclient-plugin-compatibility)).

---

## 1. Problem and demand

The Store 6 beta-features shortlist (the product brief this work is scoped from, not the
repository `ROADMAP.md`) ranks the kit fourth:

> Ktor fetcher kit: ETag/If-Modified-Since mapped onto `FetchPlan.Conditional`, typed error mapping
> — Fetcher + FreshnessValidator — Every consumer writes this by hand; it showcases
> `FetchPlan.Conditional`, which is otherwise invisible.

Precise version of the demand claim: conditional revalidation is already a first-class core
capability. Core records a validator on its metadata and selects a conditional plan automatically
([§3](#3-the-seam-as-it-exists-today)). The `graphql` kit already demonstrates the plan→`NotModified`
loop at the GraphQL layer (`graphql/README.md` lines 109–114). What has no published producer is the
plain-HTTP/REST case: turning `FetchPlan.Conditional` into an `If-None-Match` request and a `304`
back into the not-modified signal over a Ktor client. REST consumers reimplement that plumbing and
its status-to-error mapping by hand. This kit is that plumbing once, tested against the behaviors
core already pins.

## 2. Goals, non-goals, and accepted limitations

### Goals

1. Build a `Fetcher<K, V>` on a caller-owned Ktor `HttpClient` with a caller-supplied per-key
   request shape and response decoder.
2. Map `FetchPlan.Conditional` (delivered to the fetcher as a non-null `etag` argument) onto an
   HTTP conditional GET/HEAD request, supporting **both** ETag (`If-None-Match`) and Last-Modified
   (`If-Modified-Since`) validators, with no change to core.
3. Map a `304 Not Modified` onto `FetcherResult.NotModified`, so — in the residence states core
   allows it ([§3.4](#34-the-not-modified-path)) — the store emits one `StoreResult.Revalidated`.
4. Record the server's returned validator on a successful fetch so the next conditional plan can
   revalidate against it, within the lifetime constraint in
   [§3.6](#36-validator-lifetime-a-core-constraint-the-kit-inherits).
5. Map HTTP status families and transport failures onto `FetcherResult`, surfacing a typed
   exception as the `StoreError.Fetch` cause so consumers can branch on HTTP status.
6. Ship at full 12-target parity with `core`, and add **zero** lines to `core` and zero files to
   the frozen seam package.

### Non-goals

1. **No retry/backoff engine.** Core does not retry a fetcher
   (`docs/store6/important-defaults.md` lines 37–44). Retry is delegated to Ktor's
   `HttpRequestRetry` plugin and to the separate retry/backoff policy pack (shortlist items #677,
   #678). See [§11](#11-retry-and-backoff-stance).
2. **No transport ownership.** The kit does not create, own, or close the `HttpClient`, install
   `ContentNegotiation`, or choose a serialization format — mirroring `graphql`'s "you bring the
   transport" contract.
3. **No response normalization / entity graph.** One key → one decoded value.
4. **No new core seam and no core field.** Last-Modified is carried through the existing opaque
   `etag` string ([§5](#5-central-design-decision-the-validator-token)); adding a `lastModified`
   field to `StoreMeta`/`FetchPlan` is a separate core API-review proposal, out of scope here.
5. **Conditional revalidation is GET/HEAD-only.** `If-None-Match`/`If-Modified-Since` have defined
   semantics only for GET/HEAD (RFC 9110 §13.1.2–§13.1.3). For other methods the kit fetches
   unconditionally. See [§8](#8-conditional-request-construction).

### Accepted limitations (stated, not worked around)

- **Residence-lifetime validators.** Core drops the ETag when it hydrates a value from the source
  of truth, so the first fetch after eviction, restart, or a fresh engine is **unconditional**;
  conditional revalidation applies while the value is memory-resident and is re-established after
  each successful fetch. This is core behavior the kit inherits, detailed and cited in
  [§3.6](#36-validator-lifetime-a-core-constraint-the-kit-inherits).
- **`HttpCache` incompatibility.** A client with Ktor's `HttpCache` plugin installed intercepts the
  304 the kit depends on. The kit rejects such a client by default; see
  [§13.2](#132-httpclient-plugin-compatibility).

## 3. The seam as it exists today

All paths at the `store6` branch. Line ranges are load-bearing; the implementation plan re-confirms
them before coding.

### 3.1 `Fetcher`

`core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/Fetcher.kt` lines 18–28:

```kotlin
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface Fetcher<K : StoreKey, V : Any> {
    public suspend fun fetch(key: K, etag: String?): FetcherResult<V>
}
```

KDoc (lines 7–17): "The engine supplies a non-null `etag` if and only if it selected
`FetchPlan.Conditional`; return `FetcherResult.NotModified` to confirm that the resident value is
still current." Implementing it requires an explicit `DelicateStoreApi` opt-in via
`@SubclassOptInRequired`, so the kit's private implementation class is annotated
`@OptIn(DelicateStoreApi::class)` exactly as `graphql`'s is
(`graphql/.../GraphQlFetcher.kt` lines 54–60).

The kit **must** use the interface overload `StoreBuilder.fetcher(Fetcher<K, V>)`
(`StoreBuilder.kt` lines 101–104), not the `fetcher { }` or `fetcherOfResult { }` lambda sugar: the
lambda install points adapt through `LambdaFetcher`/`ResultFetcher`, which ignore the conditional
ETag (`StoreBuilder.kt` lines 92–100 state the interface overload is the one that receives them).

### 3.2 `FetcherResult`

`core/.../seam/FetcherResult.kt` lines 22–45:

```kotlin
public sealed interface FetcherResult<out V : Any> {
    public class Success<V : Any>(public val value: V, public val etag: String? = null) : FetcherResult<V>
    public class NotModified(public val etag: String? = null) : FetcherResult<Nothing>
    public class Error(public val cause: Throwable) : FetcherResult<Nothing>
    public data object Deleted : FetcherResult<Nothing>
}
```

Semantics (KDoc lines 7–21): `Success(value, etag)` commits and records `etag` as the validator;
`NotModified(etag)` refreshes metadata and emits `Revalidated` (a null `etag` keeps the previously
recorded tag; no resident value yields `StoreError.Missing`); `Error(cause)` equals throwing
`cause`; `Deleted` destructively clears residence and forgets freshness with no auto-refetch.

### 3.3 `FetchPlan.Conditional` and `FreshnessValidator`

`core/.../seam/FreshnessValidator.kt` lines 44–63:

```kotlin
public sealed interface FetchPlan {
    public data object Skip : FetchPlan
    public class Fetch(public val servesResidentWhileFetching: Boolean) : FetchPlan
    public class Conditional(public val etag: String, public val servesResidentWhileFetching: Boolean) : FetchPlan
}
```

**The conditional plan carries exactly one string, `etag`.** No Last-Modified field, no header bag.
The default validator sources it from the resident value's `StoreMeta.etag`
(`core/.../internal/FreshnessValidator.kt` lines 87–94):

```kotlin
private fun FreshnessContext.fetchPlan(servesResidentWhileFetching: Boolean): FetchPlan {
    val etag = meta?.etag
    return if (hasResidentValue && etag != null) FetchPlan.Conditional(etag, servesResidentWhileFetching)
    else FetchPlan.Fetch(servesResidentWhileFetching)
}
```

Note the plan reads `context.meta?.etag` — the **in-memory envelope's** metadata — not the durable
`context.status?.meta?.etag`. This distinction is the mechanism behind the lifetime constraint in
[§3.6](#36-validator-lifetime-a-core-constraint-the-kit-inherits).

`StoreMeta` (`core/.../StoreMeta.kt` lines 10–16) has two fields, and the KDoc names the second one
specifically:

```kotlin
public interface StoreMeta {
    /** The wall-clock time at which the value was written, in Unix epoch milliseconds. */
    public val writtenAtEpochMillis: Long
    /** The optional entity tag associated with the value. */
    public val etag: String?
}
```

That the field is documented as "the optional entity tag" is why the Last-Modified encoding in
[§5](#5-central-design-decision-the-validator-token) is asymmetric: an ETag is stored verbatim (so
the field still holds a normal entity tag in the overwhelmingly common case), and only the
Last-Modified path uses a reserved sentinel.

### 3.4 The not-modified path

`FetcherResult.NotModified` is applied by `commitNotModified`
(`core/.../internal/KeyEngine.kt` lines 1771–1836). The outcome is **not** unconditionally
`Revalidated`; it branches on the residence revision captured at fetch launch:

- **Truly cold** (`baseline == null && current == null`, lines 1789–1793): `FetchOutcome.Failed`
  with `notModifiedWithoutValueException()`. This surfaces as `StoreError.Missing` and is
  **terminal** — there is no unconditional self-heal for a 304 with no value on either side.
- **Obsolete launch snapshot** (`baseline == null || current == null || residenceRevision != baseline`,
  lines 1794–1817): `FetchOutcome.ObsoleteRevalidation`. The engine's comment (lines 1799–1803):
  "A null launch baseline with residence present at commit is an obsolete launch snapshot (residence
  hydrated mid-flight), not an adapter-contract violation." The engine replans; the `graphql`
  sample's assertion that a cold-baseline 304 self-heals with "one replanned **conditional** fetch"
  and allows 2..3 executions is a race buffer for exactly this case
  (`graphql/sample/.../Main.kt` lines 109–133).
- **Unchanged live baseline** (else, lines 1804–1817): `FetchOutcome.Revalidated`, which is the
  path that produces exactly one `StoreResult.Revalidated(age)` and refreshes metadata with
  `EngineStoreMeta(now, etag ?: current.meta?.etag)` (line 1806) — a null result `etag` keeps the
  prior tag, matching `FetcherResult.NotModified` semantics.
- **Superseded** (`KeyEffect.Superseded`, line 1820): `FetchOutcome.Superseded`.

Consequence for the kit: it returns `NotModified` and core decides the outcome. The "one
`Revalidated`, clears staleness" guarantee (`docs/store6/important-defaults.md` lines 99–100;
`StoreResult.kt` lines 39–52) holds for the unchanged-live-baseline case, which is the normal
invalidate-then-refetch flow. The kit's only obligations are to have sent a conditional request and
to pass through whatever refreshed validator the 304 carried, or null.

### 3.5 The error path

Errors flow as a thrown exception or a returned `FetcherResult.Error(cause)`, treated identically.
`CancellationException` must propagate; `graphql` shows the required shape
(`graphql/.../GraphQlFetcher.kt` lines 74–81):

```kotlin
} catch (cancellation: CancellationException) { throw cancellation }
catch (transportFailure: Throwable) { return FetcherResult.Error(transportFailure) }
```

The cause surfaces as `StoreError.Fetch.cause` (`core/.../StoreError.kt` lines 11–18), observed as
`StoreResult.Error` on `stream` or a thrown `StoreException` on `get` (`StoreResult.kt` lines 54–70;
the `graphql` sample walks this chain at `graphql/sample/.../Main.kt` lines 86–96).

### 3.6 Validator lifetime: a core constraint the kit inherits

When a value is not memory-resident and the engine hydrates it from the source of truth,
`hydrateFromSot` reconstructs the in-memory metadata from the durable bookkeeper record **but sets
the ETag to null** (`core/.../internal/KeyEngine.kt` lines 2168–2184):

```kotlin
val hydratedMeta =
    status?.meta?.let { meta ->
        EngineStoreMeta(writtenAtEpochMillis = meta.writtenAtEpochMillis, etag = null)
    }
```

Because the default validator plans from this in-memory envelope's `meta?.etag`
([§3.3](#33-fetchplanconditional-and-freshnessvalidator)), the durable ETag the bookkeeper still
holds is **not** used to plan the next fetch. The concrete consequences the kit must document:

- The first fetch after idle eviction (the 128-key cap, `StoreBuilder.kt` lines 106–121), after a
  process restart with a durable SoT, or on any fresh engine, is an **unconditional**
  `FetchPlan.Fetch`, not a conditional revalidation.
- After that first fetch succeeds, `commitFetch` records the fresh validator again
  (`KeyEngine.kt` line 1407, `EngineStoreMeta(now, etag)`), so revalidation resumes for the rest of
  that residence lifetime.
- Age-based `Freshness` still works across hydration (`writtenAtEpochMillis` survives); only the
  ETag optimization is reset.

This is a core invariant (a hydrated bare value cannot be assumed to still match a durable ETag), so
the kit neither can nor should change it. The kit's value is real but bounded: conditional
revalidation covers the common live-stream flow of invalidate-then-refetch and `MustBeFresh` reads
while resident, and every second-and-later fetch after a cold load — not the very first fetch after
a cold start. The README states this plainly.

## 4. High-level architecture

The kit is a thin, stateless mapping layer over three transformations:

1. **Request in:** the conditional `etag` argument → an HTTP conditional header (GET/HEAD only).
2. **Response out:** HTTP status + body → a `FetcherResult` case.
3. **Validator out:** response validator header(s) → the `etag` string recorded on the result.

Everything else — connection pooling, TLS, serialization, redirects, retry, connectivity, caching —
belongs to the caller's `HttpClient`, as serialization/transport belong to the caller's
`GraphQlExecutor` in `graphql`.

## 5. Central design decision: the validator token

### 5.1 The tension

The shortlist asks for `If-Modified-Since`, but the conditional plan carries only `etag: String`
([§3.3](#33-fetchplanconditional-and-freshnessvalidator)), and there is no `lastModified` anywhere
in core (repository search finds `etag` first-class throughout core and sidecars, while
`lastModified`/`If-Modified-Since`/`Expires` appear only in prose). The kit's only channel is the
opaque `etag` string that round-trips `Success.etag` → `StoreMeta.etag` →
`FetchPlan.Conditional.etag` → the `fetch` `etag` argument.

### 5.2 Decision: an asymmetric validator token in the opaque string

Core treats `etag` as opaque and never parses it, so the kit defines a private, reversible encoding
that carries the validator **kind** in that one string. To keep the common case a normal HTTP
validator (the field is documented as an entity tag, [§3.3](#33-fetchplanconditional-and-freshnessvalidator)),
the encoding is asymmetric:

```
token          = etag-verbatim / last-modified-token
etag-verbatim  = entity-tag                 ; stored exactly as received, e.g. "abc" or W/"abc"
last-modified  = "LM:" HTTP-date            ; reserved sentinel prefix + the Last-Modified value
```

Encoding: an ETag is stored **verbatim** (quotes and any weak `W/` marker included); a Last-Modified
value is stored as `LM:` + the HTTP-date.

Decoding (`fetch` receives a non-null `etag`):

- `token.startsWith("LM:")` → send `If-Modified-Since: token.removePrefix("LM:")`.
- otherwise → send `If-None-Match: token` (verbatim).

Rules that make this robust (each was a review finding):

- **Never `split(":")`.** Use `startsWith`/`removePrefix`, so an ETag containing a colon (`"a:b"`)
  and a weak tag (`W/"x"`) round-trip unchanged.
- **No collision in the common case.** A well-formed entity tag is a quoted-string or `W/"…"`, so
  it begins with `"` or `W` and never with `LM:`; a well-formed HTTP-date begins with a day-name
  and never with `LM:`. The kit always writes the `LM:` prefix for Last-Modified, so decoding a
  kit-written token is deterministic. A malformed bare value that literally begins `LM:` is treated
  as Last-Modified; the README documents that ETags are expected to be well-formed HTTP validators.
- **Strings only**, so the token round-trips identically across every Kotlin target, avoiding the
  cross-target `Double.toString` hazard `graphql` documents (`graphql/README.md` lines 83–86).
- **Opt-out.** A `lastModifiedFallback: Boolean = true` factory parameter, when set false, makes the
  kit ignore `Last-Modified` entirely and record nothing when no ETag is present — so a caller who
  wants a strict, prefix-free entity-tag column can have one, at the cost of no Last-Modified
  revalidation.

### 5.3 The token is opaque to core, but not invisible

Correcting a first-draft overstatement: the token is opaque to *core* (never parsed), but it **is**
the value written to a persistent source of truth's ETag column (`sqldelight` `etag TEXT`, `room`
`etag` column) and passed to `Bookkeeper.recordSuccess` and to any custom `FreshnessValidator` or
telemetry. A caller inspecting the raw column of a Last-Modified-backed value sees the `LM:` prefix.
The README states this. In practice it is benign because the kit is the sole producer and consumer
of the token for the stores it backs, and because of the lifetime constraint
([§3.6](#36-validator-lifetime-a-core-constraint-the-kit-inherits)) the token is often reset to a
bare ETag on the next resident fetch anyway.

### 5.4 Response-side validator selection and the no-flip rule

On a **2xx**, the kit records one token: an `ETag` header if present (verbatim), else a
`Last-Modified` header as `LM:<date>` (unless `lastModifiedFallback == false`), else null (no
validator → the next plan is an unconditional `FetchPlan.Fetch`). ETag is preferred not because
"every ETag is strong" (weak ETags are valid and supported) but because `If-None-Match` is the more
precise conditional and takes precedence when both are present (RFC 9110 §13.1.3; ETag defined in
§8.8.3, weak/strong in §8.8.1).

On a **304**, the kit adopts a new token only if the 304 carries an **ETag** (RFC 9110 §15.4.5
requires a 304 to echo the ETag a 200 would have sent). If the 304 carries no ETag, the kit returns
`NotModified(etag = null)` to **keep the previous tag**, even when the 304 carries a `Last-Modified`.
This "no-flip" rule prevents a CDN that sends only `Last-Modified` on 304 from silently downgrading a
stored strong `If-None-Match` validator to a weaker `If-Modified-Since` one on the next request.

### 5.5 Rejected alternative: add a core field

Adding `lastModified` to `StoreMeta`/`FetchPlan.Conditional` would make `If-Modified-Since`
first-class but edits `core`, violating the extension bar ("the source-of-truth seam is small on
purpose") and growing a freeze-candidate seam that CI pins to a 13-file list
(`.github/workflows/store6.yml` lines 283–305; `STABILITY.md` §3). The token approach delivers the
same observable behavior with zero core surface change. First-class Last-Modified, if a non-Ktor
consumer ever needs it structurally, is a standalone core API-review proposal
([§16](#16-open-decisions)).

## 6. Public API surface

All symbols are `@ExperimentalStoreApi`. This shape is finalized against the test list during
implementation; the API-review checkpoint in the plan gates it.

### 6.1 Factory and builder extension

```kotlin
package org.mobilenativefoundation.store6.ktor

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreBuilder
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.Fetcher

/**
 * Builds a [Fetcher] that revalidates over HTTP on [client].
 *
 * @param client caller-owned HTTP client; the kit never closes it. Must not have Ktor's HttpCache
 *   plugin installed unless [allowHttpCache] is true (see the technical design §13.2).
 * @param decode maps an adopted 2xx response to a value; invoked inside the response scope only for
 *   outcomes the kit adopts as Success
 * @param notFoundPolicy how 404 and 410 are mapped (default: typed error, non-destructive)
 * @param lastModifiedFallback whether to record and send Last-Modified when no ETag is available
 * @param errorMapper optional override of status-to-result mapping; returns Defer to keep defaults
 * @param allowHttpCache set true only when you accept that HttpCache can intercept the 304 path
 * @param configureRequest applies the per-key request shape (method, URL, headers, body)
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> ktorFetcher(
    client: HttpClient,
    notFoundPolicy: KtorNotFoundPolicy = KtorNotFoundPolicy.Error,
    lastModifiedFallback: Boolean = true,
    errorMapper: KtorErrorMapper = KtorErrorMapper.Default,
    allowHttpCache: Boolean = false,
    decode: suspend (HttpResponse) -> V,
    configureRequest: HttpRequestBuilder.(K) -> Unit,
): Fetcher<K, V>

/** Installs [ktorFetcher] as this store's fetch source. Last fetcher registration wins. */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> StoreBuilder<K, V>.ktorFetcher(
    client: HttpClient,
    notFoundPolicy: KtorNotFoundPolicy = KtorNotFoundPolicy.Error,
    lastModifiedFallback: Boolean = true,
    errorMapper: KtorErrorMapper = KtorErrorMapper.Default,
    allowHttpCache: Boolean = false,
    decode: suspend (HttpResponse) -> V,
    configureRequest: HttpRequestBuilder.(K) -> Unit,
)
```

`HttpClient`, `HttpRequestBuilder`, `HttpResponse` appear in the signature, so `ktor-client-core` is
an **`api`** dependency. `ktor-client-core` re-exports `ktor-http`, so `HttpStatusCode`/`HttpMethod`
(used by `KtorFetchException` below) are transitively available to consumers without a second `api`
entry; the klib dump will reference `io.ktor.http.*` names, which is expected and stable.

The caller's `decode` lambda is compiled at the call site with a concrete type, so
`response.body<MyDto>()` reifies there; the kit never calls `body<V>()` itself, so there is no
reification problem in the kit.

### 6.2 Policy, mapper, and error types

The generic `fun interface` sketched in the first draft does not compile (a functional interface's
abstract method cannot be parameterized) and could not return `Success` anyway (no `V`, and `decode`
has not run). The mapper is therefore non-generic and returns a small decision type:

```kotlin
/** Whether a 404/410 clears the resident value or surfaces a typed error. */
@ExperimentalStoreApi
public enum class KtorNotFoundPolicy { Error, Delete }

/** A read-only view of a completed HTTP exchange, valid only for the duration of the map call. */
@ExperimentalStoreApi
public class KtorExchange internal constructor(
    public val status: HttpStatusCode,
    public val method: HttpMethod,
    public val url: String,
    /** Sent a conditional header for this request (If-None-Match or If-Modified-Since). */
    public val conditional: Boolean,
    /** The live response; read headers here. Do not retain it past the map call. */
    public val response: HttpResponse,
)

/** The mapper's decision. Success is never a mapper output: a body is adopted only via [decode]. */
@ExperimentalStoreApi
public sealed interface KtorOutcome {
    public data object Defer : KtorOutcome              // apply the kit's default table (§7)
    public class Fail(public val exception: KtorFetchException) : KtorOutcome
    public data object Delete : KtorOutcome
    public class NotModified(public val validatorToken: String?) : KtorOutcome
}

/** Maps a completed exchange to an outcome; return Defer to keep the default mapping. */
@ExperimentalStoreApi
public fun interface KtorErrorMapper {
    public fun map(exchange: KtorExchange): KtorOutcome
    public companion object { public val Default: KtorErrorMapper } // always returns Defer
}

/** The typed failure carried as StoreError.Fetch.cause for a non-adopted HTTP status outcome. */
@ExperimentalStoreApi
public class KtorFetchException(
    public val status: HttpStatusCode,   // always an HTTP status; transport/decode failures keep their original exception
    public val method: HttpMethod,
    public val url: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
```

Precedence, fixed and documented: the mapper runs on every completed response **before** decode;
if it returns anything other than `Defer`, that wins; otherwise the kit applies the default table in
[§7](#7-http-outcome--fetcherresult-mapping), and the default 2xx branch is the only path that calls
`decode`. `KtorExchange.response` is valid only during the map call (the kit executes inside a
response scope, [§8](#8-conditional-request-construction)).

Typed-error requirement: a consumer catches `StoreException`, reads `error as? StoreError.Fetch`,
then `cause as? KtorFetchException`, and branches on `status`. Transport failures (timeouts, IO) and
decoder failures are returned as `FetcherResult.Error(originalException)` so Ktor's own typed
exceptions (for example `HttpRequestTimeoutException`) reach the consumer unwrapped; only completed
HTTP status outcomes are wrapped in `KtorFetchException`, so its `status` is always non-null.

## 7. HTTP outcome → `FetcherResult` mapping

Default table, applied when `errorMapper` returns `Defer`:

| HTTP outcome | `FetcherResult` | Notes |
|---|---|---|
| 200/2xx with a body the decoder accepts | `Success(value, token)` | token per [§5.4](#54-response-side-validator-selection-and-the-no-flip-rule); `decode` runs here |
| `206 Partial Content` | `Error(KtorFetchException(206, …))` | a partial body is not a complete representation; the kit is not range-aware |
| `204`/`205` (no content) | delegated to `decode` | `V` is non-null, so an empty body has no value; `decode` throws and the kit maps to `Error` |
| `304` **after a conditional request** | `NotModified(token or null)` | see [§3.4](#34-the-not-modified-path); no-flip rule in [§5.4](#54-response-side-validator-selection-and-the-no-flip-rule) |
| `304` with **no** conditional request sent | `Error(KtorFetchException(304, …))` | the kit sent no validator, so a 304 is a protocol violation |
| `404`/`410`, `KtorNotFoundPolicy.Error` (default) | `Error(KtorFetchException(status, …))` | preserves residence, recorded validator, success time, and stale state; bookkeeping records the fetch failure (`KeyEngine.kt` lines ~1949–1964) |
| `404`/`410`, `KtorNotFoundPolicy.Delete` | `Deleted` | opt-in destructive clear ([§3.2](#32-fetcherresult)) |
| other `4xx`, `5xx` | `Error(KtorFetchException(status, …))` | the kit does not classify retryability or retry; a caller/plugin policy decides (408/429/503 are commonly retryable) |
| transport/IO failure thrown by the client | `Error(originalException)` | `CancellationException` re-thrown; original type preserved |
| `decode` throws | `Error(originalException)` | malformed or empty 2xx body |

Notes:

- **`expectSuccess`.** The kit sets `expectSuccess = false` on its own request; a per-request value
  overrides a client-level `expectSuccess = true` (Ktor stores it in a request attribute the request
  wins on). So the kit sees raw statuses rather than thrown `ResponseException`s on the default path.
  If a caller installed a **custom** `HttpResponseValidator` that throws, that exception surfaces as
  `Error(cause)`; the kit does not swallow and reinterpret caller-defined validation.
- **Connection cleanup.** Every non-decoded path (304, 4xx, 5xx) still has a response body that must
  be released. The kit executes inside `prepareRequest { … }.execute { response -> … }`, which
  releases the response when the block returns, so no path leaks a connection
  ([§8](#8-conditional-request-construction)).
- **Redirects.** 3xx other than 304 are handled by the caller's client (Ktor follows redirects by
  default). A non-304 3xx that reaches the kit maps to `Error`. Redirect caveats for conditional
  headers are in [§8](#8-conditional-request-construction).

## 8. Conditional request construction

Each `fetch(key, etag)` builds and executes a **fresh** request (never a reused builder), using
`prepareRequest`/`request` (not `get { }`, which would force the method to GET and wipe a caller's
method):

```kotlin
client.prepareRequest {
    configureRequest(key)                 // caller sets method, url, headers, body
    expectSuccess = false
    val method = this.method
    // Authoritative, GET/HEAD-only conditional header (replace, do not append):
    headers.remove(HttpHeaders.IfNoneMatch)
    headers.remove(HttpHeaders.IfModifiedSince)
    if (etag != null && (method == HttpMethod.Get || method == HttpMethod.Head)) {
        if (etag.startsWith("LM:")) headers[HttpHeaders.IfModifiedSince] = etag.removePrefix("LM:")
        else headers[HttpHeaders.IfNoneMatch] = etag
    }
}.execute { response -> mapOutcome(response, key, conditionalSent) }
```

Load-bearing points, each from a review finding:

- **Replace, not append.** Ktor's `header(name, value)` appends. The kit removes any
  `If-None-Match`/`If-Modified-Since` the caller's block set and then sets exactly one (or none), so
  the kit's revalidation header is authoritative for the request it builds. Later pipeline plugins
  (`DefaultRequest`, `HttpCache`) can still append; that is one reason the kit is incompatible with
  `HttpCache` ([§13.2](#132-httpclient-plugin-compatibility)) and the README tells callers not to set
  these headers themselves.
- **GET/HEAD only.** `If-None-Match` on a non-GET/HEAD method yields `412 Precondition Failed`, not
  `304`, and `If-Modified-Since` is ignored off GET/HEAD (RFC 9110 §13.1.2–§13.1.3). The kit applies
  a conditional header only when the effective method is GET or HEAD; otherwise it fetches
  unconditionally, and `conditionalSent` records whether a conditional header was actually applied
  (this drives the "unconditional-304 anomaly" row in [§7](#7-http-outcome--fetcherresult-mapping)).
- **Weak ETags** are sent verbatim, including `W/`; `If-None-Match` uses weak comparison
  (RFC 9110 §13.1.2), so a weak validator is valid for a conditional GET.
- **Redirect leakage.** Ktor's redirect handling copies the request (and its conditional headers)
  across redirects, stripping only `Authorization` on an authority change. A conditional header can
  therefore travel to a different resource. The README instructs callers whose endpoints redirect to
  either disable redirects for these requests or ensure the redirect target is the same
  representation; the design does not silently assume stable URLs.

## 9. Response decoding

`decode: suspend (HttpResponse) -> V` is the only place a body is read, and it runs **inside** the
`execute { }` scope so the response is live and is released on block exit. A typical decoder is
`response.body<MyDto>()` when the caller's client has `ContentNegotiation` installed, or
`response.bodyAsText()` plus manual parsing. The kit installs no `ContentNegotiation` and depends on
no Ktor serialization artifact, keeping the serialization choice and the dependency surface
(`ktor-client-core` only) with the caller. `decode` is invoked only for a 2xx the kit adopts as
`Success`.

## 10. Key design and representation identity

The kit is generic over `K : StoreKey`; cache identity is the caller's key identity
(`StoreKey.canonicalId()`/`namespace`, `core/.../StoreKey.kt` lines 9–25). The caller maps the key
to a request via `configureRequest`.

A validator applies to the *selected representation* of a resource (RFC 9110 §8.8.1): the URL plus
any representation-selecting inputs (`Accept`, `Accept-Language`, authorization scope — the `Vary`
axes). The README therefore states a contract: **the key identity must determine the selected
representation.** If one `StoreKey` maps to requests that vary URL, `Accept`, locale, or principal,
an old validator could revalidate against a different variant and a 304 could confirm the wrong
value. Callers must fold any representation-selecting input into the key (namespace or canonical id),
or use distinct keys. A convenience key for the common single-resource case is deferred
([§16](#16-open-decisions)).

## 11. Retry and backoff stance

The kit performs no retry, aligning with core's zero-retry default
(`docs/store6/important-defaults.md` lines 37–44). Two composition points are documented instead:
Ktor's `HttpRequestRetry` plugin on the caller's client for transport-level retry (a
retried-then-succeeded request still yields a single `FetcherResult`, preserving the
one-fetch-per-demand contract), and the separate retry/backoff policy pack (shortlist #677/#678) for
connectivity-gated periodic retry, which composes at the fetcher level and can wrap this plain
`Fetcher`.

## 12. Module, build, and publishing

Follows the `graphql`/`realtime` template.

- **Gradle path / dir:** `:ktor` at `ktor/`; sample `:ktor-sample` at `ktor/sample`
  (`settings.gradle` `include`s, sample `projectDir` overridden as other samples are).
- **Convention plugin:** `org.mobilenativefoundation.store.store6.multiplatform` — the full
  12-target plugin (`tooling/plugins/.../Store6MultiplatformConventionPlugin.kt` lines 9–32). Full
  parity is achievable: `ktor-client-core` publishes klibs for all 12 targets (verified against
  Maven Central — `ktor-client-core-*` includes `wasm-js`, `linuxx64`, `mingwx64`, `watchosarm64`,
  `tvosarm64`, `js`, and every Apple target), so no subset plugin and no per-target exception.
- **`build.gradle.kts`:**
  ```kotlin
  plugins { id("org.mobilenativefoundation.store.store6.multiplatform") }
  kotlin {
      sourceSets {
          val commonMain by getting { dependencies {
              api(projects.core)
              api(libs.ktor.client.core)
          } }
          val commonTest by getting { dependencies {
              implementation(projects.testing)
              implementation(libs.ktor.client.mock)
              implementation(libs.kotlinx.coroutines.test)
              implementation(libs.turbine)
          } }
      }
  }
  android { namespace = "org.mobilenativefoundation.store6.ktor" }
  ```
- **Version catalog** (`gradle/libs.versions.toml`, no Ktor entry today): a `ktor` version plus
  `ktor-client-core` and `ktor-client-mock` entries, the version fixed by the spike
  ([§13](#13-dependency-alignment-and-httpclient-compatibility)).
- **`gradle.properties`:** `VERSION_NAME=6.0.0-SNAPSHOT`, `POM_NAME=ktor`, `POM_ARTIFACT_ID=ktor`.
- **Android manifest:** empty `ktor/src/androidMain/AndroidManifest.xml`, as `graphql` has.
- **API dumps:** commit `ktor/api/jvm/ktor.api`, `ktor/api/android/ktor.api`,
  `ktor/api/ktor.klib.api`, generated by the module's BCV dump; the dumps will name the `io.ktor.*`
  types the public factory exposes.
- **Sample:** `ktor/sample/build.gradle.kts` is a JVM `application` depending on `projects.ktor`
  **and `libs.ktor.client.mock`** (test dependencies are not exported to the sample module, so the
  sample must declare MockEngine itself). It drives scenes over a Ktor `MockEngine`, deterministic
  and offline, mirroring `graphql/sample`.
- **README / dokka:** `ktor/README.md` modeled on `graphql/README.md`; optional `ktor/dokka/Module.md`.
  The README is intentionally **not** added to `.github/docs-sync-sources.txt` — `graphql` and
  `realtime` set the precedent that a new extension README is not a docs-site source, which avoids
  the `docs-sync-ack` gate.

## 13. Dependency alignment and HttpClient compatibility

### 13.1 Version alignment (the highest-risk part; the plan front-loads a spike)

- Store 6 pins **Kotlin 2.3.20** and **kotlinx-coroutines 1.8.1** (`gradle/libs.versions.toml`;
  Kotlin floor "2.3" in `STABILITY.md` §10). `projects.testing` exports
  `kotlinx-coroutines-test` at 1.8.1.
- Ktor 3.5.x (latest 3.5.2, Aug 2026) is built with Kotlin 2.3.21 and its Native artifacts pull
  coroutines 1.11.0, plus a transitive kotlinx stack (atomicfu, kotlinx-serialization, kotlinx-io).

The primary risk is **whole-stack version skew**, not the Kotlin patch difference. Gradle's
highest-version-wins would raise the `:ktor` module's coroutines (and, in `commonTest`, would leave
`coroutines-test` at 1.8.1 unless aligned), producing a mixed kotlinx stack on that module's
classpath. The klib-ABI concern (a 2.3.20 compiler consuming 2.3.21-built klibs) is real to check
but likely benign: this repository already treats the 2.3.x klib ABI as one lane (the Room 3 native
klibs are ABI 2.3.0, consumed by the 2.3.x compiler), so a spike should read the actual Ktor klib
manifest `abi_version`/`metadata_version` rather than assume rejection.

**Spike T0 (a gate; no module wiring proceeds until it passes):**

1. Add the Ktor catalog entries at the latest 3.5.x and build + run the module's tests on every
   lane CI executes (JVM, JS-node, `iosSimulatorArm64`, `macosArm64`), and compile (link) all 12
   targets.
2. Resolve the full dependency graph for `commonMain` and `commonTest`; align the entire kotlinx
   stack, and in particular pin `coroutines-test` to the same coroutines version the module resolves
   (a single core constraint is insufficient — the test artifact must match).
3. Decision ladder if a lane fails: try 3.5.1/3.5.0 before leaving the 3.5 line; then fall back to
   the newest 3.4.x (built against Kotlin 2.3.x at or below Store's, coroutines ~1.10.2 — an
   older-producer/newer-consumer klib direction, which is the safe one). A coordinated repo-wide
   Kotlin patch bump (2.3.20 → 2.3.21) is a last resort, a separately authorized core/build change.

Note: the TD-8 CI gate bans the *identifier* `atomicfu` in production sources (a source grep,
`store6.yml` lines 307–327); a transitive `atomicfu` dependency from Ktor is unaffected, because the
kit's own sources use no such primitive.

### 13.2 HttpClient plugin compatibility

Ktor's `HttpCache` plugin (part of `ktor-client-core`, opt-in) breaks the kit's central mechanism:
on the receive pipeline it replaces a real `304` with the cached response (so the kit would see a
2xx and emit `Success`/`Data` instead of `NotModified`/`Revalidated`), and on the send pipeline it
appends its own `If-None-Match`/`If-Modified-Since` from its own cache. A default `HttpClient` does
not install it, so MockEngine tests would pass while a production client with `install(HttpCache)`
silently loses the entire conditional path.

Decision: the factory **rejects** a client with `HttpCache` installed by default — it checks
`client.pluginOrNull(HttpCache)` and throws `IllegalArgumentException` with a message naming the
conflict — unless `allowHttpCache = true` is passed by a caller who accepts the interaction (for
example a cache scoped to unrelated hosts). A dedicated conformance test installs `HttpCache` and
asserts the factory rejects it (and, under the opt-out, documents the degraded behavior). This
turns a silent production failure into a loud construction-time error.

## 14. Testing strategy

Distinguish **compilation/publication** (all 12 targets, enforced by `:ktor:build` and the
klib-publication check) from **test execution**, which CI runs on JVM, JS-node,
`iosSimulatorArm64`, and `macosArm64` only (`store6.yml` lines 441, 498–532). Test claims below are
about those executed lanes; the other targets are compiled and published, not run.

- **Transport-level unit tests (commonTest, executed lanes).** Drive `KtorFetcher` through Ktor
  `MockEngine` (`ktor-client-mock`, verified to publish klibs for all 12 targets). Cover: ETag
  conditional round-trip (`If-None-Match` sent, `304` → `NotModified`), Last-Modified round-trip
  (`If-Modified-Since` sent), validator precedence and the 304 no-flip rule, verbatim ETag storage
  vs `LM:` sentinel, `startsWith`/`removePrefix` decoding of colon-containing and weak ETags, each
  row of the [§7](#7-http-outcome--fetcherresult-mapping) table, the 404 Error-vs-Delete policy, the
  unconditional-304 anomaly, GET/HEAD-only header application (a POST key does not get a conditional
  header), header replace-not-append, `CancellationException` propagation, and the `errorMapper`
  precedence.
- **HttpCache rejection test.** Construct a client with `install(HttpCache)` and assert the factory
  throws; assert `allowHttpCache = true` constructs. This is the regression guard for
  [§13.2](#132-httpclient-plugin-compatibility).
- **Store integration tests (commonTest).** Build a real in-memory `Store` with the kit's fetcher
  and assert against core's guarantees: an invalidate-then-conditional-refetch that returns 304
  produces exactly one `StoreResult.Revalidated` (mirrors
  `conditionalRefetch_notModified_emitsOwnerRevalidatedAndClearsStaleness`), a truly cold 304 yields
  `StoreError.Missing` (not a self-heal), `Freshness.MustBeFresh` re-requests, and a typed
  `KtorFetchException` surfaces through `StoreResult.Error`/`StoreException`.
- **Validator-lifetime test.** With `maxIdleKeys(0)` (forcing eviction) or a persistent SoT plus a
  fresh engine, assert the first post-hydration fetch is **unconditional** (the fetcher receives a
  null `etag`), confirming [§3.6](#36-validator-lifetime-a-core-constraint-the-kit-inherits) so the
  behavior is pinned rather than surprising.
- **Shared `runTest` shim.** Tests using Turbine copy the byte-identical deadline shim — the
  `TEST_TIMEOUT`/`TURBINE_DEADLINE`/`runTest` wrapper block, present at
  `graphql/src/commonTest/.../GraphQlStoreIntegrationTest.kt` lines 251–260 (the `import
  kotlinx.coroutines.test.runTest as coroutineRunTest` alias is at line 12). The wrapper comment is
  deliberately identical across sibling files and must be reproduced verbatim
  (`AGENTS.md`, repository comment conventions).
- **No fetcher contract kit exists** in `testing` (unlike SourceOfTruth/Bookkeeper), so these are
  bespoke plus the Store-level integration above.
- **Sample as an executable check.** `:ktor-sample:run` asserts a few scenes over `MockEngine` and
  is a CI run step, like `:graphql-sample:run`.

## 15. CI and gate wiring

A new published module joins every explicit list in `.github/workflows/store6.yml` (none are
auto-discovered). Line numbers verified against the current workflow:

1. **`settings.gradle`** — `include ':ktor'` and `':ktor-sample'` with the sample `projectDir`.
2. **linux-build-test** — a "Build Store6 ktor" step (`:ktor:build` with the klib
   cross-compilation and Xcode-nowarn flags the sibling steps pass) and a "Run Store6 ktor sample"
   step (`:ktor-sample:run`), beside the graphql/realtime steps (lines 238–256).
3. **"Reject core-internal access from extension modules"** — add `ktor ktor/sample` to the loop
   (line 263). Passes: the kit uses only public seam APIs; this gate is also part of the
   zero-core-diff proof ([§17](#17-zero-core-diff-verification)).
4. **"Enforce the TD-8 primitive whitelist and single-writer residence"** — add `ktor/src/*Main` to
   `production_source_dirs` (line 314). Passes: the kit uses no `runBlocking`/`GlobalScope`/
   `atomicfu`/`Channel`/`actor`.
5. **JS canary** — add `:ktor:jsNodeTest` (line 441).
6. **apple-tests** — add `:ktor:iosSimulatorArm64Test` and `:ktor:macosArm64Test` (lines 498–532).
7. **klib-publication-check** — add `ktor` to the `publishToMavenLocal` list (line 675) and the
   `modules=(…)` array (line 687). The existing suffix matrix (lines 688–702) covers all 12 targets;
   full parity means **no** per-module suffix exception (unlike `room`/`paging-androidx`/
   `devtools-inspector`).

Not touched, confirmed against the workflow: the **TD-13 seam freeze list** (lines 283–305; the kit
adds no seam file), **`.github/docs-sync-sources.txt`** (see [§12](#12-module-build-and-publishing)),
the **swift-dumps** job (line 548, `checkSwiftDumps` only — no module list), and **native-stress**
(line 748, a core test-class list — no module list). `ci.yml`'s `clean build` picks the module up
once it is in `settings.gradle`.

## 16. Open decisions

1. **Artifact name.** Recommended `ktor` (matching the ecosystem-name convention of `graphql`,
   `room`, `sqldelight`, `compose`, `realtime`). The alternative `ktor-fetcher` disambiguates
   against a hypothetical future Ktor-based artifact. Renaming after CI wiring touches ~8 lists, so
   the plan resolves this with the maintainer **before** the mass-wiring task.
2. **HttpCache handling default.** Fail-fast (chosen default) vs warn-and-continue. Fail-fast is the
   safer default given the silent-breakage risk; the `allowHttpCache` opt-out covers advanced users.
3. **Convenience key.** Whether to ship a minimal `HttpResourceKey(namespace, id)` for the common
   single-resource case, or leave all key design to the caller in v1. Leaning toward leaving it out.
4. **First-class Last-Modified in core.** Out of scope; a possible future core API-review proposal
   ([§5.5](#55-rejected-alternative-add-a-core-field)).
5. **204/empty-body ergonomics.** Whether to add a small "204 means unchanged" helper or leave it to
   the decoder ([§7](#7-http-outcome--fetcherresult-mapping)).

## 17. Zero-core-diff verification

The change touches no file under `core/` and adds no file to the seam package. The existing CI gates
support this but do not fully prove it on their own — the internal-access gate scans extension
sources only, the seam-freeze gate checks filenames only, and BCV passes if `core` code and its
committed dumps change together. The actual guarantee is therefore an explicit path-diff check,
which the plan makes a mandatory step and the PR states: `git diff --name-only <base>...HEAD` must
touch only `ktor/**`, `settings.gradle`, `gradle/libs.versions.toml`, `.github/workflows/store6.yml`,
and the two design docs — never `core/**`. The supporting gates:

- "Reject core-internal access" fails on any `InternalStoreApi` or `…core.internal` reference in
  `ktor` sources.
- "TD-13 seam freeze list" fails if the seam package's file set changes.
- `core`'s `core.api`/`core.klib.api` dumps are unchanged, so `apiCheck` on `core` proves the core
  surface did not move.

## 18. Stability tier and rollout

The kit ships `@ExperimentalStoreApi` in its own artifact (`STABILITY.md` §3). It consumes the
`Fetcher`/`FetcherResult`/`FetchPlan` seam, a freeze candidate, not frozen; the README states this
as `graphql/README.md` does. By exercising `FetchPlan.Conditional` end to end on a real HTTP client
— within the lifetime bound of [§3.6](#36-validator-lifetime-a-core-constraint-the-kit-inherits) —
it is a second real producer of the conditional-fetch behavior, the kind of end-to-end exercise the
roadmap wants before a seam is called a freeze candidate (`ROADMAP.md` lines 60–66).

---

### Appendix A — evidence index

| Claim | Source |
|---|---|
| Fetcher receives conditional `etag`; interface overload only; DelicateStoreApi opt-in | `core/.../seam/Fetcher.kt` 18–28; `StoreBuilder.kt` 92–104; `graphql/.../GraphQlFetcher.kt` 54–60 |
| `FetcherResult` cases and semantics | `core/.../seam/FetcherResult.kt` 22–45 |
| `FetchPlan.Conditional` carries only `etag: String` | `core/.../seam/FreshnessValidator.kt` 44–63 |
| Plan reads in-memory envelope `meta?.etag`, not durable `status.meta.etag` | `core/.../internal/FreshnessValidator.kt` 87–94 |
| `StoreMeta` fields; `etag` KDoc = "the optional entity tag" | `core/.../StoreMeta.kt` 10–16 |
| Cold 304 is terminal Missing; mid-flight-hydrated baseline replans; Revalidated only for unchanged live baseline; Superseded branch | `core/.../internal/KeyEngine.kt` 1771–1836 |
| Cold-baseline 304 self-heals with one **conditional** replan (2..3 executions) | `graphql/sample/.../Main.kt` 109–133 |
| Hydration from SoT drops the ETag (etag = null) | `core/.../internal/KeyEngine.kt` 2168–2184 |
| Idle-eviction cap; freshness/age survive eviction | `core/.../StoreBuilder.kt` 106–121 |
| 304 → one `Revalidated`, clears staleness | `docs/store6/important-defaults.md` 99–100; `StoreResult.kt` 39–52 |
| Cancellation propagates; other throwables → `Error` | `graphql/.../GraphQlFetcher.kt` 74–81 |
| Typed error via `StoreError.Fetch.cause` | `core/.../StoreError.kt` 11–18; `graphql/sample/.../Main.kt` 86–96 |
| Zero-retry default | `docs/store6/important-defaults.md` 37–44 |
| Full 12-target convention | `tooling/plugins/.../Store6MultiplatformConventionPlugin.kt` 9–32 |
| Extension module template (deps, namespace, sample) | `graphql/build.gradle.kts`; `graphql/sample/build.gradle.kts`; `realtime/build.gradle.kts` |
| Short-name artifact coordinates | `graphql/gradle.properties` |
| Turbine/`runTest` shim block (copy verbatim) | `graphql/src/commonTest/.../GraphQlStoreIntegrationTest.kt` 251–260 (alias line 12) |
| CI lists a new module must join | `.github/workflows/store6.yml` 238–256, 263, 314, 441, 498–532, 675–702 |
| CI lists left untouched (seam freeze, swift-dumps, native-stress) | `.github/workflows/store6.yml` 283–305, 548, 748 |
| Kotlin 2.3.20 / coroutines 1.8.1 pins | `gradle/libs.versions.toml`; `STABILITY.md` §10 |
| Ktor klibs for all 12 targets; 3.5.x built with Kotlin 2.3.21 | Maven Central `ktor-client-core-*`, `ktor-client-mock-*`; Ktor 3.5.x changelog |

### Appendix B — how this revision answered the adversarial reviews

| Review finding | Resolution |
|---|---|
| `HttpCache` swallows the 304 (both reviewers, Critical) | [§13.2](#132-httpclient-plugin-compatibility): fail-fast rejection by default, `allowHttpCache` opt-out, dedicated test |
| Generic `fun interface` mapper does not compile (both, Critical) | [§6.2](#62-policy-mapper-and-error-types): non-generic `KtorErrorMapper` → `KtorOutcome`; `KtorExchange` fully declared |
| Last-Modified in a field documented as an entity tag (Sol, Critical) | [§5.2](#52-decision-an-asymmetric-validator-token-in-the-opaque-string)–[§5.3](#53-the-token-is-opaque-to-core-but-not-invisible): asymmetric encoding (ETag verbatim, `LM:` sentinel), persistence visibility stated, `lastModifiedFallback` opt-out |
| Validator lost on eviction/restart (Sol, Major) | [§3.6](#36-validator-lifetime-a-core-constraint-the-kit-inherits): documented as an accepted, cited limitation with a pinning test |
| Cold-304 "unconditional self-heal" wrong (both, Major) | [§3.4](#34-the-not-modified-path): corrected to terminal Missing vs conditional replan, cited to `KeyEngine.kt` 1771–1836 |
| `NotModified` → `Revalidated` overclaimed (Sol, Major) | [§3.4](#34-the-not-modified-path): qualified to the unchanged-live-baseline branch |
| Header append, not authoritative (both, Major) | [§8](#8-conditional-request-construction): remove-then-set replace semantics |
| GET/HEAD-only conditional semantics (both, Major) | [§8](#8-conditional-request-construction): conditional header applied only for GET/HEAD |
| Redirect validator leakage (Sol, Major) | [§8](#8-conditional-request-construction): documented caveat and guidance |
| Connection cleanup on non-decoded paths (Grok, Major) | [§7](#7-http-outcome--fetcherresult-mapping)/[§8](#8-conditional-request-construction): `prepareRequest().execute { }` scope releases the response |
| 304 validator-kind flip (both, Major) | [§5.4](#54-response-side-validator-selection-and-the-no-flip-rule): no-flip rule |
| `KtorFetchException.status` nullability contradiction (Sol, Major) | [§6.2](#62-policy-mapper-and-error-types): status is always non-null; transport/decode return the original exception |
| Representation identity / `Vary` (Sol, Major) | [§10](#10-key-design-and-representation-identity): key-must-determine-representation contract |
| `execute` vs `get` forces method; fresh builder (Grok, Major) | [§8](#8-conditional-request-construction): `prepareRequest`/`request`, fresh per fetch |
| Dependency stack alignment beyond coroutines; klib ABI overstated (both, Major/Minor) | [§13.1](#131-version-alignment-the-highest-risk-part-the-plan-front-loads-a-spike): full kotlinx stack + `coroutines-test`, manifest-based ABI check, fallback ladder |
| 12-target "tested" vs executed lanes (Sol, Major) | [§14](#14-testing-strategy): compilation/publication vs executed-lane distinction |
| Sample missing `ktor-client-mock` dep (Sol, Major) | [§12](#12-module-build-and-publishing): sample declares `libs.ktor.client.mock` |
| Zero-core-diff gates insufficient (Sol, Major) | [§17](#17-zero-core-diff-verification): explicit path-diff check is the guarantee |
| Retryability labels inaccurate (Sol, Minor) | [§7](#7-http-outcome--fetcherresult-mapping): classification removed |
| 404 "freshness untouched" imprecise (Sol, Minor) | [§7](#7-http-outcome--fetcherresult-mapping): records a fetch failure, preserves residence/validator/success time/stale |
| Demand quote not in repo ROADMAP (Sol, Minor) | [§1](#1-problem-and-demand): attributed to the beta-features brief; graphql precedent acknowledged |
| Shim citation was the import block (both, Minor) | [§14](#14-testing-strategy)/Appendix A: corrected to lines 251–260 |
