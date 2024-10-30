package org.mobilenativefoundation.store6.core.impl.definition

import org.mobilenativefoundation.store6.core.StoreWriteRequest

typealias WriteRequestQueue<Key, Output, Response> = ArrayDeque<StoreWriteRequest<Key, Output, Response>>
