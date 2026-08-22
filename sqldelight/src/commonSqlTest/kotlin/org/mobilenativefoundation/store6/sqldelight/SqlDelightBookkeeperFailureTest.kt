@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.testing.TestStoreMeta
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Fault injection at the driver boundary: runtime storage failures keep the soft-fail contract
 * (status returns null, operational writes absorb), while kotlin.Error propagates unmasked.
 */
internal class SqlDelightBookkeeperFailureTest {
    @Test
    fun status_runtimeStorageFailure_returnsNull() = runTest {
        withFaultyBookkeeper { bookkeeper, faultDriver ->
            faultDriver.fault = IllegalStateException("sqlite busy")

            assertNull(bookkeeper.status(KEY))
        }
    }

    @Test
    fun status_virtualMachineError_propagates() = runTest {
        withFaultyBookkeeper { bookkeeper, faultDriver ->
            faultDriver.fault = SyntheticVmError()

            assertFailsWith<SyntheticVmError> { bookkeeper.status(KEY) }
        }
    }

    @Test
    fun recordFailure_runtimeStorageFailure_absorbedAndGateRecovers() = runTest {
        withFaultyBookkeeper { bookkeeper, faultDriver ->
            faultDriver.fault = IllegalStateException("disk io")
            bookkeeper.recordFailure(KEY, 10L)

            faultDriver.fault = null
            bookkeeper.recordSuccess(KEY, TestStoreMeta(1L, "e1"))
            assertNotNull(bookkeeper.status(KEY))
        }
    }

    @Test
    fun recordSuccess_virtualMachineError_propagates() = runTest {
        withFaultyBookkeeper { bookkeeper, faultDriver ->
            faultDriver.fault = SyntheticVmError()

            assertFailsWith<SyntheticVmError> {
                bookkeeper.recordSuccess(KEY, TestStoreMeta(1L, "e1"))
            }
        }
    }

    private suspend fun <R> withFaultyBookkeeper(
        block: suspend (SqlDelightBookkeeper, FaultDriver) -> R,
    ): R {
        val harness = freshHarness()
        val faultDriver = FaultDriver(harness.driver)
        val bookkeeper = SqlDelightBookkeeper(faultDriver, harness.transacter)
        return try {
            block(bookkeeper, faultDriver)
        } finally {
            harness.driver.close()
        }
    }

    private companion object {
        val KEY = SqlTestKey(ns = "failure", id = "key")
    }
}

/** Delegates every driver member until armed; armed faults fire on query/execute calls only. */
private class FaultDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    var fault: Throwable? = null

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        throwIfArmed()
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        throwIfArmed()
        return delegate.execute(identifier, sql, parameters, binders)
    }

    private fun throwIfArmed() {
        fault?.let { throw it }
    }
}

private class SyntheticVmError : Error()
