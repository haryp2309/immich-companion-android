package app.immichcompanion.tailscale

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** [needsUser]: it won't connect until the user signs in or an admin approves the device. */
class TailscaleNotConnectedException(message: String, val needsUser: Boolean = false) : IOException(message)

/**
 * Polls [status] until the node is Running. Fails right away for states that need
 * the user (sign-in, admin approval), and after [timeoutMs] if the node is still
 * starting.
 */
internal suspend fun awaitRunning(
    timeoutMs: Long,
    pollMs: Long = 250,
    status: suspend () -> TailscaleStatus,
): TailscaleStatus = withTimeoutOrNull(timeoutMs) {
    var st = status()
    while (!st.isRunning) {
        when (st.state) {
            "NeedsLogin" -> throw TailscaleNotConnectedException("Tailscale needs you to sign in", needsUser = true)
            "NeedsMachineAuth" -> throw TailscaleNotConnectedException(
                "This device is waiting for approval in the Tailscale admin console",
                needsUser = true,
            )
            "Stopped" -> throw TailscaleNotConnectedException(
                "Tailscale couldn't start" + (st.error?.let { ": $it" } ?: ""),
            )
        }
        delay(pollMs)
        st = status()
    }
    st
} ?: throw TailscaleNotConnectedException("Tailscale didn't connect within ${timeoutMs / 1000} seconds")
