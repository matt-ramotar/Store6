plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
}

kotlin { jvmToolchain(11) }

composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("store6-compose/stability/store6-stability.conf"),
    )
    metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
    reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
}

dependencies {
    implementation(projects.store6Compose)
    implementation(projects.store6Testing)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application { mainClass = "org.mobilenativefoundation.store6.composedemo.MainKt" }
}
