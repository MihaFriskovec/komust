package io.komust.scope

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MutationScopeTest {

    @Test
    fun `of merges overlapping and adjacent ranges within a file`() {
        val scope = MutationScope.of(
            mapOf(
                "A.kt" to listOf(
                    LineRange(10, 12),
                    LineRange(13, 15), // adjacent -> merge
                    LineRange(11, 11), // contained -> merge
                    LineRange(40, 42), // disjoint -> kept
                ),
            ),
        )

        assertEquals(listOf(LineRange(10, 15), LineRange(40, 42)), scope.ranges("A.kt"))
    }

    @Test
    fun `mergeRanges sorts a disjoint unsorted list`() {
        assertEquals(
            listOf(LineRange(1, 3), LineRange(9, 9)),
            mergeRanges(listOf(LineRange(9, 9), LineRange(1, 3))),
        )
    }

    @Test
    fun `whole-file range swallows any other range for that file`() {
        val scope = MutationScope.of(
            mapOf("A.kt" to listOf(LineRange(5, 6), LineRange.WHOLE_FILE)),
        )
        assertEquals(listOf(LineRange.WHOLE_FILE), scope.ranges("A.kt"))
        assertTrue(scope.files.single().isWholeFile)
    }

    @Test
    fun `of sorts files by path and drops empty fragments`() {
        val scope = MutationScope.of(
            mapOf(
                "z/Z.kt" to listOf(LineRange(1, 1)),
                "a/A.kt" to listOf(LineRange(2, 2)),
                "empty/E.kt" to emptyList(),
            ),
        )
        assertEquals(listOf("a/A.kt", "z/Z.kt"), scope.files.map { it.path })
    }

    @Test
    fun `EMPTY scope is empty`() {
        assertTrue(MutationScope.EMPTY.isEmpty)
        assertFalse(MutationScope.of(mapOf("A.kt" to listOf(LineRange(1, 1)))).isEmpty)
    }

    @Test
    fun `line range rejects nonsense bounds`() {
        assertThrows<IllegalArgumentException> { LineRange(0, 5) }
        assertThrows<IllegalArgumentException> { LineRange(5, 4) }
    }

    @Test
    fun `whole-file range contains every line`() {
        assertTrue(1 in LineRange.WHOLE_FILE)
        assertTrue(1_000_000 in LineRange.WHOLE_FILE)
    }
}
