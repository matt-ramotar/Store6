package org.mobilenativefoundation.store6.ktor

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/** The mapper's decision. Success is never a mapper output: a body is adopted only via [decode]. */
@ExperimentalStoreApi
public sealed interface KtorOutcome {
    /** Apply the kit's default HTTP-status table. */
    @ExperimentalStoreApi
    public data object Defer : KtorOutcome

    @ExperimentalStoreApi
    public class Fail(
        public val exception: KtorFetchException,
    ) : KtorOutcome

    @ExperimentalStoreApi
    public data object Delete : KtorOutcome

    @ExperimentalStoreApi
    public class NotModified(
        public val validatorToken: String?,
    ) : KtorOutcome
}
