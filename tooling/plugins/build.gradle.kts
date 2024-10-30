plugins {
    `kotlin-dsl`
}

group = "org.mobilenativefoundation.store6"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11

    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.dokka.gradle.plugin)
    compileOnly(libs.maven.publish.plugin)
    compileOnly(libs.kmmBridge.gradle.plugin)
    compileOnly(libs.kover.plugin)
    compileOnly(libs.atomic.fu.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("kotlinMultiplatformConventionPlugin") {
            id = "org.mobilenativefoundation.store6.multiplatform"
            implementationClass = "org.mobilenativefoundation.store6.tooling.plugins.KotlinMultiplatformConventionPlugin"
        }

        register("androidConventionPlugin") {
            id = "org.mobilenativefoundation.store6.android"
            implementationClass = "org.mobilenativefoundation.store6.tooling.plugins.AndroidConventionPlugin"
        }
    }
}