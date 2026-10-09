package dev.smartdisplay.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

/**
 * Calls [onIdle] once [timeoutMillis] pass with no touch anywhere in [content]. Touches are observed on the way
 * down, without consuming them.
 */
@Composable
fun ReturnWhenIdle(timeoutMillis: Long, onIdle: () -> Unit, content: @Composable () -> Unit) {
    var touches by remember { mutableIntStateOf(0) }
    val currentOnIdle by rememberUpdatedState(onIdle)
    LaunchedEffect(touches) {
        delay(timeoutMillis)
        currentOnIdle()
    }
    Box(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) touches++
                }
            }
        }
    ) {
        content()
    }
}
