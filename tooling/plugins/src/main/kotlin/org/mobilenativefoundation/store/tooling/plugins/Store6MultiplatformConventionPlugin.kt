@file:Suppress("UnstableApiUsage")

package org.mobilenativefoundation.store.tooling.plugins

import com.android.build.api.dsl.LibraryExtension
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import kotlinx.validation.ApiValidationExtension
import kotlinx.validation.ExperimentalBCVApi
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithSimulatorTests

/**
 * Configures Store6 library modules with the supported multiplatform targets,
 * Android defaults, API validation, and Maven publication conventions.
 * Optional packaging, documentation, serialization, and concurrency plugins
 * remain opt-in at the module level.
 */
class Store6MultiplatformConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        with(pluginManager) {
            apply("org.jetbrains.kotlin.multiplatform")
            apply("com.android.library")
            apply("com.vanniktech.maven.publish")
            apply("org.jetbrains.kotlinx.binary-compatibility-validator")
        }

        extensions.configure<ApiValidationExtension> {
            @OptIn(ExperimentalBCVApi::class)
            klib {
                enabled = true
            }
        }

        tasks.configureEach {
            if (name.endsWith("ApiCheck")) {
                mustRunAfter("${name.removeSuffix("Check")}Dump")
            }
        }

        extensions.configure<KotlinMultiplatformExtension> {
            explicitApi()
            applyDefaultHierarchyTemplate()

            androidTarget()
            jvm()

            iosX64()
            iosArm64()
            iosSimulatorArm64()
            macosArm64()
            watchosArm64()
            tvosArm64()

            linuxX64()
            mingwX64()

            js {
                nodejs()
            }

            @OptIn(ExperimentalWasmDsl::class)
            wasmJs {
                nodejs()
            }

            jvmToolchain(11)

            providers.gradleProperty("store6.iosSimulatorDevice").orNull?.let { device ->
                targets.withType<KotlinNativeTargetWithSimulatorTests>().configureEach {
                    testRuns.configureEach {
                        deviceId = device
                    }
                }
            }

            sourceSets.getByName("commonTest") {
                dependencies {
                    implementation(kotlin("test"))
                }
            }

            sourceSets.getByName("jvmTest") {
                dependencies {
                    implementation(kotlin("test-junit"))
                }
            }
        }

        extensions.configure<LibraryExtension> {
            sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
            compileSdk = 34

            defaultConfig {
                minSdk = 24
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
        }

        val signingKeyPresent = providers.gradleProperty("signingInMemoryKey").isPresent
        extensions.configure<MavenPublishBaseExtension> {
            configure(
                KotlinMultiplatform(
                    javadocJar = JavadocJar.Empty(),
                    sourcesJar = true,
                    androidVariantsToPublish = listOf("release"),
                ),
            )
            publishToMavenCentral(automaticRelease = true)

            if (signingKeyPresent) {
                signAllPublications()
            }
        }
    }
}
