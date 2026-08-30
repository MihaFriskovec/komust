package io.komust.engine.sweep

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TimeoutPolicyTest {

    private val policy = TimeoutPolicy(baseConstant = 3.seconds, factor = 3.0, ceiling = 30.seconds)

    @Test
    fun `budget is base plus factor times the baseline time`() {
        assertEquals(3.seconds + 600.milliseconds, policy.budgetFor(200.milliseconds))
        assertEquals(3.seconds + 30.seconds, policy.budgetFor(10.seconds))
    }

    @Test
    fun `a fast test still gets at least the base constant`() {
        assertEquals(3.seconds, policy.budgetFor(kotlin.time.Duration.ZERO))
    }

    @Test
    fun `no recorded baseline time falls back to the ceiling`() {
        assertEquals(30.seconds, policy.budgetFor(null))
    }

    @Test
    fun `rejects a negative or non-finite factor`() {
        assertThrows<IllegalArgumentException> { TimeoutPolicy(factor = -1.0) }
        assertThrows<IllegalArgumentException> { TimeoutPolicy(factor = Double.NaN) }
    }

    @Test
    fun `SweepConfig rejects a zero worker count`() {
        assertThrows<IllegalArgumentException> { SweepConfig(workerCount = 0) }
    }

    @Test
    fun `SweepConfig default worker count matches the runtime processor count`() {
        assertEquals(Runtime.getRuntime().availableProcessors(), SweepConfig().workerCount)
    }
}
