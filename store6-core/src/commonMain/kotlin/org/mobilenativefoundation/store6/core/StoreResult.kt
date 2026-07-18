package org.mobilenativefoundation.store6.core

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
    ) : StoreResult<V>

    /** A retrieval failure emitted without terminating the observed stream. */
    public class Error internal constructor(
        /** The structured error describing the failure. */
        public val error: StoreError,
    ) : StoreResult<Nothing>
}
