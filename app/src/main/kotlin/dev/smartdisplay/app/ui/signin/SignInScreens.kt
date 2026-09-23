package dev.smartdisplay.app.ui.signin

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.ConnectionStatus
import dev.smartdisplay.app.ha.DisconnectReason
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.auth.SessionState
import dev.smartdisplay.app.auth.SignInProblem
import dev.smartdisplay.app.server.SavedServer
import dev.smartdisplay.app.ui.common.Panel
import dev.smartdisplay.app.ui.common.PanelBody
import dev.smartdisplay.app.ui.theme.SmartDisplayTheme

/** Asks to sign in with Home Assistant's own login page, and shows progress when the browser comes back. */
@Composable
fun SignInScreen(
    server: SavedServer,
    state: SessionState,
    onSignIn: () -> Unit,
    onChangeServer: () -> Unit,
) {
    val finishing = state == SessionState.Finishing
    Panel(eyebrow = stringResource(R.string.sign_in_eyebrow), title = server.displayName()) {
        PanelBody(server.url, topPadding = 4.dp)
        PanelBody(stringResource(R.string.sign_in_body), topPadding = 24.dp)
        (state as? SessionState.SignedOut)?.problem?.let { problem ->
            Text(
                text = stringResource(problem.message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 24.dp)) {
            Button(onClick = onSignIn, enabled = !finishing) {
                if (finishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(stringResource(R.string.sign_in_button))
                }
            }
            TextButton(onClick = onChangeServer, enabled = !finishing, modifier = Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.change_server))
            }
        }
    }
}

/** Stands in for the display until the ambient screen (v0.1 step 5) exists; shows the live connection working. */
@Composable
fun SignedInScreen(
    server: SavedServer,
    home: HomeState,
    onSignOut: () -> Unit,
) {
    Panel(eyebrow = stringResource(R.string.signed_in_eyebrow), title = server.displayName()) {
        PanelBody(server.url, topPadding = 4.dp)
        val status = when (val current = home.status) {
            ConnectionStatus.Connected -> stringResource(
                R.string.live_connected,
                home.config?.locationName ?: server.displayName(),
                home.config?.version ?: "?",
            )
            is ConnectionStatus.Waiting -> stringResource(
                when (current.reason) {
                    DisconnectReason.Unreachable -> R.string.live_waiting_unreachable
                    DisconnectReason.Rejected -> R.string.live_waiting_rejected
                    DisconnectReason.Protocol -> R.string.live_waiting_protocol
                }
            )
            else -> stringResource(R.string.live_connecting)
        }
        PanelBody(status, topPadding = 24.dp)
        if (home.loaded) {
            PanelBody(
                stringResource(R.string.live_counts, home.entities.size, home.areas.size, home.devices.size)
            )
            val change = home.lastChange
            PanelBody(
                if (change == null) {
                    stringResource(R.string.live_no_changes)
                } else {
                    stringResource(R.string.live_last_change, change.friendlyName, change.state)
                }
            )
        }
        PanelBody(stringResource(R.string.signed_in_next))
        OutlinedButton(onClick = onSignOut, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringResource(R.string.sign_out))
        }
    }
}

@Composable
private fun SavedServer.displayName() = name ?: stringResource(R.string.server_fallback_name)

@get:StringRes
private val SignInProblem.message: Int
    get() = when (this) {
        SignInProblem.NoBrowser -> R.string.sign_in_problem_no_browser
        SignInProblem.Interrupted -> R.string.sign_in_problem_interrupted
        SignInProblem.Rejected -> R.string.sign_in_problem_rejected
        SignInProblem.NoAnswer -> R.string.sign_in_problem_no_answer
        SignInProblem.Expired -> R.string.sign_in_problem_expired
        SignInProblem.CantStore -> R.string.sign_in_problem_cant_store
    }

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun SignInPreview() {
    SmartDisplayTheme {
        SignInScreen(
            server = SavedServer("http://192.168.1.20:8123", "Home", null),
            state = SessionState.SignedOut(SignInProblem.Interrupted),
            onSignIn = {},
            onChangeServer = {},
        )
    }
}
