package org.mobilenativefoundation.store6.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry

/** Placeholder; replaced by the full sink in the next change. */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class OpenTelemetryStoreTelemetry(
    @Suppress("unused") private val openTelemetry: OpenTelemetry,
) : StoreTelemetry
