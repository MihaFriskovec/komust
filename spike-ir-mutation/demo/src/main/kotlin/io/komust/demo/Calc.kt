package io.komust.demo

// PROTOTYPE — the code under mutation. Written with NO knowledge of komust.
// The `+` on line below is what the compiler plugin rewrites into a switchable branch.
fun add(a: Int, b: Int): Int = a + b

fun sumThree(a: Int, b: Int, c: Int): Int = a + b + c
