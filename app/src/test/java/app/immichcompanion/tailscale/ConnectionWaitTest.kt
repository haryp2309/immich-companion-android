package app.immichcompanion.tailscale

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionWaitTest {
    private fun states(vararg s: String): suspend () -> TailscaleStatus {
        val it = s.iterator()
        var last = s.last()
        return { TailscaleStatus(state = if (it.hasNext()) it.next().also { v -> last = v } else last) }
    }

    @Test fun returnsOnceRunning() = runTest {
        val st = awaitRunning(timeoutMs = 20_000, pollMs = 250, status = states("NoState", "Starting", "Running"))
        assertTrue(st.isRunning)
        assertEquals(500L, currentTime)
    }

    @Test fun failsFastWhenSignInNeeded() = runTest {
        val err = runCatching { awaitRunning(20_000, status = states("Starting", "NeedsLogin")) }.exceptionOrNull()
        assertTrue(err is TailscaleNotConnectedException && err.message!!.contains("sign in"))
        assertTrue(currentTime < 1_000)
    }

    @Test fun failsFastWhenWaitingForApproval() = runTest {
        val err = runCatching { awaitRunning(20_000, status = states("NeedsMachineAuth")) }.exceptionOrNull()
        assertTrue(err is TailscaleNotConnectedException && err.message!!.contains("approval"))
    }

    @Test fun timesOutWhileStillStarting() = runTest {
        val err = runCatching { awaitRunning(5_000, status = states("Starting")) }.exceptionOrNull()
        assertTrue(err is TailscaleNotConnectedException && err.message!!.contains("5 seconds"))
        assertEquals(5_000L, currentTime)
    }
}
