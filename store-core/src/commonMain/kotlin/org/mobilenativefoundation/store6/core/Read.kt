package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.flow.Flow

interface Read {
    interface Stream<Key : Any, Output : Any> {
        /**
         * Return a flow for the given key
         * @param request - see [StoreReadRequest] for configurations
         */
        fun stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<Output>>
    }

    interface StreamWithConflictResolution<Key : Any, Output : Any> {
        fun <Response : Any> stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<Output>>
    }
}
