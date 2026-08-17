package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

/**
 * An opaque [StoreKey] identifying one GraphQL operation execution.
 *
 * Create keys through [GraphQlOperation.key]. The canonical id is an application-keyed digest
 * that covers the identity format, exact document, operation name, variables, tenant partition,
 * digest key id, and cache contract version. Raw variables are never rendered into the id.
 */
@ExperimentalStoreApi
public class GraphQlOperationKey internal constructor(
    /** The GraphQL operation name this key belongs to. */
    public val operationName: String,
    /** The variables passed to the executor. */
    public val variables: GraphQlVariables,
    /** The opaque tenant and identity-format key space. */
    override val namespace: StoreNamespace,
    private val canonicalId: String,
) : StoreKey {
    /** Returns the version-prefixed keyed digest for this execution. */
    override fun canonicalId(): String = canonicalId

    override fun equals(other: Any?): Boolean =
        other is GraphQlOperationKey &&
            other.canonicalId == canonicalId &&
            other.namespace.value == namespace.value

    override fun hashCode(): Int = 31 * namespace.value.hashCode() + canonicalId.hashCode()

    override fun toString(): String = "GraphQlOperationKey(${namespace.value}/$canonicalId)"
}
