package io.komust.compiler

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.komust.runtime.MutantRegistry
import java.io.File

/**
 * The `kotlin-compile-testing` harness for `komust-compiler-plugin`.
 *
 * Compiles a small Kotlin fixture in-process with the pinned K2 compiler (kctfork
 * resolves it off the test classpath, so it is exactly the catalog Kotlin
 * version), optionally with `komust-compiler-plugin` applied, and hands the test
 * a classloader over the emitted classes plus the raw compiler messages.
 *
 * The fixture's compile classpath carries this module's own `io.komust.runtime`
 * output so a fixture can reference the woven guard once #28 starts injecting it.
 */
object FixtureCompiler {

    /** This module's output dir / jar — where `io.komust.runtime` lives. */
    private val runtimeClasspath: File =
        File(MutantRegistry::class.java.protectionDomain.codeSource.location.toURI())

    fun compile(
        fileName: String,
        source: String,
        withPlugin: Boolean = true,
    ): Compiled {
        val compilation = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin(fileName, source))
            classpaths = listOf(runtimeClasspath)
            inheritClassPath = false
            jvmTarget = "21"
            messageOutputStream = System.out
            if (withPlugin) {
                compilerPluginRegistrars = listOf(KomustCompilerPluginRegistrar())
                commandLineProcessors = listOf(KomustCommandLineProcessor())
            }
        }
        val result = compilation.compile()
        return Compiled(result)
    }

    class Compiled(private val delegate: JvmCompilationResult) {
        val ok: Boolean get() = delegate.exitCode == KotlinCompilation.ExitCode.OK
        val messages: String get() = delegate.messages

        /** Invoke a top-level function of the compiled fixture reflectively. */
        fun call(className: String, method: String, vararg args: Any?): Any? {
            check(ok) { "fixture compilation failed:\n$messages" }
            val clazz = delegate.classLoader.loadClass(className)
            val m = clazz.methods.single { it.name == method }
            return m.invoke(null, *args)
        }

        /**
         * Construct [className] with [ctorArgs] and invoke [method] on it. Picks
         * the single method named [method] and the single declared constructor —
         * enough for the small fixtures here.
         */
        fun callOn(className: String, ctorArgs: List<Any?>, method: String, vararg args: Any?): Any? {
            check(ok) { "fixture compilation failed:\n$messages" }
            val clazz = delegate.classLoader.loadClass(className)
            val instance = clazz.declaredConstructors.single().apply { isAccessible = true }
                .newInstance(*ctorArgs.toTypedArray())
            val m = clazz.methods.single { it.name == method }
            return m.invoke(instance, *args)
        }
    }
}
