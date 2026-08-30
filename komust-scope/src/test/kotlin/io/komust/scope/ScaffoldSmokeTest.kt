package io.komust.scope

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Scaffold guard: proves the shared `komust.kotlin-module` convention plugin
 * actually stands up a runnable JUnit Platform test task. Without a real test
 * somewhere, a broken `useJUnitPlatform()` wiring would silently report
 * NO-SOURCE on every module until the first feature ticket. Delete once
 * `komust-scope` has its own tests (#25).
 */
class ScaffoldSmokeTest {

    @Test
    fun `junit platform executes tests`() {
        assertEquals(4, 2 + 2)
    }
}
