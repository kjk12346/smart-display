package dev.smartdisplay.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.ConnectionStatus
import dev.smartdisplay.app.ha.DisconnectReason
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.server.SavedServer
import dev.smartdisplay.app.ui.common.Panel
import dev.smartdisplay.app.ui.common.PanelBody
import dev.smartdisplay.app.ui.signin.displayName

/** Settings, opened by long-pressing the ambient screen: the connection's details, and signing out. */
@Composable
fun SettingsScreen(
    server: SavedServer,
    home: HomeState,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Panel(eyebrow = stringResource(R.string.settings_eyebrow), title = server.displayName()) {
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 24.dp)) {
            Button(onClick = onBack) {
                Text(stringResource(R.string.settings_done))
            }
            OutlinedButton(onClick = onSignOut, modifier = Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.sign_out))
            }
        }
    }
}
