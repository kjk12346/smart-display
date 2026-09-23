package dev.smartdisplay.app.ui.common

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.time.LocalDateTime
import kotlinx.coroutines.delay

/**
 * Overrides the screen brightness while this is shown: [level] from 0 to 1, or null for the system setting. Only
 * affects this app's window, so the device's own brightness setting is left alone.
 */
@Composable
fun ScreenBrightness(level: Float?) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window, level) {
        window.setBrightness(level ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        onDispose { window.setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }
}

private fun android.view.Window.setBrightness(value: Float) {
    attributes = attributes.apply { screenBrightness = value }
}

/** Hides the status and navigation bars while this is shown; a swipe from the edge brings them back briefly. */
@Composable
fun HideSystemBars() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * The current local time, updated at the start of each minute. It reads the clock afresh each time, so a time zone
 * or clock change shows up by the next minute.
 */
@Composable
fun rememberMinuteClock(): State<LocalDateTime> {
    val now = remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = LocalDateTime.now()
            now.value = current
            val intoMinute = current.second * 1_000L + current.nano / 1_000_000
            delay(60_000L - intoMinute + 20)
        }
    }
    return now
}
