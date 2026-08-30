package io.komust.engine.coverage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CoverageIndexTest {

    private val t1 = TestId("[engine:junit-jupiter]/[class:FooTest]/[method:a()]")
    private val t2 = TestId("[engine:junit-jupiter]/[class:FooTest]/[method:b()]")

    @Test
    fun `builder unions tests covering the same line and keeps lines distinct`() {
        val index = CoverageIndexBuilder().apply {
            add("com.example.Foo", 10, t1)
            add("com.example.Foo", 10, t2)
            add("com.example.Foo", 11, t1)
        }.build()

        assertEquals(setOf(t1, t2), index.testsCovering("com.example.Foo", 10))
        assertEquals(setOf(t1), index.testsCovering("com.example.Foo", 11))
        assertEquals(2, index.size)
    }

    @Test
    fun `an uncovered line returns the empty set, never null - the NO_COVERAGE signal`() {
        val index = CoverageIndexBuilder().apply { add("com.example.Foo", 10, t1) }.build()

        assertEquals(emptySet<TestId>(), index.testsCovering("com.example.Foo", 99))
        assertEquals(emptySet<TestId>(), index.testsCovering("com.example.Other", 10))
    }

    @Test
    fun `builder drops non-positive lines`() {
        val index = CoverageIndexBuilder().apply {
            add("com.example.Foo", 0, t1)
            add("com.example.Foo", -1, t1)
        }.build()
        assertTrue(index.isEmpty)
    }

    @Test
    fun `CoverageKey rejects a non-positive line`() {
        assertThrows<IllegalArgumentException> { CoverageKey("com.example.Foo", 0) }
    }

    @Test
    fun `empty index reports empty`() {
        assertTrue(CoverageIndex.EMPTY.isEmpty)
        assertFalse(CoverageIndexBuilder().apply { add("A", 1, t1) }.build().isEmpty)
    }
}
