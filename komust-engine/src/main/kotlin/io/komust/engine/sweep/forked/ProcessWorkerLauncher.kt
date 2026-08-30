package io.komust.engine.sweep.forked

import io.komust.engine.sweep.forked.worker.WorkerMain
import java.io.File
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * The production [WorkerLauncher]: forks a real JVM running [WorkerMain].
 *
 * The command is `<java> <jvmArgs> -D<reloadableRoots> -cp <classpath> WorkerMain`.
 * A daemon thread parses the worker's stdout into [WorkerMessage]s and forwards
 * them (plus the terminal [WorkerEvent.Exited]) to the controller's sink; a
 * second daemon thread relays the worker's stderr to this process's stderr,
 * tagged, for debugging.
 *
 * @param javaExecutable the `java` binary (defaults to the one running this JVM).
 * @param classpath everything a worker needs on its class path: the engine, the
 *   JUnit Platform + a Jupiter engine, the `io.komust.runtime` switch, the
 *   Kotlin stdlib, **and** the code-under-test + test-class roots.
 * @param reloadableRoots the subset of [classpath] — code-under-test + test
 *   classes — the worker reloads in a fresh loader per mutant for state
 *   isolation (see [io.komust.engine.sweep.forked.worker.MutantClassLoader]).
 * @param jvmArgs extra JVM flags (heap sizing for the OOM path, `-javaagent`, …).
 */
internal class ProcessWorkerLauncher(
    private val classpath: List<Path>,
    private val reloadableRoots: List<Path>,
    private val jvmArgs: List<String> = emptyList(),
    private val javaExecutable: Path = defaultJavaExecutable(),
) : WorkerLauncher {

    override fun launch(id: Int, sink: (WorkerEnvelope) -> Unit): WorkerHandle {
        val command = buildList {
            add(javaExecutable.toString())
            addAll(jvmArgs)
            add("-D${WorkerMain.PROP_RELOADABLE_ROOTS}=${reloadableRoots.joinToString(File.pathSeparator)}")
            add("-cp")
            add(classpath.joinToString(File.pathSeparator))
            add(WORKER_MAIN_CLASS)
        }
        val process = ProcessBuilder(command).start()

        thread(isDaemon = true, name = "komust-worker-$id-stderr") {
            process.errorStream.bufferedReader().forEachLine { System.err.println("[komust worker $id] $it") }
        }
        thread(isDaemon = true, name = "komust-worker-$id-stdout") {
            process.inputStream.bufferedReader().forEachLine { line ->
                WorkerMessage.parse(line)?.let { sink(WorkerEnvelope(id, WorkerEvent.Message(it))) }
            }
            sink(WorkerEnvelope(id, WorkerEvent.Exited(process.waitFor())))
        }

        val stdin = process.outputStream.bufferedWriter()
        return object : WorkerHandle {
            override val id: Int = id

            override fun submit(item: WorkItem) = synchronized(stdin) {
                stdin.write(WorkerProtocol.json.encodeToString(WorkItem.serializer(), item))
                stdin.newLine()
                stdin.flush()
            }

            override fun endInput() {
                synchronized(stdin) { runCatching { stdin.close() } }
            }

            override fun kill() {
                process.destroyForcibly()
            }
        }
    }

    private companion object {
        const val WORKER_MAIN_CLASS = "io.komust.engine.sweep.forked.worker.WorkerMain"

        fun defaultJavaExecutable(): Path {
            val name = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
            return Path.of(System.getProperty("java.home"), "bin", name)
        }
    }
}
