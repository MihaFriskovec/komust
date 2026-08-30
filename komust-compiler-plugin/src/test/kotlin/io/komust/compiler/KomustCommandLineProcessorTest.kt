package io.komust.compiler

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `the operator and scope options are declared`() {
        val names = KomustCommandLineProcessor().pluginOptions.map { it.optionName }.toSet()
        assertTrue("disabledOperators" in names, "expected disabledOperators option; got $names")
        assertTrue("enabledOperators" in names, "expected enabledOperators option; got $names")
        assertTrue("scope" in names, "expected scope option (#30); got $names")
    }

    @Test
    fun `every option is optional`() {
        // The Gradle plugin may or may not emit any given SubpluginOption.
        KomustCommandLineProcessor().pluginOptions.forEach {
            assertEquals(false, it.required, "${it.optionName} must be optional")
        }
    }

    @Test
    fun `operator options repeat, the scope option is single-valued`() {
        // One SubpluginOption per operator DSL entry; scope is one resolved path.
        val byName = KomustCommandLineProcessor().pluginOptions.associateBy { it.optionName }
        assertTrue(byName.getValue("disabledOperators").allowMultipleOccurrences)
        assertTrue(byName.getValue("enabledOperators").allowMultipleOccurrences)
        assertEquals(false, byName.getValue("scope").allowMultipleOccurrences)
    }

    @Test
    fun `a processed scope option lands in the compiler configuration`() {
        val processor = KomustCommandLineProcessor()
        val option = processor.pluginOptions.single { it.optionName == "scope" }
        val configuration = CompilerConfiguration()

        assertNull(configuration.get(KomustCommandLineProcessor.KEY_SCOPE_PATH))
        processor.processOption(option, "/tmp/scope.json", configuration)
        assertEquals("/tmp/scope.json", configuration.get(KomustCommandLineProcessor.KEY_SCOPE_PATH))
    }
}
