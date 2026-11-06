package dev.smartdisplay.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.kiosk.Countdown
import dev.smartdisplay.app.kiosk.KitchenConfig
import dev.smartdisplay.app.kiosk.PersonColor
import dev.smartdisplay.app.kiosk.Sticky
import dev.smartdisplay.app.kiosk.coming
import dev.smartdisplay.app.kiosk.daysLeft
import dev.smartdisplay.app.kiosk.nextDate
import dev.smartdisplay.app.ui.theme.OnPersonColor
import dev.smartdisplay.app.ui.theme.color
import java.time.LocalDate

/** A card whose title has something to do beside it, such as adding a note. */
@Composable
internal fun ActionCard(
    title: String,
    action: String?,
    onAction: () -> Unit,
    modifier: Modifier,
    scroll: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = dashCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (action != null) TextButton(onClick = onAction) { Text(action, maxLines = 1) }
            }
            Column(
                modifier = Modifier
                    .padding(end = 8.dp, top = 4.dp)
                    .then(if (scroll) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier),
                content = content,
            )
        }
    }
}

/** A row of emoji to pick from, or none. */
@Composable
internal fun EmojiPicker(picked: String?, onPick: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf<String?>(null) + FAMILY_EMOJI).forEach { choice ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (choice == picked) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                    .clickable { onPick(choice) },
            ) {
                Text(choice ?: "–", fontSize = 22.sp)
            }
        }
    }
}

private val FAMILY_EMOJI = listOf(
    "🎂", "🎉", "✈️", "🏖️", "🎄", "🎃", "🏫", "⚽", "🎵", "🛒", "🍕", "❤️", "⭐", "😀", "🐶", "🧹", "🪥", "🛏️", "🎒", "📚",
    "🧺", "🍽️", "🌱", "🚗",
) + ICON_CHOICES.filter { it in listOf("☕", "🔥", "🌙") }

// Stickies

/** Short notes for the family. Anyone can add, change or remove one, as on a real fridge. */
@Composable
internal fun StickiesCard(
    kitchen: KitchenConfig,
    onKitchenChange: (KitchenConfig) -> Unit,
    modifier: Modifier,
    bounded: Boolean,
) {
    var editing by remember { mutableStateOf<Sticky?>(null) }
    var adding by remember { mutableStateOf(false) }
    ActionCard(
        title = stringResource(R.string.card_stickies),
        action = stringResource(R.string.stickies_add),
        onAction = { adding = true },
        modifier = modifier,
        scroll = bounded,
    ) {
        if (kitchen.stickies.isEmpty()) EmptyNote(stringResource(R.string.stickies_none))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            kitchen.stickies.sortedByDescending { it.pinned }.forEach { sticky ->
                StickyNote(sticky, Modifier.clickable { editing = sticky })
            }
        }
    }
    if (adding) {
        StickyEditor(
            sticky = null,
            onSave = {
                onKitchenChange(kitchen.copy(stickies = kitchen.stickies + it))
                adding = false
            },
            onDelete = null,
            onDismiss = { adding = false },
        )
    }
    editing?.let { sticky ->
        StickyEditor(
            sticky = sticky,
            onSave = { changed ->
                onKitchenChange(kitchen.copy(stickies = kitchen.stickies.map { if (it.id == sticky.id) changed else it }))
                editing = null
            },
            onDelete = {
                onKitchenChange(kitchen.copy(stickies = kitchen.stickies.filterNot { it.id == sticky.id }))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** A note in its color. */
@Composable
internal fun StickyNote(sticky: Sticky, modifier: Modifier = Modifier) {
    Column(
        modifier = Modifier
            .widthIn(min = 96.dp, max = 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(sticky.color.color())
            .then(modifier)
            .padding(12.dp),
    ) {
        sticky.emoji?.let { Text(it, fontSize = 22.sp) }
        Text(sticky.text, style = MaterialTheme.typography.bodyLarge, color = OnPersonColor)
    }
}

@Composable
private fun StickyEditor(
    sticky: Sticky?,
    onSave: (Sticky) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(sticky?.text.orEmpty()) }
    var emoji by remember { mutableStateOf(sticky?.emoji) }
    var color by remember { mutableStateOf(sticky?.color ?: PersonColor.Butter) }
    var pinned by remember { mutableStateOf(sticky?.pinned ?: false) }
    EditorDialog(
        title = stringResource(if (sticky == null) R.string.stickies_add else R.string.stickies_edit),
        canSave = text.isNotBlank(),
        onSave = {
            val id = sticky?.id ?: "sticky-${System.currentTimeMillis()}"
            onSave(Sticky(id, text.trim(), emoji, color, pinned))
        },
        onDelete = onDelete,
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_STICKY) },
            label = { Text(stringResource(R.string.stickies_text)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PersonColor.entries.forEach { option ->
                Box(
                    Modifier
                        .size(36.dp)
                        .then(
                            if (option == color) {
                                Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(option.color())
                        .clickable { color = option },
                )
            }
        }
        EmojiPicker(emoji) { emoji = it }
        SwitchLine(stringResource(R.string.stickies_pin), pinned) { pinned = it }
    }
}

private const val MAX_STICKY = 160

// Countdowns

/** Days to go until what's coming up, soonest first. One-off dates drop off once they've passed. */
@Composable
internal fun CountdownsCard(
    kitchen: KitchenConfig,
    onKitchenChange: (KitchenConfig) -> Unit,
    today: LocalDate,
    modifier: Modifier,
    bounded: Boolean,
) {
    var editing by remember { mutableStateOf<Countdown?>(null) }
    var adding by remember { mutableStateOf(false) }
    val coming = kitchen.countdowns.coming(today)
    ActionCard(
        title = stringResource(R.string.card_countdowns),
        action = stringResource(R.string.countdowns_add),
        onAction = { adding = true },
        modifier = modifier,
        scroll = bounded,
    ) {
        if (coming.isEmpty()) EmptyNote(stringResource(R.string.countdowns_none))
        coming.forEach { countdown ->
            CountdownRow(countdown, today, Modifier.clickable { editing = countdown })
        }
    }
    // Saving also tidies away what's passed.
    fun save(list: List<Countdown>) = onKitchenChange(kitchen.copy(countdowns = list.coming(today)))
    if (adding) {
        CountdownEditor(
            countdown = null,
            today = today,
            onSave = {
                save(kitchen.countdowns + it)
                adding = false
            },
            onDelete = null,
            onDismiss = { adding = false },
        )
    }
    editing?.let { countdown ->
        CountdownEditor(
            countdown = countdown,
            today = today,
            onSave = { changed ->
                save(kitchen.countdowns.map { if (it.id == countdown.id) changed else it })
                editing = null
            },
            onDelete = {
                save(kitchen.countdowns.filterNot { it.id == countdown.id })
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** How long to go, large, and what for. */
@Composable
internal fun CountdownRow(countdown: Countdown, today: LocalDate, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val days = countdown.daysLeft(today) ?: return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(countdown.emoji ?: "⏳", fontSize = 24.sp, modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(countdown.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            countdown.nextDate(today)?.let {
                Text(
                    dayName(it, locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            when (days) {
                0L -> stringResource(R.string.countdowns_today)
                1L -> stringResource(R.string.countdowns_tomorrow)
                else -> pluralStringResource(R.plurals.countdowns_days, days.toInt(), days.toInt())
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun CountdownEditor(
    countdown: Countdown?,
    today: LocalDate,
    onSave: (Countdown) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    var title by remember { mutableStateOf(countdown?.title.orEmpty()) }
    var date by remember { mutableStateOf(countdown?.date ?: today.plusDays(7)) }
    var yearly by remember { mutableStateOf(countdown?.yearly ?: false) }
    var emoji by remember { mutableStateOf(countdown?.emoji) }
    var picking by remember { mutableStateOf(false) }
    val draft = Countdown(countdown?.id ?: "countdown-${System.currentTimeMillis()}", title.trim(), date, emoji, yearly)
    EditorDialog(
        title = stringResource(if (countdown == null) R.string.countdowns_add else R.string.countdowns_edit),
        canSave = title.isNotBlank() && draft.nextDate(today) != null,
        onSave = { onSave(draft) },
        onDelete = onDelete,
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(MAX_TITLE) },
            label = { Text(stringResource(R.string.countdowns_what)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { picking = true }) { Text(dayName(date, locale)) }
        SwitchLine(stringResource(R.string.countdowns_yearly), yearly) { yearly = it }
        if (draft.nextDate(today) == null) {
            Text(
                stringResource(R.string.countdowns_past),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        EmojiPicker(emoji) { emoji = it }
    }
    if (picking) {
        DayPicker(date, onDismiss = { picking = false }) {
            date = it
            picking = false
        }
    }
}

// Shared bits for the family's editors

internal const val MAX_TITLE = 60

@Composable
internal fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A dialog for changing something on the family's cards: Save and Cancel, and Remove for one already there. */
@Composable
internal fun EditorDialog(
    title: String,
    canSave: Boolean,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            ) {
                content()
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.family_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.size(2.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = canSave) { Text(stringResource(R.string.pin_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
