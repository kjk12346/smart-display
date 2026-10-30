package dev.smartdisplay.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ha.ConnectionStatus
import kotlinx.coroutines.delay

/** A drop shorter than this doesn't show: Home Assistant connections blip, and the app is back within a second or two. */
private const val RECONNECT_GRACE_MS = 5_000L

/** The very first connection gets a moment before "Connecting…" shows. */
private const val FIRST_CONNECT_GRACE_MS = 1_500L

/**
 * A small chip, a slowly pulsing dot and "Reconnecting…" (or "Connecting…" before the first connection), shown
 * only once Home Assistant has been out of reach for a few seconds, and gone as soon as it's back.
 */
@Composable
fun ConnectionNotice(status: ConnectionStatus, loaded: Boolean, modifier: Modifier = Modifier) {
    val down = status is ConnectionStatus.Waiting || status == ConnectionStatus.Connecting
    val show by produceState(false, down, loaded) {
        value = false
        if (down) {
            delay(if (loaded) RECONNECT_GRACE_MS else FIRST_CONNECT_GRACE_MS)
            value = true
        }
    }
    AnimatedVisibility(visible = show, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_200), RepeatMode.Reverse),
            label = "dot",
        )
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .graphicsLayer { alpha = pulse }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
                Text(
                    stringResource(if (loaded) R.string.connection_reconnecting else R.string.live_connecting),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
