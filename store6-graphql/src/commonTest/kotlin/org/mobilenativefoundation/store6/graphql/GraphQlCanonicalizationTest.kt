@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphQlCanonicalizationTest {
    @Test
    fun canonicalIdentity_isOpaqueAndStableAcrossEquivalentAppInstances() {
        val ab =
            operation(
                document = "query Search { search }",
                identity = identity(),
            ).key(
                graphQlVariables {
                    put("a", "canary-secret-a")
                    put("b", 2)
                },
            )
        val ba =
            operation(
                document = "query Search { search }",
                identity = identity(),
            ).key(
                graphQlVariables {
                    put("b", 2)
                    put("a", "canary-secret-a")
                },
            )

        assertEquals(ab.canonicalId(), ba.canonicalId())
        assertEquals(ab.namespace.value, ba.namespace.value)
        assertEquals(ab, ba)
        assertEquals(ab.hashCode(), ba.hashCode())
        assertTrue(ab.canonicalId().matches(Regex("s6gql1:[0-9a-f]{64}")))
        assertTrue(ab.namespace.value.matches(Regex("graphql:s6gql1:[0-9a-f]{64}")))
        assertFalse(ab.canonicalId().contains("canary-secret-a"))
        val persisted = mapOf(ab.persistenceId() to "cached")
        assertEquals("cached", persisted[ba.persistenceId()])
    }

    @Test
    fun identityPreimage_hasCrossRuntimeVectorWithExactDocumentAndContract() {
        val digest = RecordingDigest()
        val document = "query GetUser(\$id: ID!) { user(id: \$id) { name } }"
        val operation =
            GraphQlOperation(
                document = document,
                name = "GetUser",
                cacheIdentity =
                    GraphQlCacheIdentity(
                        partition = "tenant-a",
                        cacheContractVersion = "decoder-2|policy-fail",
                        digestKeyId = "key-2026",
                        keyedDigest = digest,
                    ),
            )

        val key =
            operation.key(
                graphQlVariables {
                    put("limit", 10)
                    put("id", "42")
                },
            )

        assertEquals(
            "0000000e73746f7265362d6772617068716c0000000131000000036b6579" +
                "000000086b65792d323032360000000874656e616e742d61000000156465636f6465722d327c" +
                "706f6c6963792d6661696c000000074765745573657200000032717565727920476574557365" +
                "72282469643a2049442129207b20757365722869643a2024696429207b206e616d65207d207d" +
                "000000167b226964223a223432222c226c696d6974223a31307d",
            digest.inputs.last().toHex(),
        )
        assertEquals(
            "s6gql1:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            key.canonicalId(),
        )
    }

    @Test
    fun exactDocumentChange_missesPersistedIdentity() {
        val first = operation("query Search { search { id } }", identity()).key()
        val whitespaceChange = operation("query Search{search { id } }", identity()).key()
        val persisted = mapOf(first.persistenceId() to "cached")

        assertNotEquals(first.canonicalId(), whitespaceChange.canonicalId())
        assertNull(persisted[whitespaceChange.persistenceId()])
    }

    @Test
    fun policyOrDecoderContractChange_missesPersistedIdentity() {
        val unchanged =
            operation(
                document = "query Search { search }",
                identity = identity(cacheContractVersion = "decoder-1|policy-fail"),
            ).key()
        val policyChanged =
            operation(
                document = "query Search { search }",
                identity = identity(cacheContractVersion = "decoder-1|policy-adopt"),
            ).key()
        val decoderChanged =
            operation(
                document = "query Search { search }",
                identity = identity(cacheContractVersion = "decoder-2|policy-fail"),
            ).key()
        val persisted = mapOf(unchanged.persistenceId() to "cached")

        assertNotEquals(unchanged.canonicalId(), policyChanged.canonicalId())
        assertNotEquals(unchanged.canonicalId(), decoderChanged.canonicalId())
        assertNull(persisted[policyChanged.persistenceId()])
        assertNull(persisted[decoderChanged.persistenceId()])
    }

    @Test
    fun tenantPartitions_haveOpaqueDistinctNamespacesAndIds() {
        val tenantA = operation("query Search { search }", identity(partition = "tenant-a")).key()
        val tenantB = operation("query Search { search }", identity(partition = "tenant-b")).key()

        assertNotEquals(tenantA.namespace.value, tenantB.namespace.value)
        assertNotEquals(tenantA.canonicalId(), tenantB.canonicalId())
        assertFalse(tenantA.namespace.value.contains("tenant-a"))
        assertFalse(tenantB.namespace.value.contains("tenant-b"))
    }

    @Test
    fun keyedDigestRotation_missesPersistedIdentity() {
        val first = operation("query Search { search }", identity(digestKeyId = "key-1")).key()
        val rotated = operation("query Search { search }", identity(digestKeyId = "key-2")).key()

        assertNotEquals(first.namespace.value, rotated.namespace.value)
        assertNotEquals(first.canonicalId(), rotated.canonicalId())
    }

    @Test
    fun formatUpgrade_missesLegacyRowsSoApplicationsCanPurgeThem() {
        val secret = "legacy-canary-secret"
        val legacyPersistence =
            mapOf(
                "graphql:GetUser/GetUser({\"id\":\"$secret\"})" to "legacy-value",
            )
        val operation =
            GraphQlOperation(
                document = "query GetUser(\$id: ID!) { user(id: \$id) { id } }",
                name = "GetUser",
                cacheIdentity = identity(),
            )
        val key = operation.key(graphQlVariables { put("id", secret) })

        assertNull(legacyPersistence["${key.namespace.value}/${key.canonicalId()}"])
        assertFalse(key.namespace.value.contains(secret))
        assertFalse(key.canonicalId().contains(secret))
    }

    @Test
    fun diagnostics_redactCanarySecrets() {
        val canary = "canary-secret-93f2"
        val variables =
            graphQlVariables {
                put("secret", canary)
                putList("nested") {
                    add(canary)
                    addObject { put(canary, canary) }
                }
            }
        val operation =
            GraphQlOperation(
                document = "query $canary { field }",
                name = canary,
                cacheIdentity =
                    identity(
                        partition = canary,
                        cacheContractVersion = canary,
                        digestKeyId = canary,
                    ),
            )
        val key = operation.key(variables)
        val error =
            GraphQlError(
                message = canary,
                path = listOf(GraphQlError.PathSegment.Field(canary)),
            )
        val exception = GraphQlOperationException(errorCount = 1)
        val request = GraphQlRequest(operation, variables, canary)
        val diagnostics =
            listOf(
                variables.toString(),
                GraphQlValue.StringValue(canary).toString(),
                GraphQlValue.IntValue(93).toString(),
                GraphQlValue.FloatValue(9.3).toString(),
                GraphQlValue.ListValue(listOf(GraphQlValue.StringValue(canary))).toString(),
                GraphQlValue.ObjectValue(mapOf(canary to GraphQlValue.StringValue(canary))).toString(),
                operation.toString(),
                key.toString(),
                error.toString(),
                error.path.single().toString(),
                exception.toString(),
                request.toString(),
            )

        diagnostics.forEach { diagnostic ->
            assertFalse(diagnostic.contains(canary), "diagnostic leaked canary")
        }
    }

    @Test
    fun variables_equalityRemainsStructural() {
        val ab =
            graphQlVariables {
                put("a", 1)
                put("b", "x")
            }
        val ba =
            graphQlVariables {
                put("b", "x")
                put("a", 1)
            }

        assertEquals(ab, ba)
        assertEquals(ab.hashCode(), ba.hashCode())
        assertNotEquals(ab, graphQlVariables { put("a", 1) })
    }

    @Test
    fun variableKindsAndListOrderRemainDistinct() {
        assertNotEquals<GraphQlValue>(GraphQlValue.IntValue(1), GraphQlValue.FloatValue(1.0))
        assertEquals<GraphQlValue>(GraphQlValue.IntValue(1), GraphQlValue.IntValue(1))
        assertNotEquals(
            operation("query Search { search }", identity())
                .key(
                    graphQlVariables {
                        putList("ids") {
                            add(1)
                            add(2)
                        }
                    },
                ),
            operation("query Search { search }", identity())
                .key(
                    graphQlVariables {
                        putList("ids") {
                            add(2)
                            add(1)
                        }
                    },
                ),
        )
    }
}

private fun operation(
    document: String,
    identity: GraphQlCacheIdentity,
): GraphQlOperation =
    GraphQlOperation(
        document = document,
        name = "Search",
        cacheIdentity = identity,
    )

private fun identity(
    partition: String = "tenant-opaque-a",
    cacheContractVersion: String = "decoder-1|policy-fail",
    digestKeyId: String = "key-1",
): GraphQlCacheIdentity =
    GraphQlCacheIdentity(
        partition = partition,
        cacheContractVersion = cacheContractVersion,
        digestKeyId = digestKeyId,
        keyedDigest = TestKeyedDigest,
    )

private object TestKeyedDigest : GraphQlKeyedDigest {
    override fun digest(input: ByteArray): ByteArray {
        val result = ByteArray(32) { index -> (index * 17 + 11).toByte() }
        input.forEachIndexed { index, byte ->
            val slot = index % result.size
            result[slot] =
                (
                    result[slot].toInt() xor
                        (byte.toInt() and 0xff) xor
                        (index * 31)
                ).toByte()
        }
        return result
    }
}

private class RecordingDigest : GraphQlKeyedDigest {
    val inputs = mutableListOf<ByteArray>()

    override fun digest(input: ByteArray): ByteArray {
        inputs += input.copyOf()
        return ByteArray(32) { index -> index.toByte() }
    }
}

private fun ByteArray.toHex(): String =
    buildString(size * 2) {
        for (byte in this@toHex) {
            append(HEX[(byte.toInt() ushr 4) and 0x0f])
            append(HEX[byte.toInt() and 0x0f])
        }
    }

private fun GraphQlOperationKey.persistenceId(): String = "${namespace.value}/${canonicalId()}"

private const val HEX = "0123456789abcdef"
