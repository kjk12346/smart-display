package dev.smartdisplay.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smartdisplay.app.R
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.ha.MediaFolder
import dev.smartdisplay.app.ui.common.PanelBody

/** What an alarm does when it rings on this display, and clearing alarms between guests. */
@Composable
fun AlarmSettings(home: HomeState, browse: suspend (String) -> MediaFolder) {
    val alarms = (LocalContext.current.applicationContext as SmartDisplayApp).alarms
    val actions by alarms.actions.collectAsStateWithLifecycle()
    val list by alarms.alarms.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<Choosing?>(null) }

    val lights = home.entities.values.filter { it.domain == "light" }
        .map { it.entityId to it.friendlyName }.sortedBy { it.second.lowercase() }
    val speakers = home.entities.values.filter { it.domain == "media_player" }
        .map { it.entityId to it.friendlyName }.sortedBy { it.second.lowercase() }
    fun nameOf(entityId: String) = home.entities[entityId]?.friendlyName ?: entityId

    SwitchRow(
        title = stringResource(R.string.alarm_sound),
        description = stringResource(R.string.alarm_sound_body),
        checked = actions.sound,
        onCheckedChange = { alarms.setActions(actions.copy(sound = it)) },
    )

    Heading(stringResource(R.string.alarm_lights))
    PanelBody(
        if (actions.lights.isEmpty()) {
            stringResource(R.string.alarm_lights_none)
        } else {
            pluralStringResource(
                R.plurals.alarm_lights_count, actions.lights.size, actions.lights.size,
                actions.lights.map(::nameOf).sorted().joinToString(", "),
            )
        },
        topPadding = 2.dp,
    )
    OutlinedButton(onClick = { choosing = Choosing.Lights }, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.alarm_lights_choose))
    }

    Heading(stringResource(R.string.alarm_speaker))
    val speaker = actions.speaker
    PanelBody(
        if (speaker == null || actions.soundId == null) {
            stringResource(R.string.alarm_speaker_none)
        } else {
            stringResource(R.string.alarm_speaker_set, actions.soundTitle.orEmpty(), nameOf(speaker))
        },
        topPadding = 2.dp,
    )
    Row(modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = { choosing = Choosing.Speaker }) {
            Text(stringResource(R.string.alarm_speaker_choose))
        }
        TextButton(
            onClick = { choosing = Choosing.Sound },
            enabled = speaker != null,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(stringResource(R.string.alarm_sound_choose))
        }
        if (speaker != null) {
            TextButton(onClick = { alarms.setActions(actions.copy(speaker = null, soundId = null, soundTitle = null)) }) {
                Text(stringResource(R.string.alarm_speaker_off))
            }
        }
    }

    Heading(stringResource(R.string.alarm_clear_all))
    PanelBody(stringResource(R.string.alarm_clear_all_body), topPadding = 2.dp)
    OutlinedButton(onClick = alarms::clearAll, enabled = list.isNotEmpty(), modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.alarm_clear_all))
    }

    when (choosing) {
        Choosing.Lights -> ChoiceDialog(
            title = stringResource(R.string.alarm_lights_choose),
            items = lights,
            selected = actions.lights,
            multiple = true,
            onChange = { id, on ->
                alarms.setActions(actions.copy(lights = if (on) actions.lights + id else actions.lights - id))
            },
            onDismiss = { choosing = null },
        )
        Choosing.Speaker -> ChoiceDialog(
            title = stringResource(R.string.alarm_speaker_choose),
            items = speakers,
            selected = setOfNotNull(speaker),
            multiple = false,
            onChange = { id, _ ->
                alarms.setActions(actions.copy(speaker = id))
                // Straight on to the sound, if there isn't one yet.
                choosing = if (actions.soundId == null) Choosing.Sound else null
            },
            onDismiss = { choosing = null },
        )
        Choosing.Sound -> MediaBrowserDialog(
            pick = MediaPick.Sound,
            browse = browse,
            onPicked = {
                alarms.setActions(actions.copy(soundId = it.contentId, soundTitle = it.title))
                choosing = null
            },
            onDismiss = { choosing = null },
        )
        null -> Unit
    }
}

private enum class Choosing { Lights, Speaker, Sound }

/** Ticks entities from a list: several ([multiple]) or one. */
@Composable
private fun ChoiceDialog(
    title: String,
    items: List<Pair<String, String>>,
    selected: Set<String>,
    multiple: Boolean,
    onChange: (id: String, selected: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(items, key = { it.first }) { (id, name) ->
                    val checked = id in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChange(id, !checked) },
                    ) {
                        if (multiple) {
                            Checkbox(checked = checked, onCheckedChange = { onChange(id, it) })
                        } else {
                            RadioButton(selected = checked, onClick = { onChange(id, true) })
                        }
                        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp))
                    }
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
