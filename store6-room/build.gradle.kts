plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform.subset")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    androidTarget()
    jvm()

    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    watchosArm64()
    tvosArm64()

    linuxX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.store6Core)
                api(libs.room.runtime)
            }
        }
        val commonTest by getting
        val hostTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(projects.store6Testing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.androidx.sqlite.bundled)
            }
        }
        val jvmTest by getting {
            dependsOn(hostTest)
        }
        val nativeTest by getting {
            dependsOn(hostTest)
        }
        listOf(
            "jvmTest",
            "iosX64Test",
            "iosArm64Test",
            "iosSimulatorArm64Test",
            "macosArm64Test",
            "watchosArm64Test",
            "tvosArm64Test",
            "linuxX64Test",
        ).forEach { sourceSetName ->
            getByName(sourceSetName)
                .languageSettings
                .optIn("org.mobilenativefoundation.store6.core.ExperimentalStoreApi")
        }
    }
}

dependencies {
    listOf(
        "kspJvmTest",
        "kspIosX64Test",
        "kspIosArm64Test",
        "kspIosSimulatorArm64Test",
        "kspMacosArm64Test",
        "kspWatchosArm64Test",
        "kspTvosArm64Test",
        "kspLinuxX64Test",
    ).forEach { configuration ->
        add(configuration, libs.room.compiler)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "org.mobilenativefoundation.store6.room"
}
