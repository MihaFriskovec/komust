package io.komust.scope

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProductionKotlinFilterTest {

    private val filter = ProductionKotlinFilter()

    @Test
    fun `accepts production kotlin sources`() {
        assertTrue(filter.accepts("src/main/kotlin/com/example/Foo.kt"))
        assertTrue(filter.accepts("app/src/main/kotlin/Bar.kt"))
        assertTrue(filter.accepts("lib/Baz.kt")) // non-conventional layout still accepted
    }

    @Test
    fun `rejects non-kotlin and script files`() {
        assertFalse(filter.accepts("src/main/kotlin/Foo.java"))
        assertFalse(filter.accepts("build.gradle.kts"))
        assertFalse(filter.accepts("README.md"))
    }

    @Test
    fun `rejects test source sets`() {
        assertFalse(filter.accepts("src/test/kotlin/com/example/FooTest.kt"))
        assertFalse(filter.accepts("src/integrationTest/kotlin/FooIT.kt"))
        assertFalse(filter.accepts("src/testFixtures/kotlin/Fixtures.kt"))
        assertFalse(filter.accepts("app/src/androidTest/kotlin/FooTest.kt"))
    }

    @Test
    fun `rejects generated and build output`() {
        assertFalse(filter.accepts("build/generated/source/Foo.kt"))
        assertFalse(filter.accepts("komust-scope/build/classes/Foo.kt"))
        assertFalse(filter.accepts("out/production/Foo.kt"))
        assertFalse(filter.accepts(".gradle/foo/Bar.kt"))
    }

    @Test
    fun `does not mistake a non-test source set with test in the name`() {
        assertTrue(filter.accepts("src/latest/kotlin/Foo.kt"))
    }

    @Test
    fun `prunesDirectory skips build output and dot-directories`() {
        assertTrue(filter.prunesDirectory("build"))
        assertTrue(filter.prunesDirectory(".git"))
        assertTrue(filter.prunesDirectory(".idea"))
        assertFalse(filter.prunesDirectory("src"))
        assertFalse(filter.prunesDirectory("main"))
    }
}
