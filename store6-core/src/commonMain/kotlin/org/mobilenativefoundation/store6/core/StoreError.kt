package org.mobilenativefoundation.store6.core

/** A structured failure produced by a [Store] operation. */
public sealed class StoreError {
    /** Indicates that the configured fetcher failed to produce a value. */
    public class Fetch internal constructor(
        /** A human-readable description of the fetch failure. */
        public val message: String,

        /** The underlying failure, or `null` when no cause is available. */
        public val cause: Throwable?,
    ) : StoreError()
}
