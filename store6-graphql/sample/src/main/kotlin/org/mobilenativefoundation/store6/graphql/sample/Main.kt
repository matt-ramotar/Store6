@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.graphql.sample

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.graphql.GraphQlCacheIdentity
import org.mobilenativefoundation.store6.graphql.GraphQlError
import org.mobilenativefoundation.store6.graphql.GraphQlExecutor
import org.mobilenativefoundation.store6.graphql.GraphQlExecutorResult
import org.mobilenativefoundation.store6.graphql.GraphQlKeyedDigest
import org.mobilenativefoundation.store6.graphql.GraphQlOperation
import org.mobilenativefoundation.store6.graphql.GraphQlOperationException
import org.mobilenativefoundation.store6.graphql.GraphQlOperationKey
import org.mobilenativefoundation.store6.graphql.GraphQlPartialDataPolicy
import org.mobilenativefoundation.store6.graphql.GraphQlRequest
import org.mobilenativefoundation.store6.graphql.GraphQlVariables
import org.mobilenativefoundation.store6.graphql.graphQlFetcher
import org.mobilenativefoundation.store6.graphql.graphQlVariables

public fun main(): Unit =
    runBlocking {
        withTimeout(SAMPLE_TIMEOUT_MILLIS) {
            runSample()
        }
    }

private suspend fun runSample() {
    val keyedDigest = JvmHmacSha256(ByteArray(32).also(SecureRandom()::nextBytes))
    val operation =
        GraphQlOperation(
            document = "query GetUser(\$id: ID!, \$locale: String) { user(id: \$id) { id name } }",
            name = "GetUser",
            cacheIdentity = cacheIdentity("user-decoder-1|policy-fail", keyedDigest),
        )
    val ada = User(id = "1", name = "Ada")

    // Scene 1: variable insertion order does not split cache identity.
    val byIdThenLocale =
        graphQlVariables {
            put("id", "1")
            put("locale", "en")
        }
    val byLocaleThenId =
        graphQlVariables {
            put("locale", "en")
            put("id", "1")
        }
    val canonicalId = operation.key(byIdThenLocale).canonicalId()
    check(operation.key(byIdThenLocale) == operation.key(byLocaleThenId))
    check(canonicalId.matches(Regex("s6gql1:[0-9a-f]{64}")))
    println("Scene 1: both variable orders share one opaque identity; canonicalId=$canonicalId")

    // Scene 2: the store is a document cache; a resident response is served without re-execution.
    val cacheExecutor = ScriptedExecutor()
    cacheExecutor.enqueue(byIdThenLocale, GraphQlExecutorResult.Data(data = ada))
    val cacheStore = userStore(operation, cacheExecutor)
    try {
        check(cacheStore.get(operation.key(byIdThenLocale)) == ada)
        check(cacheStore.get(operation.key(byLocaleThenId)) == ada)
        check(cacheExecutor.callCount(byIdThenLocale) == 1)
        println("Scene 2: two reads across both variable orders; executions=1")
    } finally {
        cacheStore.close()
    }

    // Scene 3: a partial response fails by default and is adopted only on opt-in.
    val partialVariables = graphQlVariables { put("id", "2") }
    val partialResponse = {
        GraphQlExecutorResult.Data(
            data = User(id = "2", name = "Grace"),
            errors = listOf(GraphQlError(message = "avatar resolution failed")),
        )
    }
    val failExecutor = ScriptedExecutor()
    failExecutor.enqueue(partialVariables, partialResponse())
    val failStore = userStore(operation, failExecutor)
    val adoptOperation =
        GraphQlOperation(
            document = operation.document,
            name = operation.name,
            cacheIdentity = cacheIdentity("user-decoder-1|policy-adopt", keyedDigest),
        )
    val adoptExecutor = ScriptedExecutor()
    adoptExecutor.enqueue(partialVariables, partialResponse())
    val adoptStore =
        userStore(adoptOperation, adoptExecutor, GraphQlPartialDataPolicy.AdoptPartialData)
    try {
        val failure =
            runCatching {
                failStore.get(operation.key(partialVariables), Freshness.MustBeFresh)
            }.exceptionOrNull()
        val storeFailure = checkNotNull(failure as? StoreException) { "expected StoreException, was $failure" }
        val fetchError = checkNotNull(storeFailure.error as? StoreError.Fetch) { "expected StoreError.Fetch" }
        val operationException =
            checkNotNull(fetchError.cause as? GraphQlOperationException) {
                "expected GraphQlOperationException, was ${fetchError.cause}"
            }
        check(operationException.errorCount == 1)

        val adopted = adoptStore.get(adoptOperation.key(partialVariables))
        check(adopted == User(id = "2", name = "Grace"))
        println(
            "Scene 3: FailOnErrors surfaced ${operationException.errorCount} redacted error; " +
                "AdoptPartialData cached the decoded value",
        )
    } finally {
        failStore.close()
        adoptStore.close()
    }

    // Scene 4: a conditional refetch answered NotModified revalidates the cached response.
    val revalidationVariables = graphQlVariables { put("id", "1") }
    val revalidationExecutor = ScriptedExecutor()
    revalidationExecutor.enqueue(
        revalidationVariables,
        GraphQlExecutorResult.Data(data = ada, etag = "e1"),
        // A cold-baseline 304 may legally self-heal with one replanned conditional fetch.
        GraphQlExecutorResult.NotModified("e1"),
        GraphQlExecutorResult.NotModified("e1"),
    )
    val revalidationStore = userStore(operation, revalidationExecutor)
    try {
        val key = operation.key(revalidationVariables)
        check(revalidationStore.get(key) == ada)
        revalidationStore.invalidate(key)
        revalidationStore.stream(key).first { result -> result is StoreResult.Revalidated }
        check(revalidationStore.get(key) == ada)
        val etags = revalidationExecutor.etags(revalidationVariables)
        check(etags.size in 2..3) { "expected 2..3 executions" }
        check(etags[0] == null)
        check(etags.drop(1).all { etag -> etag == "e1" })
        println("Scene 4: NotModified revalidated the cached response; ETags remain redacted")
    } finally {
        revalidationStore.close()
    }
}

private data class User(
    val id: String,
    val name: String,
)

private fun cacheIdentity(
    cacheContractVersion: String,
    keyedDigest: GraphQlKeyedDigest,
): GraphQlCacheIdentity =
    GraphQlCacheIdentity(
        partition = "sample-partition",
        cacheContractVersion = cacheContractVersion,
        digestKeyId = "sample-key-1",
        keyedDigest = keyedDigest,
    )

private class JvmHmacSha256(key: ByteArray) : GraphQlKeyedDigest {
    private val key = key.copyOf()

    override fun digest(input: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(input)
}

private fun userStore(
    operation: GraphQlOperation,
    executor: GraphQlExecutor<User>,
    partialDataPolicy: GraphQlPartialDataPolicy = GraphQlPartialDataPolicy.FailOnErrors,
): Store<GraphQlOperationKey, User> =
    store {
        graphQlFetcher(operation, partialDataPolicy, executor)
    }

private class ScriptedExecutor : GraphQlExecutor<User> {
    private val scripts = ConcurrentHashMap<GraphQlVariables, ArrayDeque<GraphQlExecutorResult<User>>>()
    private val recordedEtags = ConcurrentHashMap<GraphQlVariables, MutableList<String?>>()

    fun enqueue(
        variables: GraphQlVariables,
        vararg results: GraphQlExecutorResult<User>,
    ) {
        scripts.computeIfAbsent(variables) { ArrayDeque() }.addAll(results)
    }

    fun callCount(variables: GraphQlVariables): Int = etags(variables).size

    fun etags(variables: GraphQlVariables): List<String?> =
        recordedEtags[variables].orEmpty().toList()

    override suspend fun execute(request: GraphQlRequest): GraphQlExecutorResult<User> {
        recordedEtags.computeIfAbsent(request.variables) {
            java.util.Collections.synchronizedList(mutableListOf())
        }.add(request.etag)
        return checkNotNull(scripts[request.variables]?.removeFirstOrNull()) {
            "No scripted result for request."
        }
    }
}

private const val SAMPLE_TIMEOUT_MILLIS = 20_000L
