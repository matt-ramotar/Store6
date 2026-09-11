package org.mobilenativefoundation.store6.ktor

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreBuilder
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult

/**
 * Builds a [Fetcher] that revalidates over HTTP on [client].
 *
 * @param client caller-owned HTTP client; the kit never closes it. Must not have Ktor's HttpCache
 *   plugin installed unless [allowHttpCache] is true (see the technical design §13.2). Must not
 *   contribute `If-None-Match` or `If-Modified-Since` from `defaultRequest` or from any other
 *   plugin: the kit's header removal is scoped to the request builder and cannot reach a header
 *   the request pipeline adds afterwards, so such a header is sent without the kit knowing, which
 *   turns a 304 into a fetch failure or adds a second entity tag beside the kit's validator.
 * @param decode maps an adopted 2xx response to a value; invoked inside the response scope only for
 *   outcomes the kit adopts as Success. The default table never calls it for 204, 205, or 206,
 *   because none of those carries a representation. A caller who wants different handling for
 *   those statuses opts in through [errorMapper]; note that no [KtorOutcome] adopts a body, so a
 *   mapper can map them to Delete, Fail, or NotModified but never to a value.
 * @param notFoundPolicy how 404 and 410 are mapped (default: typed error, non-destructive)
 * @param lastModifiedFallback whether to record and send Last-Modified when no ETag is available
 * @param errorMapper optional override of status-to-result mapping; returns Defer to keep defaults
 * @param allowHttpCache set true only when you accept that HttpCache can intercept the 304 path
 * @param configureRequest applies the per-key request shape (method, URL, headers, body). The kit
 *   removes `If-None-Match` and `If-Modified-Since` after this lambda runs and then sets at most
 *   one of them from its recorded validator, so a conditional header set here is always discarded.
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
): Fetcher<K, V> {
    requireNoHttpCache(client, allowHttpCache)
    return createKtorFetcher(
        client = client,
        notFoundPolicy = notFoundPolicy,
        lastModifiedFallback = lastModifiedFallback,
        errorMapper = errorMapper,
        decode = decode,
        configureRequest = configureRequest,
    )
}

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
) {
    val theFetcher =
        org.mobilenativefoundation.store6.ktor.ktorFetcher(
            client = client,
            notFoundPolicy = notFoundPolicy,
            lastModifiedFallback = lastModifiedFallback,
            errorMapper = errorMapper,
            allowHttpCache = allowHttpCache,
            decode = decode,
            configureRequest = configureRequest,
        )
    fetcher(theFetcher)
}

@ExperimentalStoreApi
internal fun <K : StoreKey, V : Any> createKtorFetcher(
    client: HttpClient,
    notFoundPolicy: KtorNotFoundPolicy,
    lastModifiedFallback: Boolean,
    errorMapper: KtorErrorMapper,
    decode: suspend (HttpResponse) -> V,
    configureRequest: HttpRequestBuilder.(K) -> Unit,
): Fetcher<K, V> =
    KtorFetcher(
        client = client,
        notFoundPolicy = notFoundPolicy,
        lastModifiedFallback = lastModifiedFallback,
        errorMapper = errorMapper,
        decode = decode,
        configureRequest = configureRequest,
    )

internal fun requireNoHttpCache(
    client: HttpClient,
    allowHttpCache: Boolean,
) {
    if (!allowHttpCache && client.pluginOrNull(HttpCache) != null) {
        throw IllegalArgumentException(
            "Ktor's HttpCache plugin conflicts with conditional revalidation because it can " +
                "intercept 304 responses; pass allowHttpCache = true to accept this interaction.",
        )
    }
}

@OptIn(DelicateStoreApi::class)
@ExperimentalStoreApi
private class KtorFetcher<K : StoreKey, V : Any>(
    private val client: HttpClient,
    private val notFoundPolicy: KtorNotFoundPolicy,
    private val lastModifiedFallback: Boolean,
    private val errorMapper: KtorErrorMapper,
    private val decode: suspend (HttpResponse) -> V,
    private val configureRequest: HttpRequestBuilder.(K) -> Unit,
) : Fetcher<K, V> {
    override suspend fun fetch(
        key: K,
        etag: String?,
    ): FetcherResult<V> =
        try {
            var conditionalSent = false
            client
                .prepareRequest {
                    configureRequest(key)
                    expectSuccess = false
                    headers.remove(HttpHeaders.IfNoneMatch)
                    headers.remove(HttpHeaders.IfModifiedSince)

                    if (etag != null && (method == HttpMethod.Get || method == HttpMethod.Head)) {
                        val validatorHeaders =
                            decodeValidatorToken(
                                token = etag,
                                lastModifiedFallback = lastModifiedFallback,
                            )
                        validatorHeaders?.ifNoneMatch?.let { value ->
                            headers[HttpHeaders.IfNoneMatch] = value
                            conditionalSent = true
                        }
                        validatorHeaders?.ifModifiedSince?.let { value ->
                            headers[HttpHeaders.IfModifiedSince] = value
                            conditionalSent = true
                        }
                    }
                }.execute { response ->
                    mapResponse(response, conditionalSent)
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // An engine may surface a cancelled call as its own exception type rather than as a
            // CancellationException (Darwin's NSURLErrorCancelled, OkHttp's IOException("Canceled"),
            // the JS AbortError). Recording that as a fetch failure would give the key an error
            // result and a failure record for a coroutine that is no longer alive.
            currentCoroutineContext().ensureActive()
            FetcherResult.Error(failure)
        }

    private suspend fun mapResponse(
        response: HttpResponse,
        conditionalSent: Boolean,
    ): FetcherResult<V> {
        val exchange =
            KtorExchange(
                status = response.status,
                method = response.request.method,
                url = response.request.url.toString(),
                conditional = conditionalSent,
                response = response,
            )
        return when (val outcome = errorMapper.map(exchange)) {
            KtorOutcome.Defer -> mapDefault(exchange)
            is KtorOutcome.Fail -> FetcherResult.Error(outcome.exception)
            KtorOutcome.Delete -> FetcherResult.Deleted
            is KtorOutcome.NotModified ->
                if (exchange.conditional) {
                    FetcherResult.NotModified(outcome.validatorToken)
                } else {
                    statusError(
                        exchange,
                        "KtorOutcome.NotModified requires a conditional request: this exchange " +
                            "sent no validator, so nothing was compared and freshness cannot be " +
                            "refreshed.",
                    )
                }
        }
    }

    private suspend fun mapDefault(exchange: KtorExchange): FetcherResult<V> {
        val status = exchange.status
        return when {
            status == HttpStatusCode.PartialContent ->
                statusError(
                    exchange,
                    "HTTP 206 Partial Content cannot be adopted as a complete representation.",
                )

            status == HttpStatusCode.NoContent || status == HttpStatusCode.ResetContent ->
                statusError(
                    exchange,
                    "HTTP ${status.value} ${status.description} carries no representation to adopt; " +
                        "return a KtorOutcome from a KtorErrorMapper to handle it.",
                )

            status.value in 200..299 ->
                FetcherResult.Success(
                    value = decode(exchange.response),
                    etag =
                        encodeValidatorToken(
                            etagHeader = exchange.response.headers[HttpHeaders.ETag],
                            lastModifiedHeader = exchange.response.headers[HttpHeaders.LastModified],
                            lastModifiedFallback = lastModifiedFallback,
                        ),
                )

            status == HttpStatusCode.NotModified && exchange.conditional ->
                FetcherResult.NotModified(
                    selectNotModifiedValidatorToken(
                        etagHeader = exchange.response.headers[HttpHeaders.ETag],
                    ),
                )

            status == HttpStatusCode.NotModified ->
                statusError(
                    exchange,
                    "HTTP 304 Not Modified was received without a conditional request.",
                )

            status == HttpStatusCode.NotFound || status == HttpStatusCode.Gone ->
                when (notFoundPolicy) {
                    KtorNotFoundPolicy.Error -> statusError(exchange)
                    KtorNotFoundPolicy.Delete -> FetcherResult.Deleted
                }

            else -> statusError(exchange)
        }
    }

    private fun statusError(
        exchange: KtorExchange,
        message: String =
            "HTTP ${exchange.status.value} ${exchange.status.description} was not adopted.",
    ): FetcherResult.Error =
        FetcherResult.Error(
            KtorFetchException(
                status = exchange.status,
                method = exchange.method,
                url = exchange.url,
                message = "$message ${exchange.method.value} ${exchange.url}",
            ),
        )
}
