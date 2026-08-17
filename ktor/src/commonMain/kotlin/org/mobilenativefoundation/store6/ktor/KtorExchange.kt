package org.mobilenativefoundation.store6.ktor

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

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
