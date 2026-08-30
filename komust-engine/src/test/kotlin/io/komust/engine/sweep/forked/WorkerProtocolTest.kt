package io.komust.engine.sweep.forked

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorkerProtocolTest {

    private fun roundTrip(message: WorkerMessage) =
        assertEquals(message, WorkerMessage.parse(message.encode()))

    @Test
    fun `every worker message survives an encode - parse round trip`() {
        roundTrip(WorkerMessage.Ready)
        roundTrip(WorkerMessage.Started("Calc.kt:4:40:ARITH_PLUS_TO_MINUS#0"))
        roundTrip(
            WorkerMessage.Completed(
                MutantOutcome(
                    mutantId = "Calc.kt:4:40:ARITH_PLUS_TO_MINUS#0",
                    status = MutantOutcome.Status.KILLED,
                    testsExecuted = 1,
                    killKind = KillKind.TEST_FAILURE,
                    killedByUniqueId = "[engine:junit-jupiter]/[class:CalcTest]/[method:add()]",
                ),
            ),
        )
        roundTrip(WorkerMessage.Completed(MutantOutcome("m", MutantOutcome.Status.TIMEOUT, testsExecuted = 2)))
        roundTrip(WorkerMessage.Fatal("covering test 'x' resolved to nothing"))
    }

    @Test
    fun `a stray stdout line without the marker is not a protocol message`() {
        assertNull(WorkerMessage.parse("some test printed this"))
        assertNull(WorkerMessage.parse(""))
        assertNull(WorkerMessage.parse("##komust## WAT unknown-verb"))
    }

    @Test
    fun `a work item survives a JSON round trip`() {
        val item = WorkItem(
            mutantId = "Calc.kt:4:40:ARITH_PLUS_TO_MINUS#0",
            tests = listOf(
                CoveringTestSpec("[engine:junit-jupiter]/[class:CalcTest]/[method:fast()]", 3_600),
                CoveringTestSpec("[engine:junit-jupiter]/[class:CalcTest]/[method:slow()]", 9_000),
            ),
        )
        val line = WorkerProtocol.json.encodeToString(WorkItem.serializer(), item)
        assertEquals(item, WorkerProtocol.json.decodeFromString(WorkItem.serializer(), line))
    }

    @Test
    fun `memory-error and timeout outcomes require a worker recycle, a test-failure kill does not`() {
        assertEquals(false, MutantOutcome("m", MutantOutcome.Status.SURVIVED, 1).requiresWorkerRecycle)
        assertEquals(
            false,
            MutantOutcome("m", MutantOutcome.Status.KILLED, 1, KillKind.TEST_FAILURE).requiresWorkerRecycle,
        )
        assertEquals(
            true,
            MutantOutcome("m", MutantOutcome.Status.KILLED, 1, KillKind.MEMORY_ERROR).requiresWorkerRecycle,
        )
        assertEquals(true, MutantOutcome("m", MutantOutcome.Status.TIMEOUT, 1).requiresWorkerRecycle)
    }
}
