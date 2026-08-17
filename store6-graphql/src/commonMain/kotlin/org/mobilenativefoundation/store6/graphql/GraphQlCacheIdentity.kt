package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreNamespace

/**
 * An application-owned HMAC-SHA-256 capability used to derive opaque GraphQL cache identity.
 *
 * The implementation must return the 32-byte HMAC-SHA-256 of [input] under an application-held
 * secret key. Store never receives or persists that key. Implementations must be deterministic
 * for a given key and input and safe for concurrent calls. Store clears the input and returned
 * byte arrays after each call, so implementations must not retain or reuse either array.
 */
@ExperimentalStoreApi
public fun interface GraphQlKeyedDigest {
    /**
     * Computes a 32-byte HMAC-SHA-256 without exposing the application key.
     *
     * @param input the complete versioned identity preimage
     * @return exactly 32 digest bytes
     */
    public fun digest(input: ByteArray): ByteArray
}

/**
 * The privacy and compatibility boundary for GraphQL cache keys.
 *
 * Every argument is required. [partition] identifies one tenant or cache partition and may
 * contain sensitive data because only its keyed digest is persisted. [cacheContractVersion]
 * identifies the decoded value contract. Applications must change it when decoder behavior,
 * schema interpretation, or partial-data policy can change the cached value. [digestKeyId]
 * identifies the application HMAC key and must change when that key rotates. The provider must
 * keep that key stable for this instance's lifetime; create a new identity when rotating.
 *
 * The `s6gql1` format covers the exact UTF-8 document bytes, operation name, deterministic
 * canonical variables, partition, cache contract version, and digest key id. A format change
 * changes the namespace and canonical id, so rows written by another format miss.
 */
@ExperimentalStoreApi
public class GraphQlCacheIdentity(
    partition: String,
    cacheContractVersion: String,
    digestKeyId: String,
    private val keyedDigest: GraphQlKeyedDigest,
) {
    private val partition: String = requireComponent(partition, "partition")
    private val cacheContractVersion: String =
        requireComponent(cacheContractVersion, "cacheContractVersion")
    private val digestKeyId: String = requireComponent(digestKeyId, "digestKeyId")

    internal val namespace: StoreNamespace =
        StoreNamespace(
            "$NAMESPACE_PREFIX${digestHex(NAMESPACE_KIND)}",
        )

    internal fun canonicalId(
        operationName: String,
        document: String,
        variables: GraphQlVariables,
    ): String =
        "$CANONICAL_ID_PREFIX${
            digestHex(
                KEY_KIND,
                operationName,
                document,
                variables.canonicalString(),
            )
        }"

    override fun toString(): String = "GraphQlCacheIdentity(format=$FORMAT_LABEL)"

    private fun digestHex(
        kind: String,
        vararg payload: String,
    ): String {
        val preimage =
            framedPreimage(
                listOf(
                    PREIMAGE_DOMAIN,
                    FORMAT_VERSION,
                    kind,
                    digestKeyId,
                    partition,
                    cacheContractVersion,
                ) + payload,
            )
        val digest =
            try {
                keyedDigest.digest(preimage)
            } catch (_: Throwable) {
                throw IllegalStateException("GraphQL cache identity keyed digest failed.")
            } finally {
                preimage.fill(0)
            }
        try {
            require(digest.size == HMAC_SHA_256_BYTES) {
                "GraphQL cache identity keyed digest must return exactly 32 bytes."
            }
            return digest.toHex()
        } finally {
            digest.fill(0)
        }
    }
}

private fun requireComponent(
    value: String,
    name: String,
): String {
    require(value.isNotBlank()) { "GraphQlCacheIdentity requires a non-blank $name." }
    return value
}

private fun framedPreimage(fields: List<String>): ByteArray {
    val encodedFields = fields.map(String::encodeToByteArray)
    val size = encodedFields.sumOf { field -> LENGTH_PREFIX_BYTES + field.size }
    val result = ByteArray(size)
    var offset = 0
    encodedFields.forEach { field ->
        result[offset] = (field.size ushr 24).toByte()
        result[offset + 1] = (field.size ushr 16).toByte()
        result[offset + 2] = (field.size ushr 8).toByte()
        result[offset + 3] = field.size.toByte()
        offset += LENGTH_PREFIX_BYTES
        field.copyInto(result, destinationOffset = offset)
        offset += field.size
    }
    return result
}

private fun ByteArray.toHex(): String =
    buildString(size * 2) {
        for (byte in this@toHex) {
            append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
            append(HEX_DIGITS[byte.toInt() and 0x0f])
        }
    }

private const val PREIMAGE_DOMAIN = "store6-graphql"
private const val FORMAT_VERSION = "1"
private const val FORMAT_LABEL = "s6gql1"
private const val KEY_KIND = "key"
private const val NAMESPACE_KIND = "namespace"
private const val CANONICAL_ID_PREFIX = "$FORMAT_LABEL:"
private const val NAMESPACE_PREFIX = "graphql:$FORMAT_LABEL:"
private const val LENGTH_PREFIX_BYTES = 4
private const val HMAC_SHA_256_BYTES = 32
private const val HEX_DIGITS = "0123456789abcdef"
