package io.komust.scope

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Outcome of one `git` invocation. */
internal data class GitResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * A thin `git` command wrapper. `komust-scope` shells out to the user's `git`
 * rather than embedding a Java git implementation: the working-tree state we
 * need (staged + unstaged + untracked, standard ignore rules) is exactly what
 * the porcelain already computes, and it keeps the module dependency-free.
 */
internal class GitClient(private val workingDir: Path, private val gitExecutable: String = "git") {

    /** Run `git [args]`, returning the result even on a non-zero exit. */
    fun run(vararg args: String): GitResult {
        val command = buildList {
            add(gitExecutable)
            addAll(args)
        }
        val process = try {
            ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(false)
                .start()
        } catch (e: IOException) {
            throw ScopeResolutionException(
                "could not run '$gitExecutable' — is git installed and on PATH?", e,
            )
        }

        // Drain stderr on a separate thread so a large diff on stdout and a
        // chatty stderr can't deadlock each other by filling their pipe buffers.
        val stderrBuffer = StringBuilder()
        val stderrPump = Thread {
            process.errorStream.bufferedReader().forEachLine { stderrBuffer.appendLine(it) }
        }.apply { isDaemon = true; start() }

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw ScopeResolutionException("git ${args.joinToString(" ")} timed out")
        }
        stderrPump.join(1_000)
        return GitResult(process.exitValue(), stdout, stderrBuffer.toString())
    }

    /** Run `git [args]`, throwing [ScopeResolutionException] on a non-zero exit. */
    fun runOrThrow(vararg args: String): String {
        val result = run(*args)
        if (!result.ok) {
            throw ScopeResolutionException(
                "git ${args.joinToString(" ")} failed (exit ${result.exitCode}): ${result.stderr.trim()}",
            )
        }
        return result.stdout
    }

    fun refExists(ref: String): Boolean =
        run("rev-parse", "--verify", "--quiet", "$ref^{commit}").ok

    companion object {
        private const val GIT_TIMEOUT_SECONDS = 30L
    }
}
