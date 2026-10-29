package dev.smartdisplay.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.smartdisplay.app.R
import dev.smartdisplay.app.speaker.Speaker
import dev.smartdisplay.app.ui.controls.PlayPauseGlyph

/**
 * What Home Assistant is playing on this display, with pause and stop, in a small bar at [alignment]; nothing while
 * the speaker has nothing loaded.
 */
@Composable
fun NowPlayingBar(speaker: Speaker, alignment: Alignment) {
    val now by speaker.nowPlaying.collectAsStateWithLifecycle()
    val playing = now ?: return
    Box(contentAlignment = alignment, modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                val art = playing.track.art
                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                    )
                }
                Column(modifier = Modifier.weight(1f, fill = false).padding(horizontal = 12.dp)) {
                    Text(
                        playing.track.title ?: stringResource(R.string.speaker_playing),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val detail = listOfNotNull(playing.track.artist, playing.track.album).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val pauseLabel = stringResource(if (playing.playing) R.string.speaker_pause else R.string.speaker_play)
                FilledTonalIconButton(
                    onClick = speaker::togglePause,
                    modifier = Modifier.semantics { contentDescription = pauseLabel },
                ) {
                    PlayPauseGlyph(playing = playing.playing)
                }
                val stopLabel = stringResource(R.string.speaker_stop)
                IconButton(onClick = speaker::stop, modifier = Modifier.semantics { contentDescription = stopLabel }) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
        }
    }
}
