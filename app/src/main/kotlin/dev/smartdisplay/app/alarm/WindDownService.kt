package dev.smartdisplay.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.smartdisplay.app.MainActivity
import dev.smartdisplay.app.R
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.ha.ConnectionStatus
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Runs the evening wind-down: a foreground service, so it keeps going with the screen off. The wind-down's lights
 * that are on fade to a dim, warm glow until bedtime, then go off; what's chosen elsewhere goes off at the start.
 * Lights that are off are left off, and one someone turns off during it stays off. Separate from [AlarmService], so
 * an alarm and a wind-down never stop each other.
 */
class WindDownService : Service() {
    private val app get() = application as SmartDisplayApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Lights in this wind-down and how they were at its start, less any someone turned off. */
    private val fading = mutableMapOf<String, LightStart>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val windDown = WindDown(
                    startMillis = intent.getLongExtra(EXTRA_START, 0),
                    bedtimeMillis = intent.getLongExtra(EXTRA_BEDTIME, 0),
                )
                // Android needs the foreground notification straight away, even if there's nothing to do after all.
                startForeground(notification(windDown.bedtimeMillis))
                begin(windDown)
            }
            ACTION_NOW -> {
                val now = System.currentTimeMillis()
                val windDown = _active.value
                    ?: WindDown(now, now + app.alarms.windDown.value.minutes * 60_000L)
                startForeground(notification(windDown.bedtimeMillis))
                // Tonight's doesn't start again by itself.
                app.alarms.windDownDone(windDown.bedtimeMillis)
                begin(windDown)
            }
            ACTION_LIGHTS_OFF -> {
                val windDown = _active.value
                if (windDown == null) {
                    finish()
                } else {
                    job?.cancel()
                    job = scope.launch { lightsOff(windDown) }
                }
            }
            ACTION_STOP -> {
                // The lights stay as they are now.
                _active.value?.let { app.alarms.windDownDone(it.bedtimeMillis) }
                Log.i(TAG, "Wind-down stopped")
                end()
            }
            else -> if (_active.value == null) finish()
        }
        return START_NOT_STICKY
    }

    private fun startForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0,
        )
    }

    private fun begin(windDown: WindDown) {
        val now = System.currentTimeMillis()
        val current = _active.value
        when {
            current == windDown -> return
            windDown.bedtimeMillis <= now -> {
                if (current == null) finish()
                return
            }
        }
        job?.cancel()
        fading.clear()
        _active.value = windDown
        acquireWakeLock(windDown.bedtimeMillis - now + END_MARGIN_MS)
        Log.i(TAG, "Wind-down: lights off in ${(windDown.bedtimeMillis - now) / 1000} s")
        job = scope.launch { fade(windDown, app.alarms.windDown.value) }
    }

    private suspend fun fade(windDown: WindDown, settings: WindDownSettings) {
        coroutineScope {
            // Keep the live connection open throughout, and wait for it (it may be closed while the screen sleeps).
            val keep = launch { app.home.state.collect {} }
            val state = withTimeoutOrNull(windDown.bedtimeMillis - System.currentTimeMillis()) {
                app.home.state.first { it.status == ConnectionStatus.Connected }
            }
            if (state == null) {
                Log.w(TAG, "Wind-down: Home Assistant not reachable before bedtime")
                keep.cancel()
                return@coroutineScope
            }
            if (settings.houseOff.isNotEmpty()) call("homeassistant", "turn_off", settings.houseOff)
            settings.lights.forEach { id -> state.entities[id]?.lightStart()?.let { fading[id] = it } }
            val off = settings.lights - fading.keys
            Log.i(TAG, "Wind-down: fading ${fading.keys.joinToString()}; already off: ${off.joinToString()}")
            val sent = mutableMapOf<String, SunriseLevel>()
            while (true) {
                val now = System.currentTimeMillis()
                if (now >= windDown.bedtimeMillis) break
                val home = app.home.state.value
                if (home.status == ConnectionStatus.Connected) {
                    val progress = windDown.progress(now)
                    for ((id, start) in fading.toMap()) {
                        val entity = home.entities[id] ?: continue
                        if (entity.state == "off") {
                            Log.i(TAG, "Wind-down: $id was turned off; leaving it off")
                            fading -= id
                            continue
                        }
                        if (entity.state != "on") continue
                        val level = windDownLevel(start, progress)
                        if (sent[id] == level) continue
                        val data = sunriseCall(entity.sunriseLight(), level, (STEP_MS / 1000).toInt()) ?: continue
                        call("light", "turn_on", setOf(id), data)
                        sent[id] = level
                    }
                }
                delay(minOf(STEP_MS, windDown.bedtimeMillis - now))
            }
            keep.cancel()
        }
        lightsOff(windDown)
    }

    /** Bedtime (or "Lights off now"): the wind-down's lights go off, gently on lights that can fade. */
    private suspend fun lightsOff(windDown: WindDown) {
        val lights = fading.keys.toSet()
        if (lights.isNotEmpty()) {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                coroutineScope {
                    val keep = launch { app.home.state.collect {} }
                    val home = app.home.state.first { it.status == ConnectionStatus.Connected }
                    val (gliding, others) = lights.partition { home.entities[it]?.sunriseLight()?.transitions == true }
                    if (gliding.isNotEmpty()) {
                        call("light", "turn_off", gliding.toSet(), buildJsonObject { put("transition", OFF_SECONDS) })
                    }
                    if (others.isNotEmpty()) call("light", "turn_off", others.toSet())
                    keep.cancel()
                }
            }
        }
        Log.i(TAG, "Wind-down: lights off")
        app.alarms.windDownDone(windDown.bedtimeMillis)
        end()
    }

    private suspend fun call(
        domain: String,
        service: String,
        entities: Set<String>,
        data: JsonObject = JsonObject(emptyMap()),
    ) {
        try {
            app.home.callService(
                domain, service, data,
                target = buildJsonObject { putJsonArray("entity_id") { entities.forEach { add(JsonPrimitive(it)) } } },
            )
        } catch (e: IOException) {
            Log.w(TAG, "Wind-down action $domain.$service failed", e)
        }
    }

    private fun end() {
        job?.cancel()
        job = null
        fading.clear()
        _active.value = null
        finish()
    }

    private fun finish() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartDisplay:windDown")
            .apply { acquire(timeoutMs) }
    }

    private fun notification(bedtimeMillis: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.wind_down_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val bedtime = Instant.ofEpochMilli(bedtimeMillis).atZone(ZoneId.systemDefault())
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.wind_down_title))
            .setContentText(getString(R.string.wind_down_lights_off_at, timeText(this, bedtime)))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.wind_down_lights_off_now), serviceIntent(ACTION_LIGHTS_OFF, 1))
            .addAction(0, getString(R.string.wind_down_stop), serviceIntent(ACTION_STOP, 2))
            .build()
    }

    private fun serviceIntent(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, WindDownService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        _active.value = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WindDownService"
        private const val CHANNEL_ID = "wind_down"
        /** Not [AlarmService]'s, so each keeps its own notification. */
        private const val NOTIFICATION_ID = 2
        private const val ACTION_START = "dev.smartdisplay.app.alarm.WIND_DOWN_START"
        private const val ACTION_NOW = "dev.smartdisplay.app.alarm.WIND_DOWN_NOW"
        private const val ACTION_LIGHTS_OFF = "dev.smartdisplay.app.alarm.WIND_DOWN_LIGHTS_OFF"
        private const val ACTION_STOP = "dev.smartdisplay.app.alarm.WIND_DOWN_STOP"
        private const val EXTRA_START = "start"
        private const val EXTRA_BEDTIME = "bedtime"

        /** How often the lights step down (each step glides, on lights that can). */
        private const val STEP_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 60_000L
        private const val END_MARGIN_MS = 2 * 60_000L
        /** Lights that can fade go out over this long at bedtime. */
        private const val OFF_SECONDS = 10

        private val _active = MutableStateFlow<WindDown?>(null)

        /** The wind-down under way, for the display's panel. */
        val active: StateFlow<WindDown?> = _active.asStateFlow()

        /** Starts [windDown] at its time (from Android's alarm clock). */
        fun start(context: Context, windDown: WindDown) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WindDownService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_START, windDown.startMillis)
                    .putExtra(EXTRA_BEDTIME, windDown.bedtimeMillis),
            )
        }

        /** The Wind down button: starts one now, lasting the chosen time (or shows the one under way). */
        fun startNow(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, WindDownService::class.java).setAction(ACTION_NOW),
            )
        }

        fun lightsOffNow(context: Context) {
            context.startService(Intent(context, WindDownService::class.java).setAction(ACTION_LIGHTS_OFF))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WindDownService::class.java).setAction(ACTION_STOP))
        }
    }
}
