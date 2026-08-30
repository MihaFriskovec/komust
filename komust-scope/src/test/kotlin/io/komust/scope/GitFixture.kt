package io.komust.scope

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText

/**
 * A throwaway git repository on disk for exercising [ScopeResolver] against real
 * `git` output. Every komust-scope test that touches git resolution builds one
 * of these with a crafted history and working-tree state, then asserts on the
 * resolved [MutationScope] — the module's public boundary.
 */
class GitFixture private constructor(val root: Path) {

    fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
        return output
    }

    /** Write [content] to [path] (repo-relative), creating parent dirs. */
    fun write(path: String, content: String): GitFixture {
        val file = root.resolve(path)
        file.parent?.createDirectories()
        file.writeText(content)
        return this
    }

    fun delete(path: String): GitFixture {
        root.resolve(path).deleteExisting()
        return this
    }

    fun add(vararg paths: String): GitFixture {
        git("add", *if (paths.isEmpty()) arrayOf("-A") else paths)
        return this
    }

    fun commit(message: String): GitFixture {
        git("commit", "-m", message, "--no-gpg-sign")
        return this
    }

    fun checkoutNewBranch(name: String): GitFixture {
        git("checkout", "-b", name)
        return this
    }

    fun checkout(name: String): GitFixture {
        git("checkout", name)
        return this
    }

    /**
     * Give this repo a real `origin` remote (a bare clone of its current state)
     * with `origin/HEAD` pointing at [defaultBranch] — the zero-config path
     * [ScopeResolver] actually takes in a checked-out project.
     */
    fun withOriginRemote(defaultBranch: String = "main"): GitFixture {
        val bare = Files.createTempDirectory("komust-scope-origin")
        bare.toFile().deleteOnExit()
        ProcessBuilder("git", "clone", "--bare", root.toString(), bare.toString())
            .redirectErrorStream(true).start().waitFor()
        git("remote", "add", "origin", bare.toString())
        git("fetch", "origin")
        git("remote", "set-head", "origin", defaultBranch)
        return this
    }

    fun resolveScope(config: ScopeConfig = ScopeConfig()): MutationScope =
        ScopeResolver(config).resolveFromGit(root)

    fun resolveScope(spec: ScopeSpec, config: ScopeConfig = ScopeConfig()): MutationScope =
        ScopeResolver(config).resolve(root, spec)

    companion object {
        /** A repo with one commit on `main` containing [initialFiles]. */
        fun create(initialFiles: Map<String, String> = emptyMap()): GitFixture {
            val root = Files.createTempDirectory("komust-scope-fixture")
            root.toFile().deleteOnExit()
            val fixture = GitFixture(root)
            fixture.git("init", "--initial-branch=main")
            fixture.git("config", "user.email", "test@komust.io")
            fixture.git("config", "user.name", "komust test")
            fixture.git("config", "commit.gpgsign", "false")
            if (initialFiles.isEmpty()) {
                fixture.write(".gitkeep", "")
                fixture.add(".gitkeep")
            } else {
                initialFiles.forEach { (path, content) -> fixture.write(path, content) }
                fixture.add()
            }
            fixture.commit("initial")
            return fixture
        }

        fun rootPathString(fixture: GitFixture): String = fixture.root.absolutePathString()
    }
}
