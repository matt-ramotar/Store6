package org.mobilenativefoundation.store6.quickstart

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DefaultStoreReconstructionTest {
    @Test
    fun mainPrintsTheDocumentedDeterministicOutput() {
        val previousOut = System.out
        val output = ByteArrayOutputStream()

        try {
            System.setOut(PrintStream(output))
            main()
        } finally {
            System.setOut(previousOut)
        }

        assertEquals(
            "Loading…\nData(name=User 1, origin=FETCHER)\nget: User 2\n",
            output.toString(),
        )
    }

    @Test
    fun defaultStoreDoesNotRetainRowsAfterReconstruction() =
        runTest {
            val key = QuickstartKey("1")
            val first =
                store<QuickstartKey, String> {
                    fetcher { "User ${it.id}" }
                }

            try {
                assertEquals("User 1", first.get(key))
            } finally {
                first.close()
            }

            val reconstructed =
                store<QuickstartKey, String> {
                    fetcher { error("LocalOnly must not fetch") }
                }

            try {
                val failure =
                    assertFailsWith<StoreException> {
                        reconstructed.get(key, Freshness.LocalOnly)
                    }
                assertIs<StoreError.Missing>(failure.error)
            } finally {
                reconstructed.close()
            }
        }

    private class QuickstartKey(
        val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace("quickstart-users")

        override fun canonicalId(): String = id
    }
}
