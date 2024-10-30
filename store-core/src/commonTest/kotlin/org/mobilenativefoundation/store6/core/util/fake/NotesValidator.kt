package org.mobilenativefoundation.store6.core.util.fake

import org.mobilenativefoundation.store6.core.Validator
import org.mobilenativefoundation.store6.core.impl.extensions.now
import org.mobilenativefoundation.store6.core.util.model.OutputNote

internal class NotesValidator(private val expiration: Long = now()) : Validator<OutputNote> {
    override suspend fun isValid(item: OutputNote): Boolean =
        when {
            item.ttl == 0L -> true
            else -> item.ttl > expiration
        }
}
