package io.komust.engine.sweep.forked.worker

import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * A **child-first** class loader over the code-under-test and test-class roots,
 * created **fresh for every mutant** so that all static state in those classes —
 * `object` singletons, `companion object` caches, lazily-created temp-file
 * handles — starts clean each time (issue #34 / story 23: "one mutant's side
 * effects never contaminate another's verdict").
 *
 * The worker JVM itself is reused across many mutants (it stays JIT-warm and
 * keeps the test framework loaded); only the reloadable roots are thrown away
 * and reloaded per mutant.
 *
 * Framework packages always delegate to [parent] so identity is preserved where
 * it must be:
 *  - `io.komust.runtime.*` — the process-global runtime switch; the woven guard
 *    in a reloaded class and the worker's [io.komust.engine.sweep.MutantSwitchHandle]
 *    must read and write the **same** slot.
 *  - `org.junit.*` / `org.opentest4j.*` / `org.apiguardian.*` — the Platform
 *    Launcher and engine the worker drives the covering test through.
 *  - `kotlin.*` / `kotlinx.*` and the JDK — reloading the stdlib is pointless
 *    and breaks interop identity.
 */
internal class MutantClassLoader(
    reloadableRoots: List<Path>,
    parent: ClassLoader,
) : URLClassLoader(reloadableRoots.map { it.toUri().toURL() }.toTypedArray<URL>(), parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let {
                if (resolve) resolveClass(it)
                return it
            }
            val loaded = if (delegateToParent(name)) {
                parent.loadClass(name)
            } else {
                try {
                    findClass(name) // this loader's own reloadable roots, child-first
                } catch (_: ClassNotFoundException) {
                    parent.loadClass(name) // a framework / transitive class not under those roots
                }
            }
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }

    private fun delegateToParent(name: String): Boolean =
        DELEGATED_PREFIXES.any { name.startsWith(it) }

    private companion object {
        val DELEGATED_PREFIXES = listOf(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "kotlin.", "kotlinx.",
            "io.komust.runtime.",
            "org.junit.", "junit.", "org.opentest4j.", "org.apiguardian.",
            "org.jacoco.",
        )
    }
}
