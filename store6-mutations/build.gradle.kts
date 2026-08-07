plugins {
    id("org.mobilenativefoundation.store.store6.multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.store6Core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(projects.store6Testing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.lincheck)
            }
        }
    }
}

android {
    namespace = "org.mobilenativefoundation.store6.mutations"
}

// R1-13's JVM-only API-surface audit reads the committed BCV KLib dump. The lookup is explicit,
// never a working-directory assumption (021 plan T4.8).
tasks.withType<Test>().configureEach {
    if (name == "jvmTest") {
        systemProperty(
            "store6.mutations.apiDumpDir",
            layout.projectDirectory.dir("api").asFile.absolutePath,
        )
    }
}

tasks.named("jvmTest", org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest::class) {
    // Issue 031 (RD-2=b): the authored Lincheck budget exceeds every default hosted lane.
    // The scheduled full-suite workflow passes -Pstore6.fullJvmSuite to run it; nothing else does.
    if (!providers.gradleProperty("store6.fullJvmSuite").isPresent) {
        filter {
            excludeTestsMatching("org.mobilenativefoundation.store6.mutations.MutationJournalLincheckTest")
        }
    }
}
