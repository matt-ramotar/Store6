plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.skie)
}

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "Store6CoreSkie"
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

val stagedDir = layout.buildDirectory.dir("swift-dump")
val committedDir = rootProject.layout.projectDirectory.dir("store6-core/api/swift/skie")

val generateSwiftDump by tasks.registering {
    dependsOn("linkDebugFrameworkIosArm64")
    outputs.dir(stagedDir)
    doLast {
        val out = stagedDir.get().asFile.apply { mkdirs() }

        val header = layout.buildDirectory
            .file("bin/iosArm64/debugFramework/Store6CoreSkie.framework/Headers/Store6CoreSkie.h")
            .get().asFile
        require(header.isFile) { "Expected SKIE-processed header at ${header.path}" }
        out.resolve("Store6CoreSkie.h").writeText(sanitized(header.readText()))

        // SKIE writes its generated Swift under the module build directory. The include below
        // is intentionally broad; verify the concrete path on the first run (expected under
        // build/skie/**) and tighten the include once observed.
        val swiftFiles = fileTree(layout.buildDirectory) { include("skie/**/*.swift") }
            .files.sortedBy { it.invariantSeparatorsPath() }
        require(swiftFiles.isNotEmpty()) {
            "No SKIE-generated Swift found under ${layout.buildDirectory.get()}/skie. " +
                "Locate the generated sources (find build -name '*.swift') and fix the include."
        }
        val combined = buildString {
            swiftFiles.forEach { file ->
                appendLine("// FILE: ${file.name}")
                append(sanitized(file.readText()))
                appendLine()
            }
        }
        out.resolve("Store6CoreSkie.swift").writeText(combined)
    }
}

fun File.invariantSeparatorsPath(): String = path.replace('\\', '/')

val refreshSwiftDump by tasks.registering(Copy::class) {
    dependsOn(generateSwiftDump)
    from(stagedDir)
    into(committedDir)
}

val checkSwiftDump by tasks.registering {
    dependsOn(generateSwiftDump)
    doLast {
        val staged = stagedDir.get().asFile
        val mismatch = listOf("Store6CoreSkie.h", "Store6CoreSkie.swift").any { name ->
            staged.resolve(name).readText() !=
                committedDir.file(name).asFile.takeIf { it.isFile }?.readText()
        }
        if (mismatch) {
            throw GradleException(
                "SKIE dump for store6-core has drifted from store6-core/api/swift/skie. " +
                    "Run ./gradlew refreshSwiftDumps and commit the result.",
            )
        }
    }
}
