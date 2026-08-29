package io.komust.plugin

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * PROTOTYPE — komust #2.
 *
 * Rewrites every `Int + Int` into a runtime-switched
 *
 *     if (mutantActive("<file>:<line>:<col>#ARITH:PLUS_TO_MINUS")) a - b else a + b
 *
 * so ONE compile carries both the original and the mutant, flipped per test run.
 */
class KomustIrExtension(private val messages: MessageCollector) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        // The injected runtime guard: io.komust.runtime.mutantActive(String): Boolean
        val mutantActive = pluginContext
            .referenceFunctions(CallableId(FqName("io.komust.runtime"), Name.identifier("mutantActive")))
            .singleOrNull()
            ?: run {
                messages.report(
                    CompilerMessageSeverity.WARNING,
                    "komust: mutantActive() not on classpath — is :runtime a dependency? Skipping."
                )
                return
            }

        // The replacement operator: kotlin.Int.minus(Int): Int
        val intMinus = pluginContext
            .referenceFunctions(CallableId(FqName("kotlin"), FqName("Int"), Name.identifier("minus")))
            .single { fn ->
                val params = fn.owner.parameters.filter { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }
                params.size == 1 && params[0].type == pluginContext.irBuiltIns.intType
            }

        val transformer = MutationTransformer(pluginContext, messages, mutantActive, intMinus)
        moduleFragment.transformChildrenVoid(transformer)

        messages.report(
            CompilerMessageSeverity.WARNING,
            "komust: compile-once mutation done — ${transformer.injected} mutant(s) injected."
        )
    }
}

private class MutationTransformer(
    private val ctx: IrPluginContext,
    private val messages: MessageCollector,
    private val mutantActive: IrSimpleFunctionSymbol,
    private val intMinus: IrSimpleFunctionSymbol,
) : IrElementTransformerVoidWithContext() {

    var injected = 0
        private set

    private var file: IrFile? = null

    // Start-offset is NOT unique: nested same-line operators (a + b + c) share it.
    // Disambiguate with a per-position ordinal so every operator is switchable alone.
    private val ordinals = HashMap<String, Int>()

    override fun visitFileNew(declaration: IrFile): IrFile {
        file = declaration
        return super.visitFileNew(declaration)
    }

    override fun visitCall(expression: IrCall): IrExpression {
        // Recurse first so nested `a + b + c` mutates every operator.
        val call = super.visitCall(expression)
        if (call !is IrCall) return call

        val callee = call.symbol.owner
        if (callee.name.asString() != "plus") return call
        if (callee.parentClassOrNull?.fqNameWhenAvailable?.asString() != "kotlin.Int") return call

        val receiver = call.dispatchReceiver ?: return call
        val arg = call.arguments.getOrNull(1) ?: return call  // [0]=dispatch receiver, [1]=operand

        val f = file
        val base = if (f != null) {
            val entry = f.fileEntry
            val line = entry.getLineNumber(call.startOffset) + 1
            val col = entry.getColumnNumber(call.startOffset) + 1
            "${entry.name.substringAfterLast('/')}:$line:$col#ARITH:PLUS_TO_MINUS"
        } else {
            "<unknown>:0:0#ARITH:PLUS_TO_MINUS"
        }
        val ordinal = ordinals.merge(base, 0) { old, _ -> old + 1 }!!
        val id = "$base@$ordinal"

        injected++
        messages.report(CompilerMessageSeverity.WARNING, "komust: injected mutant [$id]")

        val scopeSymbol = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(ctx, scopeSymbol, call.startOffset, call.endOffset)
        val intType = ctx.irBuiltIns.intType

        return builder.irBlock(resultType = intType) {
            val a = irTemporary(receiver)
            val b = irTemporary(arg)
            +irIfThenElse(
                type = intType,
                condition = irCall(mutantActive).apply { arguments[0] = irString(id) },
                thenPart = irCall(intMinus).apply {
                    arguments[0] = irGet(a)   // dispatch receiver
                    arguments[1] = irGet(b)   // operand
                },
                elsePart = irCall(call.symbol).apply {
                    arguments[0] = irGet(a)
                    arguments[1] = irGet(b)
                },
            )
        }
    }
}
