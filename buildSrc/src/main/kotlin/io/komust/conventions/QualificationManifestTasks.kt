package io.komust.conventions

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class GenerateQualificationManifestTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repository: DirectoryProperty

    @get:Input
    abstract val candidateCommit: Property<String>

    @get:Input
    abstract val publicGroup: Property<String>

    @get:Input
    abstract val publicPluginId: Property<String>

    @get:Input
    abstract val publicVersion: Property<String>

    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun generate() {
        val document = QualificationManifest.generate(
            repository.get().asFile.toPath(),
            candidateCommit.get(),
            publicVersion.get(),
            qualificationCoordinates(publicGroup.get(), publicPluginId.get(), publicVersion.get()),
        )
        QualificationManifest.write(document, manifest.get().asFile.toPath())
    }
}

abstract class VerifyQualificationManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repository: DirectoryProperty

    @get:Input
    abstract val publicGroup: Property<String>

    @get:Input
    abstract val publicPluginId: Property<String>

    @get:Input
    abstract val publicVersion: Property<String>

    @TaskAction
    fun verify() {
        val document = QualificationManifest.read(manifest.get().asFile.toPath())
        QualificationManifest.verify(
            document,
            repository.get().asFile.toPath(),
            qualificationCoordinates(publicGroup.get(), publicPluginId.get(), publicVersion.get()),
        )
    }
}

fun qualificationCoordinates(group: String, pluginId: String, version: String): Set<QualificationCoordinate> = setOf(
    QualificationCoordinate(pluginId, "$pluginId.gradle.plugin", version),
    QualificationCoordinate(group, "komust-gradle-plugin", version),
    QualificationCoordinate(group, "komust-compiler-plugin", version),
    QualificationCoordinate(group, "komust-engine", version),
    QualificationCoordinate(group, "komust-scope", version),
)
