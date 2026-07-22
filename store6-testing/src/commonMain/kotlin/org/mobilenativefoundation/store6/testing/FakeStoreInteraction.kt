package org.mobilenativefoundation.store6.testing

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

@ExperimentalStoreApi
public sealed class FakeStoreInteraction {
    public class Stream(
        public val key: StoreKey,
        public val freshness: Freshness,
    ) : FakeStoreInteraction()

    public class Get(
        public val key: StoreKey,
        public val freshness: Freshness,
    ) : FakeStoreInteraction()

    public class Invalidate(public val key: StoreKey) : FakeStoreInteraction()

    public class InvalidateNamespace(
        public val namespace: StoreNamespace,
    ) : FakeStoreInteraction()

    public data object InvalidateAll : FakeStoreInteraction()

    public class Clear(public val key: StoreKey) : FakeStoreInteraction()

    public class ClearNamespace(
        public val namespace: StoreNamespace,
    ) : FakeStoreInteraction()

    public data object ClearAll : FakeStoreInteraction()

    public data object Close : FakeStoreInteraction()
}
