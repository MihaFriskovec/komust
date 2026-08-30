package io.komust.engine.coverage

import org.jacoco.agent.rt.IAgent
import org.jacoco.agent.rt.RT

/**
 * The narrow seam over the JaCoCo runtime agent the coverage pass needs:
 * grab-and-zero the in-JVM probe counters at each boundary so that what is
 * captured between two calls *is* the coverage of whatever ran in between
 * (research doc §a, the reset/dump-per-test pattern).
 *
 * Kept as an interface so [PerTestCoverageListener] is unit-testable against a
 * fake and the real `org.jacoco.agent.rt.RT` dependency stays at one edge.
 */
public interface CoverageAgent {

    /**
     * Return the execution data accumulated since the last call as a JaCoCo
     * binary `.exec` stream **and** zero the counters, re-arming for the next
     * window.
     */
    public fun captureAndReset(): ByteArray
}

/**
 * The production [CoverageAgent]: talks to the agent attached to this JVM via
 * `org.jacoco.agent.rt.RT.getAgent()`.
 *
 * Construct it through [attached], which fails fast with
 * [CoverageAgentUnavailableException] when no agent is on the command line
 * rather than letting a cryptic `IllegalStateException` surface mid-pass.
 */
public class JacocoRuntimeAgent private constructor(private val agent: IAgent) : CoverageAgent {

    override fun captureAndReset(): ByteArray = agent.getExecutionData(true)

    public companion object {
        /** Whether a JaCoCo runtime agent is attached to this JVM. */
        public fun isAttached(): Boolean = runCatching { RT.getAgent() }.isSuccess

        /**
         * The agent attached to this JVM, or [CoverageAgentUnavailableException]
         * if there is none.
         */
        public fun attached(): JacocoRuntimeAgent =
            try {
                JacocoRuntimeAgent(RT.getAgent())
            } catch (e: IllegalStateException) {
                throw CoverageAgentUnavailableException(e)
            } catch (e: NoClassDefFoundError) {
                throw CoverageAgentUnavailableException(e)
            }
    }
}
