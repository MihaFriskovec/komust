package io.komust.engine

import io.komust.engine.coverage.JacocoRuntimeAgent
import io.komust.engine.report.MutantStatus
import io.komust.engine.report.ReportJson
import io.komust.engine.report.ReportWriter
import io.komust.engine.sweep.MutantFixtureProject
import io.komust.runtime.MutantRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Seam 2 (walking-skeleton stories 20–21): drive [EngineRunner] over the
 * **engine input contract** and assert on the emitted `report.json` /
 * `survivors.json`. Covers the whole engine surface — manifest read, coverage
 * pass + green baseline, forked sweep scoring, JSON emit — through observable
 * output, against a fixture compiled with `komust-compiler-plugin` (which now
 * writes the real `mutants.json` the runner consumes).
 */
class EngineRunnerTest {

    companion object {
        @BeforeAll @JvmStatic
        fun requireAgent() {
            assumeTrue(JacocoRuntimeAgent.isAttached(), "no JaCoCo runtime agent attached to this JVM")
        }

        private val CALC = "Calc.kt" to """
            package fixture

            class Calc {
                fun add(a: Int, b: Int): Int = a + b
                fun mul(a: Int, b: Int): Int = a * b
                fun unused(a: Int, b: Int): Int = a + b
            }
        """.trimIndent()

        private val TESTS = "CalcTest.kt" to """
            package fixture

            import org.junit.jupiter.api.Test
            import org.junit.jupiter.api.Assertions.assertEquals
            import org.junit.jupiter.api.Assertions.assertTrue

            class CalcTest {
                @Test fun addExact() { assertEquals(5, Calc().add(2, 3)) }
                @Test fun mulLoose() { assertTrue(Calc().mul(3, 4) != 999) }
            }
        """.trimIndent()
    }

    @AfterEach fun reset() = MutantRegistry.clear()

    private fun run(tmp: Path): EngineRunner.Outcome {
        val fx = MutantFixtureProject.compile(
            tmp.toFile().resolve("proj"),
            mainSources = listOf(CALC),
            testSources = listOf(TESTS),
        )
        val cov = fx.coverageInput()
        val input = EngineInput(
            classesUnderTest = cov.classesUnderTest.map { it.toString() },
            testClassRoots = cov.testClassRoots.map { it.toString() },
            mutationManifests = listOf(fx.manifestPath.toString()),
            workerClasspath = fx.workerClasspath.map { it.toString() },
            reloadableRoots = fx.reloadableRoots.map { it.toString() },
            outputDir = tmp.resolve("out").toString(),
            config = EngineInput.EngineConfig(workers = 2),
            komustVersion = "9.9.9-test",
        )
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = fx.classLoader
        return try {
            EngineRunner.run(input)
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    @Test fun `writes report_json and survivors_json for the whole run`(@TempDir tmp: Path) {
        val outcome = run(tmp)
        val completed = assertInstanceOf<EngineRunner.Outcome.Completed>(outcome)

        val report = ReportJson.decodeReport(completed.report.reportJson.readText())
        assertEquals("9.9.9-test", report.run.komustVersion)
        assertEquals(report.mutants.size, report.run.counts.total)
        assertTrue(report.run.counts.total > 0, "the fixture wove mutants")

        // add: exactly asserted -> at least one KILLED mutant on the add line.
        assertTrue(
            report.mutants.any { it.enclosingSymbol == "add" && it.status == MutantStatus.KILLED },
            report.mutants.joinToString("\n"),
        )
        // mul: covered but never asserted -> a SURVIVED mutant.
        assertTrue(
            report.mutants.any { it.enclosingSymbol == "mul" && it.status == MutantStatus.SURVIVED },
            report.mutants.joinToString("\n"),
        )
        // unused: no test executes it -> NO_COVERAGE.
        assertTrue(
            report.mutants.any { it.enclosingSymbol == "unused" && it.status == MutantStatus.NO_COVERAGE },
            report.mutants.joinToString("\n"),
        )

        val survivors = ReportJson.decodeSurvivors(completed.report.survivorsJson.readText())
        assertTrue(survivors.survivors.any { it.enclosingSymbol == "mul" }, survivors.toString())
        assertTrue(survivors.noCoverage.any { it.enclosingSymbol == "unused" }, survivors.toString())
        assertTrue(survivors.survivors.all { it.summary.isNotBlank() })

        // report.txt renders from the JSON (story 16).
        assertTrue(completed.report.humanReport.readText().isNotBlank())
    }

    @Test fun `report_json mutants carry the manifest's repo-relative location`(@TempDir tmp: Path) {
        val outcome = run(tmp)
        val completed = assertInstanceOf<EngineRunner.Outcome.Completed>(outcome)
        val report = ReportJson.decodeReport(completed.report.reportJson.readText())
        assertTrue(report.mutants.all { it.location.path == "Calc.kt" }, report.mutants.map { it.location.path }.toString())
    }

    @Test fun `a red baseline aborts the run with a clear reason`(@TempDir tmp: Path) {
        val fx = MutantFixtureProject.compile(
            tmp.toFile().resolve("proj"),
            mainSources = listOf(CALC),
            testSources = listOf(
                "CalcTest.kt" to """
                    package fixture
                    import org.junit.jupiter.api.Test
                    import org.junit.jupiter.api.Assertions.assertEquals
                    class CalcTest {
                        @Test fun broken() { assertEquals(999, Calc().add(1, 1)) }
                    }
                """.trimIndent(),
            ),
        )
        val cov = fx.coverageInput()
        val input = EngineInput(
            classesUnderTest = cov.classesUnderTest.map { it.toString() },
            testClassRoots = cov.testClassRoots.map { it.toString() },
            mutationManifests = listOf(fx.manifestPath.toString()),
            workerClasspath = fx.workerClasspath.map { it.toString() },
            reloadableRoots = fx.reloadableRoots.map { it.toString() },
            outputDir = tmp.resolve("out").toString(),
        )
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = fx.classLoader
        val outcome = try {
            EngineRunner.run(input)
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
        val aborted = assertInstanceOf<EngineRunner.Outcome.Aborted>(outcome)
        assertTrue(aborted.reason.contains("green baseline", ignoreCase = true), aborted.reason)
    }

    private inline fun <reified T> assertInstanceOf(value: Any?): T {
        assertNotNull(value)
        assertTrue(value is T) { "expected ${T::class.simpleName} but was ${value!!::class.simpleName}: $value" }
        return value as T
    }
}
