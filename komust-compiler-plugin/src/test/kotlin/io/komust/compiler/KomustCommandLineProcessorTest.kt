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
    fun `no options are declared yet`() {
        // #30 (scope.json path) and #29 (enabled operators) add the first ones.
        assertTrue(KomustCommandLineProcessor().pluginOptions.isEmpty())
    }
}
