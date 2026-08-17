package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * One GraphQL operation: an executable document and the name of the operation to run.
 *
 * The document is opaque to Store — it is carried to the [GraphQlExecutor] unparsed, so any
 * source of documents (hand-written strings, generated constants, persisted-query ids embedded
 * by the executor) works. [cacheIdentity] binds the document and decoded-value contract to every
 * key this operation creates. [graphQlFetcher] rejects keys created by another operation or
 * identity contract.
 *
 * @throws IllegalArgumentException if [document] or [name] is blank
 */
@ExperimentalStoreApi
public class GraphQlOperation(
    /** The executable GraphQL document, passed to the executor unparsed. */
    public val document: String,
    /** The name of the operation to execute from [document]. */
    public val name: String,
    private val cacheIdentity: GraphQlCacheIdentity,
) {
    init {
        require(document.isNotBlank()) { "GraphQlOperation requires a non-blank document." }
        require(name.isNotBlank()) {
            "GraphQlOperation requires a non-blank name; use the operation's declared name so " +
                "keys and fetchers can be matched."
        }
    }

    /**
     * Returns the [GraphQlOperationKey] for executing this operation with [variables].
     *
     * @param variables the execution's variables; defaults to none
     * @return an opaque key bound to the exact document and cache identity contract
     */
    public fun key(variables: GraphQlVariables = GraphQlVariables.Empty): GraphQlOperationKey =
        GraphQlOperationKey(
            operationName = name,
            variables = variables,
            namespace = cacheIdentity.namespace,
            canonicalId = cacheIdentity.canonicalId(name, document, variables),
        )

    override fun toString(): String = "GraphQlOperation(<redacted>)"
}
