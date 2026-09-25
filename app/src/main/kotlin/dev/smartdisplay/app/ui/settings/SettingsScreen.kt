package dev.smartdisplay.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.ConnectionStatus
import dev.smartdisplay.app.ha.DisconnectReason
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.DIM_AFTER_CHOICES
import dev.smartdisplay.app.kiosk.WALLPAPER_INTERVAL_CHOICES
import dev.smartdisplay.app.server.SavedServer
import dev.smartdisplay.app.ui.common.Panel
import dev.smartdisplay.app.ui.common.PanelBody
import dev.smartdisplay.app.ui.controls.rooms
import dev.smartdisplay.app.ui.signin.displayName
import kotlinx.coroutines.launch

/**
 * Settings, behind the exit PIN when one is set: the connection's details, display and kiosk options, and signing
 * out.
 */
@Composable
fun SettingsScreen(
    server: SavedServer,
    home: HomeState,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    BackHandler(onBack = onBack)
    val kiosk by viewModel.config.collectAsStateWithLifecycle()
    var problem by remember { mutableStateOf<KioskProblem?>(null) }
    var modeProblem by remember { mutableStateOf<KioskProblem?>(null) }
    var settingPin by remember { mutableStateOf(false) }
    var choosingWallpaper by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Panel(eyebrow = stringResource(R.string.settings_eyebrow), title = server.displayName()) {
        PanelBody(server.url, topPadding = 4.dp)
        PanelBody(connectionText(server, home), topPadding = 16.dp)
        if (home.loaded) {
            PanelBody(
                listOf(
                    pluralStringResource(R.plurals.count_entities, home.entities.size, home.entities.size),
                    pluralStringResource(R.plurals.count_areas, home.areas.size, home.areas.size),
                    pluralStringResource(R.plurals.count_devices, home.devices.size, home.devices.size),
                ).joinToString(", ")
            )
        }

        Section(stringResource(R.string.settings_mode))
        val rooms = remember(home.entities, home.areas, home.devices, home.registry) { home.rooms() }
        GuestSettings(
            kiosk = kiosk,
            areas = home.areas,
            rooms = rooms,
            onMode = { modeProblem = viewModel.setMode(it) },
            onArea = viewModel::setGuestArea,
            onAllowed = viewModel::setGuestAllowed,
            onExtra = viewModel::setGuestExtra,
        )
        modeProblem?.let { ProblemText(it) }

        Section(stringResource(R.string.settings_display))
        SwitchRow(
            title = stringResource(R.string.settings_keep_on),
            description = stringResource(R.string.settings_keep_on_body),
            checked = kiosk.keepScreenOn,
            onCheckedChange = viewModel::setKeepScreenOn,
        )
        Text(
            text = stringResource(R.string.settings_dim_after),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        PanelBody(stringResource(R.string.settings_dim_after_body), topPadding = 2.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            DIM_AFTER_CHOICES.forEach { minutes ->
                FilterChip(
                    selected = kiosk.dimAfterMinutes == minutes,
                    onClick = { viewModel.setDimAfterMinutes(minutes) },
                    label = {
                        Text(
                            if (minutes == 0) {
                                stringResource(R.string.settings_dim_never)
                            } else {
                                stringResource(R.string.settings_dim_minutes, minutes)
                            }
                        )
                    },
                )
            }
        }

        Text(
            text = stringResource(R.string.wallpaper_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        val wallpaper = kiosk.wallpaper
        PanelBody(
            if (wallpaper.folderId == null) {
                stringResource(R.string.wallpaper_none)
            } else {
                stringResource(R.string.wallpaper_folder, wallpaper.folderTitle.orEmpty())
            },
            topPadding = 2.dp,
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { choosingWallpaper = true }) {
                Text(stringResource(R.string.wallpaper_choose))
            }
            if (wallpaper.folderId != null) {
                TextButton(
                    onClick = { viewModel.setWallpaperFolder(null) },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.wallpaper_remove))
                }
            }
        }
        if (wallpaper.folderId != null) {
            PanelBody(stringResource(R.string.wallpaper_interval), topPadding = 12.dp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                WALLPAPER_INTERVAL_CHOICES.forEach { minutes ->
                    FilterChip(
                        selected = wallpaper.intervalMinutes == minutes,
                        onClick = { viewModel.setWallpaperInterval(minutes) },
                        label = { Text(stringResource(R.string.settings_dim_minutes, minutes)) },
                    )
                }
            }
        }

        Section(stringResource(R.string.settings_alarms))
        AlarmSettings(home = home, browse = viewModel::browseMedia)

        Section(stringResource(R.string.settings_kiosk))
        Text(stringResource(R.string.settings_exit_pin), style = MaterialTheme.typography.titleMedium)
        PanelBody(
            stringResource(if (kiosk.hasPin) R.string.settings_pin_set else R.string.settings_pin_unset),
            topPadding = 2.dp,
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { settingPin = true }) {
                Text(stringResource(if (kiosk.hasPin) R.string.settings_pin_change else R.string.settings_pin_create))
            }
            if (kiosk.hasPin) {
                TextButton(
                    onClick = { problem = viewModel.removePin() },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_pin_remove))
                }
            }
        }
        SwitchRow(
            title = stringResource(R.string.settings_home_app),
            description = stringResource(R.string.settings_home_app_body),
            checked = kiosk.homeApp,
            onCheckedChange = { problem = viewModel.setHomeApp(it) },
        )
        SwitchRow(
            title = stringResource(R.string.settings_pin_app),
            description = stringResource(R.string.settings_pin_app_body),
            checked = kiosk.pinApp,
            onCheckedChange = { problem = viewModel.setPinApp(it) },
        )
        problem?.let { ProblemText(it) }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 32.dp)) {
            Button(onClick = onBack) {
                Text(stringResource(R.string.settings_done))
            }
            OutlinedButton(onClick = onSignOut, modifier = Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.sign_out))
            }
        }
    }

    if (choosingWallpaper) {
        MediaBrowserDialog(
            pick = MediaPick.PhotoFolder,
            browse = viewModel::browseMedia,
            onPicked = {
                viewModel.setWallpaperFolder(it)
                choosingWallpaper = false
            },
            onDismiss = { choosingWallpaper = false },
        )
    }

    if (settingPin) {
        SetPinDialog(
            onSave = { pin ->
                scope.launch {
                    viewModel.setPin(pin)
                    settingPin = false
                    problem = null
                    modeProblem = null
                }
            },
            onDismiss = { settingPin = false },
        )
    }
}

@Composable
private fun ProblemText(problem: KioskProblem) {
    Text(
        text = stringResource(
            when (problem) {
                KioskProblem.NeedsPin -> R.string.settings_needs_pin
                KioskProblem.NoHomeChooser -> R.string.settings_no_home_chooser
                KioskProblem.PinInUse -> R.string.settings_pin_in_use
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun connectionText(server: SavedServer, home: HomeState): String = when (val status = home.status) {
    ConnectionStatus.Connected -> stringResource(
        R.string.live_connected,
        home.config?.locationName ?: server.displayName(),
        home.config?.version ?: "?",
    )
    is ConnectionStatus.Waiting -> stringResource(
        when (status.reason) {
            DisconnectReason.Unreachable -> R.string.live_waiting_unreachable
            DisconnectReason.Rejected -> R.string.live_waiting_rejected
            DisconnectReason.Protocol -> R.string.live_waiting_protocol
        }
    )
    else -> stringResource(R.string.live_connecting)
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 32.dp, bottom = 16.dp))
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
internal fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.padding(start = 16.dp))
    }
}
