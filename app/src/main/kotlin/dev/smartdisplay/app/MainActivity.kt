package dev.smartdisplay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smartdisplay.app.server.SavedServer
import dev.smartdisplay.app.ui.setup.SetupScreen
import dev.smartdisplay.app.ui.theme.SmartDisplayTheme
import dev.smartdisplay.app.ui.theme.eyebrow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val serverStore = (application as SmartDisplayApp).serverStore
        setContent {
            SmartDisplayTheme {
                val server by serverStore.server.collectAsStateWithLifecycle()
                when (val current = server) {
                    null -> SetupScreen()
                    else -> ConnectedScreen(current, onChangeServer = serverStore::clear)
                }
            }
        }
    }
}

/** Stands in until sign-in (v0.1 step 3) exists. */
@Composable
private fun ConnectedScreen(server: SavedServer, onChangeServer: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.connected_eyebrow).uppercase(),
                style = MaterialTheme.typography.eyebrow,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = server.name ?: stringResource(R.string.connected_fallback_name),
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = server.url,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.connected_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
            OutlinedButton(onClick = onChangeServer, modifier = Modifier.padding(top = 24.dp)) {
                Text(stringResource(R.string.connected_change_server))
            }
        }
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun ConnectedScreenPreview() {
    SmartDisplayTheme {
        ConnectedScreen(SavedServer("http://192.168.1.20:8123", "Home", null), onChangeServer = {})
    }
}
