package io.komust.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomustCommandLineProcessorTest {

    @Test
    fun `plugin id is the namespace SubpluginOptions will be addressed under`() {
        // Must match the SubpluginOption keys the Gradle plugin (#38) emits and
        // the -P plugin:<id>:<key> args the compiler routes to this processor.
        assertEquals("io.komust.compiler", KomustCommandLineProcessor().pluginId)
        assertEquals(KomustCommandLineProcessor.PLUGIN_ID, KomustCommandLineProcessor().pluginId)
    }

    @Test
    fun `the enabled and disabled operator options are declared`() {
        val names = KomustCommandLineProcessor().pluginOptions.map { it.optionName }.toSet()
        assertTrue("disabledOperators" in names, "expected disabledOperators option; got $names")
        assertTrue("enabledOperators" in names, "expected enabledOperators option; got $names")
    }

    @Test
    fun `all options are optional and repeatable`() {
        // The Gradle plugin may emit one SubpluginOption per DSL entry.
        KomustCommandLineProcessor().pluginOptions.forEach {
            assertEquals(false, it.required, "${it.optionName} must be optional")
            assertTrue(it.allowMultipleOccurrences, "${it.optionName} must allow multiple occurrences")
        }
    }
}
