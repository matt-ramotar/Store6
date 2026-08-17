package org.mobilenativefoundation.store6.opentelemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class InstrumentationScopeVersionTest {
    @Test
    fun scopeVersionConstantMatchesTheModuleVersion() {
        // Forwarded by the module build file from the module's VERSION_NAME project property;
        // a missing property fails the test rather than silently passing. An actual value of
        // 5.1.0-SNAPSHOT means the build file read the root's property instead of the
        // module's (see the failure playbook).
        val versionName = System.getProperty("store6.opentelemetry.versionName")
        assertNotNull(versionName, "store6.opentelemetry.versionName system property is not set")
        assertEquals(versionName, INSTRUMENTATION_SCOPE_VERSION)
    }
}
