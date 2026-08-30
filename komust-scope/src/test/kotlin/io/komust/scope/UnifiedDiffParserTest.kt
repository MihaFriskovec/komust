package io.komust.scope

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedDiffParserTest {

    @Test
    fun `reads changed post-image ranges from unified-zero hunks`() {
        val diff = """
            diff --git a/src/main/kotlin/Foo.kt b/src/main/kotlin/Foo.kt
            index 111..222 100644
            --- a/src/main/kotlin/Foo.kt
            +++ b/src/main/kotlin/Foo.kt
            @@ -3,0 +4,2 @@ class Foo {
            +    val added = 1
            +    val alsoAdded = 2
            @@ -20,1 +22,1 @@
            -    val old = 3
            +    val changed = 3
        """.trimIndent()

        val files = UnifiedDiffParser.parse(diff)

        assertEquals(1, files.size)
        val foo = files.single()
        assertEquals("src/main/kotlin/Foo.kt", foo.path)
        assertEquals(false, foo.isNewFile)
        assertEquals(listOf(LineRange(4, 5), LineRange(22, 22)), foo.ranges)
    }

    @Test
    fun `single-line hunk without a count is one line`() {
        val diff = """
            diff --git a/A.kt b/A.kt
            --- a/A.kt
            +++ b/A.kt
            @@ -5 +5 @@
            -old
            +new
        """.trimIndent()

        assertEquals(listOf(LineRange(5, 5)), UnifiedDiffParser.parse(diff).single().ranges)
    }

    @Test
    fun `pure deletion hunk surfaces the adjacent line`() {
        val diff = """
            diff --git a/A.kt b/A.kt
            --- a/A.kt
            +++ b/A.kt
            @@ -8,3 +7,0 @@
            -a
            -b
            -c
        """.trimIndent()

        assertEquals(listOf(LineRange(7, 7)), UnifiedDiffParser.parse(diff).single().ranges)
    }

    @Test
    fun `new file is flagged`() {
        val diff = """
            diff --git a/New.kt b/New.kt
            new file mode 100644
            index 0000000..abc1234
            --- /dev/null
            +++ b/New.kt
            @@ -0,0 +1,3 @@
            +package x
            +
            +class New
        """.trimIndent()

        val file = UnifiedDiffParser.parse(diff).single()
        assertTrue(file.isNewFile)
        assertEquals("New.kt", file.path)
    }

    @Test
    fun `pure rename with no edits yields a file with no ranges`() {
        val diff = """
            diff --git a/Old.kt b/New.kt
            similarity index 100%
            rename from Old.kt
            rename to New.kt
        """.trimIndent()

        val files = UnifiedDiffParser.parse(diff)
        assertEquals(emptyList<DiffFile>(), files)
    }

    @Test
    fun `multiple files parsed independently`() {
        val diff = """
            diff --git a/A.kt b/A.kt
            --- a/A.kt
            +++ b/A.kt
            @@ -1 +1 @@
            -a
            +A
            diff --git a/B.kt b/B.kt
            --- a/B.kt
            +++ b/B.kt
            @@ -2,0 +3,1 @@
            +new
        """.trimIndent()

        val files = UnifiedDiffParser.parse(diff).associateBy { it.path }
        assertEquals(listOf(LineRange(1, 1)), files.getValue("A.kt").ranges)
        assertEquals(listOf(LineRange(3, 3)), files.getValue("B.kt").ranges)
    }
}
