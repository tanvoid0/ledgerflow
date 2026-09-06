package io.tanvoid0.codecraft

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The Setup tab's checks are "did this tool answer at all" probes, so the only
 * thing that matters is the exit code — and that a probe which never returns
 * gives up instead of holding the panel's answer forever.
 */
class VerifyTest : BasePlatformTestCase() {

    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** Runs the probe and pumps the event queue until the callback lands. */
    private fun verify(cmd: String, timeoutSeconds: Long = 10): Boolean {
        var result: Boolean? = null
        Ide(project).verify(cmd, timeoutSeconds) { ok, _ -> result = ok }
        val deadline = System.currentTimeMillis() + 30_000
        while (result == null && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        return result ?: error("verify never called back for: $cmd")
    }

    fun testExitZeroIsInstalled() {
        assertTrue(verify("exit 0"))
    }

    fun testNonZeroExitIsNotInstalled() {
        assertFalse(verify("exit 1"))
    }

    /** A command that is not there at all fails rather than throwing. */
    fun testAMissingCommandIsNotInstalled() {
        assertFalse(verify("definitely-not-a-real-command --version"))
    }

    /** A probe that hangs is killed and answered "no", not waited out. */
    fun testAHangingProbeGivesUp() {
        val hang = if (windows) "ping -n 30 127.0.0.1" else "sleep 30"
        val started = System.currentTimeMillis()

        assertFalse(verify(hang, timeoutSeconds = 1))

        assertTrue("should have given up in about a second", System.currentTimeMillis() - started < 15_000)
    }
}
