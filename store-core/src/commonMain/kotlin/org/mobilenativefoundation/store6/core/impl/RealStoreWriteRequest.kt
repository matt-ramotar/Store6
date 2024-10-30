package org.mobilenativefoundation.store6.core.impl

import org.mobilenativefoundation.store6.core.StoreWriteRequest

data class RealStoreWriteRequest<Key : Any, Output : Any, Response : Any>(
    override val key: Key,
    override val value: Output,
    override val created: Long,
    override val onCompletions: List<OnStoreWriteCompletion>?,
) : StoreWriteRequest<Key, Output, Response>
