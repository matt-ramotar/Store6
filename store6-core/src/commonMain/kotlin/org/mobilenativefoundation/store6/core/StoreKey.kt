package org.mobilenativefoundation.store6.core

/**
 * Identifies a value handled by a [Store].
 *
 * A key's [namespace] and [canonicalId] form separate components of its stable identity.
 * Implementations should return the same identity components for the lifetime of the key.
 */
public interface StoreKey {
    /** The logical key space containing this key. */
    public val namespace: StoreNamespace

    /**
     * Returns the stable identifier for this key within [namespace].
     *
     * @return an identifier that is unique within the key's namespace
     */
    public fun canonicalId(): String
}

/**
 * A logical key space used to distinguish otherwise identical canonical identifiers.
 *
 * Instances compare by the exact [value]. Comparison is case-sensitive and does not normalize
 * Unicode. A namespace groups maintenance operations; it is not an authorization boundary.
 */
public class StoreNamespace(
    /** The stable name of this namespace. */
    public val value: String,
) {
    override fun equals(other: Any?): Boolean =
        other is StoreNamespace && value == other.value

    override fun hashCode(): Int = value.hashCode()
}
