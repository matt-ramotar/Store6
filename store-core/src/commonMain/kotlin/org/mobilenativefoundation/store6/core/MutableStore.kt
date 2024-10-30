package org.mobilenativefoundation.store6.core

@ExperimentalStoreApi
interface MutableStore<Key : Any, Output : Any> :
    Read.StreamWithConflictResolution<Key, Output>,
    Write<Key, Output>,
    Write.Stream<Key, Output>,
    Clear.Key<Key>,
    Clear
