package io.komust.engine.coverage

/**
 * Base type for every way the coverage pass can refuse to produce a usable
 * [CoveragePassResult]. All are terminal: the mutation run aborts.
 */
public sealed class CoveragePassException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * The mandatory **green baseline** was violated: one or more tests failed or
 * erupted while the program was **unmutated** (ADR-0003, ADR-0004). A mutant
 * kill cannot be attributed against an already-red suite, so the run aborts.
 *
 * [failures] names the offending tests (display name + failure message) so the
 * caller can print an actionable error without re-running anything.
 */
public class RedBaselineException(
    public val failures: List<Failure>,
    public val containersFailed: Int,
) : CoveragePassException(render(failures, containersFailed)) {

    public data class Failure(val displayName: String, val message: String?)

    private companion object {
        fun render(failures: List<Failure>, containersFailed: Int): String = buildString {
            append("green baseline failed: ")
            append(failures.size)
            append(" test(s)")
            if (containersFailed > 0) append(" and $containersFailed container(s)")
            append(" failed on the unmutated program. ")
            append("komust needs a green suite to attribute kills — fix these first:")
            failures.take(20).forEach { f ->
                append("\n  - ")
                append(f.displayName)
                f.message?.let { append(": ").append(it.lineSequence().first().take(200)) }
            }
            if (failures.size > 20) append("\n  ... and ${failures.size - 20} more")
        }
    }
}

/**
 * The test classpath roots produced no discoverable tests. A mutation run with
 * nothing to run every mutant against is almost certainly a wiring mistake
 * (wrong roots, missing test engine), so the pass fails loudly rather than
 * reporting every mutant as `NO_COVERAGE`.
 */
public class EmptyTestSuiteException(roots: Collection<Any>) : CoveragePassException(
    "no tests were discovered on the test classpath roots: $roots — " +
        "check the roots point at compiled test classes and a JUnit Platform engine is present",
)

/**
 * The JaCoCo runtime agent is not attached to this JVM, so per-test coverage
 * cannot be captured. The engine JVM must be launched with
 * `-javaagent:jacocoagent.jar` (the Gradle plugin, #38, is responsible for
 * this).
 */
public class CoverageAgentUnavailableException(cause: Throwable?) : CoveragePassException(
    "the JaCoCo runtime agent is not attached to this JVM — the coverage pass needs " +
        "`-javaagent:jacocoagent.jar` on the engine process (org.jacoco.agent.rt.RT.getAgent() failed)",
    cause,
)
