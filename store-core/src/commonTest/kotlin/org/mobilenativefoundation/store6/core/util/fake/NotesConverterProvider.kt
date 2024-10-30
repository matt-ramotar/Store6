package org.mobilenativefoundation.store6.core.util.fake

import org.mobilenativefoundation.store6.core.Converter
import org.mobilenativefoundation.store6.core.impl.extensions.inHours
import org.mobilenativefoundation.store6.core.util.model.InputNote
import org.mobilenativefoundation.store6.core.util.model.NetworkNote
import org.mobilenativefoundation.store6.core.util.model.OutputNote

internal class NotesConverterProvider {
    fun provide(): Converter<NetworkNote, InputNote, OutputNote> =
        Converter.Builder<NetworkNote, InputNote, OutputNote>()
            .fromOutputToLocal { value -> InputNote(data = value.data, ttl = value.ttl) }
            .fromNetworkToLocal { value: NetworkNote ->
                InputNote(
                    data = value.data,
                    ttl = value.ttl ?: inHours(12),
                )
            }
            .build()
}
