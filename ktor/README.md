# ktor

Ktor HTTP fetcher kit for Store v6. It builds a Store fetcher from a caller-owned
`HttpClient`, a per-key request configuration, and a response decoder. It maps HTTP
validators onto Store's conditional-fetch seam and maps HTTP outcomes onto
`FetcherResult`. Everything here is `@ExperimentalStoreApi`.
The seam it consumes is a freeze candidate, not frozen — see [STABILITY.md](../STABILITY.md).

The kit does not create or close the client, install `ContentNegotiation`, or select a
serialization format. Configure those concerns on the client and decode each adopted
response in the supplied `decode` function.

## Install

Until the snapshot is published remotely, publish `core` and `ktor` to Maven Local:

```shell
./gradlew :core:publishToMavenLocal :ktor:publishToMavenLocal
```

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.mobilenativefoundation.store:ktor:6.0.0-SNAPSHOT")
}
```

## First result

```kotlin
val itemStore = store<ItemKey, String> {
    ktorFetcher(
        client = client,
        decode = { response -> response.bodyAsText() },
        configureRequest = { key ->
            url("https://example.test/items/${key.canonicalId()}")
        },
    )
}

val item = itemStore.get(ItemKey("1"))
```

`configureRequest` sets the method, URL, headers, and body for a key. The default Ktor
request method is GET. `decode` runs only when the default mapping adopts a 2xx response.

## Entry points

The standalone factory and `StoreBuilder` extension have the same configuration:

```kotlin
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

- `KtorNotFoundPolicy.Error` maps 404 and 410 to a typed error.
  `KtorNotFoundPolicy.Delete` maps them to `FetcherResult.Deleted`.
- `KtorErrorMapper.map(exchange)` runs on every completed response before `decode`.
  Return `KtorOutcome.Defer` to use the default table. `KtorOutcome.Fail`,
  `KtorOutcome.Delete`, and `KtorOutcome.NotModified` override it.
- `KtorExchange` exposes `status`, `method`, `url`, whether the kit sent a conditional
  header, and the live `response`. Read the response only during `map`.
- `KtorFetchException(status, method, url, message, cause = null)` is the
  `StoreError.Fetch.cause` for an HTTP status the kit does not adopt. Transport and decode
  failures retain their original exception types.

## Response mapping

This table applies when `KtorErrorMapper` returns `KtorOutcome.Defer`.

| HTTP outcome | `FetcherResult` | Behavior |
| --- | --- | --- |
| 2xx other than 206 with a body accepted by `decode` | `Success(value, validatorToken)` | `decode` runs. The validator selection is described below. |
| `206 Partial Content` | `Error(KtorFetchException)` | A partial body is not adopted as a complete representation. |
| `204 No Content` or `205 Reset Content` | Delegated to `decode` | A returned value becomes `Success`. A thrown empty-body failure becomes `Error(originalException)`. |
| `304 Not Modified` after a conditional request | `NotModified(newEtagOrNull)` | A response ETag replaces the recorded token. Null keeps the previous token. |
| `304 Not Modified` without a conditional request | `Error(KtorFetchException)` | The status is treated as a protocol anomaly. |
| 404 or 410 with `KtorNotFoundPolicy.Error` | `Error(KtorFetchException)` | This is the non-destructive default. |
| 404 or 410 with `KtorNotFoundPolicy.Delete` | `Deleted` | This clears the resident value and its freshness metadata. |
| Other 4xx or 5xx | `Error(KtorFetchException)` | The exception retains the HTTP status, method, and URL. |
| Transport or I/O failure | `Error(originalException)` | `CancellationException` propagates instead. |
| `decode` throws | `Error(originalException)` | The original decoder exception is retained. |

The kit sets `expectSuccess = false` for its request so the default mapping receives raw
statuses. A custom `HttpResponseValidator` can still throw, and that exception is returned
unchanged as `FetcherResult.Error`. If a 3xx other than 304 reaches the kit, it maps to
`Error(KtorFetchException)`.

## Conditional requests

For a GET or HEAD with a recorded validator, the kit sends one conditional header:

- An ETag is stored and sent verbatim, including quotes and a weak `W/` marker. It becomes
  `If-None-Match`.
- When a successful 2xx has no ETag, `Last-Modified` is stored as `LM:<HTTP-date>` and
  becomes `If-Modified-Since` on the next conditional request.
- When both response headers are present, ETag wins.
- Set `lastModifiedFallback = false` to ignore `Last-Modified`. A previously stored `LM:`
  token also produces no conditional header while this option is false.

Core treats the validator token as opaque, but a persistent source of truth stores it in
its ETag field. A Last-Modified-backed value therefore has the `LM:` prefix visible in the
durable ETag column and to custom freshness validation or telemetry. ETags must be
well-formed HTTP entity tags. A bare value beginning with the reserved `LM:` prefix is
interpreted as Last-Modified.

On 304, the kit adopts a replacement token only when the response contains an ETag. A 304
with only `Last-Modified` returns `NotModified(null)`, which keeps the previous token. This
prevents an ETag-based request from switching to Last-Modified after revalidation.

## Caveats

### Validator lifetime

Validators are residence-scoped. The first fetch after idle eviction, process restart with
a durable source of truth, or any hydration of a nonresident value is unconditional
because core does not restore the durable ETag into resident metadata. A successful
response that supplies a validator re-establishes conditional revalidation for that
residence lifetime. The integration suite pins both the unconditional post-eviction
hydration request and resident revalidation after a validator-bearing fetch.

### `HttpCache`

Ktor's `HttpCache` plugin conflicts with this kit's conditional path. It can add its own
conditional headers and replace a received 304 with a cached 2xx response, so Store would
observe `Success` instead of `NotModified`. The factory detects an installed `HttpCache`
and throws `IllegalArgumentException` by default.

`allowHttpCache = true` disables that construction-time guard. It does not make the two
mechanisms compatible. Use it only when the client's cache cannot affect these requests or
when the changed 304 behavior is acceptable.

### Methods and conditional headers

The kit sends conditional headers only for GET and HEAD. Other methods fetch
unconditionally. Do not set `If-None-Match` or `If-Modified-Since` in
`configureRequest`. The kit removes both headers and then sets the one represented by its
recorded validator, or sets neither when no applicable validator exists.

### Representation identity

The `StoreKey` identity must determine the selected representation. Fold every
representation-selecting input into the key, including URL, `Accept`, `Accept-Language`,
locale, authorization scope, and other `Vary` axes. Reusing one key across variants can
send a validator for one representation while fetching another.

### Redirects

Ktor redirect handling carries conditional headers across redirects. Disable redirects for
these requests or ensure every redirect target selects the same representation as the
original request.

### Retry

The kit performs no retries. Compose Ktor's `HttpRequestRetry` plugin on the caller-owned
client for transport-level retries, or compose the Store retry/backoff policy pack at the
fetcher level.

## Sample

```shell
./gradlew :ktor-sample:run
```

The headless JVM sample in [`ktor/sample`](sample) uses `MockEngine` to assert four scenes:
an ETag-backed 200 followed by conditional 304 revalidation, a 500 surfaced through
`KtorFetchException`, the 404 Error-versus-Delete policy, and a Last-Modified round trip
through `If-Modified-Since`.
