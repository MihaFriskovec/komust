package io.komust.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * The single **`mutationTest`** task (ADR-0005 §5). A launcher: it assembles the
 * [engine input contract][EngineInputWriter], writes it under `build/komust/`,
 * and **forks a dedicated engine JVM** (`io.komust.engine.EngineMainKt`) with the
 * JaCoCo runtime agent attached. Orchestration — the coverage pass, the worker
 * pool, the JSON emit — happens in that fork, never in the Gradle daemon.
 *
 * Default scope is the git modified-files diff (#6); the `@Option`s below select
 * scope and overrides per run. `--all` / `--since` / `--files` / `--scope` are
 * read at *mutation-compile* time (the compiler plugin filters by scope), so the
 * compilation depends on this task's option values; `--tests` / `--no-cache` are
 * engine-time and consumed here.
 *
 * Not wired into `check` — a normal build and CI are unaffected until opted in.
 */
public abstract class MutationTestTask : DefaultTask() {

    // --- Per-run scope + overrides (ADR-0005 §5) ---------------------------

    @get:Input @get:Optional
    @get:Option(option = "all", description = "Mutate the whole project (opt out of the modified-files default)")
    public abstract val allScope: Property<Boolean>

    @get:Input @get:Optional
    @get:Option(option = "since", description = "Override the git base ref the modified-files scope diffs against")
    public abstract val since: Property<String>

    @get:Input @get:Optional
    @get:Option(option = "files", description = "Explicit Mutation Scope override — comma-separated path globs (whole-file)")
    public abstract val files: Property<String>

    @get:Input @get:Optional
    @get:Option(option = "scope", description = "Explicit Mutation Scope override — a scope.json document")
    public abstract val scopeDocument: Property<String>

    @get:Input @get:Optional
    @get:Option(
        option = "tests",
        description = "Explicit test-selection override: comma-separated JUnit uniqueIds, " +
            "or `path.kt=id;id` segments for per-file pinning",
    )
    public abstract val tests: Property<String>

    @get:Input @get:Optional
    @get:Option(option = "no-cache", description = "Bypass the cross-run mutant-result cache for this run")
    public abstract val noCache: Property<Boolean>

    // --- Wired by the plugin from the Gradle model ------------------------

    @get:Classpath
    public abstract val engineClasspath: ConfigurableFileCollection

    /** The mutation compilation's output — analysed for coverage, then swept. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val classesUnderTest: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val testClassRoots: ConfigurableFileCollection

    @get:Classpath
    public abstract val testRuntimeClasspath: ConfigurableFileCollection

    @get:Classpath
    public abstract val mainRuntimeClasspath: ConfigurableFileCollection

    /** `io.komust.runtime` — the woven guard's home; on every forked classpath. */
    @get:Classpath
    public abstract val runtimeGuardClasspath: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    public abstract val mutationManifests: ConfigurableFileCollection

    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    @get:Input public abstract val workers: Property<Int>
    @get:Input public abstract val timeoutFactor: Property<Double>
    @get:Input public abstract val cache: Property<Boolean>
    @get:Input public abstract val humanReport: Property<Boolean>
    @get:Input public abstract val consoleSurvivorsOnly: Property<Boolean>
    @get:Input public abstract val komustVersion: Property<String>
    @get:Input public abstract val kotlinVersion: Property<String>

    @get:Inject
    protected abstract val exec: ExecOperations

    @TaskAction
    public fun mutationTest() {
        val out = outputDirectory.get().asFile
        out.mkdirs()

        val jacocoAgent = engineClasspath.files.firstOrNull { it.name.matches(JACOCO_AGENT) }
            ?: error(
                "the JaCoCo runtime agent jar (org.jacoco.agent:…:runtime) is not on the komustEngine " +
                    "configuration — the coverage pass needs -javaagent:jacocoagent.jar on the engine fork",
            )

        val workerClasspath = (
            engineClasspath.files +
                testRuntimeClasspath.files +
                mainRuntimeClasspath.files +
                runtimeGuardClasspath.files +
                classesUnderTest.files +
                testClassRoots.files
            ).distinct().map { it.absolutePath }

        val override = parseTestsOverride(tests.orNull)

        val model = EngineInputWriter.Model(
            classesUnderTest = classesUnderTest.files.map { it.absolutePath },
            testClassRoots = testClassRoots.files.map { it.absolutePath },
            mutationManifests = mutationManifests.files.filter { it.isFile }.map { it.absolutePath },
            workerClasspath = workerClasspath,
            reloadableRoots = (classesUnderTest.files + testClassRoots.files).map { it.absolutePath },
            outputDir = out.absolutePath,
            workers = workers.get(),
            timeoutFactor = timeoutFactor.get(),
            cache = cache.get() && noCache.getOrElse(false).not(),
            humanReport = humanReport.get(),
            consoleSurvivorsOnly = consoleSurvivorsOnly.get(),
            testOverrideGlobal = override.first,
            testOverridePerFile = override.second,
            komustVersion = komustVersion.get(),
            kotlinVersion = kotlinVersion.get(),
            jdkVersion = System.getProperty("java.version") ?: "unknown",
        )

        val inputJson = out.resolve("engine-input.json")
        EngineInputWriter.write(inputJson, model)

        val result = exec.javaexec { spec ->
            spec.mainClass.set("io.komust.engine.EngineMainKt")
            spec.classpath = engineClasspath + runtimeGuardClasspath + classesUnderTest + testClassRoots +
                testRuntimeClasspath + mainRuntimeClasspath
            spec.args(inputJson.absolutePath)
            spec.jvmArgs("-javaagent:${jacocoAgent.absolutePath}")
            spec.isIgnoreExitValue = true
        }
        val code = result.exitValue
        if (code != 0) {
            throw org.gradle.api.GradleException(
                "komust: the mutation run exited with code $code — see the output above and " +
                    "${outputDirectory.get().asFile.resolve("report.txt")}",
            )
        }
        logger.lifecycle("komust: report → ${out.resolve("report.json")}")
    }

    /** `--tests` value → (global ids, per-file id sets). `path=a;b` segments are per-file; bare ids are global. */
    private fun parseTestsOverride(raw: String?): Pair<List<String>, Map<String, List<String>>> {
        if (raw.isNullOrBlank()) return emptyList<String>() to emptyMap()
        val global = mutableListOf<String>()
        val perFile = linkedMapOf<String, MutableList<String>>()
        for (segment in raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val eq = segment.indexOf('=')
            if (eq > 0) {
                val path = segment.substring(0, eq).trim()
                perFile.getOrPut(path) { mutableListOf() }
                    .addAll(segment.substring(eq + 1).split(';').map { it.trim() }.filter { it.isNotEmpty() })
            } else {
                global += segment
            }
        }
        return global to perFile
    }

    internal companion object {
        const val NAME: String = "mutationTest"
        private val JACOCO_AGENT = Regex("""org\.jacoco\.agent-.*-runtime\.jar""")
    }
}
