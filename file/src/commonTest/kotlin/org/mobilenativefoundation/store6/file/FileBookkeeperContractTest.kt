@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import kotlinx.io.files.Path
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.testing.BookkeeperContractKit
import kotlin.test.AfterTest

internal class FileBookkeeperContractTest : BookkeeperContractKit() {
    private val directories = mutableListOf<Path>()

    override fun createBookkeeper(): Bookkeeper {
        val directory = createTempDirectory("store6-file-bookkeeper-kit").also { directories += it }
        return FileBookkeeper(directory)
    }

    @AfterTest
    fun cleanupDirectories() {
        var firstFailure: Throwable? = null
        directories.forEach { directory ->
            try {
                cleanupDirectory(directory)
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        directories.clear()
        firstFailure?.let { throw it }
    }
}
