import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("org.jetbrains.kotlin.multiplatform")
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
