package io.komust.conventions

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Produces the exact GitHub Release notes only after public documentation qualifies. */
abstract class VerifyReleaseDocumentationTask : DefaultTask() {
    init {
        // Link targets are discovered from Markdown at execution time, so every
        // qualification must re-check the current work tree rather than reuse a
        // stale successful output after a linked file is removed.
        outputs.upToDateWhen { false }
    }

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documents: ConfigurableFileCollection

    @get:Input
    abstract val publicVersion: Property<String>

    @get:Input
    abstract val publicIdentityName: Property<String>

    @get:OutputFile
    abstract val githubReleaseNotes: RegularFileProperty

    @TaskAction
    fun verify() {
        val notes = ReleaseDocumentationVerifier.verify(
            repositoryRoot.get().asFile.toPath(),
            publicVersion.get(),
            PublicIdentity.named(publicIdentityName.get()),
        )
        githubReleaseNotes.get().asFile.apply {
            parentFile.mkdirs()
            writeText("$notes\n")
        }
    }
}
