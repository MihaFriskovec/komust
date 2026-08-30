package io.komust.compiler

/**
 * An operator's default activation state (CONTEXT.md — **Tier**). komust v1 has
 * exactly two: [DEFAULT] (on unless disabled) and [EXPERIMENTAL] (off unless
 * explicitly enabled). There is deliberately no "stronger" middle tier.
 */
internal enum class Tier { DEFAULT, EXPERIMENTAL }

/**
 * The v1 **operator catalog** (ADR-0001), as pure identity/policy data — no IR
 * API. The weaving that each id drives lives behind the compat-shim seam in
 * [io.komust.compiler.ir.KotlinIrCompat].
 *
 * [slug] is the stable name the `komust {}` DSL and the plugin's
 * enabled/disabled-operators option address an operator by; it never changes
 * once shipped. [spikeGated] marks the three operators ADR-0001 made contingent
 * on the IR prototype — if one proves awkward in K2 IR it demotes to
 * experimental / skip with a recorded note, rather than reopening the catalog.
 */
internal enum class MutationOperatorId(
    val slug: String,
    val tier: Tier,
    val spikeGated: Boolean = false,
) {
    ARITHMETIC("arithmetic", Tier.DEFAULT),
    RELATIONAL("relational", Tier.DEFAULT),
    EQUALITY("equality", Tier.DEFAULT),
    BOOLEAN_LOGIC("boolean-logic", Tier.DEFAULT),
    BOOLEAN_INVERSION("boolean-inversion", Tier.DEFAULT),
    CONSTANT_BOUNDARY("constant-boundary", Tier.DEFAULT),
    BOOLEAN_RETURN("boolean-return", Tier.DEFAULT),
    NULLABLE_RETURN("nullable-return", Tier.DEFAULT),
    INCREMENT("increment", Tier.DEFAULT, spikeGated = true),
    EMPTY_RETURN("empty-return", Tier.DEFAULT, spikeGated = true),
    VOID_CALL("void-call", Tier.DEFAULT, spikeGated = true),
    ;

    internal companion object {
        fun bySlug(slug: String): MutationOperatorId? = entries.firstOrNull { it.slug == slug }

        val defaultTier: Set<MutationOperatorId> = entries.filter { it.tier == Tier.DEFAULT }.toSet()
    }
}

/**
 * The resolved set of operators a mutation compilation should apply.
 *
 * The default is the whole [Tier.DEFAULT] catalog. The Gradle plugin's
 * `operators { enable / disable }` DSL (ADR-0005) flows in as two plugin
 * options the [KomustCommandLineProcessor] parses:
 *
 *  - `disabledOperators` — slugs removed from the default-on set;
 *  - `enabledOperators` — slugs added on top (the opt-in path for
 *    [Tier.EXPERIMENTAL] operators; still valid for a default one already on).
 *
 * An unknown slug is ignored with a warning rather than failing the compile —
 * a stale DSL entry should never wedge a build.
 */
internal data class OperatorConfig(val enabled: Set<MutationOperatorId>) {

    operator fun contains(id: MutationOperatorId): Boolean = id in enabled

    internal companion object {
        val DEFAULT: OperatorConfig = OperatorConfig(MutationOperatorId.defaultTier)

        /**
         * Resolve `default-tier minus [disabledSlugs] plus [enabledSlugs]`.
         * [onUnknownSlug] is called once per slug that matches no operator.
         */
        fun resolve(
            disabledSlugs: List<String>,
            enabledSlugs: List<String>,
            onUnknownSlug: (String) -> Unit = {},
        ): OperatorConfig {
            fun resolveSlugs(slugs: List<String>): Set<MutationOperatorId> =
                slugs.mapNotNull { slug ->
                    MutationOperatorId.bySlug(slug.trim()).also { if (it == null) onUnknownSlug(slug.trim()) }
                }.toSet()

            val enabled = (MutationOperatorId.defaultTier - resolveSlugs(disabledSlugs)) + resolveSlugs(enabledSlugs)
            return OperatorConfig(enabled)
        }
    }
}
