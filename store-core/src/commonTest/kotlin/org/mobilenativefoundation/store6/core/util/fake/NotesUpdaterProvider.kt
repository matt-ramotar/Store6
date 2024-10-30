package org.mobilenativefoundation.store6.core.util.fake

import org.mobilenativefoundation.store6.core.Updater
import org.mobilenativefoundation.store6.core.UpdaterResult
import org.mobilenativefoundation.store6.core.util.model.InputNote
import org.mobilenativefoundation.store6.core.util.model.NotesWriteResponse
import org.mobilenativefoundation.store6.core.util.model.OutputNote

internal class NotesUpdaterProvider(private val api: NotesApi) {
    fun provide(): Updater<NotesKey, OutputNote, NotesWriteResponse> =
        Updater.by(
            post = { key, input ->
                val response = api.post(key, InputNote(input.data, input.ttl ?: 0))
                if (response.ok) {
                    UpdaterResult.Success.Typed(response)
                } else {
                    UpdaterResult.Error.Message("Failed to sync")
                }
            },
        )
}
