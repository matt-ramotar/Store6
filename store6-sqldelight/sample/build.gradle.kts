plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    alias(libs.plugins.sqldelight)
}

kotlin { jvmToolchain(11) }

dependencies {
    implementation(projects.store6Sqldelight)
    implementation(libs.sqldelight.sqlite.driver)
    testImplementation(kotlin("test"))
}

sqldelight {
    databases {
        create("SampleDatabase") {
            packageName.set("org.mobilenativefoundation.store6.sqldelight.sample.db")
            dialect("app.cash.sqldelight:sqlite-3-24-dialect:2.1.0")
        }
    }
}

application { mainClass.set("org.mobilenativefoundation.store6.sqldelight.sample.MainKt") }
