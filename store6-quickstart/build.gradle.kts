plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(11) }

dependencies {
    implementation(projects.store6Core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

application { mainClass.set("org.mobilenativefoundation.store6.quickstart.MainKt") }
