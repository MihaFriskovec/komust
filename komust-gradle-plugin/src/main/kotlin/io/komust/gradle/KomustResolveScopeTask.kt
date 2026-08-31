package io.komust.gradle

import io.komust.scope.ScopeResolver
import io.komust.scope.ScopeSpec
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.nio.file.Path

/**
 * Resolves the **Mutation Scope** (ADR-0002) and writes `build/komust/scope.json`
 * **before** the mutation compilation runs — the compiler plugin filters
 * mutation sites by the scope at compile time (#30), so scope resolution cannot
 * wait for `mutationTest`.
 *
 * Its inputs mirror `mutationTest`'s per-run `@Option`s (`--all` / `--since` /
 * `--files` / `--scope`); the plugin wires them across as plain value providers
 * so a single set of options on `mutationTest` drives both tasks.
 *
 * [scopeMode] records `all` (whole project — `mutationTest`'s `applyToCompilation`
 * then omits the `scope` SubpluginOption entirely) or `scoped`.
 */
public abstract class KomustResolveScopeTask : DefaultTask() {

    @get:Input @get:Optional
    public abstract val allScope: Property<Boolean>

    @get:Input @get:Optional
    public abstract val since: Property<String>

    /** The `komust { baseRef }` DSL value — the default git base to diff against; `--since` overrides it per run. */
    @get:Input @get:Optional
    public abstract val baseRef: Property<String>

    @get:Input @get:Optional
    public abstract val files: Property<String>

    @get:Input @get:Optional
    public abstract val scopeDocument: Property<String>

    @get:Internal
    public abstract val projectDirectory: DirectoryProperty

    @get:OutputFile
    public abstract val scopeJson: RegularFileProperty

    @get:OutputFile
    public abstract val scopeMode: RegularFileProperty

    @TaskAction
    public fun resolve() {
        val root = projectDirectory.get().asFile.toPath()
        val json = scopeJson.get().asFile
        json.parentFile.mkdirs()

        val spec = scopeSpec()
        if (spec == null) {
            // --all: whole project. Still write an (empty) scope.json so the
            // output is always present; the mode tells applyToCompilation to
            // skip the `scope` option so the compiler weaves the whole module.
            json.writeText("{\n  \"version\": 1,\n  \"files\": []\n}\n")
            scopeMode.get().asFile.writeText("all")
            logger.lifecycle("komust: --all — mutating the whole project")
            return
        }

        val scope = ScopeResolver().resolveAndWrite(root, spec, json.toPath())
        scopeMode.get().asFile.writeText("scoped")
        logger.lifecycle("komust: mutation scope — ${scope.files.size} changed production file(s)")
    }

    private fun scopeSpec(): ScopeSpec? {
        if (allScope.getOrElse(false)) return null
        scopeDocument.orNull?.let { return ScopeSpec.ScopeFile(Path.of(it)) }
        files.orNull?.let { raw ->
            val globs = raw.split(',').map(String::trim).filter(String::isNotEmpty)
            if (globs.isNotEmpty()) return ScopeSpec.Files(globs)
        }
        val ref = since.orNull?.takeIf { it.isNotBlank() }
            ?: baseRef.orNull?.takeIf { it.isNotBlank() }
        return ScopeSpec.Git(since = ref)
    }

    internal companion object {
        const val NAME: String = "komustResolveScope"
    }
}
