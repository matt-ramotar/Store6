package org.mobilenativefoundation.store6.core.util.fake

sealed class NotesKey {
    data class Single(val id: String) : NotesKey()

    data class Collection(val id: String) : NotesKey()
}
