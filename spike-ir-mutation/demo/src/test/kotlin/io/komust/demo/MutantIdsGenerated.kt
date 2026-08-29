package io.komust.demo

// PROTOTYPE — regenerated from the plugin's build-time warnings (see run-log.txt).
// These are the exact ids the K2 IR plugin emitted for demo/Calc.kt.
// Order matters: the `add` operator is first, so the switch tests flip `add`.
object MutantIdsGenerated {
    val ids: List<String> = listOf(
        "Calc.kt:5:32#ARITH:PLUS_TO_MINUS@0", // add:      a + b   -> a - b
        "Calc.kt:7:45#ARITH:PLUS_TO_MINUS@0", // sumThree: (a + b) -> (a - b)
        "Calc.kt:7:45#ARITH:PLUS_TO_MINUS@1", // sumThree: (..+ c) -> (..- c)
    )
}
