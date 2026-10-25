package app.immichcompanion.tailscale

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.immichcompanion.data.SettingsStore
import app.immichcompanion.tsbridge.Tsbridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class TailscaleStatus(
    /** Tailscale backend state: Stopped, NoState, NeedsLogin, NeedsMachineAuth, Starting, Running. */
    val state: String = "Stopped",
    val authUrl: String? = null,
    val ips: List<String> = emptyList(),
    val dnsName: String? = null,
    val tailnet: String? = null,
    val loginName: String? = null,
    val error: String? = null,
) {
    val isRunning get() = state == "Running"
}

/** Loopback HTTP proxy that tunnels into the tailnet. */
data class TailnetProxy(val port: Int, val token: String)

/** Owns the embedded tsnet node: starting, stopping, connection status and network changes. */
class TailscaleManager(
    context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val stateDir = File(context.noBackupFilesDir, "tailscale")
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private val _status = MutableStateFlow(TailscaleStatus())
    val status: StateFlow<TailscaleStatus> = _status.asStateFlow()

    @Volatile
    private var statusAt = 0L

    /** Whether the user wants Immich traffic routed over Tailscale. */
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** The loopback proxy of the started node. Use [awaitProxy] to make sure the node is also connected. */
    @Volatile
    var proxy: TailnetProxy? = null
        private set

    fun init() {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = pushInterfaces()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = pushInterfaces()
            override fun onLost(network: Network) = pushInterfaces()
        })
        scope.launch {
            _enabled.value = settings.tailscaleEnabled.first()
            if (_enabled.value) start()
        }
        // Keep the status fresh only while something (a screen) is watching it.
        scope.launch(Dispatchers.IO) {
            _status.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { watched ->
                while (watched) {
                    refreshStatus()
                    delay(if (_status.value.isRunning) 5_000 else 1_000)
                }
            }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        settings.setTailscaleEnabled(enabled)
        if (enabled) start() else stop()
    }

    /**
     * Starts the node if needed and waits until it's connected. Throws
     * [TailscaleNotConnectedException] if it needs the user or doesn't connect in time.
     */
    suspend fun awaitProxy(timeoutMs: Long = 20_000): TailnetProxy {
        val cached = proxy
        if (cached != null && _status.value.isRunning && SystemClock.elapsedRealtime() - statusAt < 2_000) return cached
        if (proxy == null) start()
        awaitRunning(timeoutMs) { withContext(Dispatchers.IO) { refreshStatus() } }
        return proxy ?: throw TailscaleNotConnectedException("Tailscale was turned off")
    }

    /** Starts the node. A non-blank [authKey] logs in without the browser. */
    suspend fun start(authKey: String = "") = lock.withLock {
        withContext(Dispatchers.IO) {
            if (authKey.isNotBlank()) Tsbridge.stop()
            pushInterfaces()
            runCatching { Tsbridge.start(stateDir.absolutePath, hostname(), authKey.trim()) }
                .onFailure { Log.w(TAG, "start failed", it) }
            val port = Tsbridge.proxyPort().toInt()
            proxy = if (port > 0) TailnetProxy(port, Tsbridge.proxyToken()) else null
            refreshStatus()
        }
    }

    suspend fun stop() = lock.withLock {
        withContext(Dispatchers.IO) {
            proxy = null
            Tsbridge.stop()
            refreshStatus()
        }
    }

    /** Logs this device out of the tailnet. The next start asks for a new login. */
    suspend fun logout() = lock.withLock {
        withContext(Dispatchers.IO) {
            proxy = null
            runCatching { Tsbridge.logout() }.onFailure { Log.w(TAG, "logout failed", it) }
            refreshStatus()
        }
        _enabled.value = false
        settings.setTailscaleEnabled(false)
    }

    private fun refreshStatus(): TailscaleStatus {
        val st = runCatching { json.decodeFromString<TailscaleStatus>(Tsbridge.status()) }
            .getOrElse { TailscaleStatus(state = "Stopped", error = it.message) }
        _status.value = st
        statusAt = SystemClock.elapsedRealtime()
        return st
    }

    /**
     * Passes the current interfaces to Go, which can't read them on Android.
     * This also makes a running node re-check the network.
     */
    private fun pushInterfaces() {
        runCatching { Tsbridge.setInterfaces(NetworkInterfaces.describe()) }
            .onFailure { Log.w(TAG, "couldn't pass network interfaces to Tailscale", it) }
    }

    private fun hostname(): String =
        "immich-companion-" + Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private companion object {
        const val TAG = "TailscaleManager"
    }
}
