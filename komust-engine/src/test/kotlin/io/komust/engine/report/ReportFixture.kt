package io.komust.engine.report

import io.komust.engine.coverage.TestId
import io.komust.engine.sweep.MutantResult
import io.komust.engine.sweep.MutantStatus as SweepStatus
import io.komust.engine.sweep.SweepResult
import java.time.Instant

/**
 * A small, fully-deterministic run used across the output-contract tests and as
 * the source for the golden files.
 *
 * Four mutants over two files, deliberately declared **out of `(path, line)`
 * order** so the `(path, startLine, id)` sort is actually exercised:
 *
 *  - `Calc.kt:6`  `*` → `/`   — SURVIVED (one covering test, passes)
 *  - `Calc.kt:4`  `+` → `-`   — KILLED   (two covering tests, first kills)
 *  - `Bar.kt:10`  `<` → `<=`  — NO_COVERAGE
 *  - `Calc.kt:4`  `+` → `+ 1` — SURVIVED (same line as the kill, different id)
 */
object ReportFixture {

    val startedAt: Instant = Instant.parse("2026-08-30T10:00:00Z")
    val finishedAt: Instant = Instant.parse("2026-08-30T10:00:42Z")
    const val KOMUST_VERSION = "0.1.0-SNAPSHOT"

    private val calcAddFast = TestId("[engine:junit-jupiter]/[class:fixture.CalcTest]/[method:addExact()]")
    private val calcAddLoose = TestId("[engine:junit-jupiter]/[class:fixture.CalcTest]/[method:addLoose()]")
    private val calcMulLoose = TestId("[engine:junit-jupiter]/[class:fixture.CalcTest]/[method:mulLoose()]")

    private fun loc(path: String, line: Int) = SourceLocation(path, line, line)

    private val mulToDiv = MutantDescriptor(
        id = "Calc.kt:6:40:ARITH_TIMES_TO_DIV#0",
        location = loc("src/main/kotlin/fixture/Calc.kt", 6),
        operator = "arithmetic",
        original = "*",
        mutated = "/",
        enclosingSymbol = "Calc.mul",
        binaryClassName = "fixture.Calc",
    )
    private val plusToMinus = MutantDescriptor(
        id = "Calc.kt:4:40:ARITH_PLUS_TO_MINUS#0",
        location = loc("src/main/kotlin/fixture/Calc.kt", 4),
        operator = "arithmetic",
        original = "+",
        mutated = "-",
        enclosingSymbol = "Calc.add",
        binaryClassName = "fixture.Calc",
    )
    private val ltToLe = MutantDescriptor(
        id = "Bar.kt:10:12:REL_LT_TO_LE#0",
        location = loc("src/main/kotlin/fixture/Bar.kt", 10),
        operator = "relational",
        original = "<",
        mutated = "<=",
        enclosingSymbol = "Bar.clamp",
        binaryClassName = "fixture.Bar",
    )
    private val plusBoundary = MutantDescriptor(
        id = "Calc.kt:4:40:CONST_BOUNDARY_ADD_ONE#0",
        location = loc("src/main/kotlin/fixture/Calc.kt", 4),
        operator = "constant-boundary",
        original = "a + b",
        mutated = "a + b + 1",
        enclosingSymbol = "Calc.add",
        binaryClassName = "fixture.Calc",
    )

    val descriptors: List<MutantDescriptor> = listOf(mulToDiv, plusToMinus, ltToLe, plusBoundary)

    val sweep: SweepResult = SweepResult(
        listOf(
            MutantResult(
                mutant = mulToDiv.toMutant(),
                status = SweepStatus.SURVIVED,
                coveringTests = listOf(calcMulLoose),
                killedBy = null,
                testsExecuted = 1,
            ),
            MutantResult(
                mutant = plusToMinus.toMutant(),
                status = SweepStatus.KILLED,
                coveringTests = listOf(calcAddFast, calcAddLoose),
                killedBy = calcAddFast,
                testsExecuted = 1,
            ),
            MutantResult(
                mutant = ltToLe.toMutant(),
                status = SweepStatus.NO_COVERAGE,
                coveringTests = emptyList(),
                killedBy = null,
                testsExecuted = 0,
            ),
            MutantResult(
                mutant = plusBoundary.toMutant(),
                status = SweepStatus.SURVIVED,
                coveringTests = listOf(calcAddFast, calcAddLoose),
                killedBy = null,
                testsExecuted = 2,
            ),
        ),
    )

    fun runInfo() = ReportBuilder.RunInfo(startedAt, finishedAt, KOMUST_VERSION)

    fun report(): Report = ReportBuilder.build(descriptors, sweep, runInfo())
}
