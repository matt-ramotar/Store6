package org.mobilenativefoundation.store6.core

import kotlin.time.Duration

/**
 * A state or value reported while observing a [Store].
 *
 * @param V the type of data carried by [Data]
 */
public sealed interface StoreResult<out V> {
    /** Indicates that the store is retrieving a value and has no resident value to emit. */
    public class Loading internal constructor() : StoreResult<Nothing>

    /**
     * A value available from the store.
     *
     * @param V the value type
     */
    public class Data<V> internal constructor(
        /** The value produced by the store. */
        public val value: V,

        /** The source from which [value] was obtained. */
        public val origin: Origin,

        /** Elapsed time since [value] was committed to the store. */
        public val age: Duration,

        /** Whether [value] has been invalidated and a fresher value should be expected. */
        public val isStale: Boolean,

        /** Whether a fetch was in flight for this key when this result was emitted. */
        public val refreshing: Boolean,
    ) : StoreResult<V>

    /**
     * Confirmation that the current value is still fresh without a new value being produced —
     * the not-modified signal of a conditional fetch.
     *
     * Present behavior: never emitted; conditional fetches arrive with the freshness engine.
     * The type is part of the frozen result vocabulary so its arrival is not a breaking change.
     */
    public class Revalidated internal constructor(
        /** Elapsed time since the value was last committed, measured at revalidation. */
        public val age: Duration,
    ) : StoreResult<Nothing>

    /** A retrieval failure emitted without terminating the observed stream. */
    public class Error internal constructor(
        /** The structured error describing the failure. */
        public val error: StoreError,

        /**
         * Whether a stale value was served alongside this failure under a stale-tolerant policy.
         *
         * Present behavior: always `false`; stale-if-error serving arrives with the freshness
         * engine.
         */
        public val servedStale: Boolean,
    ) : StoreResult<Nothing>
}
