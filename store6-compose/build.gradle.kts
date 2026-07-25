plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
    alias(libs.plugins.kotlin.compose.compiler)
}

composeCompiler {
    // Dogfoods the shipped consumer snippet: core types are stable inside this module's own
    // composables. The conf file lands in this same task (T1) so this wiring never dangles.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("stability/store6-stability.conf"))
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.store6Core)
                // PIN (preflight): CMP 1.8.2 is the pinned Compose Multiplatform runtime line.
                api(libs.jetbrains.compose.runtime)
            }
        }
        // CMP lifecycle tier: exactly the targets lifecycle-runtime-compose 2.9.1 publishes.
        val lifecycleMain by creating {
            dependsOn(commonMain)
            dependencies { api(libs.jetbrains.lifecycle.runtime.compose) }
        }
        val androidMain by getting { dependsOn(lifecycleMain) }
        val jvmMain by getting { dependsOn(lifecycleMain) }
        val iosMain by getting { dependsOn(lifecycleMain) }
        val macosMain by getting { dependsOn(lifecycleMain) }
        val jsMain by getting { dependsOn(lifecycleMain) }
        val wasmJsMain by getting { dependsOn(lifecycleMain) }
        val commonTest by getting {
            dependencies {
                implementation(projects.store6Testing)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.compose"
}
