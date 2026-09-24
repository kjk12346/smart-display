package dev.smartdisplay.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.Area
import dev.smartdisplay.app.kiosk.DisplayMode
import dev.smartdisplay.app.kiosk.KioskConfig
import dev.smartdisplay.app.ui.common.PanelBody
import dev.smartdisplay.app.ui.controls.Room

/**
 * Owner or guest room, and for a guest room: which room, which of its devices, and which devices from other rooms.
 */
@Composable
fun GuestSettings(
    kiosk: KioskConfig,
    areas: List<Area>,
    rooms: List<Room>,
    onMode: (DisplayMode) -> Unit,
    onArea: (String) -> Unit,
    onAllowed: (entityId: String, allowed: Boolean) -> Unit,
    onExtra: (entityId: String, included: Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !kiosk.isGuest,
            onClick = { onMode(DisplayMode.Owner) },
            label = { Text(stringResource(R.string.mode_owner)) },
        )
        FilterChip(
            selected = kiosk.isGuest,
            onClick = { onMode(DisplayMode.Guest) },
            label = { Text(stringResource(R.string.mode_guest)) },
        )
    }
    PanelBody(
        stringResource(if (kiosk.isGuest) R.string.mode_guest_body else R.string.mode_owner_body),
        topPadding = 4.dp,
    )
    if (!kiosk.isGuest) return

    val guest = kiosk.guest
    PanelBody(stringResource(R.string.guest_account_note), topPadding = 12.dp)

    Heading(stringResource(R.string.guest_room))
    if (guest.areaId == null) PanelBody(stringResource(R.string.guest_room_none), topPadding = 2.dp)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        areas.forEach { area ->
            FilterChip(
                selected = area.id == guest.areaId,
                onClick = { onArea(area.id) },
                label = { Text(area.name) },
            )
        }
    }

    if (guest.areaId != null) {
        Heading(stringResource(R.string.guest_in_room))
        val own = rooms.firstOrNull { it.id == guest.areaId }?.controls.orEmpty()
        if (own.isEmpty()) PanelBody(stringResource(R.string.guest_room_empty), topPadding = 2.dp)
        own.forEach { control ->
            CheckRow(
                title = control.name,
                checked = control.entityId !in guest.hidden,
                onCheckedChange = { onAllowed(control.entityId, it) },
            )
        }
    }

    Heading(stringResource(R.string.guest_elsewhere))
    val others = rooms.filter { it.id == null || it.id != guest.areaId }
    val extras = others.flatMap { room ->
        room.controls.filter { it.entityId in guest.extras }.map { room to it }
    }
    if (extras.isEmpty()) PanelBody(stringResource(R.string.guest_elsewhere_none), topPadding = 2.dp)
    extras.forEach { (room, control) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(control.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = room.name ?: stringResource(R.string.controls_other_room),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onExtra(control.entityId, false) }) {
                Text(stringResource(R.string.guest_remove))
            }
        }
    }
    var adding by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { adding = true }, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.guest_add))
    }
    if (adding) {
        AddExtrasDialog(
            rooms = others,
            extras = guest.extras,
            onExtra = onExtra,
            onDismiss = { adding = false },
        )
    }
}

/** Ticks devices from rooms other than the guest's, grouped by room. */
@Composable
private fun AddExtrasDialog(
    rooms: List<Room>,
    extras: Set<String>,
    onExtra: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.guest_add_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                rooms.forEach { room ->
                    item(key = "room:${room.id}") {
                        Text(
                            text = room.name ?: stringResource(R.string.controls_other_room),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(room.controls, key = { it.entityId }) { control ->
                        CheckRow(
                            title = control.name,
                            checked = control.entityId in extras,
                            onCheckedChange = { onExtra(control.entityId, it) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
    )
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
}

@Composable
private fun CheckRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp))
    }
}
