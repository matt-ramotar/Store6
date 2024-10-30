plugins {
    id("org.mobilenativefoundation.store6.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.datetime)
                api(libs.kotlinx.atomic.fu)
                implementation(libs.touchlab.kermit)
                implementation(projects.storeMulticast)
                implementation(projects.storeCache)
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
    namespace = "org.mobilenativefoundation.store6.core"
}
