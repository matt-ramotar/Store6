package org.mobilenativefoundation.store6.benchmarks

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry

/**
 * Configured-but-empty sink. Every hook keeps its interface-default no-op body, so the delta
 * between a store built with this sink and one with telemetry unset measures exactly the
 * engine-side telemetry machinery: the non-null branch at each call site plus the fetch-duration
 * mark (KeyEngine.launchFetch) plus virtual dispatch into empty bodies.
 */
@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
internal object NoopTelemetry : StoreTelemetry
