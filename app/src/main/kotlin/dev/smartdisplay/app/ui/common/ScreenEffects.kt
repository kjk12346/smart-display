package dev.smartdisplay.app.ui.common

import android.app.ActivityManager
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.time.LocalDateTime
import kotlinx.coroutines.delay

/**
 * The window's brightness, shared by everything that wants to dim it (night hours, idle): each asks for a level,
 * and the darkest wins. Only this app's window changes, so the device's own brightness setting is left alone.
 */
class WindowBrightness(private val window: Window) {
    private val levels = LinkedHashMap<Any, Float>()

    /** Sets [owner]'s wanted level, from 0 to 1, or clears it with null. */
    fun request(owner: Any, level: Float?) {
        if (level == null) levels.remove(owner) else levels[owner] = level
        window.attributes = window.attributes.apply {
            screenBrightness = levels.values.minOrNull() ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
}

val LocalWindowBrightness = staticCompositionLocalOf<WindowBrightness?> { null }

/** Asks for a screen brightness while this is shown: [level] from 0 to 1, or null for no preference. */
@Composable
fun ScreenBrightness(level: Float?) {
    val brightness = LocalWindowBrightness.current ?: return
    val owner = remember { Any() }
    DisposableEffect(brightness, level) {
        brightness.request(owner, level)
        onDispose { brightness.request(owner, null) }
    }
}

/** Keeps the screen from sleeping while this is shown and [enabled]. */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window, enabled) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * Screen pinning: pins the app while this is shown and [enabled], and unpins when [enabled] turns false (the way
 * out, behind the exit PIN). Android shows its own notice when pinning starts; if pinning is refused (turned off in
 * Android's settings), the display just carries on unpinned.
 */
@Composable
fun LockTask(enabled: Boolean) {
    val activity = LocalActivity.current ?: return
    LifecycleResumeEffect(activity, enabled) {
        val pinned = activity.getSystemService(ActivityManager::class.java).lockTaskModeState !=
            ActivityManager.LOCK_TASK_MODE_NONE
        try {
            if (enabled && !pinned) activity.startLockTask()
            if (!enabled && pinned) activity.stopLockTask()
        } catch (e: RuntimeException) {
            // Refused, or pinned by someone else; nothing to do.
        }
        onPauseOrDispose {}
    }
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
