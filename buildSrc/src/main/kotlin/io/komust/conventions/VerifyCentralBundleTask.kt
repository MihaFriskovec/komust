package io.komust.conventions

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Verification executes GnuPG against a deliberately ephemeral identity")
abstract class VerifyCentralBundleTask : DefaultTask() {
    @get:InputFile
    abstract val bundle: RegularFileProperty

    @get:InputFile
    abstract val publicKey: RegularFileProperty

    @get:Input
    abstract val publicGroup: Property<String>

    @get:Input
    abstract val publicVersion: Property<String>

    @get:OutputFile
    abstract val verificationReport: RegularFileProperty

    @TaskAction
    fun verify() {
        val home = Files.createTempDirectory(temporaryDir.toPath(), "gpg-")
        try {
            requireEphemeralQualificationKey(home)
            val sequence = AtomicInteger()
            CentralBundleVerifier.verify(
                bundle.get().asFile.toPath(),
                publicGroup.get(),
                publicVersion.get(),
            ) { primary, signature ->
                val index = sequence.incrementAndGet()
                val primaryFile = home.resolve("primary-$index")
                val signatureFile = home.resolve("primary-$index.asc")
                Files.write(primaryFile, primary)
                Files.write(signatureFile, signature)
                runGpg(home, "--verify", signatureFile.toString(), primaryFile.toString())
            }
            val report = verificationReport.get().asFile.toPath()
            Files.createDirectories(report.parent)
            Files.writeString(
                report,
                "Verified ${sequence.get()} signed primary files for exactly " +
                    "komust-compiler-plugin, komust-engine, and komust-scope.\n",
            )
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    private fun requireEphemeralQualificationKey(home: Path) {
        runGpg(home, "--import", publicKey.get().asFile.absolutePath)
        val listing = runGpg(home, "--with-colons", "--show-keys", publicKey.get().asFile.absolutePath)
        require(listing.lineSequence().any { it.startsWith("uid:") && it.contains("komust qualification") }) {
            "Qualification key must identify itself as a komust qualification identity"
        }
        val expiry = listing.lineSequence().firstOrNull { it.startsWith("pub:") }
            ?.split(':')?.getOrNull(6)?.toLongOrNull()
            ?: error("Qualification key must have an expiry")
        val latestAllowed = Instant.now().plus(2, ChronoUnit.DAYS).epochSecond
        require(expiry in (Instant.now().epochSecond + 1)..latestAllowed) {
            "Qualification key must be short-lived (no more than two days)"
        }
    }

    private fun runGpg(home: Path, vararg arguments: String): String {
        val process = ProcessBuilder(
            "gpg", "--batch", "--no-tty", "--no-autostart", "--homedir", home.toString(), *arguments,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "GnuPG verification failed: $output" }
        return output
    }
}
