package io.komust.compiler

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import io.komust.runtime.MutantRegistry
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import java.io.File

/**
 * The `kotlin-compile-testing` harness for `komust-compiler-plugin`.
 *
 * Compiles a small Kotlin fixture in-process with the pinned K2 compiler (kctfork
 * resolves it off the test classpath, so it is exactly the catalog Kotlin
 * version), optionally with `komust-compiler-plugin` applied, and hands the test
 * a classloader over the emitted classes plus the parsed mutant set.
 *
 * The fixture's compile classpath carries this module's own `io.komust.runtime`
 * output so the woven `mutantActive` guard resolves, and the result classloader
 * is parented to the test's, so `MutantRegistry` flips the same slot the woven
 * code reads.
 */
object FixtureCompiler {

    /** This module's output dir / jar — where `io.komust.runtime` lives. */
    private val runtimeClasspath: File =
        File(MutantRegistry::class.java.protectionDomain.codeSource.location.toURI())

    /**
     * [scopeJson] — when non-null — is written to `scope.json` in the
     * compilation working dir and its path passed as the `scope` SubpluginOption,
     * exercising enclosing-symbol expansion (#30). [scopeOptionValue] passes a
     * raw option value verbatim (no file written) — for the missing-/invalid-path
     * cases. At most one of the two is set.
     */
    fun compile(
        fileName: String,
        source: String,
        withPlugin: Boolean = true,
        extraRegistrars: List<CompilerPluginRegistrar> = emptyList(),
        disabledOperators: List<String> = emptyList(),
        enabledOperators: List<String> = emptyList(),
        extraFiles: List<Pair<String, String>> = emptyList(),
        scopeJson: String? = null,
        scopeOptionValue: String? = null,
    ): Compiled {
        require(scopeJson == null || scopeOptionValue == null) {
            "pass at most one of scopeJson / scopeOptionValue"
        }
        val compilation = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin(fileName, source)) +
                extraFiles.map { (name, text) -> SourceFile.kotlin(name, text) }
            classpaths = listOf(runtimeClasspath)
            inheritClassPath = false
            jvmTarget = "21"
            messageOutputStream = System.out
            if (withPlugin) {
                // komust first, then any test-only inspector — registration order
                // is IR-extension order, so an inspector sees the woven tree.
                compilerPluginRegistrars = listOf(KomustCompilerPluginRegistrar()) + extraRegistrars
                commandLineProcessors = listOf(KomustCommandLineProcessor())
                val scopeValue = scopeOptionValue
                    ?: scopeJson?.let { workingDir.resolve("scope.json").apply { writeText(it) }.absolutePath }
                pluginOptions = buildList {
                    disabledOperators.forEach {
                        add(PluginOption(KomustCommandLineProcessor.PLUGIN_ID, "disabledOperators", it))
                    }
                    enabledOperators.forEach {
                        add(PluginOption(KomustCommandLineProcessor.PLUGIN_ID, "enabledOperators", it))
                    }
                    if (scopeValue != null) {
                        add(PluginOption(KomustCommandLineProcessor.PLUGIN_ID, "scope", scopeValue))
                    }
                }
            }
        }
        return Compiled(compilation.compile())
    }

    class Compiled(private val delegate: JvmCompilationResult) {
        val ok: Boolean get() = delegate.exitCode == KotlinCompilation.ExitCode.OK
        val messages: String get() = delegate.messages

        /** Every mutant the plugin reported it wove, in traversal order. */
        val mutants: List<Mutant> by lazy {
            MUTANT_LINE.findAll(messages).map { m ->
                Mutant(
                    id = m.groupValues[1],
                    operator = m.groupValues[2],
                    binaryClass = m.groupValues[3],
                    startOffset = m.groupValues[4].toInt(),
                    path = m.groupValues[5],
                    description = m.groupValues[6].trim(),
                )
            }.toList()
        }

        /** The count from the plugin's own summary line — cross-checks [mutants]. */
        val summaryCount: Int
            get() = SUMMARY_LINE.find(messages)?.groupValues?.get(1)?.toInt()
                ?: error("no komust summary line in:\n$messages")

        /** `"<binaryClass> L<line> <token> #<ordinal>"` for each mutant — the golden shape. */
        fun goldenSet(): Set<String> =
            mutants.map { "${it.binaryClass} L${it.line} ${it.token} #${it.ordinal}" }.toSet()

        /** Invoke a top-level function of the compiled fixture reflectively. */
        fun call(className: String, method: String, vararg args: Any?): Any? {
            check(ok) { "fixture compilation failed:\n$messages" }
            val clazz = delegate.classLoader.loadClass(className)
            val m = clazz.methods.single { it.name == method }
            return m.invoke(null, *args)
        }

        /**
         * Construct [className] with [ctorArgs] and invoke [method] on it. Picks
         * the single method named [method] and the single declared constructor.
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

    /** A parsed `komust-mutant …` diagnostic line. */
    data class Mutant(
        val id: String,
        val operator: String,
        val binaryClass: String,
        val startOffset: Int,
        val path: String,
        val description: String,
    ) {
        // id == <file>:<line>:<col>:<token>#<ordinal>
        private val parts = id.split(":")
        val fileName: String get() = parts[0]
        val line: Int get() = parts[1].toInt()
        val column: Int get() = parts[2].toInt()
        val token: String get() = parts[3].substringBefore("#")
        val ordinal: Int get() = parts[3].substringAfter("#").toInt()
    }

    private val MUTANT_LINE = Regex(
        """komust-mutant id=(\S+) op=(\S+) class=(\S+) startOffset=(\d+) path=(\S+) desc=(.*)""",
    )
    private val SUMMARY_LINE = Regex("""komust: woven (\d+) mutant\(s\) over module""")
}
