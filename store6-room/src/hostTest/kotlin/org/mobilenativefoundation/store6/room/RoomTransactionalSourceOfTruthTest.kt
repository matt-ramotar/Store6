@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.room

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.testing.TestStoreMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

internal class RoomTransactionalSourceOfTruthTest {
    private val keyA = RoomKitKey(StoreNamespace("users"), "a")

    @Test
    fun withTransaction_writeThenThrow_rollsBackRow(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val sourceOfTruth = sourceOfTruth(database)
            val dao = database.kitRowDao()

            assertFailsWith<IllegalStateException> {
                sourceOfTruth.withTransaction {
                    sourceOfTruth.write(keyA, "v1")
                    error("boom")
                }
            }

            assertNull(dao.row(keyA.namespace.value, keyA.canonicalId()).first())
            assertNull(sourceOfTruth.reader(keyA).first())
        } finally {
            database.close()
        }
    }

    @Test
    fun withTransaction_valueAndMeta_commitAtomically(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val sourceOfTruth = sourceOfTruth(database)
            val bookkeeper = RoomBookkeeper(database, database.store6BookkeeperDao())

            sourceOfTruth.withTransaction {
                sourceOfTruth.write(keyA, "v1")
                bookkeeper.recordSuccess(
                    keyA,
                    TestStoreMeta(writtenAtEpochMillis = 1L, etag = "e1"),
                )
            }

            assertEquals(
                "v1",
                database
                    .kitRowDao()
                    .row(keyA.namespace.value, keyA.canonicalId())
                    .first()
                    ?.payload,
            )
            assertEquals("e1", bookkeeper.status(keyA)?.meta?.etag)

            assertFailsWith<IllegalStateException> {
                sourceOfTruth.withTransaction {
                    sourceOfTruth.write(keyA, "v2")
                    bookkeeper.recordSuccess(
                        keyA,
                        TestStoreMeta(writtenAtEpochMillis = 2L, etag = "e2"),
                    )
                    error("boom after value and metadata")
                }
            }

            assertEquals(
                "v1",
                database
                    .kitRowDao()
                    .row(keyA.namespace.value, keyA.canonicalId())
                    .first()
                    ?.payload,
            )
            assertEquals("e1", bookkeeper.status(keyA)?.meta?.etag)
        } finally {
            database.close()
        }
    }

    @Test
    fun withTransaction_throwBetweenValueAndMeta_rollsBackBoth(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val sourceOfTruth = sourceOfTruth(database)
            val bookkeeper = RoomBookkeeper(database, database.store6BookkeeperDao())

            assertFailsWith<IllegalStateException> {
                sourceOfTruth.withTransaction {
                    sourceOfTruth.write(keyA, "v1")
                    error("boom before metadata")
                }
            }

            assertNull(
                database
                    .kitRowDao()
                    .row(keyA.namespace.value, keyA.canonicalId())
                    .first(),
            )
            assertNull(bookkeeper.status(keyA))
        } finally {
            database.close()
        }
    }

    @Test
    fun withTransaction_returnsBlockResult(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val sourceOfTruth = sourceOfTruth(database)

            val result = sourceOfTruth.withTransaction { "block-result" }

            assertEquals("block-result", result)
        } finally {
            database.close()
        }
    }
}

private fun sourceOfTruth(
    database: Store6RoomTestDatabase,
): RoomSourceOfTruth<RoomKitKey, String> {
    val dao = database.kitRowDao()
    return RoomSourceOfTruth(
        database = database,
        rowReader = { key ->
            dao.row(key.namespace.value, key.canonicalId()).map { it?.payload }
        },
        rowWriter = { key, value ->
            dao.upsert(KitRowEntity(key.namespace.value, key.canonicalId(), value))
        },
        rowDeleter = { key ->
            dao.delete(key.namespace.value, key.canonicalId())
        },
        namespaceDeleter = { namespace ->
            dao.deleteNamespace(namespace.value)
        },
        allDeleter = {
            dao.deleteAll()
        },
    )
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
