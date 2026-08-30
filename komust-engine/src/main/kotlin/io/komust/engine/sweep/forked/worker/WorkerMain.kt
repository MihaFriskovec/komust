package io.komust.engine.sweep.forked.worker

import io.komust.engine.coverage.TestId
import io.komust.engine.sweep.CoveringTestOutcome
import io.komust.engine.sweep.JUnitPlatformCoveringTestRunner
import io.komust.engine.sweep.MutantSwitchHandle
import io.komust.engine.sweep.TestVerdict
import io.komust.engine.sweep.UnresolvableCoveringTestException
import io.komust.engine.sweep.forked.CoveringTestSpec
import io.komust.engine.sweep.forked.KillKind
import io.komust.engine.sweep.forked.MutantOutcome
import io.komust.engine.sweep.forked.WorkItem
import io.komust.engine.sweep.forked.WorkerMessage
import io.komust.engine.sweep.forked.WorkerProtocol
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * The entry point of a forked worker JVM (ADR-0003 §Isolation architecture).
 *
 * The controller ([io.komust.engine.sweep.forked.ForkedMutantSweep]) forks this
 * with the [PROP_RELOADABLE_ROOTS] system property, then streams [WorkItem]s one
 * per line on stdin. For each item the worker:
 *
 *  1. announces `START`, builds a **fresh [MutantClassLoader]** (state isolation),
 *  2. switches the mutant on via the process-global runtime switch,
 *  3. runs the covering tests fastest-first on a **harness thread** with
 *     `join(budget)` — a test that overruns its budget is a `TIMEOUT` and the
 *     worker **halts itself** (the runaway thread is unkillable); a
 *     `VirtualMachineError` (OOM / `StackOverflowError`) is a memory-error
 *     `KILLED` and the worker also halts (its heap is now untrustworthy),
 *  4. stops at the first failing test (**fail-fast** → `KILLED`), or reports
 *     `SURVIVED` if every covering test passed,
 *  5. clears the switch and reads the next item. EOF on stdin → clean exit.
 *
 * On a self-halt the controller respawns a fresh worker that picks up the queue.
 */
public object WorkerMain {

    /** `File.pathSeparator`-joined code-under-test + test-class roots to reload per mutant. */
    public const val PROP_RELOADABLE_ROOTS: String = "komust.worker.reloadableRoots"

    // The controller keys off the START/RESULT stream, not the exit code; these
    // just make a recycled worker's exit legible in a stderr log.
    private const val EXIT_TIMEOUT_RECYCLE = 70
    private const val EXIT_MEMORY_ERROR_RECYCLE = 71

    @JvmStatic
    public fun main(args: Array<String>) {
        // Take exclusive ownership of the real stdout for the protocol stream and
        // push anything the code under test prints onto stderr, which the
        // controller passes through for debugging. The MARKER prefix is a second
        // line of defence.
        val protocol = PrintStream(FileOutputStream(FileDescriptor.out), true)
        System.setOut(System.err)

        val reloadableRoots = System.getProperty(PROP_RELOADABLE_ROOTS)
            ?.split(File.pathSeparatorChar)
            ?.filter { it.isNotBlank() }
            ?.map { Path.of(it) }
            ?: error("$PROP_RELOADABLE_ROOTS system property is required")

        val worker = Worker(reloadableRoots, protocol)
        worker.emit(WorkerMessage.Ready)

        generateSequence(::readLine).forEach { line ->
            if (line.isBlank()) return@forEach
            worker.score(WorkerProtocol.json.decodeFromString(WorkItem.serializer(), line))
        }
        exitProcess(0)
    }

    private class Worker(
        private val reloadableRoots: List<Path>,
        private val protocol: PrintStream,
    ) {
        private val frameworkLoader: ClassLoader = WorkerMain::class.java.classLoader
        private val switch = MutantSwitchHandle.processGlobal(frameworkLoader)

        fun emit(message: WorkerMessage) {
            protocol.println(message.encode())
            protocol.flush()
        }

        fun score(item: WorkItem) {
            emit(WorkerMessage.Started(item.mutantId))
            MutantClassLoader(reloadableRoots, frameworkLoader).use { loader ->
                switch.activate(item.mutantId)
                try {
                    emit(runCovering(item, loader))
                } catch (e: UnresolvableCoveringTestException) {
                    emit(WorkerMessage.Fatal(e.message ?: "unresolvable covering test"))
                    exitProcess(1)
                } finally {
                    switch.clear()
                }
            }
        }

        private fun runCovering(item: WorkItem, loader: MutantClassLoader): WorkerMessage {
            val runner = JUnitPlatformCoveringTestRunner()
            var executed = 0
            for (spec in item.tests) {
                executed++
                when (runOnHarnessThread(spec, loader, runner)) {
                    TestRun.PASSED -> Unit
                    TestRun.FAILED -> return completed(item, MutantOutcome.Status.KILLED, executed, KillKind.TEST_FAILURE, spec.uniqueId)
                    TestRun.TIMED_OUT -> {
                        emit(completed(item, MutantOutcome.Status.TIMEOUT, executed))
                        exitProcess(EXIT_TIMEOUT_RECYCLE)
                    }
                    TestRun.MEMORY_ERROR -> {
                        emit(completed(item, MutantOutcome.Status.KILLED, executed, KillKind.MEMORY_ERROR))
                        exitProcess(EXIT_MEMORY_ERROR_RECYCLE)
                    }
                }
            }
            return completed(item, MutantOutcome.Status.SURVIVED, executed)
        }

        /**
         * Run one covering test on a dedicated thread and wait up to its budget.
         * A thread still alive at the deadline is the unkillable runaway of a
         * non-terminating mutant → [TestRun.TIMED_OUT].
         */
        private fun runOnHarnessThread(
            spec: CoveringTestSpec,
            loader: MutantClassLoader,
            runner: JUnitPlatformCoveringTestRunner,
        ): TestRun {
            val outcome = AtomicReference<CoveringTestOutcome>()
            val memoryError = AtomicReference<VirtualMachineError>()
            val harnessError = AtomicReference<Throwable>()

            val thread = Thread({
                try {
                    outcome.set(runner.runReportingFailures(TestId(spec.uniqueId)))
                } catch (e: VirtualMachineError) {
                    memoryError.set(e)
                } catch (e: Throwable) {
                    harnessError.set(e)
                }
            }, "komust-covering-test")
            thread.isDaemon = true
            thread.contextClassLoader = loader
            thread.start()
            thread.join(spec.timeoutMillis)
            if (thread.isAlive) return TestRun.TIMED_OUT
            thread.join() // now instant — establishes happens-before on the fields below

            if (memoryError.get() != null) return TestRun.MEMORY_ERROR
            harnessError.get()?.let { throw it }
            val result = outcome.get()
            return when {
                result.verdict != TestVerdict.FAILED -> TestRun.PASSED
                // A VirtualMachineError JUnit caught and reported as a test failure
                // still means this worker's heap is suspect — recycle it.
                result.isMemoryError -> TestRun.MEMORY_ERROR
                else -> TestRun.FAILED
            }
        }

        private fun completed(
            item: WorkItem,
            status: MutantOutcome.Status,
            executed: Int,
            killKind: KillKind? = null,
            killedByUniqueId: String? = null,
        ) = WorkerMessage.Completed(MutantOutcome(item.mutantId, status, executed, killKind, killedByUniqueId))
    }

    private enum class TestRun { PASSED, FAILED, TIMED_OUT, MEMORY_ERROR }
}
