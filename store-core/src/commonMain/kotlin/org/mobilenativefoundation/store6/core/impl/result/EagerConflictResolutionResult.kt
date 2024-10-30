package org.mobilenativefoundation.store6.core.impl.result

import org.mobilenativefoundation.store6.core.UpdaterResult

sealed class EagerConflictResolutionResult<out Response : Any> {
    sealed class Success<Response : Any> : EagerConflictResolutionResult<Response>() {
        object NoConflicts : Success<Nothing>()

        data class ConflictsResolved<Response : Any>(val value: UpdaterResult.Success) : Success<Response>()
    }

    sealed class Error : EagerConflictResolutionResult<Nothing>() {
        data class Message(val message: String) : Error()

        data class Exception(val error: Throwable) : Error()
    }
}
