package dev.smartdisplay.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smartdisplay.app.auth.REDIRECT_URI
import dev.smartdisplay.app.auth.SessionState
import dev.smartdisplay.app.ui.common.LocalNetworkPrompt
import dev.smartdisplay.app.ui.common.rememberLocalNetworkAccess
import dev.smartdisplay.app.ui.setup.SetupScreen
import dev.smartdisplay.app.ui.signin.SignInScreen
import dev.smartdisplay.app.ui.signin.SignedInScreen
import dev.smartdisplay.app.ui.theme.SmartDisplayTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as SmartDisplayApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch carries a new sign-in result; after a rotation the intent is the one already handled.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            SmartDisplayTheme {
                AppContent(app)
            }
        }
    }

    // singleTask: the browser's sign-in redirect comes back to the existing activity.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val redirect = REDIRECT_URI.toUri()
        if (uri.scheme == redirect.scheme && uri.host == redirect.host) app.session.handleRedirect(uri)
    }
}

@Composable
private fun AppContent(app: SmartDisplayApp) {
    val localNetwork = rememberLocalNetworkAccess()
    if (!localNetwork.granted) {
        LocalNetworkPrompt(localNetwork)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val server by app.serverStore.server.collectAsStateWithLifecycle()
    val session by app.session.state.collectAsStateWithLifecycle()

    val current = server
    when {
        current == null -> SetupScreen()
        session == SessionState.SignedIn -> SignedInScreen(
            server = current,
            loadConfig = app.session::serverConfig,
            onSignOut = { scope.launch { app.session.signOut() } },
        )
        else -> SignInScreen(
            server = current,
            state = session,
            onSignIn = { app.session.beginSignIn(context) },
            onChangeServer = { scope.launch { app.session.forgetServer() } },
        )
    }
}
