package dev.smartdisplay.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.KitchenConfig
import dev.smartdisplay.app.ui.settings.ChoiceDialog
import dev.smartdisplay.app.ui.settings.SettingRow

/** Icons to pick from for a button: rooms, times of day and the things scenes and scripts tend to be about. */
private val ICON_CHOICES = listOf(
    "🌙", "☀️", "🌅", "🌇", "🛏️", "🛋️", "🍽️", "☕", "🎬", "📺", "🎵", "💡",
    "🔒", "🏠", "👋", "🚗", "✈️", "❄️", "🔥", "🌿", "🧺", "🚿", "🎉", "📚",
)

/**
 * A button's icon in a circle: its emoji, or else the first letter of its [label]. Also used, with a mark in place of
 * the icon, to show for a moment whether a run worked.
 */
@Composable
internal fun ShortcutIcon(icon: String?, label: String, size: Dp = 40.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f)),
    ) {
        if (icon != null) {
            Text(icon, fontSize = (size.value * 0.5f).sp)
        } else {
            Text(
                label.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/** The Buttons card's sources: which buttons, then each one's short name and icon. */
@Composable
internal fun ButtonsSources(
    kitchen: KitchenConfig,
    home: HomeState,
    onKitchenChange: (KitchenConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    val shown = kitchen.shortcuts.filter { it in home.entities }
    fun nameOf(id: String) = home.entities[id]?.friendlyName ?: id
    val labels = shortLabels(shown.associateWith(::nameOf), kitchen.shortcutNames)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dashboard_buttons)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingRow(
                    stringResource(R.string.kitchen_buttons_choose),
                    stringResource(R.string.buttons_count, shown.size),
                ) { choosing = true }
                if (shown.isNotEmpty()) {
                    Text(
                        stringResource(R.string.buttons_edit_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                shown.forEach { id ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { editing = id }.padding(vertical = 8.dp),
                    ) {
                        ShortcutIcon(kitchen.shortcutIcons[id], labels.getValue(id))
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(labels.getValue(id), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                nameOf(id),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
    )
    if (choosing) {
        ChoiceDialog(
            title = stringResource(R.string.kitchen_buttons_choose),
            items = home.runnables().map { it.entityId to it.friendlyName },
            selected = kitchen.shortcuts.toSet(),
            multiple = true,
            onChange = { id, on ->
                val ids = kitchen.shortcuts - id
                onKitchenChange(kitchen.copy(shortcuts = if (on) ids + id else ids))
            },
            onDismiss = { choosing = false },
        )
    }
    editing?.let { id -> ShortcutEditor(id, kitchen, home, onKitchenChange, onDismiss = { editing = null }) }
}

/** One button's short name and icon, saved in [kitchen]. */
@Composable
internal fun ShortcutEditor(
    id: String,
    kitchen: KitchenConfig,
    home: HomeState,
    onKitchenChange: (KitchenConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    fun nameOf(entityId: String) = home.entities[entityId]?.friendlyName ?: entityId
    // The name it would have without one of its own, for the field's hint.
    val shown = kitchen.shortcuts.filter { it in home.entities }
    val automatic = shortLabels(shown.associateWith(::nameOf))[id] ?: nameOf(id)
    ShortcutDialog(
        fullName = nameOf(id),
        automatic = automatic,
        name = kitchen.shortcutNames[id].orEmpty(),
        icon = kitchen.shortcutIcons[id],
        onSave = { name, icon ->
            onKitchenChange(
                kitchen.copy(
                    shortcutNames = (kitchen.shortcutNames - id) +
                        listOfNotNull(name.trim().ifEmpty { null }?.let { id to it }),
                    shortcutIcons = (kitchen.shortcutIcons - id) + listOfNotNull(icon?.let { id to it }),
                ),
            )
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/** One button's short name (blank for the automatic one) and icon (none for its first letter). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutDialog(
    fullName: String,
    automatic: String,
    name: String,
    icon: String?,
    onSave: (name: String, icon: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf(name) }
    var picked by rememberSaveable { mutableStateOf(icon) }
    val label = typed.trim().ifEmpty { automatic }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(fullName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(MAX_NAME) },
                    label = { Text(stringResource(R.string.buttons_short_name)) },
                    placeholder = { Text(automatic) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.buttons_icon),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The first letter, then the emoji.
                    (listOf<String?>(null) + ICON_CHOICES).forEach { choice ->
                        val selected = choice == picked
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .border(
                                    2.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape,
                                )
                                .clickable { picked = choice }
                                .padding(3.dp),
                        ) {
                            ShortcutIcon(choice, label, size = 44.dp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(typed, picked) }) { Text(stringResource(R.string.buttons_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The longest short name, so it still fits on a tile in two lines. */
private const val MAX_NAME = 24
