import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.mobilenativefoundation.store.store6.swift-dump.skie")
    alias(libs.plugins.skie)
}

kotlin {
    explicitApi()

    val xcf = XCFramework("Store6Kotlin")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
        macosArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Store6Kotlin"
            isStatic = true
            export(project(":store6-core"))
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":store6-core"))
            }
        }
        commonTest {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test")
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

store6SwiftDump {
    surfaceName.set("store6-swift")
    frameworkName.set("Store6Kotlin")
}
