package io.komust.scope

import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Exercises [ScopeResolver] against real `git` output using [GitFixture] temp
 * repositories with crafted diffs — the module's public boundary (ADR-0002
 * acceptance: "tests run against fixture git repositories").
 */
class ScopeResolverGitTest {

    private val fooKt = "src/main/kotlin/com/example/Foo.kt"
    private val original = buildString {
        appendLine("package com.example")           // 1
        appendLine("")                               // 2
        appendLine("class Foo {")                    // 3
        appendLine("    fun a(): Int = 1")           // 4
        appendLine("    fun b(): Int = 2")           // 5
        appendLine("    fun c(): Int = 3")           // 6
        appendLine("}")                              // 7
    }

    @Test
    fun `empty changeset resolves to an empty scope`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))

        val scope = fixture.resolveScope()

        assertTrue(scope.isEmpty)
    }

    @Test
    fun `unstaged edit puts the changed lines in scope`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.checkoutNewBranch("feature")
        fixture.write(fooKt, original.replace("fun b(): Int = 2", "fun b(): Int = 22"))

        val scope = fixture.resolveScope()

        assertEquals(listOf(fooKt), scope.files.map { it.path })
        assertEquals(listOf(LineRange(5, 5)), scope.ranges(fooKt))
    }

    @Test
    fun `staged and unstaged edits both count`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.checkoutNewBranch("feature")
        fixture.write(fooKt, original.replace("fun a(): Int = 1", "fun a(): Int = 11"))
        fixture.add(fooKt)
        fixture.write(
            fooKt,
            original
                .replace("fun a(): Int = 1", "fun a(): Int = 11")
                .replace("fun c(): Int = 3", "fun c(): Int = 33"),
        )

        val scope = fixture.resolveScope()

        assertEquals(listOf(LineRange(4, 4), LineRange(6, 6)), scope.ranges(fooKt))
    }

    @Test
    fun `untracked production kotlin enters whole`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        val newKt = "src/main/kotlin/com/example/Bar.kt"
        fixture.write(newKt, "package com.example\n\nclass Bar\n")

        val scope = fixture.resolveScope()

        assertEquals(listOf(newKt), scope.files.map { it.path })
        assertTrue(scope.files.single().isWholeFile)
    }

    @Test
    fun `newly added committed file on the branch enters whole`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.checkoutNewBranch("feature")
        val newKt = "src/main/kotlin/com/example/Bar.kt"
        fixture.write(newKt, "package com.example\n\nclass Bar {\n    fun x() = 1\n}\n")
        fixture.add(newKt).commit("add Bar")

        val scope = fixture.resolveScope()

        assertEquals(listOf(newKt), scope.files.map { it.path })
        assertTrue(scope.files.single().isWholeFile)
    }

    @Test
    fun `resolves against the remote default branch via origin HEAD`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.withOriginRemote(defaultBranch = "main")
        fixture.checkoutNewBranch("feature")
        // main advances on the remote after the branch point...
        fixture.checkout("main")
        fixture.write(fooKt, original.replace("fun a(): Int = 1", "fun a(): Int = 99"))
        fixture.add(fooKt).commit("main advances")
        fixture.git("push", "origin", "main")
        fixture.git("checkout", "feature")
        // ...and feature edits a different line; only feature's change is in scope.
        fixture.write(fooKt, original.replace("fun c(): Int = 3", "fun c(): Int = 77"))

        val scope = fixture.resolveScope()

        assertEquals(listOf(LineRange(6, 6)), scope.ranges(fooKt))
    }

    @Test
    fun `unrelated histories fail with a clear error`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        // an orphan branch shares no ancestor with main
        fixture.git("checkout", "--orphan", "feature")
        fixture.git("rm", "-rf", ".")
        fixture.write(fooKt, original.replace("fun b(): Int = 2", "fun b(): Int = 22"))
        fixture.add(fooKt).commit("orphan root")

        val ex = assertThrows<ScopeResolutionException> { fixture.resolveScope() }
        assertTrue(ex.message!!.contains("no common ancestor"))
    }

    @Test
    fun `resolveAndWrite emits scope json at the default path`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.checkoutNewBranch("feature")
        fixture.write(fooKt, original.replace("fun b(): Int = 2", "fun b(): Int = 22"))

        val scope = ScopeResolver().resolveAndWrite(fixture.root)

        val written = fixture.root.resolve("build/komust/scope.json")
        assertTrue(written.exists())
        assertEquals(scope, ScopeJson.read(written))
        assertEquals(listOf(LineRange(5, 5)), scope.ranges(fooKt))
    }

    @Test
    fun `resolveAndWrite on an empty changeset writes an empty scope file`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))

        val scope = ScopeResolver().resolveAndWrite(fixture.root)

        assertTrue(scope.isEmpty)
        assertEquals(
            ScopeJson.encode(MutationScope.EMPTY) + "\n",
            fixture.root.resolve("build/komust/scope.json").let { java.nio.file.Files.readString(it) },
        )
    }

    @Test
    fun `paths stay repo-root-relative when pointed at a subdirectory`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.checkoutNewBranch("feature")
        fixture.write(fooKt, original.replace("fun b(): Int = 2", "fun b(): Int = 22"))
        val subDir = fixture.root.resolve("src/main/kotlin").also { it.createDirectories() }

        val scope = ScopeResolver().resolveFromGit(subDir)

        assertEquals(listOf(fooKt), scope.files.map { it.path })
    }

    @Test
    fun `diff is taken against the merge-base not the branch tip`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        // main moves on after the branch point...
        fixture.write(fooKt, original.replace("fun a(): Int = 1", "fun a(): Int = 99"))
        fixture.add(fooKt).commit("main advances")
        fixture.git("checkout", "-b", "feature", "HEAD~1")
        // ...and the branch changes a different line.
        fixture.write(fooKt, original.replace("fun c(): Int = 3", "fun c(): Int = 77"))

        val scope = fixture.resolveScope()

        // Only the branch's own change — main's later commit is behind the merge-base.
        assertEquals(listOf(LineRange(6, 6)), scope.ranges(fooKt))
    }

    @Test
    fun `test sources and non-kotlin files are excluded`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.checkoutNewBranch("feature")
        fixture.write("src/test/kotlin/com/example/FooTest.kt", "class FooTest")
        fixture.write("README.md", "# changed")
        fixture.write("build.gradle.kts", "// changed")

        val scope = fixture.resolveScope()

        assertTrue(scope.isEmpty)
    }

    @Test
    fun `deleted file drops out of scope`() {
        val second = "src/main/kotlin/com/example/Bar.kt"
        val fixture = GitFixture.create(
            mapOf(fooKt to original, second to "package com.example\n\nclass Bar\n"),
        )
        fixture.checkoutNewBranch("feature")
        fixture.delete(second)

        val scope = fixture.resolveScope()

        assertTrue(scope.isEmpty)
    }

    @Test
    fun `on the default branch only working-tree changes are in scope`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        // no branch checkout — still on main
        fixture.write(fooKt, original.replace("fun b(): Int = 2", "fun b(): Int = 20"))

        val scope = fixture.resolveScope()

        assertEquals(listOf(LineRange(5, 5)), scope.ranges(fooKt))
    }

    @Test
    fun `--since HEAD narrows scope to working-tree changes only`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.checkoutNewBranch("feature")
        fixture.write(fooKt, original.replace("fun a(): Int = 1", "fun a(): Int = 11"))
        fixture.add(fooKt).commit("committed edit on feature")
        fixture.write(
            fooKt,
            original
                .replace("fun a(): Int = 1", "fun a(): Int = 11")
                .replace("fun c(): Int = 3", "fun c(): Int = 33"),
        )

        // default: committed + working-tree changes, both vs the merge-base
        assertEquals(
            listOf(LineRange(4, 4), LineRange(6, 6)),
            fixture.resolveScope(ScopeSpec.Git()).ranges(fooKt),
        )
        // --since HEAD: merge-base(HEAD, HEAD) is HEAD, so only the uncommitted edit
        assertEquals(
            listOf(LineRange(6, 6)),
            fixture.resolveScope(ScopeSpec.Git(since = "HEAD")).ranges(fooKt),
        )
    }

    @Test
    fun `--since compares against a caller-supplied ref`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.write(fooKt, original.replace("fun a(): Int = 1", "fun a(): Int = 11"))
        fixture.add(fooKt).commit("c1")
        fixture.write(
            fooKt,
            original
                .replace("fun a(): Int = 1", "fun a(): Int = 11")
                .replace("fun c(): Int = 3", "fun c(): Int = 33"),
        )
        fixture.add(fooKt).commit("c2")

        val scope = fixture.resolveScope(ScopeSpec.Git(since = "HEAD~1"))

        // only c2's change; c1 is behind the --since ref
        assertEquals(listOf(LineRange(6, 6)), scope.ranges(fooKt))
    }

    @Test
    fun `--since a diverged branch scopes to the divergence point, not the branch tip`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.checkoutNewBranch("feature")
        fixture.write(fooKt, original.replace("fun c(): Int = 3", "fun c(): Int = 33"))
        fixture.add(fooKt).commit("feature edits c")
        fixture.checkout("main")
        fixture.checkoutNewBranch("other")
        fixture.write(fooKt, original.replace("fun a(): Int = 1", "fun a(): Int = 11"))
        fixture.add(fooKt).commit("other edits a")
        fixture.checkout("feature")

        val scope = fixture.resolveScope(ScopeSpec.Git(since = "other"))

        // merge-base(feature, other) is the common root: only feature's own change
        // shows — 'other's line-4 edit is on the far side and is not a reverted diff.
        assertEquals(listOf(LineRange(6, 6)), scope.ranges(fooKt))
    }

    @Test
    fun `--since with an unknown ref fails clearly`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))

        val ex = assertThrows<ScopeResolutionException> {
            fixture.resolveScope(ScopeSpec.Git(since = "no-such-ref"))
        }
        assertTrue(ex.message!!.contains("does not resolve"))
    }

    @Test
    fun `a renamed and edited file follows to its new path with only the changed lines`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.checkoutNewBranch("feature")
        val movedKt = "src/main/kotlin/com/example/Renamed.kt"
        fixture.git("mv", fooKt, movedKt)
        fixture.write(movedKt, original.replace("fun b(): Int = 2", "fun b(): Int = 22"))
        fixture.add().commit("rename and edit")

        val scope = fixture.resolveScope()

        assertEquals(listOf(movedKt), scope.files.map { it.path })
        assertEquals(listOf(LineRange(5, 5)), scope.ranges(movedKt))
    }

    @Test
    fun `a pure rename contributes nothing`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.checkoutNewBranch("feature")
        fixture.git("mv", fooKt, "src/main/kotlin/com/example/Renamed.kt")
        fixture.add().commit("rename only")

        assertTrue(fixture.resolveScope().isEmpty)
    }

    @Test
    fun `a renamed file whose content is rewritten past the rename threshold enters whole`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.checkoutNewBranch("feature")
        val movedKt = "src/main/kotlin/com/example/Rewritten.kt"
        fixture.git("mv", fooKt, movedKt)
        // completely different content — git will not pair this as a rename
        fixture.write(
            movedKt,
            buildString {
                appendLine("package com.example")
                appendLine("")
                appendLine("class Rewritten {")
                appendLine("    fun onlyThis(): String = \"nothing like the original\"")
                appendLine("}")
            },
        )
        fixture.add().commit("rename then rewrite")

        val scope = fixture.resolveScope()

        assertEquals(listOf(movedKt), scope.files.map { it.path })
        assertTrue(scope.files.single().isWholeFile)
    }

    @Test
    fun `writes scope json for the resolved scope`() {
        val fixture = GitFixture.create(mapOf(fooKt to original))
        fixture.checkoutNewBranch("feature")
        fixture.write(fooKt, original.replace("fun b(): Int = 2", "fun b(): Int = 22"))

        val scope = fixture.resolveScope()
        val target = fixture.root.resolve("build/komust/scope.json")
        ScopeJson.write(scope, target)

        assertEquals(scope, ScopeJson.read(target))
    }
}
