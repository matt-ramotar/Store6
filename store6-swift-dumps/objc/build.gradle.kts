plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "Store6Core"
            export(project(":store6-core"))
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":store6-core"))
            }
        }
    }
}

/** Lines that vary per toolchain invocation and must not churn the committed dump. */
val volatileLine = Regex("""(?i)^\s*(//|/\*|\*)?\s*(compiler|kotlin|skie)\s+version\b.*""")

fun sanitized(raw: String): String =
    raw.lineSequence().filterNot { volatileLine.containsMatchIn(it) }.joinToString("\n").trimEnd() + "\n"

val stagedHeader = layout.buildDirectory.file("swift-dump/Store6Core.h")
val committedDir = rootProject.layout.projectDirectory.dir("store6-core/api/swift/objc")

val generateSwiftDump by tasks.registering {
    dependsOn("linkDebugFrameworkIosArm64")
    outputs.file(stagedHeader)
    doLast {
        val header = layout.buildDirectory
            .file("bin/iosArm64/debugFramework/Store6Core.framework/Headers/Store6Core.h")
            .get().asFile
        require(header.isFile) { "Expected Obj-C export header at ${header.path}" }
        stagedHeader.get().asFile.apply {
            parentFile.mkdirs()
            writeText(sanitized(header.readText()))
        }
    }
}

val refreshSwiftDump by tasks.registering(Copy::class) {
    dependsOn(generateSwiftDump)
    from(stagedHeader)
    into(committedDir)
}

val checkSwiftDump by tasks.registering {
    dependsOn(generateSwiftDump)
    doLast {
        val generated = stagedHeader.get().asFile.readText()
        val committed = committedDir.file("Store6Core.h").asFile.takeIf { it.isFile }?.readText()
        if (generated != committed) {
            throw GradleException(
                "Obj-C export dump for store6-core has drifted from store6-core/api/swift/objc. " +
                    "Run ./gradlew refreshSwiftDumps and commit the result.",
            )
        }
    }
}
