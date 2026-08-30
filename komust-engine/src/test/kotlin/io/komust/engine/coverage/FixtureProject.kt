@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package io.komust.engine.coverage

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.addPreviousResultToClasspath
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * Compiles a throwaway Kotlin project (production + JUnit 5 tests) in process
 * with the pinned K2 compiler and exposes it the way the engine input contract
 * does: directories of compiled classes plus a class loader that can load both
 * the classes and a JUnit Platform engine.
 *
 * This is the "fixture project" the coverage pass is verified against
 * (issue #32 acceptance criterion 5). [compileSplit] keeps production and test
 * classes in **separate** directories — the real-project shape.
 */
class FixtureProject private constructor(
    private val prodDir: Path,
    private val testDir: Path,
    val classLoader: ClassLoader,
    private val sources: List<Pair<String, String>>,
) {
    val classesUnderTest: List<Path> get() = listOf(prodDir)
    val testClassRoots: List<Path> get() = listOf(testDir).distinct()

    fun input() = CoveragePassInput(classesUnderTest, testClassRoots)

    /** Line number (1-based) of the first source line containing [needle]. */
    fun lineOf(fileName: String, needle: String): Int {
        val text = sources.first { it.first == fileName }.second
        val idx = text.lines().indexOfFirst { it.contains(needle) }
        require(idx >= 0) { "no line containing '$needle' in $fileName" }
        return idx + 1
    }

    companion object {
        /** Single directory for both production and test classes. */
        fun compile(workDir: File, sources: List<Pair<String, String>>): FixtureProject {
            val result = compileOne(workDir, sources, previous = null)
            val dir = result.outputDirectory.toPath()
            return FixtureProject(dir, dir, result.classLoader, sources)
        }

        /** Production and test classes compiled into separate directories. */
        fun compileSplit(
            workDir: File,
            mainSources: List<Pair<String, String>>,
            testSources: List<Pair<String, String>>,
        ): FixtureProject {
            val main = compileOne(workDir.resolve("main"), mainSources, previous = null)
            val test = compileOne(workDir.resolve("test"), testSources, previous = main)
            val loader = URLClassLoader(
                arrayOf(
                    main.outputDirectory.toURI().toURL(),
                    test.outputDirectory.toURI().toURL(),
                ),
                FixtureProject::class.java.classLoader,
            )
            return FixtureProject(
                main.outputDirectory.toPath(),
                test.outputDirectory.toPath(),
                loader,
                mainSources + testSources,
            )
        }

        private fun compileOne(
            workDir: File,
            sources: List<Pair<String, String>>,
            previous: JvmCompilationResult?,
        ): JvmCompilationResult {
            workDir.mkdirs()
            val compilation = KotlinCompilation().apply {
                this.workingDir = workDir
                this.sources = sources.map { (name, text) -> SourceFile.kotlin(name, text) }
                inheritClassPath = true // pulls JUnit 5 onto the fixture compile classpath
                jvmTarget = "21"
                messageOutputStream = System.out
                verbose = false
                previous?.let { addPreviousResultToClasspath(it) }
            }
            val result = compilation.compile()
            check(result.exitCode == KotlinCompilation.ExitCode.OK) {
                "fixture compilation failed:\n${result.messages}"
            }
            return result
        }
    }
}
