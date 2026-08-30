package io.komust.engine.coverage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmapParserTest {

    @Test
    fun `parses the Kotlin file and line sections`() {
        // Foo.kt (self, id 1) inlines a body from InlineKt (id 2): output lines
        // 8..10 of Foo were copied from InlineKt source lines 3..5.
        val smap = """
            SMAP
            Foo.kt
            Kotlin
            *S Kotlin
            *F
            + 1 Foo.kt
            example/Foo
            + 2 Inline.kt
            example/InlineKt
            *L
            1#1,7:1
            3#2,3:8
            *E
        """.trimIndent()

        val parsed = SmapParser.parse(smap)

        assertEquals(setOf(1, 2), parsed.files.keys)
        assertEquals("example.Foo", parsed.files[1]!!.binaryClassName)
        assertEquals("example.InlineKt", parsed.files[2]!!.binaryClassName)

        val inlineMapping = parsed.lineMappings.single { it.fileId == 2 }
        assertEquals(3 to 8, inlineMapping.inputStart to inlineMapping.outputStart)
        assertEquals(10, inlineMapping.outputEnd)
        assertEquals(2 to 3, inlineMapping.resolve(8))
        assertEquals(2 to 4, inlineMapping.resolve(9))
        assertEquals(2 to 5, inlineMapping.resolve(10))
        assertNull(inlineMapping.resolve(11))
    }

    @Test
    fun `self source file with a bare kt path resolves to no class`() {
        val smap = """
            SMAP
            Foo.kt
            Kotlin
            *S Kotlin
            *F
            + 1 Foo.kt
            Foo.kt
            *L
            1#1,5:1
            *E
        """.trimIndent()

        assertNull(SmapParser.parse(smap).files[1]!!.binaryClassName)
    }

    @Test
    fun `honours the output line increment`() {
        val smap = """
            SMAP
            X.kt
            Kotlin
            *S Kotlin
            *F
            + 1 X.kt
            p/XKt
            *L
            10#1,3:100,2
            *E
        """.trimIndent()

        val m = SmapParser.parse(smap).lineMappings.single()
        assertEquals(105, m.outputEnd) // 100 + 3*2 - 1
        assertEquals(1 to 10, m.resolve(100))
        assertEquals(1 to 10, m.resolve(101))
        assertEquals(1 to 11, m.resolve(102))
        assertEquals(1 to 12, m.resolve(104))
    }

    @Test
    fun `ignores a trailing KotlinDebug stratum`() {
        val smap = """
            SMAP
            Foo.kt
            Kotlin
            *S Kotlin
            *F
            + 1 Foo.kt
            p/Foo
            + 2 Bar.kt
            p/BarKt
            *L
            3#2,2:8
            *S KotlinDebug
            *F
            + 1 Foo.kt
            p/Foo
            *L
            99#1:8
            *E
        """.trimIndent()

        val parsed = SmapParser.parse(smap)
        // Only the Kotlin stratum's single mapping, not the KotlinDebug one.
        assertEquals(1, parsed.lineMappings.size)
        assertEquals(2, parsed.lineMappings.single().fileId)
    }

    @Test
    fun `garbage in returns an empty parse, never an exception`() {
        assertTrue(SmapParser.parse(null).lineMappings.isEmpty())
        assertTrue(SmapParser.parse("").lineMappings.isEmpty())
        assertTrue(SmapParser.parse("not an smap at all").lineMappings.isEmpty())
        assertTrue(SmapParser.parse("SMAP\nFoo.kt\nKotlin\n*S Kotlin\n*L\nnonsense\n*E").lineMappings.isEmpty())
    }
}
