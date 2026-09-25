package dev.smartdisplay.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.MediaFolder
import dev.smartdisplay.app.ha.MediaItem
import java.io.IOException

/** What the media browser is picking. */
enum class MediaPick {
    /** A folder with photos in it, for wallpaper. */
    PhotoFolder,
    /** A sound file, for an alarm on a speaker. */
    Sound,
}

/**
 * Browses Home Assistant's media browser (local media, and any photo or music sources added to Home Assistant) to
 * pick a photo folder or a sound.
 */
@Composable
fun MediaBrowserDialog(
    pick: MediaPick,
    browse: suspend (String) -> MediaFolder,
    onPicked: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
) {
    // The folders opened so far; the last is the one showing. Empty means the top level.
    val path = remember { mutableStateListOf<MediaItem>() }
    var folder by remember { mutableStateOf<MediaFolder?>(null) }
    var failed by remember { mutableStateOf(false) }
    val currentId = path.lastOrNull()?.contentId ?: ""

    LaunchedEffect(currentId) {
        folder = null
        failed = false
        try {
            folder = browse(currentId)
        } catch (e: IOException) {
            failed = true
        }
    }

    val shown = folder
    val photoCount = shown?.children?.count { it.isImage } ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(path.lastOrNull()?.title?.ifBlank { null } ?: stringResource(R.string.media_title))
        },
        text = {
            Box(Modifier.heightIn(min = 120.dp, max = 420.dp)) {
                when {
                    failed -> Text(stringResource(R.string.media_failed), color = MaterialTheme.colorScheme.error)
                    shown == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp))
                    else -> LazyColumn {
                        if (path.isNotEmpty()) {
                            item { MediaRow(stringResource(R.string.media_up)) { path.removeAt(path.lastIndex) } }
                        }
                        items(shown.children.filter { it.canExpand }, key = { it.contentId }) { child ->
                            MediaRow("${child.title.ifBlank { "…" }} ›") { path.add(child) }
                        }
                        if (pick == MediaPick.Sound) {
                            items(shown.children.filter { it.isAudio && it.canPlay }, key = { it.contentId }) { sound ->
                                MediaRow(sound.title) { onPicked(sound) }
                            }
                        }
                        if (pick == MediaPick.PhotoFolder && photoCount > 0) {
                            item {
                                Text(
                                    text = pluralStringResource(R.plurals.media_photo_count, photoCount, photoCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                        if (shown.children.none { it.canExpand } && photoCount == 0 &&
                            (pick == MediaPick.PhotoFolder || shown.children.none { it.isAudio })
                        ) {
                            item {
                                Text(
                                    text = stringResource(R.string.media_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (pick == MediaPick.PhotoFolder) {
                // Named as it was listed ("My media"): a folder's own title can be an internal name ("media").
                val listedTitle = path.lastOrNull()?.title?.ifBlank { null }
                TextButton(
                    onClick = { shown?.let { onPicked(it.item.copy(title = listedTitle ?: it.item.title)) } },
                    enabled = photoCount > 0,
                ) {
                    Text(stringResource(R.string.media_use_folder))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MediaRow(text: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
