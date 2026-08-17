package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StoreKeyIdentityTest {

    private class RawKey(
        override val namespace: StoreNamespace,
        private val canonicalId: String,
    ) : StoreKey {
        override fun canonicalId(): String = canonicalId
    }

    private class CompositeKey(
        override val namespace: StoreNamespace,
        private val fields: List<String>,
    ) : StoreKey {
        override fun canonicalId(): String = canonicalFieldsV1(fields)
    }

    @Test
    fun namespacesWithTheSameValue_areEqualAndShareAHashCode() {
        val first = StoreNamespace("documents")
        val second = StoreNamespace("documents")

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(1, setOf(first, second).size)
    }

    @Test
    fun namespaceComparison_isCaseSensitiveAndDoesNotNormalizeUnicode() {
        assertNotEquals(StoreNamespace("documents"), StoreNamespace("Documents"))
        assertNotEquals(StoreNamespace("\u00e9"), StoreNamespace("e\u0301"))
    }

    @Test
    fun canonicalEncoding_disambiguatesThePriorDelimiterCollision() {
        val expandedId = "user"
        val literalSuffixId = "user+org"
        assertEquals(
            priorCanonicalId(expandedId, includeOrganization = true),
            priorCanonicalId(literalSuffixId, includeOrganization = false),
        )

        val namespace = StoreNamespace("documents")
        val left = CompositeKey(namespace, listOf(expandedId, "expanded"))
        val right = CompositeKey(namespace, listOf(literalSuffixId, "base"))

        assertNotEquals(left.canonicalId(), right.canonicalId())
    }

    @Test
    fun canonicalEncoding_versionsAndLengthPrefixesUtf8Bytes() {
        assertEquals("s6k1", canonicalFieldsV1(emptyList()))
        assertEquals("s6k1:0:", canonicalFieldsV1(listOf("")))
        assertEquals("s6k1:2:c3a9", canonicalFieldsV1(listOf("\u00e9")))
        assertNotEquals(
            canonicalFieldsV1(listOf("tenant", "document")),
            canonicalFieldsV1(listOf("document", "tenant")),
        )
    }

    @Test
    fun storeIdentity_keepsNamespaceAndCanonicalIdAsSeparateComponents() = runTest {
        var fetchCount = 0
        val store =
            store<RawKey, String> {
                fetcher {
                    fetchCount += 1
                    "value:$fetchCount"
                }
            }
        val left = RawKey(StoreNamespace("tenant:document"), "revision")
        val right = RawKey(StoreNamespace("tenant"), "document:revision")
        assertEquals(
            "${left.namespace.value}:${left.canonicalId()}",
            "${right.namespace.value}:${right.canonicalId()}",
        )

        assertNotEquals(store.get(left), store.get(right))
        assertEquals(2, fetchCount)
        store.close()
    }

    @Test
    fun canonicalEncoding_generatedUtf8FieldSequencesDoNotCollide() {
        val random = Random(0x5a17c0de)
        val atoms =
            listOf(
                "\u0000",
                ":",
                "|",
                "+",
                "/",
                "\n",
                "\u00e9",
                "e\u0301",
                "\u03a9",
                "\u4e2d",
                "\ud83e\udded",
                "a",
                "Z",
            )
        val observed = mutableMapOf<String, List<String>>()

        repeat(10_000) {
            val fields =
                List(random.nextInt(from = 0, until = 6)) {
                    buildString {
                        repeat(random.nextInt(from = 0, until = 9)) {
                            append(atoms[random.nextInt(atoms.size)])
                        }
                    }
                }
            val canonicalId = canonicalFieldsV1(fields)
            val previous = observed[canonicalId]
            if (previous == null) {
                observed[canonicalId] = fields
            } else {
                assertEquals(previous, fields, "Canonical id collision: $canonicalId")
            }
        }
    }

    private companion object {
        private const val HEX = "0123456789abcdef"

        fun priorCanonicalId(id: String, includeOrganization: Boolean): String =
            if (includeOrganization) "$id+org" else id

        fun canonicalFieldsV1(fields: List<String>): String = buildString {
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
    }
}
