package dev.smartdisplay.app.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.ha.string
import dev.smartdisplay.app.kiosk.DashCard
import dev.smartdisplay.app.ui.controls.Calls
import dev.smartdisplay.app.ui.controls.ControlCard
import dev.smartdisplay.app.ui.controls.PlayPauseGlyph
import dev.smartdisplay.app.ui.controls.ServiceCall
import dev.smartdisplay.app.ui.controls.SkipGlyph
import dev.smartdisplay.app.ui.controls.mediaInfo
import dev.smartdisplay.app.ui.controls.tvRemote
import kotlinx.coroutines.delay

/** How often a camera card fetches a new picture. */
private const val CAMERA_REFRESH_MS = 10_000L

/** A device card's narrowest; the card fits as many across as it can. */
private val DEVICE_MIN_WIDTH = 160.dp

/** Chosen lights, switches, thermostats and media players (or a whole area's), as on the controls screen. */
@Composable
internal fun DevicesCard(
    card: DashCard,
    home: HomeState,
    onCall: (ServiceCall, name: String) -> Unit,
    modifier: Modifier,
    scroll: Boolean,
) {
    val controls = home.cardControls(card.areaId, card.entities)
    val title = card.areaId?.let { id -> home.areas.firstOrNull { it.id == id }?.name }
        ?: stringResource(R.string.card_devices)
    TitledCard(title, modifier, scroll) {
        if (controls.isEmpty()) Hint(stringResource(R.string.card_choose_sources))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 12.dp
            val columns = ((maxWidth + gap) / (DEVICE_MIN_WIDTH + gap)).toInt().coerceAtLeast(1)
            val width = (maxWidth - gap * (columns - 1)) / columns
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                controls.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { control ->
                            Box(Modifier.width(width)) {
                                ControlCard(control, home.config?.temperatureUnit, home.tvRemote(control.entityId)) {
                                    onCall(it, control.name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Chosen sensors: each name with its value, large. */
@Composable
internal fun SensorsCard(card: DashCard, home: HomeState, modifier: Modifier, scroll: Boolean) {
    val sensors = card.entities.mapNotNull { home.entities[it] }
    TitledCard(stringResource(R.string.card_sensors), modifier, scroll) {
        if (sensors.isEmpty()) Hint(stringResource(R.string.card_choose_sources))
        sensors.forEach { sensor ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                Text(
                    sensor.friendlyName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(
                    sensorText(sensor) ?: "–",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun sensorText(sensor: EntityState): String? {
    if (sensor.domain != "binary_sensor") return sensorValue(sensor)
    if (sensor.state != "on" && sensor.state != "off") return null
    val on = sensor.state == "on"
    return stringResource(
        when (binaryMeaning(sensor.attributes.string("device_class"))) {
            BinaryMeaning.OpenClosed -> if (on) R.string.sensor_open else R.string.sensor_closed
            BinaryMeaning.DetectedClear -> if (on) R.string.sensor_detected else R.string.sensor_clear
            BinaryMeaning.WetDry -> if (on) R.string.sensor_wet else R.string.sensor_dry
            BinaryMeaning.OnOff -> if (on) R.string.sensor_on else R.string.sensor_off
        },
    )
}

/** A media player's cover art, what's playing, and previous / play-pause / next. */
@Composable
internal fun NowPlayingCard(
    card: DashCard,
    home: HomeState,
    baseUrl: String?,
    onCall: (ServiceCall, name: String) -> Unit,
    modifier: Modifier,
    scroll: Boolean,
) {
    val player = card.entities.firstOrNull()?.let { home.entities[it] }
    TitledCard(player?.friendlyName ?: stringResource(R.string.card_now_playing), modifier, scroll) {
        if (player == null) {
            Hint(stringResource(R.string.card_choose_sources))
            return@TitledCard
        }
        val info = player.mediaInfo()
        val art = absoluteUrl(baseUrl, player.attributes.string("entity_picture")).takeIf { info.active }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (art != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(art).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
            Column(Modifier.padding(start = if (art != null) 16.dp else 0.dp)) {
                val idle = if (info.active) R.string.card_now_playing else R.string.card_nothing_playing
                Text(
                    info.title ?: stringResource(idle),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                info.artist?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (info.active) {
            val name = player.friendlyName
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 16.dp)) {
                FilledTonalIconButton(onClick = { onCall(Calls.previousTrack(player.entityId), name) }) {
                    SkipGlyph(forward = false)
                }
                FilledTonalIconButton(
                    onClick = { onCall(Calls.playPause(player.entityId), name) },
                    enabled = info.canPlayPause,
                ) { PlayPauseGlyph(info.playing, glyphSize = 20.dp) }
                FilledTonalIconButton(onClick = { onCall(Calls.nextTrack(player.entityId), name) }) {
                    SkipGlyph(forward = true)
                }
            }
        }
    }
}

/**
 * A camera's picture, fetched again every [CAMERA_REFRESH_MS]. Home Assistant's `entity_picture` carries a token, so
 * it loads without signing in; the token changes now and then, which arrives as a state change.
 */
@Composable
internal fun CameraCard(card: DashCard, home: HomeState, baseUrl: String?, modifier: Modifier, scroll: Boolean) {
    val camera = card.entities.firstOrNull()?.let { home.entities[it] }
    TitledCard(camera?.friendlyName ?: stringResource(R.string.card_camera), modifier, scroll) {
        val picture = absoluteUrl(baseUrl, camera?.attributes?.string("entity_picture"))
        if (camera == null || picture == null) {
            Hint(stringResource(if (camera == null) R.string.card_choose_sources else R.string.card_camera_off))
            return@TitledCard
        }
        var tick by remember { mutableLongStateOf(0L) }
        LaunchedEffect(picture) {
            while (true) {
                delay(CAMERA_REFRESH_MS)
                tick++
            }
        }
        val url = picture + (if ('?' in picture) "&" else "?") + "_=$tick"
        AsyncImage(
            // The last picture stays up while the next loads.
            model = ImageRequest.Builder(LocalContext.current).data(url).placeholderMemoryCacheKey(previous(url))
                .crossfade(true).build(),
            contentDescription = camera.friendlyName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
        )
    }
}

/** The URL loaded before [url], so its picture can stand in while [url] loads. */
@Composable
private fun previous(url: String): String? {
    val last = remember { arrayOf<String?>(null) }
    val before = last[0]
    last[0] = url
    return before
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
