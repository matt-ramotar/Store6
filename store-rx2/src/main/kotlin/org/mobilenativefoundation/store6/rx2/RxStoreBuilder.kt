package org.mobilenativefoundation.store6.rx2

import io.reactivex.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.rx2.asCoroutineDispatcher
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreBuilder

/**
 *  A store multicasts same [Output] value to many consumers (Similar to RxJava.share()), by default
 *  [Store] will open a global scope for management of shared responses, if instead you'd like to control
 *  the scheduler that sharing/multicasting happens in you can pass a @param [scheduler]
 *
 *  Note this does not control what scheduler a response is emitted on but rather what thread/scheduler
 *  to use when managing in flight responses. This is usually used for things like testing where you
 *  may want to confine to a scheduler backed by a single thread executor
 *
 *   @param scheduler - scheduler to use for sharing
 *  if a scheduler is not set Store will use [GlobalScope]
 */
fun <Key : Any, Output : Any> StoreBuilder<Key, Output>.withScheduler(scheduler: Scheduler): StoreBuilder<Key, Output> {
    return scope(CoroutineScope(scheduler.asCoroutineDispatcher()))
}
