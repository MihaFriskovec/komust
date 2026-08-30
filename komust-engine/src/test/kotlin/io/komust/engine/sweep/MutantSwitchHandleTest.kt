package io.komust.engine.sweep

import io.komust.runtime.MutantRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * [MutantSwitchHandle.processGlobal] reaches `io.komust.runtime.MutantRegistry`
 * reflectively through a class loader and flips the same process-global slot the
 * woven guard reads.
 */
class MutantSwitchHandleTest {

    @AfterEach
    fun reset() {
        MutantRegistry.clear()
        MutantRegistry.resetSwitch()
    }

    @Test
    fun `activate and clear flip the real process-global slot`() {
        val handle = MutantSwitchHandle.processGlobal(javaClass.classLoader)

        handle.activate("Calc.kt:4:40:ARITH_PLUS_TO_MINUS#0")
        assertEquals("Calc.kt:4:40:ARITH_PLUS_TO_MINUS#0", MutantRegistry.current())

        handle.clear()
        assertNull(MutantRegistry.current())
    }

    @Test
    fun `a class loader without the runtime fails loudly`() {
        // A parent-less loader that cannot see io.komust.runtime.
        val isolated = java.net.URLClassLoader(emptyArray(), null)
        val ex = assertThrows(IllegalStateException::class.java) {
            MutantSwitchHandle.processGlobal(isolated)
        }
        assertEquals(true, ex.message!!.contains("MutantRegistry"))
    }
}
