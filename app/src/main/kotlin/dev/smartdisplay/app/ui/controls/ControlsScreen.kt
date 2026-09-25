package dev.smartdisplay.app.ui.controls

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.ConnectionStatus
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.GuestConfig
import kotlinx.coroutines.delay

/** After this long without a touch, the display goes back to the clock. */
private const val IDLE_RETURN_MS = 2 * 60_000L

/** Rooms narrower than this get chips across the top instead of a list down the side. */
private val SIDE_LIST_MIN_WIDTH = 720.dp

private const val OTHER_ROOM_KEY = "\u0000other"

private val Room.key: String get() = id ?: OTHER_ROOM_KEY

/**
 * Room-by-room controls for lights, switches, media players and thermostats. Goes back to the clock on Done, Back,
 * or after [IDLE_RETURN_MS] without a touch. With a [guest] configuration, shows only the guest's room and extras,
 * with no way to reach other rooms.
 */
@Composable
fun ControlsScreen(
    home: HomeState,
    guest: GuestConfig?,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAlarms: () -> Unit,
    viewModel: ControlsViewModel = viewModel(),
) {
    BackHandler(onBack = onDone)
    val rooms = remember(home.entities, home.areas, home.devices, home.registry) { home.rooms() }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = rooms.firstOrNull { it.key == selectedKey } ?: rooms.firstOrNull()

    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.errors.collect { error ->
            val message = if (error.notConnected) {
                resources.getString(R.string.controls_error_not_connected)
            } else {
                resources.getString(R.string.controls_error_failed, error.name)
            }
            snackbar.showSnackbar(message)
        }
    }
    val call: (ServiceCall, String) -> Unit = viewModel::call

    ReturnWhenIdle(IDLE_RETURN_MS, onIdle = onDone) {
        Surface(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(modifier = Modifier.safeDrawingPadding()) {
                val roomName = selected?.name ?: stringResource(R.string.controls_other_room)
                if (guest != null) {
                    val guestRooms = remember(rooms, guest) { rooms.forGuest(guest) }
                    Column {
                        val title = guestRooms.firstOrNull { it.id == guest.areaId }?.name
                        Header(title, home, onDone, onOpenSettings, onOpenAlarms)
                        GuestGrid(home, guestRooms, ownAreaId = guest.areaId, onCall = call)
                    }
                } else if (maxWidth >= SIDE_LIST_MIN_WIDTH) {
                    Row {
                        RoomList(
                            rooms = rooms,
                            selected = selected,
                            onSelect = { selectedKey = it.key },
                            modifier = Modifier
                                .width(240.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow),
                        )
                        Column(Modifier.weight(1f)) {
                            val title = if (selected != null) roomName else null
                            Header(title, home, onDone, onOpenSettings, onOpenAlarms)
                            RoomGrid(home, selected, call)
                        }
                    }
                } else {
                    Column {
                        Header(null, home, onDone, onOpenSettings, onOpenAlarms)
                        RoomChips(rooms, selected, onSelect = { selectedKey = it.key })
                        RoomGrid(home, selected, call)
                    }
                }
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun Header(
    title: String?,
    home: HomeState,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAlarms: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = MaterialTheme.typography.headlineMedium)
            if (home.status != ConnectionStatus.Connected && home.status != ConnectionStatus.Idle) {
                Text(
                    text = stringResource(R.string.ambient_reconnecting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onOpenAlarms) { Text(stringResource(R.string.alarms_button)) }
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_eyebrow)) }
        FilledTonalButton(onClick = onDone, modifier = Modifier.padding(start = 8.dp)) {
            Text(stringResource(R.string.settings_done))
        }
    }
}

@Composable
private fun RoomList(rooms: List<Room>, selected: Room?, onSelect: (Room) -> Unit, modifier: Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(rooms, key = { it.key }) { room ->
            NavigationDrawerItem(
                label = { Text(room.name ?: stringResource(R.string.controls_other_room)) },
                badge = { Text(room.controls.size.toString()) },
                selected = room.key == selected?.key,
                onClick = { onSelect(room) },
                shape = RoundedCornerShape(16.dp),
            )
        }
    }
}

@Composable
private fun RoomChips(rooms: List<Room>, selected: Room?, onSelect: (Room) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rooms, key = { it.key }) { room ->
            FilterChip(
                selected = room.key == selected?.key,
                onClick = { onSelect(room) },
                label = { Text(room.name ?: stringResource(R.string.controls_other_room)) },
            )
        }
    }
}

@Composable
private fun RoomGrid(home: HomeState, room: Room?, onCall: (ServiceCall, String) -> Unit) {
    if (room == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(if (home.loaded) R.string.controls_empty else R.string.live_connecting),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 240.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(room.controls, key = { it.entityId }) { control ->
            ControlCard(control, home.config?.temperatureUnit) { onCall(it, control.name) }
        }
    }
}

/** A guest's controls: their room's cards, then each other room's extras under that room's name. */
@Composable
private fun GuestGrid(home: HomeState, rooms: List<Room>, ownAreaId: String?, onCall: (ServiceCall, String) -> Unit) {
    if (rooms.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(if (home.loaded) R.string.controls_guest_not_set_up else R.string.live_connecting),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val otherName = stringResource(R.string.controls_other_room)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 240.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rooms.forEach { room ->
            if (room.id == null || room.id != ownAreaId) {
                item(key = "header:${room.key}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = room.name ?: otherName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            items(room.controls, key = { it.entityId }) { control ->
                ControlCard(control, home.config?.temperatureUnit) { onCall(it, control.name) }
            }
        }
    }
}

/**
 * Calls [onIdle] once [timeoutMillis] pass with no touch anywhere in [content]. Touches are observed on the way
 * down, without consuming them.
 */
@Composable
private fun ReturnWhenIdle(timeoutMillis: Long, onIdle: () -> Unit, content: @Composable () -> Unit) {
    var touches by remember { mutableIntStateOf(0) }
    val currentOnIdle by rememberUpdatedState(onIdle)
    LaunchedEffect(touches) {
        delay(timeoutMillis)
        currentOnIdle()
    }
    Box(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) touches++
                }
            }
        }
    ) {
        content()
    }
}
