package consumer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CalculatorTest {
    @Test
    fun `adds two values`() {
        assertEquals(5, Calculator().add(2, 3))
    }
}
