plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // Kit and fake signatures expose core/seam types.
                api(projects.store6Core)
                // The kits' plainly inherited @Test members must resolve in consumer test
                // compilations on every target; kotlin-test is part of this artifact's API.
                api(kotlin("test"))
                // The kits' inherited members are expression-bodied `: TestResult = runTest {…}`,
                // so kotlinx.coroutines.test.TestResult is in the published ABI on all 12 targets
                // (plain inheritance — no expect/actual, no forwarding members on js/wasmJs).
                // This is the exact evidence phase0 item 35 conditioned api scope on (approved).
                api(libs.kotlinx.coroutines.test)
                // The SoT kit BODIES use turbine (app.cash.turbine.test/turbineScope) but turbine
                // never appears in any published signature: implementation scope keeps it off
                // consumer compile classpaths while the POM's runtime scope still delivers it to
                // consumer test runtimes. (Deliberate narrowing vs the landed contract-tests
                // api(libs.turbine) — recorded in the decision doc, flagged for Matt.)
                implementation(libs.turbine)
            }
        }
        val jvmMain by getting {
            dependencies {
                // Mirrors the landed contract-tests module: JUnit-variant resolution for JVM consumers.
                api(kotlin("test-junit"))
            }
        }
        val androidMain by getting {
            dependencies {
                api(kotlin("test-junit"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.turbine)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.testing"
}
