package app.immichcompanion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.immichcompanion.ImmichCompanionApp
import app.immichcompanion.ui.theme.ImmichCompanionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ImmichCompanionApp).container
        setContent {
            ImmichCompanionTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            container = container,
                            onOpenTailscale = { nav.navigate("tailscale") },
                            onSignIn = { nav.navigate("login") },
                        )
                    }
                    composable("login") {
                        LoginScreen(container = container, onBack = { nav.popBackStack() }, onSignedIn = { nav.popBackStack() })
                    }
                    composable("tailscale") {
                        TailscaleScreen(container = container, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
