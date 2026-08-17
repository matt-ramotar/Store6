package org.mobilenativefoundation.store6.graphql

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * One entry of a GraphQL response's `errors` array.
 *
 * @property message the server-provided error message
 * @property path the response field path the error applies to; empty for request-level errors
 */
@ExperimentalStoreApi
public class GraphQlError(
    public val message: String,
    public val path: List<PathSegment> = emptyList(),
) {
    /** One step of a response field path. */
    public sealed interface PathSegment {
        /** A field name step. */
        public class Field(
            /** The field name. */
            public val name: String,
        ) : PathSegment {
            override fun equals(other: Any?): Boolean = other is Field && other.name == name

            override fun hashCode(): Int = name.hashCode()

            override fun toString(): String = "Field(<redacted>)"
        }

        /** A list index step. */
        public class Index(
            /** The zero-based list index. */
            public val index: Int,
        ) : PathSegment {
            override fun equals(other: Any?): Boolean = other is Index && other.index == index

            override fun hashCode(): Int = index.hashCode()

            override fun toString(): String = "Index(<redacted>)"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is GraphQlError && other.message == message && other.path == path

    override fun hashCode(): Int = 31 * message.hashCode() + path.hashCode()

    override fun toString(): String = "GraphQlError(pathSegments=${path.size}, message=<redacted>)"
}

/**
 * The failure carried as [org.mobilenativefoundation.store6.core.StoreError.Fetch] cause when a
 * GraphQL response reports errors the fetcher does not adopt.
 *
 * Raw server error messages and paths are deliberately not retained because exceptions are
 * commonly logged. Inspect and sanitize response errors inside the application executor when
 * diagnostics are required.
 *
 * @property errorCount the number of errors reported by the response
 */
@ExperimentalStoreApi
public class GraphQlOperationException internal constructor(
    public val errorCount: Int,
) : Exception("GraphQL operation failed with $errorCount response error(s); details are redacted.")
