plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform.subset")
}

kotlin {
    // JVM-family subset: this module builds on opentelemetry-java, which publishes JVM
    // bytecode only. androidTarget() is mandatory under the subset plugin. The remaining
    // Store6 targets are additive later via a multiplatform OpenTelemetry API once one is
    // stable; see README "Targets".
    androidTarget()
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core)
            }
        }
        // Kotlin's hierarchy template has no JVM+Android intermediate; this created source
        // set is compiled per target and gets no metadata compilation, which is what lets it
        // hold a Java-only dependency.
        val jvmAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                api(libs.opentelemetry.api)
            }
        }
        val jvmMain by getting {
            dependsOn(jvmAndroidMain)
        }
        val androidMain by getting {
            dependsOn(jvmAndroidMain)
        }
        val jvmTest by getting {
            dependencies {
                implementation(projects.testing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.opentelemetry.sdk.testing)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.opentelemetry"
}

tasks.withType<Test>().configureEach {
    // The instrumentation-scope version constant must match this module's published version;
    // InstrumentationScopeVersionTest reads this property. findProperty is load-bearing: the
    // module's gradle.properties overrides the root's for project properties, while
    // providers.gradleProperty would read only the root's VERSION_NAME.
    systemProperty(
        "store6.opentelemetry.versionName",
        findProperty("VERSION_NAME") as String,
    )
}
