plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.store6Core)
                api(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines.extensions)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(projects.store6Testing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
        val commonSqlTest by creating {
            dependsOn(commonTest)
            // T6 adds the borrowed core conformance source directory together with its helpers.
        }
        val jvmTest by getting {
            dependsOn(commonSqlTest)
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        val androidUnitTest by getting {
            dependsOn(commonSqlTest)
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        val nativeSqlTest by creating {
            dependsOn(commonSqlTest)
            dependencies {
                implementation(libs.sqldelight.native.driver)
            }
        }
        listOf(
            "iosX64Test",
            "iosArm64Test",
            "iosSimulatorArm64Test",
            "macosArm64Test",
            "linuxX64Test",
            "mingwX64Test",
        ).forEach { getByName(it).dependsOn(nativeSqlTest) }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.sqldelight"
}
