package io.komust.compiler

import io.komust.compiler.ir.KotlinIrCompat
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `the scope option is declared (#30)`() {
        // #29 adds the enabled-operators option alongside it.
        val options = KomustCommandLineProcessor().pluginOptions
        val scope = options.singleOrNull { it.optionName == "scope" }
        assertTrue(scope != null, "expected a 'scope' option, got ${options.map { it.optionName }}")
        assertTrue(!scope!!.required, "scope must be optional — an --all run passes no scope.json")
    }

    @Test
    fun `a processed scope option lands in the compiler configuration`() {
        val processor = KomustCommandLineProcessor()
        val option = processor.pluginOptions.single { it.optionName == "scope" }
        val configuration = CompilerConfiguration()

        assertNull(KotlinIrCompat.configuredScopePath(configuration))
        processor.processOption(option, "/tmp/scope.json", configuration)
        assertEquals("/tmp/scope.json", KotlinIrCompat.configuredScopePath(configuration))
    }

    @Test
    fun `an unknown option name is rejected`() {
        val processor = KomustCommandLineProcessor()
        val bogus = object : org.jetbrains.kotlin.compiler.plugin.AbstractCliOption {
            override val optionName = "not-a-komust-option"
            override val valueDescription = ""
            override val description = ""
            override val required = false
            override val allowMultipleOccurrences = false
        }
        assertThrows(RuntimeException::class.java) {
            processor.processOption(bogus, "x", CompilerConfiguration())
        }
    }
}
