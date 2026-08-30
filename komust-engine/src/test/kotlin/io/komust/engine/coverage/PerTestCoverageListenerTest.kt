package io.komust.engine.coverage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the listener through a real (tiny) JUnit Platform run with a fake
 * agent, to pin the one-record-per-leaf-test contract, the distinct per-test
 * slices, `@BeforeAll` setup inheritance, and the outcome mapping the fixture
 * end-to-end test does not separately exercise (notably ABORTED).
 */
class PerTestCoverageListenerTest {

    /** Hands out a uniquely tagged snapshot on every capture. */
    private class FakeAgent : CoverageAgent {
        val captures = AtomicInteger()
        override fun captureAndReset(): ByteArray = "snap-${captures.incrementAndGet()}".toByteArray()
    }

    private fun run(vararg classes: Class<*>): Pair<PerTestCoverageListener, FakeAgent> {
        val agent = FakeAgent()
        val listener = PerTestCoverageListener(agent)
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(classes.map { selectClass(it) })
            .build()
        LauncherFactory.create().execute(request, listener)
        return listener to agent
    }

    @Test
    fun `one record per leaf test, each with a distinct own-slice`() {
        val (listener, _) = run(PassingCases::class.java)

        val chunks = listener.perTestCoverageChunks()
        assertEquals(2, chunks.size)
        chunks.values.forEach { assertTrue(it.isNotEmpty()) }

        // The first chunk of each test is that test's own slice — distinct.
        val ownSlices = chunks.values.map { String(it.first()) }.toSet()
        assertEquals(2, ownSlices.size)
    }

    @Test
    fun `records timing and maps outcomes including failure and abort`() {
        val (listener, _) = run(MixedOutcomeCases::class.java)

        val byName = listener.executions().associateBy { it.displayName.substringBefore("(") }
        assertEquals(TestOutcome.PASSED, byName["passes"]!!.outcome)
        assertEquals(TestOutcome.FAILED, byName["fails"]!!.outcome)
        assertEquals(TestOutcome.ABORTED, byName["aborts"]!!.outcome)
        listener.executions().forEach { assertTrue(it.duration.inWholeNanoseconds >= 0) }
    }

    @Test
    fun `every leaf test inherits its container's setup slice`() {
        val (listener, _) = run(SetupInheritingCases::class.java)

        val chunks = listener.perTestCoverageChunks()
        assertEquals(2, chunks.size)
        // Each test carries more than just its own slice — the container setup
        // buffer (where a @BeforeAll would land) is merged in.
        chunks.values.forEach { assertTrue(it.size >= 2, "expected own slice + inherited setup, got $it") }
    }
}

// --- fixtures ---
// Tagged `coverage-fixture` and excluded from this module's own test run
// (build.gradle.kts); the tests above discover them explicitly by class.

@org.junit.jupiter.api.Tag("coverage-fixture")
class PassingCases {
    @Test fun one() = Unit
    @Test fun two() = Unit
}

@org.junit.jupiter.api.Tag("coverage-fixture")
class MixedOutcomeCases {
    @Test fun passes() = Unit

    @Test fun fails() {
        throw AssertionError("boom")
    }

    @Test fun aborts() {
        org.junit.jupiter.api.Assumptions.assumeTrue(false)
    }
}

@org.junit.jupiter.api.Tag("coverage-fixture")
class SetupInheritingCases {
    @Test fun a() = Unit
    @Test fun b() = Unit
}
