package io.komust.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * The compat-shim seam.
 *
 * This is the **single place** `komust-compiler-plugin` touches the
 * Kotlin-version-specific K2 compiler / IR API for anything beyond declaring an
 * SPI entry point. Everything unstable that a Kotlin upgrade is likely to break
 * lives behind this object, so porting komust to a new Kotlin release is a diff
 * to one file rather than a scavenger hunt.
 *
 * Why this seam exists (see docs/adr/0005-gradle-plugin-architecture.md and the
 * catalog note in gradle/libs.versions.toml):
 *
 *  - The K2 compiler-plugin entry points are gated behind
 *    `@ExperimentalCompilerApi`, and IR construction behind
 *    `@UnsafeDuringIrConstructionAPI`. The Kotlin team explicitly does not keep
 *    these source- or binary-compatible across releases.
 *  - The #2 IR spike already hit concrete churn: value arguments moved onto the
 *    unified `IrMemberAccessExpression.arguments[]` index list — which is now
 *    1-to-1 with the callee's parameters (dispatch receiver, then extension
 *    receiver, then context parameters, then regular arguments, with **no**
 *    null placeholder for an absent receiver). Reading "the operand of `a + b`"
 *    is therefore a parameter-kind lookup, not a fixed index — exactly the kind
 *    of version-specific subtlety this seam is here to absorb.
 *
 * Rules for this file:
 *
 *  1. The Kotlin version is exact-pinned in the version catalog. Bumping it is a
 *     deliberate, reviewed change — and the review starts here.
 *  2. Outside this file, a class may import an `org.jetbrains.kotlin.*` symbol
 *     **only** to declare an SPI entry point it cannot exist without: the
 *     `CompilerPluginRegistrar` / `CommandLineProcessor` it subclasses, the
 *     `IrGenerationExtension` it implements, and the types in those overridden
 *     signatures (`CompilerConfiguration`, `IrModuleFragment`, `IrPluginContext`).
 *     Every other contact with the compiler / IR API — message reporting,
 *     extension registration, IR traversal, IR construction, symbol resolution —
 *     adds a narrow, intent-named helper here and calls that.
 *  3. Each helper is named for the komust-level intent ("the source line of an
 *     IR node"), not the current API shape, so callers survive a port.
 *
 * The seed helpers below are the source-location primitives every operator
 * needs, plus the plugin-load plumbing (#27). The operator-argument accessors
 * (reading and rewriting the operands of a binary call, receiver-kind aware)
 * land with the first operator, #28, which extends this object rather than
 * reaching around it.
 */
internal object KotlinIrCompat {

    // --- Plugin-load plumbing (#27) ------------------------------------------

    /**
     * The [PluginDiagnostics] sink for this compilation, drawn from the
     * compiler's own message collector (or a no-op if none was configured).
     */
    fun pluginDiagnostics(configuration: CompilerConfiguration): PluginDiagnostics =
        PluginDiagnostics(
            configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE),
        )

    /** Register [extension] as this compilation's IR generation pass. */
    fun registerIrExtension(
        storage: CompilerPluginRegistrar.ExtensionStorage,
        extension: IrGenerationExtension,
    ) {
        with(storage) { IrGenerationExtension.registerExtension(extension) }
    }

    // --- Source-location + IR-traversal primitives --------------------------

    /** 1-based `(line, column)` of [offset] within [file]. */
    fun lineColumn(file: IrFile, offset: Int): Pair<Int, Int> {
        val entry = file.fileEntry
        return (entry.getLineNumber(offset) + 1) to (entry.getColumnNumber(offset) + 1)
    }

    /** Source path backing [file], as the compiler reports it. */
    fun filePath(file: IrFile): String = file.fileEntry.name

    /** The compiled module's name as the compiler reports it (e.g. `<main>`). */
    fun moduleName(module: IrModuleFragment): String = module.name.asString()

    /** The IR files in [module], in compiler order. */
    fun filesOf(module: IrModuleFragment): List<IrFile> = module.files
}

/**
 * komust's compile-time diagnostic channel — a thin, port-stable wrapper over
 * the compiler's `MessageCollector` so no other file in the module names the
 * message-collector API or its severity enum.
 */
internal class PluginDiagnostics(private val collector: MessageCollector) {

    /** Informational progress note (shown with `-verbose`). */
    fun info(message: String) = collector.report(CompilerMessageSeverity.INFO, message)

    /** A recoverable problem the user should see (e.g. a misconfigured classpath). */
    fun warn(message: String) = collector.report(CompilerMessageSeverity.WARNING, message)
}
