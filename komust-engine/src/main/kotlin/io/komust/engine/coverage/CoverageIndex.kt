package io.komust.engine.coverage

/**
 * A JUnit Platform test's stable, re-selectable identity — its
 * `TestIdentifier.getUniqueId()` string, e.g.
 * `[engine:junit-jupiter]/[class:com.foo.BarTest]/[method:baz()]`.
 *
 * This is the test side of the [CoverageIndex] join key and the handle the
 * mutant sweep (#33) hands back to the Launcher via a `UniqueIdSelector` to
 * re-run exactly the covering tests.
 */
@JvmInline
public value class TestId(public val uniqueId: String) {
    override fun toString(): String = uniqueId
}

/**
 * The line side of the coverage join: a binary (dotted) class name plus a
 * 1-based source line number.
 *
 * The mutant record carries the *same* pair — its enclosing declaration's binary
 * class name (known at IR-injection time) and the mutated site's source line —
 * so selection is a direct [CoverageIndex.testsCovering] lookup, strictly more
 * precise than a file-level union (ADR-0004 §2).
 */
public data class CoverageKey(val binaryClassName: String, val line: Int) {
    init {
        require(line >= 1) { "coverage line must be >= 1, was $line" }
    }
}

/**
 * The **coverage index**: for every covered `(binary class name, source line)`,
 * the set of tests that executed it during the coverage pass (ADR-0004).
 *
 * Built once per source snapshot from the JaCoCo per-test snapshots, with
 * Kotlin inline-function lines already normalised ([InlineLineNormalizer]), so
 * every consumer downstream is a dumb exact lookup.
 *
 * A key that is **absent** means no test covered that line — the mutant sweep
 * reports such a mutant as `NO_COVERAGE`, never as a survivor (ADR-0004 §4).
 */
public class CoverageIndex internal constructor(
    private val byKey: Map<CoverageKey, Set<TestId>>,
) {
    /** Every covered `(class, line)` key, unordered. */
    public val keys: Set<CoverageKey> get() = byKey.keys

    /** Number of covered `(class, line)` keys. */
    public val size: Int get() = byKey.size

    public val isEmpty: Boolean get() = byKey.isEmpty()

    /**
     * The tests that executed [line] of [binaryClassName], or the empty set if
     * that line has no coverage. The empty set is the `NO_COVERAGE` signal — it
     * is never `null`.
     */
    public fun testsCovering(binaryClassName: String, line: Int): Set<TestId> =
        byKey[CoverageKey(binaryClassName, line)] ?: emptySet()

    /** Convenience overload for a [CoverageKey] already in hand. */
    public fun testsCovering(key: CoverageKey): Set<TestId> = byKey[key] ?: emptySet()

    /** Every entry, for inspection / serialisation. */
    public fun entries(): Map<CoverageKey, Set<TestId>> = byKey

    override fun equals(other: Any?): Boolean =
        this === other || (other is CoverageIndex && other.byKey == byKey)

    override fun hashCode(): Int = byKey.hashCode()

    override fun toString(): String = "CoverageIndex(${byKey.size} keys)"

    public companion object {
        public val EMPTY: CoverageIndex = CoverageIndex(emptyMap())
    }
}

/**
 * Accumulates `(class, line) -> { test }` fragments — one fragment per test, per
 * covered class — into a normalised [CoverageIndex].
 */
internal class CoverageIndexBuilder {
    private val byKey = HashMap<CoverageKey, MutableSet<TestId>>()

    fun add(binaryClassName: String, line: Int, test: TestId) {
        if (line < 1) return
        byKey.getOrPut(CoverageKey(binaryClassName, line)) { LinkedHashSet() }.add(test)
    }

    fun build(): CoverageIndex = CoverageIndex(byKey.mapValues { (_, v) -> v.toSet() })
}
