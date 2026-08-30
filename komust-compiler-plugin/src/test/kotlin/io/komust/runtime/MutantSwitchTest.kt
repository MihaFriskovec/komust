package io.komust.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The runtime switch, exercised through its public boundary — [MutantRegistry]
 * and the woven guard [mutantActive]. These are the operations the execution
 * engine and the compiler-injected code will use unchanged.
 */
class MutantSwitchTest {

    @AfterEach
    fun restoreDefaults() {
        MutantRegistry.resetSwitch()
        MutantRegistry.clear()
    }

    @Test
    fun `baseline is no mutant active`() {
        assertNull(MutantRegistry.current())
        assertFalse(mutantActive("komust:anything"))
    }

    @Test
    fun `activate switches exactly one mutant on, and the woven guard sees it`() {
        MutantRegistry.activate("komust:abc123")

        assertEquals("komust:abc123", MutantRegistry.current())
        assertTrue(mutantActive("komust:abc123"))
        assertFalse(mutantActive("komust:other"), "only the activated id is active")
    }

    @Test
    fun `clear and activate(null) both return to the green baseline`() {
        MutantRegistry.activate("komust:abc123")
        MutantRegistry.clear()
        assertNull(MutantRegistry.current())

        MutantRegistry.activate("komust:abc123")
        MutantRegistry.activate(null)
        assertNull(MutantRegistry.current())
    }

    @Test
    fun `the active slot is process-global, not thread-scoped`() {
        // ADR-0003: the spike's thread-local slot silently ran the original on
        // any thread the code under test spawned. A process-global slot fires
        // the mutation wherever the code actually runs.
        MutantRegistry.activate("komust:crossthread")

        val seenOnOtherThread = AtomicReference<String?>("<unset>")
        val started = CountDownLatch(1)
        val t = Thread {
            seenOnOtherThread.set(MutantRegistry.current())
            started.countDown()
        }
        t.start()
        assertTrue(started.await(5, TimeUnit.SECONDS), "worker thread did not run")
        t.join()

        assertEquals("komust:crossthread", seenOnOtherThread.get())
        assertTrue(mutantActive("komust:crossthread"))
    }

    @Test
    fun `the default switch is the process-global volatile slot`() {
        assertSame(ProcessGlobalMutantSwitch, MutantRegistry.switch())
    }

    @Test
    fun `installSwitch swaps the implementation behind the same registry API`() {
        // The documented seam: a future thread-scoped slot is installed here and
        // nothing else — no caller, and no woven guard — changes.
        val recording = RecordingSwitch()
        MutantRegistry.installSwitch(recording)

        MutantRegistry.activate("komust:viaSeam")
        assertEquals(listOf("komust:viaSeam"), recording.activations)
        assertEquals("komust:viaSeam", MutantRegistry.current())
        assertTrue(mutantActive("komust:viaSeam"))

        MutantRegistry.resetSwitch()
        assertSame(ProcessGlobalMutantSwitch, MutantRegistry.switch())
    }

    private class RecordingSwitch : MutantSwitch {
        val activations = mutableListOf<String?>()
        private var active: String? = null

        override fun activeMutantId(): String? = active

        override fun activate(id: String?) {
            activations += id
            active = id
        }
    }
}
