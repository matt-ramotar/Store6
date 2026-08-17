# store6-graphql

GraphQL operation fetcher kit for Store v6. A store built with this module is a document
cache: each execution of one GraphQL operation caches one decoded response value under an
opaque, application-keyed identity. Everything here is `@ExperimentalStoreApi`.
The seam it consumes is a freeze candidate, not frozen — see [STABILITY.md](../STABILITY.md).

The module has no dependency beyond `store6-core` and ships the same Kotlin Multiplatform
target set as `store6-core`. You bring the transport: a `GraphQlExecutor` you implement on
your HTTP client owns JSON encoding of variables, decoding of response data, and
translation of the response `errors` array into `GraphQlError` values. The kit never parses
GraphQL documents and never normalizes responses into entities. The module has no crypto
dependency. The application supplies an HMAC-SHA-256 capability while retaining the key.

## Install

Until the snapshot is published remotely, publish `store6-core` and `store6-graphql` to
Maven Local:

```shell
./gradlew :store6-core:publishToMavenLocal :store6-graphql:publishToMavenLocal
```

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.mobilenativefoundation.store:store6-graphql:6.0.0-SNAPSHOT")
}
```

## First result

```kotlin
val cacheIdentity = GraphQlCacheIdentity(
    partition = tenant.opaqueCachePartition,
    cacheContractVersion = "user-decoder-3|policy-fail",
    digestKeyId = "app-hmac-2026-01",
    keyedDigest = GraphQlKeyedDigest { preimage ->
        applicationHmacSha256(preimage)
    },
)

val getUser = GraphQlOperation(
    document = "query GetUser(\$id: ID!) { user(id: \$id) { id name } }",
    name = "GetUser",
    cacheIdentity = cacheIdentity,
)

val store = store<GraphQlOperationKey, User> {
    graphQlFetcher(getUser) { request ->
        // Your transport: encode request.variables, send request.operation.document,
        // decode response data to User and response errors to GraphQlError values.
        val response = api.execute(request.operation.document, request.variables)
        GraphQlExecutorResult.Data(data = response.user, errors = response.errors)
    }
}

val ada = store.get(getUser.key(graphQlVariables { put("id", "1") }))
```

A second `get` of the same key, in any variable insertion order, serves the cached response
without executing. Every Store read policy applies unchanged: `Freshness.MustBeFresh`
re-executes, `Freshness.LocalOnly` never executes, and `store.invalidate(key)` marks one
variable set stale.

## Entry points

- `GraphQlCacheIdentity(partition, cacheContractVersion, digestKeyId, keyedDigest)` — the
  required privacy and compatibility boundary. `keyedDigest` must compute a 32-byte
  HMAC-SHA-256 with an application-held key.
- `GraphQlOperation(document, name, cacheIdentity)` — an executable document, operation name,
  and cache identity contract. The document is opaque to Store and reaches the executor
  unparsed.
- `GraphQlOperation.key(variables)` — the `GraphQlOperationKey` for one execution.
- `graphQlVariables { }` — builder for `GraphQlVariables` over the `GraphQlValue` model
  (`NullValue`, `BooleanValue`, `IntValue`, `FloatValue`, `StringValue`, `ListValue`,
  `ObjectValue`).
- `graphQlFetcher(operation, partialDataPolicy, executor)` — the seam `Fetcher`, also
  installable through the `StoreBuilder.graphQlFetcher(...)` extension.
- `GraphQlExecutor<V>` — `suspend execute(GraphQlRequest): GraphQlExecutorResult<V>`.
  Throw for transport failure. Return `Data(data, errors, etag)` for any response the
  server produced, or `NotModified(etag)` to answer a conditional request.
- `GraphQlOperationException.errorCount` — the redacted `StoreError.Fetch` cause when a
  response reports errors the fetcher does not adopt. The exception does not retain server
  messages or paths.

## Cache identity

`GraphQlOperation.key(...)` is the only key construction path. Its `s6gql1` canonical id is
the lowercase hexadecimal HMAC-SHA-256 of a length-framed tuple containing:

1. the identity format and domain;
2. the digest key id;
3. the explicit tenant or cache partition;
4. the explicit cache-contract version;
5. the operation name;
6. the exact UTF-8 document bytes; and
7. deterministic, typed canonical variables.

Object fields sort by UTF-16 code-unit order at every nesting depth. List order is
significant. Explicit `null` differs from an absent variable. Floating-point identity uses
normalized IEEE-754 bits rather than runtime number formatting. Equivalent inputs therefore
produce the same preimage on supported Kotlin targets.

The namespace is a separate HMAC-derived `graphql:s6gql1:<digest>` value. The partition is
required and neither it nor variables, document text, cache-contract version, or digest key
id appears in the namespace or canonical id. `toString()` implementations redact variables,
documents, ETags, response messages, and response paths. Application telemetry and logs
should record only the opaque namespace and canonical id.

The HMAC implementation is part of the contract: it must return exactly 32 bytes, retain the
secret key outside Store, and be deterministic for the same input. Use platform key storage
where available. Keep the selected key stable for the `GraphQlCacheIdentity` lifetime. Never
substitute an unkeyed hash or a digest of the variables alone.

### Persistence upgrades

Keep `cacheContractVersion` unchanged only while the persisted decoded value has the same
meaning. Change it when the decoder, schema interpretation, or partial-data policy can change
the cached value. Change `digestKeyId` when rotating the HMAC key. Exact document changes,
including whitespace, automatically change identity.

The pre-`s6gql1` format embedded operation names and raw canonical variables. Upgrading to
this API makes those rows miss because both namespace and canonical id formats change.
Purge legacy `graphql:<operationName>` rows in the application's persistence migration.
Future identity-format changes have the same miss-and-purge requirement; rows are never
read across formats.

## Response mapping

| Executor outcome | Store outcome |
| --- | --- |
| `Data(data != null, no errors)` | `FetcherResult.Success(data, etag)` |
| `Data(data != null, errors)` + `FailOnErrors` (default) | `FetcherResult.Error(GraphQlOperationException)` |
| `Data(data != null, errors)` + `AdoptPartialData` | `FetcherResult.Success(data, etag)` |
| `Data(data == null, errors)` | `FetcherResult.Error(GraphQlOperationException)` with only the error count retained |
| `Data(data == null, no errors)` | `FetcherResult.Error` naming the protocol violation |
| `NotModified(etag)` | `FetcherResult.NotModified(etag)` — streams observe `StoreResult.Revalidated` |
| thrown exception | redacted `FetcherResult.Error`; a redacted `CancellationException` propagates |
| key from another operation or cache-identity contract | redacted `FetcherResult.Error`, without executing |

`GraphQlPartialDataPolicy.FailOnErrors` is the default so a cached value never contains
error-substituted nulls. Opt into `AdoptPartialData` only when the decoded type tolerates
missing fields, because the store caches the partial value as if it were complete. Changing
this policy requires a new `cacheContractVersion`.

## Conditional requests

When the engine plans a conditional fetch for a key with a recorded ETag,
`GraphQlRequest.etag` is non-null. An executor that can revalidate (for example HTTP
`If-None-Match` on a GET-shaped operation) returns `NotModified` to confirm the cached
response. Executors without revalidation support ignore the ETag and execute normally.

## Sample

```shell
./gradlew :store6-graphql-sample:run
```

The headless JVM sample supplies HMAC-SHA-256 through the JDK and asserts four scenes over an
in-process scripted executor: opaque identity across variable orders, document-cache serve
without re-execution, separately versioned fail-vs-adopt partial-response behavior, and
`NotModified` revalidation with recorded ETags.
