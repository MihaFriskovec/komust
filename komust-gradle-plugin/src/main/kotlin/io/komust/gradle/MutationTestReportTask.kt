package io.komust.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Finalizer for [MutationTestTask] that guarantees the user always sees the run
 * summary on the console — including when a re-run with unchanged inputs makes
 * `mutationTest` `UP-TO-DATE` and its own action never fires (#62).
 *
 * It is deliberately always out of date (it has no meaningful inputs). It is the
 * **single** place the summary is printed, so a fresh run shows it exactly once.
 * [MutationTestTask] writes [summaryFile] only on a successful run (and clears it
 * up front), so this task never re-shows a previous run's numbers after a
 * failure. [markerFile] — present only right after a fresh run — just picks the
 * header wording; a stale marker mis-labels one report and self-heals.
 */
public abstract class MutationTestReportTask : DefaultTask() {

    @get:Internal
    public abstract val summaryFile: RegularFileProperty

    @get:Internal
    public abstract val markerFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    public fun report() {
        val freshRun = markerFile.get().asFile.delete()

        val summary = summaryFile.get().asFile
        val lines = summary.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty()
        if (lines.isEmpty()) {
            if (!freshRun) {
                logger.lifecycle("komust: no mutation run summary yet — run `mutationTest` (results land in build/komust/)")
            }
            return
        }

        logger.lifecycle(if (freshRun) "komust: mutation run complete:" else "komust: mutationTest is up to date — last run:")
        lines.forEach { logger.lifecycle(it) }
    }

    internal companion object {
        const val NAME: String = "mutationTestReport"
    }
}
