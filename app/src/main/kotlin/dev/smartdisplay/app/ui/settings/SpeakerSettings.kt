package dev.smartdisplay.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.kiosk.DisplayMode
import dev.smartdisplay.app.kiosk.SpeakerConfig
import dev.smartdisplay.app.kiosk.defaultSpeakerName
import dev.smartdisplay.app.ui.common.PanelBody

/** On or off, and the name Home Assistant shows; how to add it there. */
@Composable
internal fun SpeakerSettings(speaker: SpeakerConfig, mode: DisplayMode, onChange: (SpeakerConfig) -> Unit) {
    SwitchRow(
        title = stringResource(R.string.settings_speaker_enable),
        description = stringResource(R.string.settings_speaker_enable_body),
        checked = speaker.enabled,
        onCheckedChange = { onChange(speaker.copy(enabled = it)) },
    )
    val placeholder = stringResource(defaultSpeakerName(mode))
    var name by rememberSaveable(speaker.name) { mutableStateOf(speaker.name.orEmpty()) }
    Text(
        stringResource(R.string.settings_speaker_name),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp),
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(MAX_NAME) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        // Saved on its own button: a new name restarts the speaker, which shouldn't happen at every letter.
        Button(
            onClick = { onChange(speaker.copy(name = name.trim().takeIf { it.isNotEmpty() })) },
            enabled = name.trim() != speaker.name.orEmpty(),
            modifier = Modifier.padding(start = 12.dp),
        ) {
            Text(stringResource(R.string.settings_wifi_save))
        }
    }
    if (speaker.enabled) {
        PanelBody(stringResource(R.string.settings_speaker_how, speaker.name ?: placeholder), topPadding = 24.dp)
    }
}

private const val MAX_NAME = 40
