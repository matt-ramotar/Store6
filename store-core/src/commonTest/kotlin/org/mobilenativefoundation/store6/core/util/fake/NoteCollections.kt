package org.mobilenativefoundation.store6.core.util.fake

import org.mobilenativefoundation.store6.core.util.model.NoteData

internal object NoteCollections {
    object Keys {
        const val OneAndTwo = "ONE_AND_TWO"
    }

    val OneAndTwo = NoteData.Collection(listOf(Notes.One, Notes.Two))
}
