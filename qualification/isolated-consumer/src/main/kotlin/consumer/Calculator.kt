package consumer

import io.komust.runtime.SuppressMutations

class Calculator {
    @SuppressMutations
    fun stableSeed(): Int = 2

    fun add(left: Int, right: Int): Int = left + right
}
