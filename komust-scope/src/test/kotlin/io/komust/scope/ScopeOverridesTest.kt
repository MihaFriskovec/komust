package io.komust.scope

import kotlin.io.path.exists
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Exercises the explicit-override producers — `--files` (whole-file sugar) and
 * `--scope` (precise line ranges) — against [GitFixture] work trees (ADR-0002;
 * issue #26). `--since` is a git base-ref knob and lives with the git tests in
 * [ScopeResolverGitTest].
 *
 * The load-bearing property here: an explicit override **fully replaces** git —
 * git's changeset is never consulted.
 */
class ScopeOverridesTest {

    private val fooKt = "src/main/kotlin/com/example/Foo.kt"
    private val barKt = "src/main/kotlin/com/example/Bar.kt"
    private val original = "package com.example\n\nclass Foo {\n    fun a() = 1\n}\n"

    // --- --files ----------------------------------------------------------

    @Test
    fun `--files resolves an exact path to whole-file scope`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))

        val scope = fixture.resolveScope(ScopeSpec.Files(listOf(fooKt)))

        assertEquals(listOf(fooKt), scope.files.map { it.path })
        assertTrue(scope.files.single().isWholeFile)
    }

    @Test
    fun `--files fully replaces git — a clean tree still yields the file`() {
        // Foo is committed and unmodified: the git-derived scope would be empty.
        val fixture = GitFixture.create(mapOf(fooKt to original))

        assertTrue(fixture.resolveScope().isEmpty)
        assertFalse(fixture.resolveScope(ScopeSpec.Files(listOf(fooKt))).isEmpty)
    }

    @Test
    fun `--files glob matches across directories and skips test sources`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.write(barKt, "package com.example\nclass Bar\n")
        fixture.write("src/test/kotlin/com/example/FooTest.kt", "class FooTest\n")

        val scope = fixture.resolveScope(ScopeSpec.Files(listOf("**/*.kt")))

        assertEquals(listOf(barKt, fooKt), scope.files.map { it.path })
        assertTrue(scope.files.all { it.isWholeFile })
    }

    @Test
    fun `--files matches untracked files too — the walk is filesystem-based`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.write(barKt, "package com.example\nclass Bar\n") // never git-added

        val scope = fixture.resolveScope(ScopeSpec.Files(listOf("Bar.kt")))

        assertEquals(listOf(barKt), scope.files.map { it.path })
    }

    @Test
    fun `--files does not descend build or dot directories`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.write("build/generated/com/example/Gen.kt", "class Gen\n")
        fixture.write(".idea/scratch/Scratch.kt", "class Scratch\n")

        val ex = assertThrows<ScopeResolutionException> {
            fixture.resolveScope(ScopeSpec.Files(listOf("**/Gen.kt", "**/Scratch.kt")))
        }
        assertTrue(ex.message!!.contains("matched no production Kotlin"))
    }

    @Test
    fun `--files pattern matching nothing is an error, not an empty scope`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))

        val ex = assertThrows<ScopeResolutionException> {
            fixture.resolveScope(ScopeSpec.Files(listOf("Nope.kt")))
        }
        assertTrue(ex.message!!.contains("Nope.kt"))
    }

    // --- --scope ---------------------------------------------------------

    @Test
    fun `--scope passes precise line ranges straight through`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        val doc = fixture.root.resolve("agent-scope.json")
        ScopeJson.write(
            MutationScope.of(mapOf(fooKt to listOf(LineRange(4, 6), LineRange(20, 24)))),
            doc,
        )

        val scope = fixture.resolveScope(ScopeSpec.ScopeFileDocument(doc))

        assertEquals(listOf(LineRange(4, 6), LineRange(20, 24)), scope.ranges(fooKt))
    }

    @Test
    fun `--scope fully replaces git`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.checkoutNewBranch("feature")
        fixture.write(fooKt, original.replace("fun a() = 1", "fun a() = 11"))
        val doc = fixture.root.resolve("agent-scope.json")
        ScopeJson.write(MutationScope.of(mapOf(barKt to listOf(LineRange(1, 3)))), doc)

        val scope = fixture.resolveScope(ScopeSpec.ScopeFileDocument(doc))

        // Only what the document names — Foo's real git change is not consulted.
        assertEquals(listOf(barKt), scope.files.map { it.path })
    }

    @Test
    fun `--scope on a missing file is an error`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))

        val ex = assertThrows<ScopeResolutionException> {
            fixture.resolveScope(ScopeSpec.ScopeFileDocument(fixture.root.resolve("absent.json")))
        }
        assertTrue(ex.message!!.contains("does not exist"))
    }

    @Test
    fun `resolveAndWrite with --scope re-emits the normalised document at the default path`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        val doc = fixture.root.resolve("in.json")
        ScopeJson.write(
            MutationScope.of(mapOf(fooKt to listOf(LineRange(6, 7), LineRange(4, 5)))),
            doc,
        )

        val scope = ScopeResolver().resolveAndWrite(fixture.root, ScopeSpec.ScopeFileDocument(doc))

        val written = fixture.root.resolve("build/komust/scope.json")
        assertTrue(written.exists())
        assertEquals(scope, ScopeJson.read(written))
        // adjacent input ranges are merged on the way out
        assertEquals(listOf(LineRange(4, 7)), scope.ranges(fooKt))
    }
}
