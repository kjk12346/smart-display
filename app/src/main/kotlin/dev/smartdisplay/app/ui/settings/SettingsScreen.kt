package dev.smartdisplay.app.ui.settings

import androidx.activity.compose.BackHandler
import dev.smartdisplay.app.kiosk.KioskConfig
import dev.smartdisplay.app.kiosk.DisplayMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
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
import dev.smartdisplay.app.kiosk.defaultSpeakerName
import dev.smartdisplay.app.server.SavedServer
import dev.smartdisplay.app.ui.common.Panel
import dev.smartdisplay.app.ui.common.PanelBody
import dev.smartdisplay.app.ui.controls.rooms
import dev.smartdisplay.app.ui.signin.displayName
import kotlinx.coroutines.launch

/** The pages of settings, each opened from the list on the first. */
internal enum class SettingsPage { Mode, Kitchen, Display, Wifi, Speaker, Alarms, WindDown, Kiosk, Connection }

/**
 * Settings, behind the exit PIN when one is set: a short list of pages (who uses the display, the family dashboard,
 * the screen, alarms, winding down, kiosk options, and the Home Assistant connection with signing out), each opened
 * on its own. Back goes from a page to the list, and from the list out of settings.
 */
@Composable
fun SettingsScreen(
    server: SavedServer,
    home: HomeState,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val kiosk by viewModel.config.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    // Kept while a found page is open, so Back returns to the results.
    var query by rememberSaveable { mutableStateOf("") }
    // Pages this display doesn't have aren't listed, or found.
    val pageShown = { shown: SettingsPage ->
        when (shown) {
            SettingsPage.Kitchen -> kiosk.isKitchen
            SettingsPage.WindDown -> !kiosk.isGuest
            else -> true
        }
    }
    BackHandler { if (page != null) page = null else onBack() }
    var problem by remember { mutableStateOf<KioskProblem?>(null) }
    var modeProblem by remember { mutableStateOf<KioskProblem?>(null) }
    var settingPin by remember { mutableStateOf(false) }
    var choosingWallpaper by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    when (val shown = page) {
        null -> Panel(eyebrow = stringResource(R.string.settings_eyebrow), title = server.displayName()) {
            PanelBody(connectionText(server, home), topPadding = 4.dp)
            SearchField(query, onQuery = { query = it })
            if (query.isNotBlank()) {
                SearchResults(query, pageShown) { page = it }
            } else Column(Modifier.padding(top = 8.dp)) {
                PageRow(
                    stringResource(R.string.settings_mode),
                    stringResource(
                        when (kiosk.mode) {
                            DisplayMode.Owner -> R.string.mode_owner
                            DisplayMode.Guest -> R.string.mode_guest
                            DisplayMode.Kitchen -> R.string.mode_kitchen
                        },
                    ),
                ) { page = SettingsPage.Mode }
                if (pageShown(SettingsPage.Kitchen)) {
                    PageRow(
                        stringResource(R.string.settings_kitchen_page),
                        stringResource(R.string.settings_kitchen_summary),
                    ) { page = SettingsPage.Kitchen }
                }
                PageRow(
                    stringResource(R.string.settings_display),
                    stringResource(R.string.settings_display_summary),
                ) { page = SettingsPage.Display }
                PageRow(
                    stringResource(R.string.settings_wifi),
                    kiosk.wifi.ssid?.takeIf { kiosk.wifi.isSet } ?: stringResource(R.string.settings_wifi_none),
                ) { page = SettingsPage.Wifi }
                PageRow(
                    stringResource(R.string.settings_speaker),
                    if (kiosk.speaker.enabled) {
                        kiosk.speaker.name ?: stringResource(defaultSpeakerName(kiosk.mode))
                    } else {
                        stringResource(R.string.settings_speaker_off)
                    },
                ) { page = SettingsPage.Speaker }
                PageRow(
                    stringResource(R.string.settings_alarms),
                    stringResource(R.string.settings_alarms_summary),
                ) { page = SettingsPage.Alarms }
                // Not in guest rooms: a guest's lights are their own business.
                if (pageShown(SettingsPage.WindDown)) {
                    PageRow(
                        stringResource(R.string.wind_down_title),
                        stringResource(R.string.settings_wind_down_summary),
                    ) { page = SettingsPage.WindDown }
                }
                PageRow(
                    stringResource(R.string.settings_kiosk),
                    stringResource(
                        if (kiosk.hasPin) R.string.settings_kiosk_summary_pin else R.string.settings_kiosk_summary,
                    ),
                ) { page = SettingsPage.Kiosk }
                PageRow(
                    stringResource(R.string.settings_connection),
                    server.url,
                ) { page = SettingsPage.Connection }
            }
            Button(onClick = onBack, modifier = Modifier.padding(top = 32.dp)) {
                Text(stringResource(R.string.settings_done))
            }
        }
        else -> Panel(eyebrow = stringResource(R.string.settings_eyebrow), title = pageTitle(shown)) {
            when (shown) {
                SettingsPage.Mode -> {
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
                }
                SettingsPage.Kitchen -> KitchenSettings(kiosk.kitchen, home, viewModel::setKitchen)
                SettingsPage.Display -> DisplaySettings(kiosk, viewModel) { choosingWallpaper = true }
                SettingsPage.Wifi -> WifiSettings(kiosk.wifi, viewModel::setWifi)
                SettingsPage.Speaker -> SpeakerSettings(kiosk.speaker, kiosk.mode, viewModel::setSpeaker)
                SettingsPage.Alarms -> AlarmSettings(home = home, browse = viewModel::browseMedia)
                SettingsPage.WindDown -> WindDownSettings(home)
                SettingsPage.Kiosk -> {
                    KioskSettings(
                        hasPin = kiosk.hasPin,
                        homeApp = kiosk.homeApp,
                        pinApp = kiosk.pinApp,
                        onSetPin = { settingPin = true },
                        onRemovePin = { problem = viewModel.removePin() },
                        onHomeApp = { problem = viewModel.setHomeApp(it) },
                        onPinApp = { problem = viewModel.setPinApp(it) },
                    )
                    problem?.let { ProblemText(it) }
                }
                SettingsPage.Connection -> {
                    PanelBody(server.url, topPadding = 4.dp)
                    PanelBody(connectionText(server, home), topPadding = 16.dp)
                    if (home.loaded) {
                        PanelBody(
                            listOf(
                                pluralStringResource(R.plurals.count_entities, home.entities.size, home.entities.size),
                                pluralStringResource(R.plurals.count_areas, home.areas.size, home.areas.size),
                                pluralStringResource(R.plurals.count_devices, home.devices.size, home.devices.size),
                            ).joinToString(", "),
                        )
                    }
                    OutlinedButton(onClick = onSignOut, modifier = Modifier.padding(top = 24.dp)) {
                        Text(stringResource(R.string.sign_out))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 32.dp)) {
                Button(onClick = { page = null }) { Text(stringResource(R.string.settings_back)) }
                TextButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) {
                    Text(stringResource(R.string.settings_done))
                }
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
private fun pageTitle(page: SettingsPage): String = stringResource(pageTitleRes(page))

private fun pageTitleRes(page: SettingsPage): Int = when (page) {
    SettingsPage.Mode -> R.string.settings_mode
    SettingsPage.Kitchen -> R.string.settings_kitchen_page
    SettingsPage.Display -> R.string.settings_display
    SettingsPage.Wifi -> R.string.settings_wifi
    SettingsPage.Speaker -> R.string.settings_speaker
    SettingsPage.Alarms -> R.string.settings_alarms
    SettingsPage.WindDown -> R.string.wind_down_title
    SettingsPage.Kiosk -> R.string.settings_kiosk
    SettingsPage.Connection -> R.string.settings_connection
}

/** The search box over the list of pages, with a way to empty it. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.settings_search)) },
        singleLine = true,
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { onQuery("") }) {
                    Text("\u00d7", style = MaterialTheme.typography.headlineSmall)
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    )
}

/** The settings [query] finds, each with the page it's on; tapping one opens that page. */
@Composable
private fun SearchResults(query: String, pageShown: (SettingsPage) -> Boolean, onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val found = SEARCH_TOPICS.filter { topic ->
        pageShown(topic.page) && searchMatches(
            query,
            listOfNotNull(
                context.getString(topic.title),
                context.getString(pageTitleRes(topic.page)),
                topic.keywords?.let(context::getString),
            ),
        )
    }
    Column(Modifier.padding(top = 8.dp)) {
        if (found.isEmpty()) {
            PanelBody(stringResource(R.string.settings_search_none, query.trim()))
        }
        found.forEach { topic ->
            PageRow(stringResource(topic.title), pageTitle(topic.page)) { onOpen(topic.page) }
        }
    }
}

/** A page of settings in the list: its name, what's in it (or how it's set), and an arrow. */
@Composable
private fun PageRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "\u203a",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
    HorizontalDivider()
}

/** The screen: keeping it on, dimming when idle, and the wallpaper photos. */
@Composable
private fun DisplaySettings(kiosk: KioskConfig, viewModel: SettingsViewModel, onChooseWallpaper: () -> Unit) {
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
        OutlinedButton(onClick = onChooseWallpaper) {
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
        PanelBody(stringResource(R.string.wallpaper_clock), topPadding = 12.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            FilterChip(
                selected = wallpaper.clockCorner,
                onClick = { viewModel.setClockCorner(true) },
                label = { Text(stringResource(R.string.wallpaper_clock_corner)) },
            )
            FilterChip(
                selected = !wallpaper.clockCorner,
                onClick = { viewModel.setClockCorner(false) },
                label = { Text(stringResource(R.string.wallpaper_clock_middle)) },
            )
        }
        if (wallpaper.clockCorner) {
            SwitchRow(
                title = stringResource(R.string.wallpaper_photo_info),
                description = stringResource(R.string.wallpaper_photo_info_description),
                checked = wallpaper.photoInfo,
                onCheckedChange = viewModel::setPhotoInfo,
            )
        }
    }
}

/** The way out (the exit PIN), and keeping people in: Home app and pinning, which both need the PIN. */
@Composable
private fun KioskSettings(
    hasPin: Boolean,
    homeApp: Boolean,
    pinApp: Boolean,
    onSetPin: () -> Unit,
    onRemovePin: () -> Unit,
    onHomeApp: (Boolean) -> Unit,
    onPinApp: (Boolean) -> Unit,
) {
    Text(stringResource(R.string.settings_exit_pin), style = MaterialTheme.typography.titleMedium)
    PanelBody(
        stringResource(if (hasPin) R.string.settings_pin_set else R.string.settings_pin_unset),
        topPadding = 2.dp,
    )
    Row(modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = onSetPin) {
            Text(stringResource(if (hasPin) R.string.settings_pin_change else R.string.settings_pin_create))
        }
        if (hasPin) {
            TextButton(onClick = onRemovePin, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.settings_pin_remove))
            }
        }
    }
    SwitchRow(
        title = stringResource(R.string.settings_home_app),
        description = stringResource(R.string.settings_home_app_body),
        checked = homeApp,
        onCheckedChange = onHomeApp,
    )
    SwitchRow(
        title = stringResource(R.string.settings_pin_app),
        description = stringResource(R.string.settings_pin_app_body),
        checked = pinApp,
        onCheckedChange = onPinApp,
    )
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
