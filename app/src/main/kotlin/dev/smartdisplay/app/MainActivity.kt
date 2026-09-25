package dev.smartdisplay.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.smartdisplay.app.auth.REDIRECT_URI
import dev.smartdisplay.app.auth.SessionState
import dev.smartdisplay.app.ui.ambient.AmbientScreen
import dev.smartdisplay.app.ui.common.HideSystemBars
import dev.smartdisplay.app.ui.common.KeepScreenOn
import dev.smartdisplay.app.ui.common.LocalNetworkPrompt
import dev.smartdisplay.app.ui.common.LocalWindowBrightness
import dev.smartdisplay.app.ui.common.LockTask
import dev.smartdisplay.app.ui.common.WindowBrightness
import dev.smartdisplay.app.ui.common.rememberLocalNetworkAccess
import dev.smartdisplay.app.ui.controls.ControlsScreen
import dev.smartdisplay.app.ui.settings.EnterPinDialog
import dev.smartdisplay.app.ui.settings.SettingsScreen
import dev.smartdisplay.app.ui.settings.checkExitPin
import dev.smartdisplay.app.ui.setup.SetupScreen
import dev.smartdisplay.app.ui.signin.SignInScreen
import dev.smartdisplay.app.ui.theme.SmartDisplayTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Screen brightness when dimmed for being idle (0 to 1). */
private const val IDLE_BRIGHTNESS = 0.02f

class MainActivity : ComponentActivity() {
    private val app get() = application as SmartDisplayApp
    private lateinit var brightness: WindowBrightness

    // Dim when idle: after the chosen time without a touch, the screen dims; the next touch only wakes it.
    private val handler = Handler(Looper.getMainLooper())
    private var dimAfterMillis = 0L
    private var dimmed = false
    private var swallowingTouch = false
    private val dimRunnable = Runnable { setDimmed(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        brightness = WindowBrightness(window)
        // Only a fresh launch carries a new sign-in result; after a rotation the intent is the one already handled.
        if (savedInstanceState == null) handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.kiosk.config.map { it.dimAfterMinutes }.distinctUntilChanged().collect { minutes ->
                    dimAfterMillis = minutes * 60_000L
                    if (minutes == 0) setDimmed(false)
                    scheduleDim()
                }
            }
        }

        setContent {
            SmartDisplayTheme {
                CompositionLocalProvider(LocalWindowBrightness provides brightness) {
                    AppContent(app)
                }
            }
        }
    }

    // singleTask: the browser's sign-in redirect (and Home, when this is the Home app) comes back here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        scheduleDim()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(dimRunnable)
        setDimmed(false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // The first touch on a dimmed screen only wakes it, so it can't switch a lamp by accident.
            if (dimmed) {
                setDimmed(false)
                swallowingTouch = true
            }
            scheduleDim()
        }
        if (swallowingTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowingTouch = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        if (dimAfterMillis > 0) handler.postDelayed(dimRunnable, dimAfterMillis)
    }

    private fun setDimmed(on: Boolean) {
        dimmed = on
        brightness.request(IDLE_DIM, if (on) IDLE_BRIGHTNESS else null)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val redirect = REDIRECT_URI.toUri()
        if (uri.scheme == redirect.scheme && uri.host == redirect.host) app.session.handleRedirect(uri)
    }

    private companion object {
        val IDLE_DIM = Any()
    }
}

@Composable
private fun AppContent(app: SmartDisplayApp) {
    val localNetwork = rememberLocalNetworkAccess()
    if (!localNetwork.granted) {
        LocalNetworkPrompt(localNetwork)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val server by app.serverStore.server.collectAsStateWithLifecycle()
    val session by app.session.state.collectAsStateWithLifecycle()
    val kiosk by app.kiosk.config.collectAsStateWithLifecycle()

    val current = server
    when {
        current == null -> SetupScreen()
        session == SessionState.SignedIn -> {
            // Collecting keeps the live connection open while these screens are showing.
            val home by app.home.state.collectAsStateWithLifecycle()
            var screen by rememberSaveable { mutableStateOf(Screen.Ambient) }
            var askingPin by rememberSaveable { mutableStateOf(false) }
            val openSettings = { if (kiosk.hasPin) askingPin = true else screen = Screen.Settings }

            // Full screen for the whole display, not per screen: switching screens would briefly show the bars.
            HideSystemBars()
            KeepScreenOn(kiosk.keepScreenOn)
            // Settings (behind the PIN) unpins: it's the way out of a pinned display.
            LockTask(enabled = kiosk.pinApp && screen != Screen.Settings)
            // A kiosk display doesn't back out of the clock. Controls and Settings handle Back themselves.
            BackHandler(enabled = kiosk.locked) {}

            when (screen) {
                Screen.Ambient -> AmbientScreen(
                    home = home,
                    wallpaperConfig = kiosk.wallpaper,
                    onOpenControls = { screen = Screen.Controls },
                    onOpenSettings = openSettings,
                )
                Screen.Controls -> ControlsScreen(
                    home = home,
                    guest = kiosk.guest.takeIf { kiosk.isGuest },
                    onDone = { screen = Screen.Ambient },
                    onOpenSettings = openSettings,
                )
                Screen.Settings -> SettingsScreen(
                    server = current,
                    home = home,
                    onBack = { screen = Screen.Ambient },
                    onSignOut = {
                        screen = Screen.Ambient
                        scope.launch { app.session.signOut() }
                    },
                )
            }
            if (askingPin) {
                EnterPinDialog(
                    check = { app.checkExitPin(it) },
                    onCorrect = {
                        askingPin = false
                        screen = Screen.Settings
                    },
                    onDismiss = { askingPin = false },
                )
            }
        }
        else -> SignInScreen(
            server = current,
            state = session,
            onSignIn = { app.session.beginSignIn(context) },
            onChangeServer = { scope.launch { app.session.forgetServer() } },
        )
    }
}

/** The screens once signed in. */
private enum class Screen { Ambient, Controls, Settings }
