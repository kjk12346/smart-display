package dev.smartdisplay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.smartdisplay.app.auth.REDIRECT_URI
import dev.smartdisplay.app.auth.SessionState
import dev.smartdisplay.app.kiosk.HomeApp
import dev.smartdisplay.app.ui.alarms.AlarmsScreen
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

    // The power button in a kiosk: see onScreenOff.
    private val screenOffs = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var sleptWhileShowing = false
    private var watchingScreen = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onScreenOff()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        brightness = WindowBrightness(window)
        // Only a fresh launch carries a new sign-in result; after a rotation the intent is the one already handled.
        if (savedInstanceState == null) handleIntent(intent)

        // One display at a time. As the Home app, Android keeps the display in its own Home task, and an ordinary
        // launch (the app icon, the alarm's clock icon, the sign-in redirect) would start a second copy beside it.
        // Such a launch goes to the Home one instead, and the Home one closes any copy that was already running.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            running.filter { it !== this }.forEach { it.finish() }
        } else if (HomeApp.isDefault(this)) {
            startActivity(HomeApp.homeIntent)
            finish()
            return
        }
        running += this

        // A kiosk shows over the lock screen, so waking it (below) brings back the display, not the lock screen.
        lifecycleScope.launch {
            app.kiosk.config.map { it.locked }.distinctUntilChanged().collect(::showOverLockScreen)
        }
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        watchingScreen = true

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
                    AppContent(app, screenOffs)
                }
            }
        }
    }

    // singleTask: the browser's sign-in redirect (and Home, when this is the Home app) comes back here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        sleptWhileShowing = false
    }

    override fun onStop() {
        super.onStop()
        // Stopped because the screen is going off (the power button or a timeout), not because something opened
        // on top. The screen-off broadcast arrives just after this.
        sleptWhileShowing = !getSystemService(PowerManager::class.java).isInteractive
    }

    override fun onDestroy() {
        running -= this
        if (watchingScreen) unregisterReceiver(screenOffReceiver)
        // The owner turned "Use as Home app" off: Android closes the Home display (a second or so later, once the
        // alias is off), so open an ordinary one in its place rather than dropping to the launcher. Not when someone
        // simply chose another Home app in Android's settings: then the display's own setting is still on.
        if (isFinishing && intent.hasCategory(Intent.CATEGORY_HOME) && !app.kiosk.config.value.homeApp &&
            !HomeApp.isDefault(this)
        ) {
            val ordinary = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(ordinary)
        }
        super.onDestroy()
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

    /**
     * The power button, in a kiosk: apps can't catch the button itself, so when the screen goes off while the display
     * was showing, it goes back to the clock and wakes the screen straight away. Only with "Keep screen on", or the
     * screen's own sleep timeout would be undone too. Holding the button still offers Android's power menu.
     */
    private fun onScreenOff() {
        val config = app.kiosk.config.value
        val showing = sleptWhileShowing || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (!config.locked || !config.keepScreenOn || !showing) return
        screenOffs.tryEmit(Unit)
        // Deprecated in favour of setTurnScreenOn, which only acts when an activity is resumed; the display is
        // already open, so a brief wake lock that wakes the device is what's needed here.
        @Suppress("DEPRECATION")
        val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
        getSystemService(PowerManager::class.java).newWakeLock(flags, WAKE_TAG).acquire(WAKE_MS)
    }

    private fun showOverLockScreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
        } else {
            @Suppress("DEPRECATION")
            if (on) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val redirect = REDIRECT_URI.toUri()
        if (uri.scheme == redirect.scheme && uri.host == redirect.host) app.session.handleRedirect(uri)
    }

    private companion object {
        val IDLE_DIM = Any()

        const val WAKE_TAG = "smartdisplay:power-button"

        /** How long the wake lock holds; after that the window's keep-screen-on keeps it awake. */
        const val WAKE_MS = 5_000L

        /** The displays open now (main thread only): normally one, briefly two while becoming the Home app. */
        val running = mutableSetOf<MainActivity>()
    }
}

@Composable
private fun AppContent(app: SmartDisplayApp, screenOffs: Flow<Unit>) {
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
            // The power button in a kiosk wakes the display straight back to the clock (MainActivity.onScreenOff).
            LaunchedEffect(screenOffs) {
                screenOffs.collect {
                    screen = Screen.Ambient
                    askingPin = false
                }
            }
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
                    onOpenAlarms = { screen = Screen.Alarms },
                )
                Screen.Alarms -> AlarmsScreen(onDone = { screen = Screen.Controls })
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
private enum class Screen { Ambient, Controls, Alarms, Settings }
