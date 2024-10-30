import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.mobilenativefoundation.store6.multiplatform")
}

kotlin {

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.atomic.fu)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.cache"
}
