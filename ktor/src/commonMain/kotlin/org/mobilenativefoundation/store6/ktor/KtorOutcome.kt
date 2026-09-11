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

    /**
     * The resident value is unchanged; refresh its freshness metadata with [validatorToken].
     *
     * This refreshes freshness without the kit comparing anything itself, so it is accepted only
     * for an exchange the kit sent a validator on (`KtorExchange.conditional == true`). Returning
     * it for an unconditional exchange is rejected with a [KtorFetchException]: no validator was
     * compared, so nothing licenses calling a stale value fresh.
     *
     * @property validatorToken the replacement validator, or null to keep the recorded one
     */
    @ExperimentalStoreApi
    public class NotModified(
        public val validatorToken: String?,
    ) : KtorOutcome
}
