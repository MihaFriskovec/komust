package io.komust.demo

import io.komust.runtime.MutantRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PROTOTYPE — the whole point of the spike, proven as an assertion:
 * the SAME compiled `add`/`sumThree` behave as original OR mutant depending only
 * on the runtime switch. No recompile between the two states.
 */
class MutationSwitchTest {

    @AfterTest fun reset() = MutantRegistry.clear()

    @Test
    fun baseline_is_original_addition() {
        MutantRegistry.clear()
        assertEquals(5, add(2, 3))          // 2 + 3
        assertEquals(6, sumThree(1, 2, 3))  // 1 + 2 + 3
    }

    @Test
    fun activating_a_single_mutant_flips_one_operator() {
        // The id is emitted by the plugin as a compile warning; discover it here
        // by scanning the demo's mutant table (printed at build time). For the
        // spike we activate whichever plus-mutant the plugin found in Calc.kt.
        val mutants = discoverMutantIds()
        require(mutants.isNotEmpty()) { "no mutants were injected — plugin did not run" }

        // Activate the first mutant (the `+` in `add`).
        MutantRegistry.activate(mutants.first())
        // add's + became - :  2 - 3 = -1
        assertEquals(-1, add(2, 3))
    }

    @Test
    fun mutants_are_thread_isolated() {
        val mutants = discoverMutantIds()
        MutantRegistry.activate(mutants.first())
        val onOtherThread = run {
            var r = 0
            val t = Thread { r = add(2, 3) }  // no mutant active on this thread
            t.start(); t.join()
            r
        }
        assertEquals(5, onOtherThread, "other thread must see the original")
        assertEquals(-1, add(2, 3), "this thread still sees the mutant")
    }

    /**
     * The plugin records ids as compile warnings; the runtime has no registry of
     * them. For the spike we reconstruct the ids deterministically from the known
     * source positions the plugin uses (file:line:col). See mutants.txt captured
     * at build time for the authoritative list.
     */
    private fun discoverMutantIds(): List<String> =
        MutantIds.all
}

/** Filled in from the build-time warning output — see run-log.txt. */
object MutantIds {
    val all: List<String> = MutantIdsGenerated.ids
}
