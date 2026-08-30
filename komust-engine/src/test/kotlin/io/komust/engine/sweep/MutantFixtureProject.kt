@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package io.komust.engine.sweep

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.addPreviousResultToClasspath
import io.komust.compiler.KomustCommandLineProcessor
import io.komust.compiler.KomustCompilerPluginRegistrar
import io.komust.engine.coverage.CoveragePassInput
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * A throwaway Kotlin project compiled **with `komust-compiler-plugin` applied to
 * the production sources** — the "hand-built mutant-instrumented fixture" the
 * sweep is verified against at the engine seam (issue #33 acceptance criterion
 * 5).
 *
 * Production and test classes land in separate directories (the real-project
 * shape). The production compile weaves the full default operator catalog and
 * emits one `komust-mutant …` diagnostic per site; those are parsed back into
 * [Mutant] records — exactly the sweep's slice of the engine input contract.
 *
 * The result class loader is parented to this test's, so the woven
 * `io.komust.runtime.mutantActive` guard and [MutantSwitchHandle.processGlobal]
 * resolve the **same** process-global slot.
 */
class MutantFixtureProject private constructor(
    private val prodDir: Path,
    private val testDir: Path,
    val classLoader: ClassLoader,
    private val sources: List<Pair<String, String>>,
    val mutants: List<Mutant>,
) {
    fun coverageInput() = CoveragePassInput(listOf(prodDir), listOf(testDir))

    /**
     * Code-under-test + test-class output dirs — what a forked worker reloads in
     * a fresh loader per mutant ([io.komust.engine.sweep.forked.worker.MutantClassLoader]).
     */
    val reloadableRoots: List<Path> get() = listOf(prodDir, testDir)

    /**
     * A full class path for a forked worker JVM: the reloadable roots plus this
     * test JVM's own class path (JUnit Platform + Jupiter engine, the
     * `io.komust.runtime` switch, the engine, kotlinx-serialization, the Kotlin
     * stdlib — everything `WorkerMain` needs).
     */
    val workerClasspath: List<Path>
        get() = (reloadableRoots + System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { Path.of(it) }).distinct()

    /** Line number (1-based) of the first source line containing [needle]. */
    fun lineOf(fileName: String, needle: String): Int {
        val text = sources.first { it.first == fileName }.second
        val idx = text.lines().indexOfFirst { it.contains(needle) }
        require(idx >= 0) { "no line containing '$needle' in $fileName" }
        return idx + 1
    }

    /** Every woven mutant whose site is [needle]'s line in [fileName]. */
    fun mutantsOn(fileName: String, needle: String): List<Mutant> {
        val line = lineOf(fileName, needle)
        // The mutant id is `<file>:<line>:<col>:<token>#<ord>` — match the file too
        // so a multi-file fixture does not cross-match on a shared line number.
        return mutants.filter { it.line == line && it.id.substringBefore(':') == fileName }
    }

    companion object {
        fun compile(
            workDir: File,
            mainSources: List<Pair<String, String>>,
            testSources: List<Pair<String, String>>,
        ): MutantFixtureProject {
            val main = compileMain(workDir.resolve("main"), mainSources)
            val test = compileTests(workDir.resolve("test"), testSources, previous = main.result)
            val loader = URLClassLoader(
                arrayOf(
                    main.result.outputDirectory.toURI().toURL(),
                    test.outputDirectory.toURI().toURL(),
                ),
                MutantFixtureProject::class.java.classLoader,
            )
            return MutantFixtureProject(
                main.result.outputDirectory.toPath(),
                test.outputDirectory.toPath(),
                loader,
                mainSources + testSources,
                main.mutants,
            )
        }

        private class MainCompile(val result: JvmCompilationResult, val mutants: List<Mutant>)

        private fun compileMain(workDir: File, sources: List<Pair<String, String>>): MainCompile {
            workDir.mkdirs()
            val compilation = KotlinCompilation().apply {
                this.workingDir = workDir
                this.sources = sources.map { (name, text) -> SourceFile.kotlin(name, text) }
                inheritClassPath = true // io.komust.runtime + kotlin-stdlib for the woven guard
                jvmTarget = "21"
                messageOutputStream = System.out
                compilerPluginRegistrars = listOf(KomustCompilerPluginRegistrar())
                commandLineProcessors = listOf(KomustCommandLineProcessor())
                // No plugin options → full default catalog, whole module in scope.
            }
            val result = compilation.compile()
            check(result.exitCode == KotlinCompilation.ExitCode.OK) {
                "fixture main compilation failed:\n${result.messages}"
            }
            return MainCompile(result, parseMutants(result.messages))
        }

        private fun compileTests(
            workDir: File,
            sources: List<Pair<String, String>>,
            previous: JvmCompilationResult,
        ): JvmCompilationResult {
            workDir.mkdirs()
            val compilation = KotlinCompilation().apply {
                this.workingDir = workDir
                this.sources = sources.map { (name, text) -> SourceFile.kotlin(name, text) }
                inheritClassPath = true // JUnit 5 for the fixture's own tests
                jvmTarget = "21"
                messageOutputStream = System.out
                addPreviousResultToClasspath(previous)
            }
            val result = compilation.compile()
            check(result.exitCode == KotlinCompilation.ExitCode.OK) {
                "fixture test compilation failed:\n${result.messages}"
            }
            return result
        }

        // `komust-mutant id=<file>:<line>:<col>:<token>#<ord> op=<slug> class=<binaryName> startOffset=… path=… desc=…`
        private val MUTANT_LINE =
            Regex("""komust-mutant id=(\S+) op=\S+ class=(\S+) startOffset=\d+ path=(\S+) desc=.*""")

        private fun parseMutants(messages: String): List<Mutant> {
            val mutants = MUTANT_LINE.findAll(messages).map { m ->
                val id = m.groupValues[1]
                Mutant(
                    id = id,
                    binaryClassName = m.groupValues[2],
                    line = id.split(":")[1].toInt(),
                    sourceFile = m.groupValues[3],
                )
            }.toList()
            val dupes = mutants.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
            check(dupes.isEmpty()) { "the plugin was applied more than once — duplicate mutant ids: $dupes" }
            check(mutants.isNotEmpty()) { "no komust-mutant diagnostics in the fixture compile output" }
            return mutants
        }
    }
}
