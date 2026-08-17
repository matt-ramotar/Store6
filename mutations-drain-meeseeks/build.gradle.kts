import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform.subset")
    // Versioned alias(libs.plugins.kotlin.serialization) fails: root buildscript already
    // classpaths libs.kotlin.serialization.plugin, so Gradle cannot check compatibility.
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget()
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    js {
        nodejs()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.mutationsDrain)
                api(libs.meeseeks.runtime)
                implementation(libs.kotlinx.serialization.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.mutations.drain.meeseeks"
}

// Meeseeks 1.1.0 publishes Java 17 bytecode. Compile targets stay on the repo-wide
// toolchain; the test JVMs must be 17+ to load Meeseeks classes at runtime.
val javaToolchains = extensions.getByType<JavaToolchainService>()
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        },
    )
}
