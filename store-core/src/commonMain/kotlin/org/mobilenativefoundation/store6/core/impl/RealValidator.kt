package org.mobilenativefoundation.store6.core.impl

import org.mobilenativefoundation.store6.core.Validator

internal class RealValidator<Output : Any>(
    private val realValidator: suspend (item: Output) -> Boolean,
) : Validator<Output> {
    override suspend fun isValid(item: Output): Boolean = realValidator(item)
}
