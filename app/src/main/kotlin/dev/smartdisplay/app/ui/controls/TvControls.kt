package dev.smartdisplay.app.ui.controls

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.smartdisplay.app.R

/**
 * A TV's controls, opened from its card: power and inputs, the arrows, OK, Back and Home (when the TV's integration
 * has a [remote]), play/pause and skipping, and the volume. Made for whoever cast something to it from their phone
 * and wants to pause it or turn it down without their phone.
 */
@Composable
internal fun TvControlsDialog(
    control: Control,
    remote: TvRemote?,
    onCall: (ServiceCall) -> Unit,
    onDismiss: () -> Unit,
) {
    val info = control.entity.mediaInfo()
    val id = control.entityId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(control.name) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                val nowPlaying = listOfNotNull(info.title, info.artist).joinToString(" – ").ifBlank { null }
                Text(
                    if (info.active) nowPlaying ?: mediaStateLabel(control.entity.state) else mediaStateLabel("off"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.canTurnOn || info.canTurnOff) {
                    val on = info.active
                    OutlinedButton(onClick = { onCall(Calls.power(id, !on)) }) {
                        Text(stringResource(if (on) R.string.tv_turn_off else R.string.tv_turn_on))
                    }
                }
                if (info.sources.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        info.sources.forEach { source ->
                            FilterChip(
                                selected = source == info.source,
                                onClick = { if (source != info.source) onCall(Calls.source(id, source)) },
                                label = { Text(source) },
                            )
                        }
                    }
                }
                if (remote != null) DirectionPad(remote, onCall)
                if (info.canPlayPause || info.canSkip) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (info.canSkip) {
                            FilledTonalIconButton(onClick = { onCall(Calls.previousTrack(id)) }) {
                                SkipGlyph(forward = false)
                            }
                        }
                        if (info.canPlayPause) {
                            FilledTonalIconButton(
                                onClick = { onCall(Calls.playPause(id)) },
                                modifier = Modifier.size(56.dp),
                            ) { PlayPauseGlyph(playing = info.playing) }
                        }
                        if (info.canSkip) {
                            FilledTonalIconButton(onClick = { onCall(Calls.nextTrack(id)) }) {
                                SkipGlyph(forward = true)
                            }
                        }
                    }
                }
                VolumeRow(info, id, onCall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
    )
}

/** The arrows around OK, then Back and Home. */
@Composable
private fun DirectionPad(remote: TvRemote, onCall: (ServiceCall) -> Unit) {
    @Composable
    fun Key(key: TvKey, label: String, big: Boolean = false) {
        FilledTonalIconButton(
            onClick = { onCall(remote.press(key)) },
            shape = CircleShape,
            modifier = Modifier.size(if (big) 72.dp else 56.dp),
        ) { Text(label, fontSize = if (big) 18.sp else 22.sp) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Key(TvKey.Up, "▲")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Key(TvKey.Left, "◀")
            Key(TvKey.Select, stringResource(R.string.tv_ok), big = true)
            Key(TvKey.Right, "▶")
        }
        Key(TvKey.Down, "▼")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            FilledTonalButton(onClick = { onCall(remote.press(TvKey.Back)) }) { Text(stringResource(R.string.tv_back)) }
            FilledTonalButton(onClick = { onCall(remote.press(TvKey.Home)) }) { Text(stringResource(R.string.tv_home)) }
        }
    }
}

/** Volume down, mute and up: in steps where the TV takes them, else a twentieth of the range at a time. */
@Composable
private fun VolumeRow(info: MediaInfo, id: String, onCall: (ServiceCall) -> Unit) {
    val canChange = info.canStepVolume || info.canSetVolume
    if (!canChange && !info.canMute) return
    fun step(up: Boolean) = if (info.canStepVolume) {
        Calls.volumeStep(id, up)
    } else {
        Calls.volume(id, (info.volume ?: 0f) + if (up) VOLUME_STEP else -VOLUME_STEP)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.tv_volume), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        if (canChange) {
            FilledTonalIconButton(onClick = { onCall(step(up = false)) }) { Text("−", fontSize = 22.sp) }
        }
        if (info.canMute) {
            Box {
                TextButton(onClick = { onCall(Calls.mute(id, !info.muted)) }) {
                    Text(stringResource(if (info.muted) R.string.tv_unmute else R.string.tv_mute))
                }
            }
        }
        if (canChange) {
            FilledTonalIconButton(onClick = { onCall(step(up = true)) }) { Text("+", fontSize = 22.sp) }
        }
    }
}

private const val VOLUME_STEP = 0.05f
