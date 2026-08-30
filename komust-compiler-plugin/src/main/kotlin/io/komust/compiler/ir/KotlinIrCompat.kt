package io.komust.compiler.ir

import io.komust.compiler.ArithmeticMutation
import io.komust.compiler.WovenMutant
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

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
 * The first operator (#28) lives here as [weaveArithmeticOperators] and its
 * private [ArithmeticWeaver] — the traversal, the operand-kind lookup and the
 * runtime-switched `if/else` construction are all IR-API contact, so they sit
 * inside the seam and [io.komust.compiler.KomustIrGenerationExtension] only
 * orchestrates.
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

    // --- The first operator: arithmetic weaving (#28) ----------------------

    /**
     * Weave every in-module arithmetic site komust mutates ([ArithmeticMutation])
     * into a **runtime-switched `if/else`** — both the original and the mutant in
     * one compilation, selected at run time by [io.komust.runtime.mutantActive].
     *
     * Returns the [WovenMutant]s injected, in traversal order. If the runtime
     * guard is not on the compilation classpath nothing is woven and a warning
     * is reported (the guard ships in `komust-compiler-plugin`; a misconfigured
     * mutation compilation is the only way it goes missing).
     */
    fun weaveArithmeticOperators(
        module: IrModuleFragment,
        pluginContext: IrPluginContext,
        diagnostics: PluginDiagnostics,
    ): List<WovenMutant> {
        val mutantActive = pluginContext
            .referenceFunctions(CallableId(RUNTIME_PACKAGE, Name.identifier("mutantActive")))
            .singleOrNull()
        if (mutantActive == null) {
            diagnostics.warn(
                "komust: io.komust.runtime.mutantActive() is not on the compilation classpath — " +
                    "weaving nothing. The runtime guard ships in komust-compiler-plugin; check the " +
                    "mutation compilation's classpath.",
            )
            return emptyList()
        }

        val weaver = ArithmeticWeaver(pluginContext, diagnostics, mutantActive)
        module.transformChildrenVoid(weaver)
        return weaver.woven
    }

    private val KOTLIN_PACKAGE = FqName("kotlin")
    private val RUNTIME_PACKAGE = FqName("io.komust.runtime")

    /** The primitive numeric receiver types komust's arithmetic operator mutates. */
    private val NUMERIC_PRIMITIVES = setOf("Int", "Long", "Short", "Byte", "Double", "Float")

    /**
     * Rewrites each arithmetic site into `if (mutantActive("<id>")) <mutant> else
     * <original>`, both operands spilled to temporaries so each branch reads them
     * once through a fresh `irGet` (an IR node has exactly one parent — operands
     * cannot be shared across branches).
     *
     * The builder is anchored at the site's original `startOffset` / `endOffset`,
     * so the injected `if/else` reports the same source position as the operator
     * it replaces (ADR-0003's stack-trace-fidelity concern); a mismatch is
     * warned about, not fatal.
     *
     * ## What v1 deliberately does not weave
     *
     * Only bodies of **user-written top-level and member functions** are mutated
     * (`origin == DEFINED`, not a property accessor). Compiler-generated members
     * (a `data class`'s `hashCode`/`equals`/`componentN`/`copy`), property
     * getters and setters, and — for now — lambda and local-function bodies are
     * left alone. The first three are ADR-0001 skip-list items (mutating a
     * synthetic `result * 31 + h` yields a guaranteed equivalent mutant); the
     * last is because a non-inline lambda body is not yet lifted into its
     * synthetic class here, so its binary name cannot be resolved — deferred
     * with the inline-function / coroutine work in the map fog.
     */
    private class ArithmeticWeaver(
        private val pluginContext: IrPluginContext,
        private val diagnostics: PluginDiagnostics,
        private val mutantActive: IrSimpleFunctionSymbol,
    ) : IrElementTransformerVoidWithContext() {

        val woven = mutableListOf<WovenMutant>()

        /**
         * Per-`(line, col, operator)` counter within the current file — the
         * ordinal that disambiguates a shared position (`a + b + c`). Reset in
         * [visitFileNew] so two files with the same basename never entangle
         * their ordinals.
         */
        private val ordinals = HashMap<String, Int>()

        override fun visitFileNew(declaration: IrFile): IrFile {
            ordinals.clear()
            return super.visitFileNew(declaration)
        }

        override fun visitCall(expression: IrCall): IrExpression {
            // Post-order: recurse first so `a + b + c` mutates every operator.
            val visited = super.visitCall(expression)
            if (visited !is IrCall) return visited

            val callee = visited.symbol.owner
            val mutation = ArithmeticMutation.forCallee(callee.name.asString()) ?: return visited

            val receiverClassId = callee.parentClassOrNull?.classId ?: return visited
            if (receiverClassId.packageFqName != KOTLIN_PACKAGE) return visited
            if (receiverClassId.shortClassName.asString() !in NUMERIC_PRIMITIVES) return visited
            if (inSkippedDeclaration()) return visited

            val dispatchIndex = callee.parameters.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }
            val operandIndex = callee.parameters.indexOfFirst { it.kind == IrParameterKind.Regular }
            if (dispatchIndex < 0 || operandIndex < 0) return visited
            if (callee.parameters.count { it.kind == IrParameterKind.Regular } != 1) return visited

            val receiverExpr = visited.arguments.getOrNull(dispatchIndex) ?: return visited
            val operandExpr = visited.arguments.getOrNull(operandIndex) ?: return visited

            val replacement = resolveReplacement(receiverClassId, mutation, callee)
            if (replacement == null) {
                diagnostics.warn(
                    "komust: no ${receiverClassId.shortClassName}.${mutation.replacement} counterpart for " +
                        "${callee.name} — leaving the site unmutated.",
                )
                return visited
            }

            val file = currentFile
            val path = filePath(file)
            val (line, column) = lineColumn(file, visited.startOffset)
            val fileName = path.substringAfterLast('/')
            val positionKey = "$line:$column:${mutation.token}"
            val ordinal = ordinals.merge(positionKey, 0) { existing, _ -> existing + 1 }!!

            val mutant = WovenMutant(
                filePath = path,
                fileName = fileName,
                line = line,
                column = column,
                mutation = mutation,
                ordinal = ordinal,
                binaryClassName = binaryClassName(enclosingClass(), file),
                startOffset = visited.startOffset,
            )

            val builder = DeclarationIrBuilder(
                pluginContext,
                currentScope!!.scope.scopeOwnerSymbol,
                visited.startOffset,
                visited.endOffset,
            )
            val switched = builder.irBlock(resultType = visited.type) {
                val lhs = irTemporary(receiverExpr)
                val rhs = irTemporary(operandExpr)
                +irIfThenElse(
                    type = visited.type,
                    condition = irCall(mutantActive).apply { arguments[0] = irString(mutant.id) },
                    thenPart = irCall(replacement).apply {
                        arguments[dispatchIndex] = irGet(lhs)
                        arguments[operandIndex] = irGet(rhs)
                    },
                    elsePart = irCall(visited.symbol).apply {
                        arguments[dispatchIndex] = irGet(lhs)
                        arguments[operandIndex] = irGet(rhs)
                    },
                )
            }

            if (switched.startOffset != visited.startOffset) {
                // Not fatal — the woven code is correct either way; only a raw
                // stack trace through this site would point a line off. Warn so a
                // Kotlin bump that breaks offset propagation is noticed.
                diagnostics.warn(
                    "komust: the injected if/else for ${mutant.id} did not keep the site's startOffset " +
                        "(${switched.startOffset} != ${visited.startOffset}) — stack traces here may be off by a line.",
                )
            }

            woven += mutant
            return switched
        }

        /**
         * Whether the current site sits in a declaration komust does not mutate:
         * a compiler-generated member (`data class` `hashCode` etc.), a property
         * accessor, or a lambda / local function (ADR-0001 skip-list plus the
         * unresolved-binary-name limitation — see the class doc).
         */
        private fun inSkippedDeclaration(): Boolean {
            val function = allScopes.asReversed()
                .firstNotNullOfOrNull { it.irElement as? IrFunction }
                ?: return false
            if (function.origin != IrDeclarationOrigin.DEFINED) return true
            return (function as? IrSimpleFunction)?.correspondingPropertySymbol != null
        }

        /** The counterpart operator function (`Int.plus` → `Int.minus`) with the same operand type. */
        private fun resolveReplacement(
            receiverClassId: ClassId,
            mutation: ArithmeticMutation,
            original: IrSimpleFunction,
        ): IrSimpleFunctionSymbol? {
            val originalOperandTypes = original.parameters
                .filter { it.kind == IrParameterKind.Regular }
                .map { it.type.classFqName }
            return pluginContext
                .referenceFunctions(CallableId(receiverClassId, Name.identifier(mutation.replacement)))
                .singleOrNull { candidate ->
                    val params = candidate.owner.parameters
                    params.count { it.kind == IrParameterKind.DispatchReceiver } == 1 &&
                        params.filter { it.kind == IrParameterKind.Regular }.map { it.type.classFqName } ==
                        originalOperandTypes
                }
        }

        private fun enclosingClass(): IrClass? =
            allScopes.asReversed().firstNotNullOfOrNull { it.irElement as? IrClass }

        /**
         * The JVM binary name of the site's enclosing class, or the file-facade
         * class (`Golden.kt` → `<pkg>.GoldenKt`) for a top-level declaration.
         * Nested classes are joined with `$`, matching the runtime class name the
         * coverage index (ADR-0004) keys on.
         *
         * v1 covers the shapes v1 operators reach: regular top-level, member and
         * nested-class declarations. `@file:JvmName` / `@JvmMultifileClass`
         * facades are not yet honoured; lambda and local-function bodies are not
         * woven at all ([inSkippedDeclaration]), so their synthetic names never
         * need resolving here — both deferred with the inline-function and
         * coroutine work in the map fog.
         */
        private fun binaryClassName(enclosingClass: IrClass?, file: IrFile): String {
            val packageName = file.packageFqName.asString()
            val simpleName = if (enclosingClass == null) {
                PackagePartClassUtils.getFilePartShortName(file.fileEntry.name.substringAfterLast('/'))
            } else {
                generateSequence(enclosingClass) { it.parentClassOrNull }
                    .map { it.name.asString() }
                    .toList()
                    .asReversed()
                    .joinToString("$")
            }
            return if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"
        }
    }
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
