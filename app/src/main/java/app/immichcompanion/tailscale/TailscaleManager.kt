package app.immichcompanion.tailscale

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.util.Log
import app.immichcompanion.data.SettingsStore
import app.immichcompanion.tsbridge.Tsbridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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

/** Owns the embedded tsnet node: starting, stopping, status polling and network changes. */
class TailscaleManager(
    context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val stateDir = File(context.noBackupFilesDir, "tailscale")
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var pollJob: Job? = null

    private val _status = MutableStateFlow(TailscaleStatus())
    val status: StateFlow<TailscaleStatus> = _status.asStateFlow()

    /** Whether the user wants Immich traffic routed over Tailscale. */
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var localProxy: TailnetProxy? = null

    /** The tailnet proxy, or null until the node is connected (requests would just hang before that). */
    val proxy: TailnetProxy?
        get() = localProxy?.takeIf { _status.value.isRunning }

    fun init() {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = pushInterfaces()
            override fun onLost(network: Network) = pushInterfaces()
        })
        scope.launch {
            _enabled.value = settings.tailscaleEnabled.first()
            if (_enabled.value) start()
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        settings.setTailscaleEnabled(enabled)
        if (enabled) start() else stop()
    }

    /** Starts the node. A non-blank [authKey] logs in without the browser. */
    suspend fun start(authKey: String = "") = lock.withLock {
        withContext(Dispatchers.IO) {
            if (authKey.isNotBlank()) Tsbridge.stop()
            pushInterfaces()
            runCatching { Tsbridge.start(stateDir.absolutePath, hostname(), authKey.trim()) }
                .onFailure { Log.w(TAG, "start failed", it) }
            val port = Tsbridge.proxyPort().toInt()
            localProxy = if (port > 0) TailnetProxy(port, Tsbridge.proxyToken()) else null
            refreshStatus()
        }
        startPolling()
    }

    suspend fun stop() = lock.withLock {
        pollJob?.cancel()
        withContext(Dispatchers.IO) {
            localProxy = null
            Tsbridge.stop()
            refreshStatus()
        }
    }

    /** Logs this device out of the tailnet. The next start asks for a new login. */
    suspend fun logout() = lock.withLock {
        pollJob?.cancel()
        withContext(Dispatchers.IO) {
            localProxy = null
            runCatching { Tsbridge.logout() }.onFailure { Log.w(TAG, "logout failed", it) }
            refreshStatus()
        }
        _enabled.value = false
        settings.setTailscaleEnabled(false)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshStatus()
                // Poll fast while logging in, slowly once connected.
                delay(if (_status.value.isRunning) 10_000 else 1_000)
            }
        }
    }

    private fun refreshStatus() {
        _status.value = runCatching { json.decodeFromString<TailscaleStatus>(Tsbridge.status()) }
            .getOrElse { TailscaleStatus(state = "Stopped", error = it.message) }
    }

    private fun pushInterfaces() {
        runCatching { Tsbridge.setInterfaces(NetworkInterfaces.describe()) }
    }

    private fun hostname(): String =
        "immich-companion-" + Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private companion object {
        const val TAG = "TailscaleManager"
    }
}
