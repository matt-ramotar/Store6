package org.mobilenativefoundation.store.tooling.plugins

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

private const val SKIE_GENERATED_SWIFT_LAYOUT =
    "skie/binaries/debugFramework/DEBUG/iosArm64/swift/generated"

private val volatileVersionLine =
    Regex("""(?i)^\s*(//|/\*|\*)?\s*(compiler|kotlin|skie)\s+version\b.*""")

@CacheableTask
abstract class GenerateObjcSwiftDumpTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val linkedHeader: RegularFileProperty

    @get:Input
    abstract val outputHeaderName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val header = linkedHeader.get().asFile
        if (!header.isFile) {
            throw GradleException("Expected Obj-C export header at ${header.path}")
        }

        val output = outputDirectory.get().asFile
        recreateDirectory(output)
        output.resolve(outputHeaderName.get()).writeText(sanitized(header.readText()))
    }
}

@CacheableTask
abstract class GenerateSkieSwiftDumpTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val linkedHeader: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedSwiftRoot: DirectoryProperty

    @get:Input
    abstract val supportedLayout: Property<String>

    @get:Input
    abstract val outputHeaderName: Property<String>

    @get:Input
    abstract val outputSwiftName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val header = linkedHeader.get().asFile
        if (!header.isFile) {
            throw GradleException("Expected SKIE-processed header at ${header.path}")
        }

        val swiftRoot = generatedSwiftRoot.get().asFile
        if (!swiftRoot.isDirectory) {
            throw unsupportedSkieLayout(swiftRoot)
        }

        val swiftFiles = swiftRoot.walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
            .sortedBy { it.relativeTo(swiftRoot).invariantSeparatorsPath() }
            .toList()
        if (swiftFiles.isEmpty()) {
            throw unsupportedSkieLayout(swiftRoot)
        }

        val output = outputDirectory.get().asFile
        recreateDirectory(output)
        output.resolve(outputHeaderName.get()).writeText(sanitized(header.readText()))
        output.resolve(outputSwiftName.get()).writeText(
            buildString {
                swiftFiles.forEach { file ->
                    val relativePath = file.relativeTo(swiftRoot).invariantSeparatorsPath()
                    appendLine("// FILE: $relativePath")
                    append(sanitized(file.readText()))
                    appendLine()
                }
            },
        )
    }

    private fun unsupportedSkieLayout(swiftRoot: File): GradleException =
        GradleException(
            "SKIE-generated Swift output is unavailable at ${swiftRoot.path}. " +
                "The pinned SKIE 0.10.13 layout '${supportedLayout.get()}' was not produced; " +
                "no supported SKIE output API is available.",
        )
}

@DisableCachingByDefault(because = "Verification task has no outputs")
abstract class CheckSwiftDumpTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val committedFiles: ConfigurableFileCollection

    @get:Internal
    abstract val generatedDirectory: DirectoryProperty

    @get:Internal
    abstract val committedDirectory: DirectoryProperty

    @get:Input
    abstract val failureMessage: Property<String>

    @TaskAction
    fun check() {
        val generated = snapshot(generatedDirectory.get().asFile)
        val committed = snapshot(committedDirectory.get().asFile)
        if (generated.isEmpty()) {
            throw GradleException("No generated Swift dump files are available to check.")
        }

        val missing = (generated.keys - committed.keys).sorted()
        val stale = (committed.keys - generated.keys).sorted()
        val changed = (generated.keys intersect committed.keys)
            .filterNot { generated.getValue(it).contentEquals(committed.getValue(it)) }
            .sorted()

        if (missing.isNotEmpty() || stale.isNotEmpty() || changed.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(failureMessage.get())
                    appendDifference("Missing committed files", missing)
                    appendDifference("Stale committed files", stale)
                    appendDifference("Changed files", changed)
                }.trimEnd(),
            )
        }
    }
}

class Store6ObjcSwiftDumpPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            val stagedDirectory = layout.buildDirectory.dir("swift-dump")
            val committedDumpDirectory = rootProject.layout.projectDirectory.dir("store6-core/api/swift/objc")
            val generate = tasks.register("generateSwiftDump", GenerateObjcSwiftDumpTask::class.java) {
                group = "Store6 verification"
                description = "Generates the sanitized Obj-C export dump for store6-core."
                dependsOn("linkDebugFrameworkIosArm64")
                linkedHeader.set(
                    layout.buildDirectory.file(
                        "bin/iosArm64/debugFramework/Store6Core.framework/Headers/Store6Core.h",
                    ),
                )
                outputHeaderName.set("Store6Core.h")
                outputDirectory.set(stagedDirectory)
            }

            val refresh = tasks.register("refreshSwiftDump", Sync::class.java) {
                group = "Store6 verification"
                description = "Refreshes the committed Obj-C export dump for store6-core."
                dependsOn(generate)
                doNotTrackState("Refresh must always reconcile stale files in the committed dump.")
                from(stagedDirectory)
                into(committedDumpDirectory)
                includeEmptyDirs = false
            }

            tasks.register("checkSwiftDump", CheckSwiftDumpTask::class.java) {
                group = "Store6 verification"
                description = "Checks the committed Obj-C export dump for store6-core."
                dependsOn(generate)
                mustRunAfter(refresh)
                generatedFiles.from(stagedDirectory)
                committedFiles.from(committedDumpDirectory)
                generatedDirectory.set(stagedDirectory)
                committedDirectory.set(committedDumpDirectory)
                failureMessage.set(
                    "Obj-C export dump for store6-core has drifted from store6-core/api/swift/objc. " +
                        "Run ./gradlew refreshSwiftDumps and commit the result.",
                )
            }
        }
    }
}

class Store6SkieSwiftDumpPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            val stagedDirectory = layout.buildDirectory.dir("swift-dump")
            val committedDumpDirectory = rootProject.layout.projectDirectory.dir("store6-core/api/swift/skie")
            val generatedSwiftDirectory = layout.buildDirectory.dir(SKIE_GENERATED_SWIFT_LAYOUT)
            val generate = tasks.register("generateSwiftDump", GenerateSkieSwiftDumpTask::class.java) {
                group = "Store6 verification"
                description = "Generates the sanitized SKIE dump for store6-core."
                dependsOn("linkDebugFrameworkIosArm64")
                linkedHeader.set(
                    layout.buildDirectory.file(
                        "bin/iosArm64/debugFramework/Store6CoreSkie.framework/Headers/Store6CoreSkie.h",
                    ),
                )
                generatedSwiftRoot.set(generatedSwiftDirectory)
                supportedLayout.set(SKIE_GENERATED_SWIFT_LAYOUT)
                outputHeaderName.set("Store6CoreSkie.h")
                outputSwiftName.set("Store6CoreSkie.swift")
                outputDirectory.set(stagedDirectory)
            }

            val refresh = tasks.register("refreshSwiftDump", Sync::class.java) {
                group = "Store6 verification"
                description = "Refreshes the committed SKIE dump for store6-core."
                dependsOn(generate)
                doNotTrackState("Refresh must always reconcile stale files in the committed dump.")
                from(stagedDirectory)
                into(committedDumpDirectory)
                includeEmptyDirs = false
            }

            tasks.register("checkSwiftDump", CheckSwiftDumpTask::class.java) {
                group = "Store6 verification"
                description = "Checks the committed SKIE dump for store6-core."
                dependsOn(generate)
                mustRunAfter(refresh)
                generatedFiles.from(stagedDirectory)
                committedFiles.from(committedDumpDirectory)
                generatedDirectory.set(stagedDirectory)
                committedDirectory.set(committedDumpDirectory)
                failureMessage.set(
                    "SKIE dump for store6-core has drifted from store6-core/api/swift/skie. " +
                        "Run ./gradlew refreshSwiftDumps and commit the result.",
                )
            }
        }
    }
}

private fun sanitized(raw: String): String =
    raw.lineSequence()
        .filterNot { volatileVersionLine.containsMatchIn(it) }
        .joinToString("\n")
        .trimEnd() + "\n"

private fun recreateDirectory(directory: File) {
    if (directory.exists() && !directory.deleteRecursively()) {
        throw GradleException("Could not clear Swift dump staging directory at ${directory.path}")
    }
    if (!directory.mkdirs() && !directory.isDirectory) {
        throw GradleException("Could not create Swift dump staging directory at ${directory.path}")
    }
}

private fun snapshot(directory: File): Map<String, ByteArray> {
    if (!directory.isDirectory) return emptyMap()

    return directory.walkTopDown()
        .filter(File::isFile)
        .associate { file -> file.relativeTo(directory).invariantSeparatorsPath() to file.readBytes() }
}

private fun StringBuilder.appendDifference(label: String, paths: List<String>) {
    if (paths.isNotEmpty()) {
        appendLine("$label: ${paths.joinToString()}")
    }
}

private fun File.invariantSeparatorsPath(): String = path.replace('\\', '/')
