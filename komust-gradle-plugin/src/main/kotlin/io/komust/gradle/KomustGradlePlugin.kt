package io.komust.gradle

import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

/**
 * The thin Gradle adapter over `komust-engine` (ADR-0005).
 *
 * Responsibilities, and nothing else:
 *
 *  - a **dedicated mutation compilation** of the production sources, applied via
 *    the official [KotlinCompilerPluginSupportPlugin] — its `applyToCompilation`
 *    returns the `SubpluginOption`s (the `scope.json` path, operator policy, the
 *    manifest path). The ordinary `compileKotlin` is never touched;
 *  - the property-based [KomustExtension] (`komust {}`) DSL;
 *  - [KomustResolveScopeTask] — git diff → `scope.json`, before the mutation
 *    compile;
 *  - the single [MutationTestTask] (`mutationTest`), **not** wired into `check`,
 *    which forks the engine with the [engine input contract][EngineInputWriter].
 *
 * All mutation / coverage / execution logic lives in the engine.
 */
public class KomustGradlePlugin : KotlinCompilerPluginSupportPlugin {

    private lateinit var extension: KomustExtension

    /** The plugin's own version, from the generated resource; immutable for the plugin's lifetime. */
    private val pluginVersion: String by lazy {
        KomustGradlePlugin::class.java.classLoader
            .getResourceAsStream("io/komust/gradle/komust-version.properties")
            ?.use { Properties().apply { load(it) }.getProperty("version") }
            ?: "0.1.0-SNAPSHOT"
    }
    private lateinit var resolveScope: TaskProvider<KomustResolveScopeTask>
    private lateinit var mutationTest: TaskProvider<MutationTestTask>

    override fun apply(target: Project) {
        extension = target.extensions.create(KomustExtension.NAME, KomustExtension::class.java).apply {
            // No baseRef convention: unset ⇒ the scope resolver auto-detects the
            // default branch (origin/HEAD → origin/main → main → master). Setting
            // it pins the diff base explicitly, like a persistent `--since`.
            workers.convention(Runtime.getRuntime().availableProcessors())
            timeoutFactor.convention(3.0)
            cache.convention(true)
            sourceSets.convention(listOf(SourceSet.MAIN_SOURCE_SET_NAME))
            operators.experimental.convention(false)
            output.humanReport.convention(true)
            output.consoleSurvivorsOnly.convention(false)
        }

        val komustVersion = pluginVersion
        val komustDir = target.layout.buildDirectory.dir("komust")

        val engineDeps = target.configurations.dependencyScope("komustEngine") {
            it.description = "The komust-engine the mutationTest task forks."
        }
        val engineClasspath = target.configurations.resolvable("komustEngineClasspath") {
            it.extendsFrom(engineDeps.get())
            it.attributes { attrs ->
                attrs.attribute(Usage.USAGE_ATTRIBUTE, target.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attrs.attribute(Category.CATEGORY_ATTRIBUTE, target.objects.named(Category::class.java, Category.LIBRARY))
            }
        }
        val runtimeGuardDeps = target.configurations.dependencyScope("komustRuntime") {
            it.description = "io.komust.runtime — the woven guard, on every forked classpath."
        }
        val runtimeGuardClasspath = target.configurations.resolvable("komustRuntimeClasspath") {
            it.extendsFrom(runtimeGuardDeps.get())
            it.attributes { attrs ->
                attrs.attribute(Usage.USAGE_ATTRIBUTE, target.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attrs.attribute(Category.CATEGORY_ATTRIBUTE, target.objects.named(Category::class.java, Category.LIBRARY))
            }
        }
        target.dependencies.add(engineDeps.name, "io.komust:komust-engine:$komustVersion")
        target.dependencies.add(runtimeGuardDeps.name, "io.komust:komust-compiler-plugin:$komustVersion")

        resolveScope = target.tasks.register(KomustResolveScopeTask.NAME, KomustResolveScopeTask::class.java) { task ->
            task.group = GROUP
            task.description = "Resolves the komust Mutation Scope (git diff) and writes scope.json."
            task.projectDirectory.set(target.layout.projectDirectory)
            task.baseRef.set(extension.baseRef)
            task.scopeJson.set(komustDir.map { it.file("scope.json") })
            task.scopeMode.set(komustDir.map { it.file("scope.mode") })
        }

        mutationTest = target.tasks.register(MutationTestTask.NAME, MutationTestTask::class.java) { task ->
            task.group = GROUP
            task.description = "Runs komust mutation testing over the git-modified production sources."
            task.outputDirectory.set(komustDir)
            task.engineClasspath.from(engineClasspath)
            task.runtimeGuardClasspath.from(runtimeGuardClasspath)
            task.workers.set(extension.workers)
            task.timeoutFactor.set(extension.timeoutFactor)
            task.cache.set(extension.cache)
            task.humanReport.set(extension.output.humanReport)
            task.consoleSurvivorsOnly.set(extension.output.consoleSurvivorsOnly)
            task.komustVersion.set(komustVersion)
            task.kotlinVersion.set(runCatching { target.getKotlinPluginVersion() }.getOrDefault("unknown"))
            task.dependsOn(resolveScope)
        }

        // A single set of `@Option`s on `mutationTest` drives scope resolution
        // too — feed its values across as plain value providers (no task dep).
        resolveScope.configure { scope ->
            scope.allScope.set(mutationTest.flatMap { it.allScope })
            scope.since.set(mutationTest.flatMap { it.since })
            scope.files.set(mutationTest.flatMap { it.files })
            scope.scopeDocument.set(mutationTest.flatMap { it.scopeDocument })
        }

        target.plugins.withId("org.jetbrains.kotlin.jvm") {
            wireMutationCompilation(target)
        }
    }

    // --- The dedicated mutation compilation -------------------------------

    private fun wireMutationCompilation(project: Project) {
        val kotlin = project.extensions.getByType(KotlinJvmProjectExtension::class.java)
        val kotlinTarget = kotlin.target
        val mainCompilation = kotlinTarget.compilations.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val classesDir = project.layout.buildDirectory.dir("komust/classes/main")
        val manifestFile = project.layout.buildDirectory.file("komust/mutants-main.json")

        project.afterEvaluate {
            val configured = extension.sourceSets.getOrElse(listOf(SourceSet.MAIN_SOURCE_SET_NAME))
            if (configured != listOf(SourceSet.MAIN_SOURCE_SET_NAME)) {
                project.logger.warn(
                    "komust: `komust { sourceSets = $configured }` is reserved — v1 mutates only `main`.",
                )
            }
        }

        val mutation = kotlinTarget.compilations.create(MUTATION_COMPILATION_NAME)
        mutation.defaultSourceSet.kotlin.setSrcDirs(mainCompilation.defaultSourceSet.kotlin.srcDirs)

        mutation.compileTaskProvider.configure { compileTask ->
            (compileTask as KotlinCompile).apply {
                // Same dependencies the ordinary main compile sees, plus the
                // `io.komust.runtime` guard so `mutantActive(...)` resolves.
                libraries.from(mainCompilation.compileDependencyFiles)
                libraries.from(project.configurations.getByName("komustRuntimeClasspath"))
                destinationDirectory.set(classesDir)
                dependsOn(resolveScope)
            }
        }

        mutationTest.configure { task ->
            task.classesUnderTest.from(classesDir)
            task.mutationManifests.from(manifestFile)
            task.dependsOn(mutation.compileTaskProvider)
        }

        kotlinTarget.compilations.findByName(SourceSet.TEST_SOURCE_SET_NAME)?.let { testCompilation ->
            mutationTest.configure { task ->
                task.testClassRoots.from(testCompilation.output.classesDirs)
                task.testRuntimeClasspath.from(testCompilation.runtimeDependencyFiles ?: project.files())
                task.mainRuntimeClasspath.from(mainCompilation.runtimeDependencyFiles ?: project.files())
                task.dependsOn(testCompilation.compileTaskProvider)
            }
        }
    }

    // --- KotlinCompilerPluginSupportPlugin -------------------------------

    override fun getCompilerPluginId(): String = COMPILER_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact("io.komust", "komust-compiler-plugin", pluginVersion)

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.name == MUTATION_COMPILATION_NAME

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        val manifest = project.layout.buildDirectory.file("komust/mutants-main.json").get().asFile
        val scopeJson = project.layout.buildDirectory.file("komust/scope.json").get().asFile
        val scopeMode = project.layout.buildDirectory.file("komust/scope.mode").get().asFile

        return project.provider {
            buildList {
                add(SubpluginOption("manifest", manifest.absolutePath))
                add(SubpluginOption("projectDir", project.projectDir.absolutePath))

                extension.operators.disabled.get().takeIf { it.isNotEmpty() }
                    ?.let { add(SubpluginOption("disabledOperators", it.joinToString(","))) }
                extension.operators.enabled.get().takeIf { it.isNotEmpty() }
                    ?.let { add(SubpluginOption("enabledOperators", it.joinToString(","))) }
                if (extension.operators.experimental.getOrElse(false)) {
                    add(SubpluginOption("experimentalTier", "true"))
                }

                // scope.mode == "all" (the `--all` run) ⇒ omit the option so the
                // compiler weaves the whole module (MutationScopeFilter.unfiltered).
                val mode = scopeMode.takeIf { it.isFile }?.readText()?.trim()
                if (mode != "all" && scopeJson.isFile) {
                    add(SubpluginOption("scope", scopeJson.absolutePath))
                }
            }
        }
    }

    internal companion object {
        const val GROUP: String = "verification"
        const val COMPILER_PLUGIN_ID: String = "io.komust.compiler"

        /** The one dedicated mutation compilation — distinct from `main`, so `compileKotlin` stays mutant-free. */
        const val MUTATION_COMPILATION_NAME: String = "komust"
    }
}
