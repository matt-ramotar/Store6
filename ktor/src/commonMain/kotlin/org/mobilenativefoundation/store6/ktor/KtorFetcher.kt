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
            is KtorOutcome.NotModified -> FetcherResult.NotModified(outcome.validatorToken)
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
                        lastModifiedHeader = exchange.response.headers[HttpHeaders.LastModified],
                        lastModifiedFallback = lastModifiedFallback,
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
