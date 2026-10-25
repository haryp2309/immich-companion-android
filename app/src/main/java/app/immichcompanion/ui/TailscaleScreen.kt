package app.immichcompanion.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immichcompanion.AppContainer
import kotlinx.coroutines.launch

@Composable
fun TailscaleScreen(container: AppContainer, onBack: () -> Unit) {
    val ts = container.tailscale
    val enabled by ts.enabled.collectAsStateWithLifecycle()
    val status by ts.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAuthKey by rememberSaveable { mutableStateOf(false) }
    var authKey by rememberSaveable { mutableStateOf("") }

    Scaffold(topBar = { TopBar("Tailscale", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Connect through Tailscale", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Only this app's traffic uses the tailnet. No VPN is set up, so it works next to other VPNs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { on -> container.scope.launch { ts.setEnabled(on) } })
            }

            if (enabled) {
                SectionCard("Status") {
                    InfoRow("State", status.state.toDisplayState())
                    status.loginName?.let { InfoRow("Account", it) }
                    status.tailnet?.let { InfoRow("Tailnet", it) }
                    status.dnsName?.let { InfoRow("Device name", it) }
                    status.ips.forEach { InfoRow("IP", it) }
                    status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }

                val authUrl = status.authUrl
                if (status.state == "NeedsLogin") {
                    SectionCard("Sign in") {
                        if (authUrl != null) {
                            Text("Sign in to Tailscale in the browser and approve this device.")
                            Button(onClick = {
                                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(authUrl))
                            }) { Text("Sign in to Tailscale") }
                        } else {
                            Text("Getting a sign-in link…")
                        }
                        TextButton(onClick = { showAuthKey = !showAuthKey }) { Text("Use an auth key instead") }
                        if (showAuthKey) {
                            OutlinedTextField(
                                value = authKey,
                                onValueChange = { authKey = it },
                                label = { Text("Auth key (tskey-auth-…)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { container.scope.launch { ts.start(authKey) }; authKey = "" },
                                enabled = authKey.isNotBlank(),
                            ) { Text("Connect with key") }
                        }
                    }
                }

                if (status.isRunning) {
                    OutlinedButton(onClick = { container.scope.launch { ts.logout() } }) {
                        Text("Log out of Tailscale")
                    }
                }
            }
        }
    }
}
