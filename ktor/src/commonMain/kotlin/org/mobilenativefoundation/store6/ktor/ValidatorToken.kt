package org.mobilenativefoundation.store6.ktor

internal const val LAST_MODIFIED_TOKEN_PREFIX: String = "LM:"

internal class ConditionalValidatorHeaders internal constructor(
    internal val ifNoneMatch: String?,
    internal val ifModifiedSince: String?,
)

/**
 * Stores ETags verbatim and prefixes only Last-Modified values with
 * [LAST_MODIFIED_TOKEN_PREFIX], keeping ordinary entity-tag tokens unchanged.
 */
internal fun encodeValidatorToken(
    etagHeader: String?,
    lastModifiedHeader: String?,
    lastModifiedFallback: Boolean,
): String? =
    etagHeader
        ?: if (lastModifiedFallback && lastModifiedHeader != null) {
            LAST_MODIFIED_TOKEN_PREFIX + lastModifiedHeader
        } else {
            null
        }

/**
 * Restores the conditional header represented by [token]. A disabled Last-Modified fallback also
 * rejects previously stored [LAST_MODIFIED_TOKEN_PREFIX] tokens.
 */
internal fun decodeValidatorToken(
    token: String,
    lastModifiedFallback: Boolean,
): ConditionalValidatorHeaders? =
    if (token.startsWith(LAST_MODIFIED_TOKEN_PREFIX)) {
        if (lastModifiedFallback) {
            ConditionalValidatorHeaders(
                ifNoneMatch = null,
                ifModifiedSince = token.removePrefix(LAST_MODIFIED_TOKEN_PREFIX),
            )
        } else {
            null
        }
    } else {
        ConditionalValidatorHeaders(
            ifNoneMatch = token,
            ifModifiedSince = null,
        )
    }

/**
 * Selects a replacement token from a 304 response. Ignoring Last-Modified here prevents a
 * previously stored ETag from being downgraded to a Last-Modified validator.
 */
internal fun selectNotModifiedValidatorToken(
    etagHeader: String?,
    lastModifiedHeader: String?,
    lastModifiedFallback: Boolean,
): String? = etagHeader
