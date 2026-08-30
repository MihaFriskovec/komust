package io.komust.compiler.ir

import io.komust.compiler.MutationOperatorId
import io.komust.compiler.OperatorConfig
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
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObjectValue
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isByte
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isFloat
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isShort
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import java.io.File

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
 *    unified `IrMemberAccessExpression.arguments[]` index list — 1-to-1 with the
 *    callee's parameters (dispatch receiver, then extension receiver, then
 *    context parameters, then regular arguments), with **no** null placeholder
 *    for an absent receiver. Reading "the operand of `a + b`" is a
 *    parameter-kind lookup, not a fixed index.
 *
 * Rules for this file:
 *
 *  1. The Kotlin version is exact-pinned in the version catalog. Bumping it is a
 *     deliberate, reviewed change — and the review starts here.
 *  2. Outside this file, a class may import an `org.jetbrains.kotlin.*` symbol
 *     **only** to declare an SPI entry point it cannot exist without: the
 *     `CompilerPluginRegistrar` / `CommandLineProcessor` it subclasses, the
 *     `IrGenerationExtension` it implements, and the types in those overridden
 *     signatures. Every other contact with the compiler / IR API adds a narrow,
 *     intent-named helper here and calls that.
 *  3. Each helper is named for the komust-level intent, not the current API
 *     shape, so callers survive a port.
 *
 * The full default operator catalog (ADR-0001, #29) lives here as
 * [weaveMutations] and its private [MutationWeaver]: the traversal, the
 * operand-kind lookups, the runtime-switched `if/else` construction and the
 * skip-list guards are all IR-API contact, so they sit inside the seam and
 * [io.komust.compiler.KomustIrGenerationExtension] only orchestrates.
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

    // --- Source-location primitives ----------------------------------------

    /** 1-based `(line, column)` of [offset] within [file]. */
    fun lineColumn(file: IrFile, offset: Int): Pair<Int, Int> {
        val entry = file.fileEntry
        return (entry.getLineNumber(offset) + 1) to (entry.getColumnNumber(offset) + 1)
    }

    /** Source path backing [file], as the compiler reports it. */
    fun filePath(file: IrFile): String = file.fileEntry.name

    /** The compiled module's name as the compiler reports it (e.g. `<main>`). */
    fun moduleName(module: IrModuleFragment): String = module.name.asString()

    // --- The catalog weave (#29) -----------------------------------------

    /**
     * Weave every in-module mutation site the enabled [config] operators reach
     * into a **runtime-switched `if/else`** — both the original and the mutant
     * in one compilation, selected at run time by
     * [io.komust.runtime.mutantActive]. One site can carry several mutants
     * (a relational `<` weaves both a boundary shift and a flip); each gets its
     * own guard, nested so the original is the final `else`.
     *
     * Returns the [WovenMutant]s injected, in traversal order. If the runtime
     * guard is not on the compilation classpath nothing is woven and a warning
     * is reported.
     */
    fun weaveMutations(
        module: IrModuleFragment,
        pluginContext: IrPluginContext,
        diagnostics: PluginDiagnostics,
        config: OperatorConfig,
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

        val weaver = MutationWeaver(pluginContext, diagnostics, config, mutantActive)
        module.transformChildrenVoid(weaver)
        // The guard temporaries (`%→/` / `*→/`) are fresh IrVariables; re-anchor
        // every declaration's parent so later lowerings (const evaluation) don't
        // trip over a temporary that still reads `<no parent>`.
        module.files.forEach { it.patchDeclarationParents() }
        return weaver.woven
    }

    private val KOTLIN_PACKAGE = FqName("kotlin")
    private val RUNTIME_PACKAGE = FqName("io.komust.runtime")
    private val SUPPRESS_MUTATIONS = FqName("io.komust.runtime.SuppressMutations")
    private const val IGNORE_MARKER = "komust:ignore"

    /** The primitive numeric receiver types komust's arithmetic / increment operators mutate. */
    private val NUMERIC_PRIMITIVES = setOf("Int", "Long", "Short", "Byte", "Double", "Float")

    /** Integer types whose `/` throws on a zero divisor — the ones that need the `0/0` guard. */
    private val INTEGER_PRIMITIVES = setOf("Int", "Long", "Short", "Byte")

    /** Callees komust never removes as a void call (ADR-0001 skip-list). */
    private val SKIPPED_CALL_NAMES = setOf("TODO", "require", "check", "error", "assert")

    /**
     * A single mutant the weaver could inject at the site under the cursor:
     * [operator] and its stable [token] / human [description], plus a [build]
     * that produces the mutant expression fresh (no IR node is shared with the
     * original, which stays intact in the switch's `else`).
     */
    private class Candidate(
        val operator: MutationOperatorId,
        val token: String,
        val description: String,
        val build: DeclarationIrBuilder.() -> IrExpression,
    )

    /**
     * Rewrites each mutation site into
     * `if (mutantActive("<id>")) <mutant> else <original>`, the original node
     * kept verbatim in the `else` and each mutant built from fresh copies of the
     * sub-expressions it needs. Because exactly one branch runs, no
     * sub-expression is evaluated twice and short-circuit operators keep their
     * semantics — the one exception (`%→/` / `*→/` read the divisor twice, for
     * the `0/0` guard and the division) spills that divisor to a local.
     *
     * For **binary operators** the mutant branch rebuilds from a *pre-weaving*
     * snapshot of the operands ([rawArgs]), not from the already-woven operand
     * subtrees. The original (with its nested mutants) still sits in the `else`,
     * and komust activates one mutant at a time, so a mutant branch that skips
     * the nested switches is behaviourally exact — and keeps woven code linear
     * in expression-nesting depth instead of exponential.
     *
     * The builder is anchored at the site's original `startOffset` /
     * `endOffset`, so the injected `if/else` reports the same source position as
     * the construct it replaces (ADR-0003's stack-trace-fidelity concern).
     *
     * ## What v1 deliberately does not weave
     *
     * Only bodies of **user-written top-level and member declarations** are
     * mutated (`origin == DEFINED`, not a property accessor). Compiler-generated
     * members (a `data class`'s `hashCode`/`equals`/`componentN`/`copy`),
     * property getters and setters, and — for now — lambda and local-function
     * bodies are left alone.
     */
    private class MutationWeaver(
        private val pluginContext: IrPluginContext,
        private val diagnostics: PluginDiagnostics,
        private val config: OperatorConfig,
        private val mutantActive: IrSimpleFunctionSymbol,
    ) : IrElementTransformerVoidWithContext() {

        val woven = mutableListOf<WovenMutant>()

        private val irBuiltIns get() = pluginContext.irBuiltIns

        /** Per-`(line, col, token)` counter within the current file. */
        private val ordinals = HashMap<String, Int>()

        /** 1-based source lines carrying a `// komust:ignore` marker in the current file. */
        private var ignoredLines: Set<Int> = emptySet()

        /** Whether the current file carries `@file:SuppressMutations` (constant per file). */
        private var fileSuppressed: Boolean = false

        override fun visitFileNew(declaration: IrFile): IrFile {
            ordinals.clear()
            ignoredLines = readIgnoredLines(declaration)
            fileSuppressed = declaration.hasSuppressAnnotation()
            return super.visitFileNew(declaration)
        }

        // --- Node visits ------------------------------------------------

        /**
         * A pre-weaving snapshot of the current binary-operator call's arguments,
         * taken *before* `super.visitCall` recurses (and mutates the operand
         * subtrees in place). A mutant branch rebuilds from these raw copies
         * rather than deep-copying the already-woven operands — the original
         * (with its nested mutants) still sits in the switch's `else`, and komust
         * only ever activates one mutant at a time, so a mutant branch that skips
         * the nested switches is behaviourally exact and keeps woven code linear
         * in expression-nesting depth rather than exponential.
         */
        private var rawArgs: List<IrExpression?>? = null

        override fun visitCall(expression: IrCall): IrExpression {
            // Skip-list: `!!`, `require`/`check`/`error`/`TODO()`/`assert` — leave
            // the call *and everything inside its arguments* untouched (ADR-0001).
            if (isProtectedCall(expression)) return expression

            val savedRawArgs = rawArgs
            rawArgs = if (looksLikeBinaryOperator(expression)) {
                expression.arguments.map { it?.deepCopy() }
            } else {
                null
            }
            val visited = super.visitCall(expression)
            val siteRawArgs = rawArgs
            rawArgs = savedRawArgs

            if (visited !is IrCall || inSkippedDeclaration()) return visited

            val candidates = buildList {
                addAll(arithmeticCandidates(visited, siteRawArgs))
                addAll(relationalCandidates(visited, siteRawArgs))
                addAll(equalityCandidates(visited, siteRawArgs))
                addAll(booleanInversionCallCandidates(visited))
                addAll(incrementCandidates(visited))
                addAll(voidCallCandidates(visited))
            }
            return weaveSite(visited, visited.type, candidates)
        }

        private fun looksLikeBinaryOperator(call: IrCall): Boolean =
            call.symbol == irBuiltIns.eqeqSymbol ||
                call.symbol == irBuiltIns.booleanNotSymbol ||
                call.origin in RELATIONAL_ORIGINS ||
                call.symbol.owner.name.asString() in ARITH_SWAP

        override fun visitConst(expression: IrConst): IrExpression {
            val visited = super.visitConst(expression)
            if (visited !is IrConst || inSkippedDeclaration()) return visited
            return weaveSite(visited, visited.type, constantBoundaryCandidates(visited))
        }

        override fun visitReturn(expression: IrReturn): IrExpression {
            // Whether the *pre-weaving* value is already the empty/zero constant an
            // empty-return mutant would produce — an equivalent mutant to skip.
            // Captured before `super` recurses (constant-boundary rewrites it).
            val rawIsEmptyDefault = (expression.value as? IrConst)?.let { isEmptyDefaultConst(it) } == true

            val visited = super.visitReturn(expression)
            if (visited !is IrReturn || inSkippedDeclaration()) return visited

            val value = visited.value
            // Skip-list: synthetic / expression-body returns with an undefined span.
            if (value.startOffset < 0) return visited

            val candidates = buildList {
                addAll(booleanReturnCandidates(value))
                addAll(nullableReturnCandidates(value))
                if (!rawIsEmptyDefault) addAll(emptyReturnCandidates(value))
            }
            if (candidates.isNotEmpty()) {
                visited.value = weaveSite(value, value.type, candidates)
            }
            return visited
        }

        override fun visitWhen(expression: IrWhen): IrExpression {
            // Skip-list: an exhaustive `when` with no `else` desugars to a final
            // branch that throws `NoWhenBranchMatchedException`; mutating a
            // *condition* inside can make nothing match and always crash
            // (ADR-0001). The branch *results* are ordinary code, so still
            // recurse into those.
            if (isExhaustiveWhenWithoutElse(expression)) {
                expression.branches.forEach { it.result = it.result.transform(this, null) as IrExpression }
                return expression
            }

            val visited = super.visitWhen(expression)
            if (visited !is IrWhen || inSkippedDeclaration()) return visited

            val candidates = booleanLogicCandidates(visited) + ifNegateCandidates(visited)
            return weaveSite(visited, visited.type, candidates)
        }

        // --- Operators: arithmetic ------------------------------------

        /** A fresh, pre-weaving copy of argument [index] for a mutant branch. */
        private fun IrCall.rawArg(index: Int, rawArgs: List<IrExpression?>?): IrExpression =
            (rawArgs?.getOrNull(index) ?: arguments[index])!!.deepCopy()

        private fun arithmeticCandidates(call: IrCall, rawArgs: List<IrExpression?>?): List<Candidate> {
            if (MutationOperatorId.ARITHMETIC !in config) return emptyList()
            val callee = call.symbol.owner
            val swap = ARITH_SWAP[callee.name.asString()] ?: return emptyList()

            val receiverClassId = callee.parentClassOrNull?.classId ?: return emptyList()
            if (receiverClassId.packageFqName != KOTLIN_PACKAGE) return emptyList()
            val typeName = receiverClassId.shortClassName.asString()
            if (typeName !in NUMERIC_PRIMITIVES) return emptyList()

            val dispatchIndex = callee.parameters.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }
            val operandIndex = callee.parameters.indexOfFirst { it.kind == IrParameterKind.Regular }
            if (dispatchIndex < 0 || operandIndex < 0) return emptyList()
            if (callee.parameters.count { it.kind == IrParameterKind.Regular } != 1) return emptyList()
            if (call.arguments.getOrNull(dispatchIndex) == null) return emptyList()
            if (call.arguments.getOrNull(operandIndex) == null) return emptyList()

            val replacement = resolveOperator(receiverClassId, swap.replacement, callee) ?: run {
                diagnostics.warn(
                    "komust: no ${receiverClassId.shortClassName}.${swap.replacement} counterpart for " +
                        "${callee.name} — leaving the site unmutated.",
                )
                return emptyList()
            }
            val operandType = callee.parameters[operandIndex].type
            val guardDivisor = swap.guardZeroDivisor && typeName in INTEGER_PRIMITIVES

            return listOf(
                Candidate(MutationOperatorId.ARITHMETIC, swap.token, swap.description) {
                    val lhs = call.rawArg(dispatchIndex, rawArgs)
                    val rhs = call.rawArg(operandIndex, rawArgs)
                    if (!guardDivisor) {
                        irCall(replacement).apply {
                            arguments[dispatchIndex] = lhs
                            arguments[operandIndex] = rhs
                        }
                    } else {
                        irBlock(resultType = call.type) {
                            val divisor = irTemporary(rhs)
                            +irIfThenElse(
                                type = call.type,
                                condition = irEquals(irGet(divisor), numericLiteral(operandType, 0)),
                                thenPart = numericLiteral(call.type, 1),
                                elsePart = irCall(replacement).apply {
                                    arguments[dispatchIndex] = lhs
                                    arguments[operandIndex] = irGet(divisor)
                                },
                            )
                        }
                    }
                },
            )
        }

        // --- Operators: relational boundary / flip -------------------

        private fun relationalCandidates(call: IrCall, rawArgs: List<IrExpression?>?): List<Candidate> {
            if (MutationOperatorId.RELATIONAL !in config) return emptyList()
            val rel = RELATIONAL_ORIGINS[call.origin] ?: return emptyList()
            if (call.arguments.size < 2 || call.arguments[0] == null || call.arguments[1] == null) return emptyList()
            val operandClass = call.arguments[0]!!.type.classOrNull ?: return emptyList()

            fun target(kind: RelKind): IrSimpleFunctionSymbol? = when (kind) {
                RelKind.LESS -> irBuiltIns.lessFunByOperandType[operandClass]
                RelKind.LESS_OR_EQUAL -> irBuiltIns.lessOrEqualFunByOperandType[operandClass]
                RelKind.GREATER -> irBuiltIns.greaterFunByOperandType[operandClass]
                RelKind.GREATER_OR_EQUAL -> irBuiltIns.greaterOrEqualFunByOperandType[operandClass]
            }

            fun candidate(kind: RelKind, token: String, description: String): Candidate? {
                val sym = target(kind) ?: return null
                return Candidate(MutationOperatorId.RELATIONAL, token, description) {
                    irCall(sym).apply {
                        arguments[0] = call.rawArg(0, rawArgs)
                        arguments[1] = call.rawArg(1, rawArgs)
                    }
                }
            }

            return listOfNotNull(
                candidate(rel.boundary, rel.boundaryToken, rel.boundaryDescription),
                candidate(rel.flip, rel.flipToken, rel.flipDescription),
            )
        }

        // --- Operators: equality swap --------------------------------

        private fun equalityCandidates(call: IrCall, rawArgs: List<IrExpression?>?): List<Candidate> {
            if (MutationOperatorId.EQUALITY !in config) return emptyList()

            // `a == b`: the raw EQEQ intrinsic (origin EQEQ, not part of a `!=`).
            if (call.symbol == irBuiltIns.eqeqSymbol && call.origin != IrStatementOrigin.EXCLEQ) {
                if (comparesToNull(call)) return emptyList()
                val eqeq = call.symbol
                return listOf(
                    Candidate(MutationOperatorId.EQUALITY, "EQ_TO_NE", "== → !=") {
                        irBooleanNot(
                            irCall(eqeq).apply {
                                arguments[0] = call.rawArg(0, rawArgs)
                                arguments[1] = call.rawArg(1, rawArgs)
                            },
                        )
                    },
                )
            }

            // `a != b`: `not(EQEQ(a, b))` with origin EXCLEQ. Drop the `not`.
            if (call.symbol == irBuiltIns.booleanNotSymbol && call.origin == IrStatementOrigin.EXCLEQ) {
                val inner = call.arguments.getOrNull(0) as? IrCall ?: return emptyList()
                if (inner.symbol != irBuiltIns.eqeqSymbol || comparesToNull(inner)) return emptyList()
                // rawArgs[0] is the pre-weaving copy of the inner `a == b`.
                return listOf(
                    Candidate(MutationOperatorId.EQUALITY, "NE_TO_EQ", "!= → ==") { call.rawArg(0, rawArgs) },
                )
            }
            return emptyList()
        }

        // --- Operators: boolean inversion ---------------------------

        private fun booleanInversionCallCandidates(call: IrCall): List<Candidate> {
            if (MutationOperatorId.BOOLEAN_INVERSION !in config) return emptyList()
            // An explicit `!x` in source: `x.not()` with origin EXCL. Drop it.
            if (call.symbol == irBuiltIns.booleanNotSymbol && call.origin == IrStatementOrigin.EXCL) {
                val operand = call.arguments.getOrNull(0) ?: return emptyList()
                return listOf(
                    Candidate(MutationOperatorId.BOOLEAN_INVERSION, "BOOL_DROP_NOT", "!e → e") { operand.deepCopy() },
                )
            }
            return emptyList()
        }

        /**
         * Negate the guard of a user `if`. v1 negates only the first branch's
         * condition; the `else if` conditions of a flattened chain still get
         * their sub-expression mutations (relational, equality, `!e`) through
         * recursion, but not a standalone condition-inversion mutant.
         */
        private fun ifNegateCandidates(expression: IrWhen): List<Candidate> {
            if (MutationOperatorId.BOOLEAN_INVERSION !in config) return emptyList()
            if (expression.origin != IrStatementOrigin.IF) return emptyList()
            if (expression.branches.isEmpty()) return emptyList()
            return listOf(
                Candidate(MutationOperatorId.BOOLEAN_INVERSION, "IF_NEGATE", "if (c) → if (!c)") {
                    val copy = expression.deepCopy() as IrWhen
                    copy.branches[0].condition = irBooleanNot(copy.branches[0].condition)
                    copy
                },
            )
        }

        // --- Operators: boolean logic (&& ↔ ||) --------------------

        private fun booleanLogicCandidates(expression: IrWhen): List<Candidate> {
            if (MutationOperatorId.BOOLEAN_LOGIC !in config) return emptyList()
            val first = expression.branches.firstOrNull() ?: return emptyList()
            return when (expression.origin) {
                IrStatementOrigin.ANDAND -> listOf(
                    Candidate(MutationOperatorId.BOOLEAN_LOGIC, "AND_TO_OR", "&& → ||") {
                        irIfThenElse(
                            irBuiltIns.booleanType,
                            first.condition.deepCopy(),
                            irBoolean(true),
                            first.result.deepCopy(),
                        )
                    },
                )
                IrStatementOrigin.OROR -> {
                    val elseResult = expression.branches.getOrNull(1)?.result ?: return emptyList()
                    listOf(
                        Candidate(MutationOperatorId.BOOLEAN_LOGIC, "OR_TO_AND", "|| → &&") {
                            irIfThenElse(
                                irBuiltIns.booleanType,
                                first.condition.deepCopy(),
                                elseResult.deepCopy(),
                                irBoolean(false),
                            )
                        },
                    )
                }
                else -> emptyList()
            }
        }

        // --- Operators: constant boundary --------------------------

        private fun constantBoundaryCandidates(const: IrConst): List<Candidate> {
            if (MutationOperatorId.CONSTANT_BOUNDARY !in config) return emptyList()
            if (const.startOffset < 0) return emptyList()
            val plus = numericConst(const, +1) ?: return emptyList()
            val minus = numericConst(const, -1) ?: return emptyList()
            return listOf(
                Candidate(MutationOperatorId.CONSTANT_BOUNDARY, "CONST_PLUS_1", "${const.value} → ${plus.value}") { plus },
                Candidate(
                    MutationOperatorId.CONSTANT_BOUNDARY,
                    "CONST_MINUS_1",
                    "${const.value} → ${minus.value}",
                ) { minus },
            )
        }

        // --- Operators: return value ------------------------------

        private fun booleanReturnCandidates(value: IrExpression): List<Candidate> {
            if (MutationOperatorId.BOOLEAN_RETURN !in config || !value.type.isBoolean()) return emptyList()
            // Skip-list: an already-constant `return true` / `return false`.
            if (value is IrConst) return emptyList()
            return listOf(
                Candidate(MutationOperatorId.BOOLEAN_RETURN, "RET_TRUE", "return … → return true") { irBoolean(true) },
                Candidate(MutationOperatorId.BOOLEAN_RETURN, "RET_FALSE", "return … → return false") { irBoolean(false) },
            )
        }

        private fun nullableReturnCandidates(value: IrExpression): List<Candidate> {
            if (MutationOperatorId.NULLABLE_RETURN !in config) return emptyList()
            // Prefer the *function's* declared return type — a nullable-return
            // function very often returns a non-null expression (`fun f(): T? = x`).
            val declared = enclosingFunction()?.returnType
            val targetType = declared?.takeIf { it.isMarkedNullable() }
                ?: value.type.takeIf { it.isMarkedNullable() }
                ?: return emptyList()
            if (targetType.classFqName == FqName("kotlin.Nothing")) return emptyList()
            // Skip-list: an already-constant `return null`.
            if (value is IrConst && value.value == null) return emptyList()
            return listOf(
                Candidate(MutationOperatorId.NULLABLE_RETURN, "RET_NULL", "return … → return null") {
                    IrConstImpl.constNull(value.startOffset, value.endOffset, targetType)
                },
            )
        }

        private fun emptyReturnCandidates(value: IrExpression): List<Candidate> {
            if (MutationOperatorId.EMPTY_RETURN !in config) return emptyList()
            val type = value.type
            if (type.isMarkedNullable() || type.isBoolean()) return emptyList()
            // Skip-list: the return is already the empty/zero default.
            if (value is IrConst && isEmptyDefaultConst(value)) return emptyList()
            val build: (DeclarationIrBuilder.() -> IrExpression) = when {
                isNumeric(type) -> ({ numericLiteral(type, 0) })
                type.isString() -> ({ irString("") })
                else -> emptyCollectionCall(type) ?: return emptyList()
            }
            return listOf(Candidate(MutationOperatorId.EMPTY_RETURN, "RET_EMPTY", "return … → return <empty>", build))
        }

        // --- Operators: increments (spike-gated) ------------------

        private fun incrementCandidates(call: IrCall): List<Candidate> {
            if (MutationOperatorId.INCREMENT !in config) return emptyList()
            val callee = call.symbol.owner
            val (replacementName, tokenAndDesc) = when (callee.name.asString()) {
                "inc" -> "dec" to ("INC_TO_DEC" to "++ → --")
                "dec" -> "inc" to ("DEC_TO_INC" to "-- → ++")
                else -> return emptyList()
            }
            val receiverClassId = callee.parentClassOrNull?.classId ?: return emptyList()
            if (receiverClassId.packageFqName != KOTLIN_PACKAGE) return emptyList()
            if (receiverClassId.shortClassName.asString() !in NUMERIC_PRIMITIVES) return emptyList()
            val dispatchIndex = callee.parameters.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }
            if (dispatchIndex < 0 || call.arguments.getOrNull(dispatchIndex) == null) return emptyList()
            val replacement = resolveOperator(receiverClassId, replacementName, callee) ?: return emptyList()
            val (token, description) = tokenAndDesc
            return listOf(
                Candidate(MutationOperatorId.INCREMENT, token, description) {
                    irCall(replacement).apply {
                        arguments[dispatchIndex] = call.arguments[dispatchIndex]!!.deepCopy()
                    }
                },
            )
        }

        // --- Operators: per-call-site void-call removal (spike-gated) ---

        private fun voidCallCandidates(call: IrCall): List<Candidate> {
            if (MutationOperatorId.VOID_CALL !in config) return emptyList()
            if (!call.type.isUnit()) return emptyList()
            val callee = call.symbol.owner
            if (callee.correspondingPropertySymbol != null) return emptyList()
            val name = callee.name.asString()
            if (name.startsWith("<") || name in SKIPPED_CALL_NAMES) return emptyList()
            if (call.symbol == mutantActive) return emptyList()
            return listOf(
                Candidate(MutationOperatorId.VOID_CALL, "VOID_CALL_REMOVE", "remove call $name()") { irUnit() },
            )
        }

        // --- Weave one site ----------------------------------------

        /**
         * Fold [candidates] (already operator-filtered) into nested runtime
         * switches around [original], recording a [WovenMutant] for each. The
         * `@SuppressMutations` / `// komust:ignore` hatch is checked once here,
         * covering every candidate at the site.
         */
        private fun weaveSite(
            original: IrExpression,
            resultType: IrType,
            candidates: List<Candidate>,
        ): IrExpression {
            if (candidates.isEmpty()) return original
            // Skip-list: synthetic nodes with an undefined source span — nothing
            // the Mutation Scope or coverage index could ever join against.
            if (original.startOffset < 0 || original.endOffset < original.startOffset) return original
            if (inConstOnlyContext()) return original
            val scopeOwner = currentScope?.scope?.scopeOwnerSymbol ?: return original
            val file = currentFile
            val path = filePath(file)
            val (line, column) = lineColumn(file, original.startOffset)
            if (isSuppressed(line)) return original

            val fileName = path.substringAfterLast('/')
            var acc: IrExpression = original
            for (candidate in candidates.asReversed()) {
                val positionKey = "$line:$column:${candidate.token}"
                val ordinal = ordinals.merge(positionKey, 0) { existing, _ -> existing + 1 }!!
                val mutant = WovenMutant(
                    filePath = path,
                    fileName = fileName,
                    line = line,
                    column = column,
                    operator = candidate.operator,
                    token = candidate.token,
                    description = candidate.description,
                    ordinal = ordinal,
                    binaryClassName = binaryClassName(enclosingClass(), file),
                    startOffset = original.startOffset,
                )
                val builder = DeclarationIrBuilder(pluginContext, scopeOwner, original.startOffset, original.endOffset)
                val elsePart = acc
                acc = builder.irIfThenElse(
                    type = resultType,
                    condition = builder.irCall(mutantActive).apply { arguments[0] = builder.irString(mutant.id) },
                    thenPart = builder.run(candidate.build),
                    elsePart = elsePart,
                )
                if (acc.startOffset != original.startOffset) {
                    diagnostics.warn(
                        "komust: the injected if/else for ${mutant.id} did not keep the site's startOffset " +
                            "(${acc.startOffset} != ${original.startOffset}) — stack traces here may be off by a line.",
                    )
                }
                woven += mutant
            }
            return acc
        }

        // --- Skip-list & suppression -------------------------------

        /**
         * Whether the current site sits in a declaration komust does not mutate:
         * a compiler-generated member (`data class` `hashCode` etc.), a property
         * accessor, or a lambda / local function (ADR-0001 skip-list plus the
         * unresolved-binary-name limitation).
         */
        private fun inSkippedDeclaration(): Boolean {
            val function = allScopes.asReversed()
                .firstNotNullOfOrNull { it.irElement as? IrFunction }
                ?: return false
            if (function.origin != IrDeclarationOrigin.DEFINED) return true
            return (function as? IrSimpleFunction)?.correspondingPropertySymbol != null
        }

        /**
         * Whether the site sits somewhere whose value must stay a **compile-time
         * constant** — a `const val` initializer or an annotation-class parameter
         * default. Weaving a runtime `if/else` there produces a non-constant
         * expression the backend rejects, so komust leaves it alone entirely.
         */
        private fun inConstOnlyContext(): Boolean {
            val roots = allScopes.map { it.irElement } + listOfNotNull(currentDeclarationParent)
            for (root in roots) {
                var node: Any? = root
                while (node != null) {
                    when (node) {
                        is IrClass -> if (node.isAnnotationClass) return true
                        is IrProperty -> if (node.isConst) return true
                        is IrField -> if (node.correspondingPropertySymbol?.owner?.isConst == true) return true
                    }
                    node = (node as? IrDeclaration)?.parent
                }
            }
            return false
        }

        /**
         * `!!` and the stdlib assertions `require` / `check` / `error` / `TODO()`
         * / `assert` — ADR-0001 protects the whole call and everything inside its
         * arguments, so the weaver does not recurse into a match.
         */
        private fun isProtectedCall(call: IrCall): Boolean {
            if (call.symbol == irBuiltIns.checkNotNullSymbol) return true
            val callee = call.symbol.owner
            if (callee.name.asString() !in SKIPPED_CALL_NAMES) return false
            return callee.parentClassOrNull == null &&
                callee.fqNameWhenAvailable?.asString()?.startsWith("kotlin.") == true
        }

        /**
         * An exhaustive `when` **expression** with no `else` desugars to a final
         * branch that throws `NoWhenBranchMatchedException`. Mutating a condition
         * inside can leave nothing matching and always crash, so ADR-0001
         * protects the whole construct.
         */
        private fun isExhaustiveWhenWithoutElse(expression: IrWhen): Boolean {
            if (expression.origin != IrStatementOrigin.WHEN) return false
            val last = expression.branches.lastOrNull()?.result ?: return false
            val thrownName = when (last) {
                is IrThrow -> (last.value as? IrConstructorCall)?.type?.classFqName?.shortName()?.asString()
                is IrCall -> last.symbol.owner.name.asString()
                else -> null
            }
            return thrownName?.contains("NoWhenBranchMatched", ignoreCase = true) == true
        }

        /**
         * The `@SuppressMutations` / `// komust:ignore` hatch (ADR-0001, #36):
         * an annotation on any enclosing declaration or on the file, or the
         * marker comment on the site's line or the line just above it.
         */
        private fun isSuppressed(line: Int): Boolean {
            if (line in ignoredLines || (line - 1) in ignoredLines) return true
            if (fileSuppressed) return true
            val roots = allScopes.map { it.irElement } + listOfNotNull(currentDeclarationParent)
            for (root in roots) {
                var node: Any? = root
                while (node != null) {
                    if (node is IrAnnotationContainer && node.hasSuppressAnnotation()) return true
                    node = (node as? IrDeclaration)?.parent
                }
            }
            return false
        }

        /**
         * `@SuppressMutations` presence, checked directly against
         * `annotations` — `IrAnnotationContainer.hasAnnotation(FqName)` misses
         * the `@file:` case for this annotation under the pinned compiler.
         */
        private fun IrAnnotationContainer.hasSuppressAnnotation(): Boolean =
            annotations.any { it.type.classFqName == SUPPRESS_MUTATIONS } ||
                hasAnnotation(SUPPRESS_MUTATIONS)

        private fun readIgnoredLines(file: IrFile): Set<Int> {
            val source = runCatching { File(file.fileEntry.name).takeIf { it.isFile }?.readLines() }.getOrNull()
                ?: return emptySet()
            return buildSet {
                source.forEachIndexed { index, text ->
                    val commentAt = text.indexOf("//")
                    if (commentAt >= 0 && text.indexOf(IGNORE_MARKER, commentAt) >= 0) add(index + 1)
                }
            }
        }

        // --- IR construction helpers ------------------------------

        @Suppress("DEPRECATION")
        private fun IrExpression.deepCopy(): IrExpression = deepCopyWithSymbols()

        private fun DeclarationIrBuilder.irBooleanNot(arg: IrExpression): IrExpression =
            irCall(irBuiltIns.booleanNotSymbol).apply { arguments[0] = arg }

        private fun DeclarationIrBuilder.irUnit(): IrExpression =
            irGetObjectValue(irBuiltIns.unitType, irBuiltIns.unitClass)

        private fun resolveOperator(
            receiverClassId: ClassId,
            replacementName: String,
            original: IrSimpleFunction,
        ): IrSimpleFunctionSymbol? {
            val originalOperandTypes = original.parameters
                .filter { it.kind == IrParameterKind.Regular }
                .map { it.type.classFqName }
            return pluginContext
                .referenceFunctions(CallableId(receiverClassId, Name.identifier(replacementName)))
                .singleOrNull { candidate ->
                    val params = candidate.owner.parameters
                    params.count { it.kind == IrParameterKind.DispatchReceiver } == 1 &&
                        params.filter { it.kind == IrParameterKind.Regular }.map { it.type.classFqName } ==
                        originalOperandTypes
                }
        }

        private fun comparesToNull(eqeq: IrCall): Boolean =
            eqeq.arguments.any { it is IrConst && it.value == null }

        private fun DeclarationIrBuilder.numericLiteral(type: IrType, n: Int): IrExpression = when {
            type.isLong() -> IrConstImpl.long(startOffset, endOffset, type, n.toLong())
            type.isShort() -> IrConstImpl.short(startOffset, endOffset, type, n.toShort())
            type.isByte() -> IrConstImpl.byte(startOffset, endOffset, type, n.toByte())
            type.isFloat() -> IrConstImpl.float(startOffset, endOffset, type, n.toFloat())
            type.isDouble() -> IrConstImpl.double(startOffset, endOffset, type, n.toDouble())
            else -> irInt(n)
        }

        private fun numericConst(const: IrConst, delta: Int): IrConst? {
            val so = const.startOffset
            val eo = const.endOffset
            val t = const.type
            return when (val v = const.value) {
                is Int -> IrConstImpl.int(so, eo, t, v + delta)
                is Long -> IrConstImpl.long(so, eo, t, v + delta)
                is Short -> IrConstImpl.short(so, eo, t, (v + delta).toShort())
                is Byte -> IrConstImpl.byte(so, eo, t, (v + delta).toByte())
                is Float -> IrConstImpl.float(so, eo, t, v + delta)
                is Double -> IrConstImpl.double(so, eo, t, v + delta)
                else -> null
            }
        }

        private fun isNumeric(type: IrType): Boolean =
            type.isInt() || type.isLong() || type.isShort() || type.isByte() || type.isFloat() || type.isDouble()

        private fun emptyCollectionCall(type: IrType): (DeclarationIrBuilder.() -> IrExpression)? {
            val (pkg, name) = when (type.classFqName?.asString()) {
                "kotlin.collections.List", "kotlin.collections.Collection", "kotlin.collections.Iterable" ->
                    "kotlin.collections" to "emptyList"
                "kotlin.collections.Set" -> "kotlin.collections" to "emptySet"
                "kotlin.collections.Map" -> "kotlin.collections" to "emptyMap"
                else -> return null
            }
            val symbol = pluginContext
                .referenceFunctions(CallableId(FqName(pkg), Name.identifier(name)))
                .firstOrNull() ?: return null
            val typeArgs = (type as? IrSimpleType)?.arguments.orEmpty().mapNotNull { it.typeOrNull() }
            if (typeArgs.isEmpty()) return null
            return {
                irCall(symbol).apply {
                    typeArgs.forEachIndexed { i, arg -> if (i < typeArguments.size) typeArguments[i] = arg }
                }
            }
        }

        private fun IrTypeArgument.typeOrNull(): IrType? = (this as? IrTypeProjection)?.type

        private fun enclosingClass(): IrClass? =
            allScopes.asReversed().firstNotNullOfOrNull { it.irElement as? IrClass }

        private fun enclosingFunction(): IrFunction? =
            allScopes.asReversed().firstNotNullOfOrNull { it.irElement as? IrFunction }

        /** Whether [const] is the zero / empty value an `empty-return` mutant would produce. */
        private fun isEmptyDefaultConst(const: IrConst): Boolean = when (val v = const.value) {
            is Int -> v == 0
            is Long -> v == 0L
            is Short -> v.toInt() == 0
            is Byte -> v.toInt() == 0
            is Float -> v == 0f
            is Double -> v == 0.0
            is String -> v.isEmpty()
            else -> false
        }

        /**
         * The JVM binary name of the site's enclosing class, or the file-facade
         * class (`Golden.kt` → `<pkg>.GoldenKt`) for a top-level declaration.
         * Nested classes are joined with `$`, matching the runtime class name
         * the coverage index (ADR-0004) keys on.
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

    // --- Operator lookup tables (pure data) ------------------------

    private class ArithSwap(
        val token: String,
        val replacement: String,
        val description: String,
        val guardZeroDivisor: Boolean = false,
    )

    private val ARITH_SWAP: Map<String, ArithSwap> = mapOf(
        "plus" to ArithSwap("ARITH_PLUS_TO_MINUS", "minus", "+ → -"),
        "minus" to ArithSwap("ARITH_MINUS_TO_PLUS", "plus", "- → +"),
        "times" to ArithSwap("ARITH_TIMES_TO_DIV", "div", "* → /", guardZeroDivisor = true),
        "div" to ArithSwap("ARITH_DIV_TO_TIMES", "times", "/ → *"),
        "rem" to ArithSwap("ARITH_REM_TO_DIV", "div", "% → /", guardZeroDivisor = true),
    )

    private enum class RelKind { LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL }

    private class RelRewrite(
        val boundary: RelKind,
        val boundaryToken: String,
        val boundaryDescription: String,
        val flip: RelKind,
        val flipToken: String,
        val flipDescription: String,
    )

    private val RELATIONAL_ORIGINS: Map<IrStatementOrigin?, RelRewrite> = mapOf(
        IrStatementOrigin.LT to RelRewrite(
            RelKind.LESS_OR_EQUAL, "REL_LT_TO_LE", "< → <=",
            RelKind.GREATER_OR_EQUAL, "REL_LT_TO_GE", "< → >=",
        ),
        IrStatementOrigin.LTEQ to RelRewrite(
            RelKind.LESS, "REL_LE_TO_LT", "<= → <",
            RelKind.GREATER, "REL_LE_TO_GT", "<= → >",
        ),
        IrStatementOrigin.GT to RelRewrite(
            RelKind.GREATER_OR_EQUAL, "REL_GT_TO_GE", "> → >=",
            RelKind.LESS_OR_EQUAL, "REL_GT_TO_LE", "> → <=",
        ),
        IrStatementOrigin.GTEQ to RelRewrite(
            RelKind.GREATER, "REL_GE_TO_GT", ">= → >",
            RelKind.LESS, "REL_GE_TO_LT", ">= → <",
        ),
    )
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
