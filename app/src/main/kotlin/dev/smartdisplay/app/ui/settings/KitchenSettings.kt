package dev.smartdisplay.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.kiosk.KitchenConfig
import dev.smartdisplay.app.kiosk.PersonColor
import dev.smartdisplay.app.kiosk.SCREENSAVER_CHOICES
import dev.smartdisplay.app.kiosk.personColors
import dev.smartdisplay.app.ui.common.PanelBody
import dev.smartdisplay.app.ui.dashboard.FAMILY
import dev.smartdisplay.app.ui.dashboard.calendarOwner
import dev.smartdisplay.app.ui.dashboard.calendars
import dev.smartdisplay.app.ui.dashboard.people
import dev.smartdisplay.app.ui.dashboard.runnables
import dev.smartdisplay.app.ui.theme.color
import dev.smartdisplay.app.ui.theme.label

/**
 * The kitchen dashboard's buttons, what its Home and Away buttons run, whose each calendar is (or that it isn't
 * shown), and each person's color.
 */
@Composable
fun KitchenSettings(kitchen: KitchenConfig, home: HomeState, onChange: (KitchenConfig) -> Unit) {
    var choosing by remember { mutableStateOf<KitchenChoice?>(null) }
    var whoseCalendar by remember { mutableStateOf<String?>(null) }
    var colorFor by remember { mutableStateOf<String?>(null) }
    val runnables = remember(home.entities, home.registry) { home.runnables() }
    val items = runnables.map { it.entityId to it.friendlyName }
    fun nameOf(entityId: String) = home.entities[entityId]?.friendlyName ?: entityId

    Heading(stringResource(R.string.kitchen_buttons))
    PanelBody(
        if (kitchen.shortcuts.isEmpty()) {
            stringResource(R.string.kitchen_buttons_body) + " " + stringResource(R.string.kitchen_buttons_none)
        } else {
            kitchen.shortcuts.joinToString(", ", transform = ::nameOf)
        },
        topPadding = 2.dp,
    )
    OutlinedButton(onClick = { choosing = KitchenChoice.Buttons }, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.kitchen_buttons_choose))
    }

    Heading(stringResource(R.string.kitchen_modes))
    PanelBody(stringResource(R.string.kitchen_modes_body), topPadding = 2.dp)
    val notSet = stringResource(R.string.kitchen_not_set)
    val homeRuns = kitchen.homeAction?.let(::nameOf) ?: notSet
    val awayRuns = kitchen.awayAction?.let(::nameOf) ?: notSet
    PanelBody(stringResource(R.string.kitchen_home_runs, homeRuns), topPadding = 8.dp)
    PanelBody(stringResource(R.string.kitchen_away_runs, awayRuns), topPadding = 2.dp)
    Row(modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = { choosing = KitchenChoice.Home }) {
            Text(stringResource(R.string.kitchen_choose_home))
        }
        OutlinedButton(onClick = { choosing = KitchenChoice.Away }, modifier = Modifier.padding(start = 8.dp)) {
            Text(stringResource(R.string.kitchen_choose_away))
        }
        if (kitchen.homeAction != null || kitchen.awayAction != null) {
            TextButton(onClick = { onChange(kitchen.copy(homeAction = null, awayAction = null)) }) {
                Text(stringResource(R.string.kitchen_clear))
            }
        }
    }

    Heading(stringResource(R.string.kitchen_screensaver))
    PanelBody(stringResource(R.string.kitchen_screensaver_body), topPadding = 2.dp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
    ) {
        SCREENSAVER_CHOICES.forEach { minutes ->
            FilterChip(
                selected = kitchen.screensaverMinutes == minutes,
                onClick = { onChange(kitchen.copy(screensaverMinutes = minutes)) },
                label = {
                    Text(
                        if (minutes == 0) {
                            stringResource(R.string.settings_dim_never)
                        } else {
                            stringResource(R.string.settings_dim_minutes, minutes)
                        },
                    )
                },
            )
        }
    }

    val calendars = remember(home.entities, home.registry) { home.calendars() }
    val people = home.people().sortedBy { it.name.lowercase() }
    val colors = personColors(people.map { it.entityId }, kitchen.personColors)
    val familyName = stringResource(R.string.kitchen_calendar_family)
    val hiddenName = stringResource(R.string.kitchen_calendar_hidden)
    fun whoseName(calendarId: String): String = when {
        calendarId in kitchen.hiddenCalendars -> hiddenName
        else -> when (val owner = calendarOwner(calendarId, kitchen.calendarOwners, colors.keys)) {
            FAMILY -> familyName
            else -> nameOf(owner)
        }
    }

    Heading(stringResource(R.string.kitchen_calendars))
    if (calendars.isEmpty()) {
        PanelBody(stringResource(R.string.kitchen_calendars_none), topPadding = 2.dp)
    } else {
        PanelBody(stringResource(R.string.kitchen_calendars_body), topPadding = 2.dp)
        calendars.forEach { calendar ->
            SettingRow(calendar.friendlyName, whoseName(calendar.entityId)) { whoseCalendar = calendar.entityId }
        }
    }

    if (people.isNotEmpty()) {
        Heading(stringResource(R.string.kitchen_colors))
        PanelBody(stringResource(R.string.kitchen_colors_body), topPadding = 2.dp)
        people.forEach { person ->
            val color = colors.getValue(person.entityId)
            SettingRow(person.name, stringResource(color.label()), swatch = color) { colorFor = person.entityId }
        }
    }

    whoseCalendar?.let { calendarId ->
        val current = when {
            calendarId in kitchen.hiddenCalendars -> HIDDEN
            else -> calendarOwner(calendarId, kitchen.calendarOwners, colors.keys)
        }
        ChoiceDialog(
            title = stringResource(R.string.kitchen_calendar_whose, nameOf(calendarId)),
            items = listOf(FAMILY to familyName) + people.map { it.entityId to it.name } + (HIDDEN to hiddenName),
            selected = setOf(current),
            multiple = false,
            onChange = { choice, _ ->
                onChange(
                    kitchen.copy(
                        hiddenCalendars = if (choice == HIDDEN) {
                            kitchen.hiddenCalendars + calendarId
                        } else {
                            kitchen.hiddenCalendars - calendarId
                        },
                        calendarOwners = when (choice) {
                            HIDDEN -> kitchen.calendarOwners
                            FAMILY -> kitchen.calendarOwners - calendarId
                            else -> kitchen.calendarOwners + (calendarId to choice)
                        },
                    ),
                )
                whoseCalendar = null
            },
            onDismiss = { whoseCalendar = null },
        )
    }
    colorFor?.let { personId ->
        ColorDialog(
            title = stringResource(R.string.kitchen_color_choose, nameOf(personId)),
            selected = colors[personId],
            onChoose = { color ->
                onChange(kitchen.copy(personColors = kitchen.personColors + (personId to color)))
                colorFor = null
            },
            onDismiss = { colorFor = null },
        )
    }

    when (choosing) {
        KitchenChoice.Buttons -> ChoiceDialog(
            title = stringResource(R.string.kitchen_buttons_choose),
            items = items,
            selected = kitchen.shortcuts.toSet(),
            multiple = true,
            // Ticked buttons go on the end, so the dashboard shows them in the order they were chosen.
            onChange = { id, on ->
                onChange(kitchen.copy(shortcuts = if (on) kitchen.shortcuts - id + id else kitchen.shortcuts - id))
            },
            onDismiss = { choosing = null },
        )
        KitchenChoice.Home -> ChoiceDialog(
            title = stringResource(R.string.kitchen_choose_home),
            items = items,
            selected = setOfNotNull(kitchen.homeAction),
            multiple = false,
            onChange = { id, _ ->
                onChange(kitchen.copy(homeAction = id))
                choosing = null
            },
            onDismiss = { choosing = null },
        )
        KitchenChoice.Away -> ChoiceDialog(
            title = stringResource(R.string.kitchen_choose_away),
            items = items,
            selected = setOfNotNull(kitchen.awayAction),
            multiple = false,
            onChange = { id, _ ->
                onChange(kitchen.copy(awayAction = id))
                choosing = null
            },
            onDismiss = { choosing = null },
        )
        null -> Unit
    }
}

private enum class KitchenChoice { Buttons, Home, Away }

/** The choice for a calendar that isn't shown, beside the family and each person. */
private const val HIDDEN = "hidden"

/** A tappable row: a name, its current setting under it, and optionally that color's swatch. */
@Composable
private fun SettingRow(title: String, value: String, swatch: PersonColor? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        if (swatch != null) {
            Box(Modifier.padding(end = 14.dp).size(28.dp).clip(CircleShape).background(swatch.color()))
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Every person color as a swatch to tap, the current one ringed. */
@Composable
private fun ColorDialog(title: String, selected: PersonColor?, onChoose: (PersonColor) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PersonColor.entries.forEach { color ->
                    val name = stringResource(color.label())
                    Box(
                        Modifier
                            .size(52.dp)
                            .then(
                                if (color == selected) {
                                    Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                } else {
                                    Modifier
                                },
                            )
                            .padding(6.dp)
                            .clip(CircleShape)
                            .background(color.color())
                            .clickable { onChoose(color) }
                            .semantics { contentDescription = name },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
    )
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
}
