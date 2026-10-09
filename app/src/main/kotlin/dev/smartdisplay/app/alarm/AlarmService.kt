package dev.smartdisplay.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.smartdisplay.app.R
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.ha.ConnectionStatus
import dev.smartdisplay.app.ha.HomeState
import java.io.IOException
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
 * Rings an alarm: a foreground service, so it keeps going with the screen off or the app closed. Plays the sound,
 * does the Home Assistant actions (lights, speaker), and shows the alarm screen, until Snooze, Dismiss, or
 * [RING_LIMIT_MS] pass. Before that, it runs the alarm's sunrise, if the owner turned that on.
 */
class AlarmService : Service() {
    private val app get() = application as SmartDisplayApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringJobs = mutableListOf<Job>()
    private var sunriseJob: Job? = null

    /** Lights someone turned off during the sunrise: they stay off, even when the alarm rings. */
    private val sunriseDropped = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> {
                val alarm = app.alarms.alarm(intent.getIntExtra(EXTRA_ALARM_ID, -1))
                if (alarm == null) stop(stopSpeaker = false) else startRinging(alarm)
            }
            ACTION_SNOOZE -> {
                ringing.value?.let { app.alarms.snooze(it.id) }
                stop(stopSpeaker = true)
            }
            ACTION_DISMISS -> stop(stopSpeaker = true)
            ACTION_SUNRISE -> startSunrise(
                alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1),
                ringMillis = intent.getLongExtra(EXTRA_RING_AT, 0),
            )
            ACTION_AWAKE -> imUp()
            else -> stop(stopSpeaker = false)
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

    /**
     * Brightens the alarm's lights from a dim warm glow to full daylight until it rings at [ringMillis] (the screen
     * glows too: the ambient screen follows [sunrise]). Lights already on are left alone, so nobody who's up gets
     * dimmed; a light someone turns off during it stays off.
     */
    private fun startSunrise(alarmId: Int, ringMillis: Long) {
        val alarm = app.alarms.alarm(alarmId)
        // Android needs the foreground notification straight away, even if there's nothing to do after all.
        startForeground(sunriseNotification(alarm, ringMillis))
        val current = _sunrise.value
        val now = System.currentTimeMillis()
        val minutes = app.alarms.actions.value.sunriseMinutes
        when {
            _ringing.value != null -> return
            current != null && current.alarmId == alarmId && current.ringMillis == ringMillis -> return
            alarm == null || minutes <= 0 || ringMillis <= now -> {
                if (current == null) finish()
                return
            }
        }
        sunriseJob?.cancel()
        sunriseDropped.clear()
        // A sunrise that starts late (the alarm was set for sooner than that) runs its whole course in the time left.
        val sunrise = Sunrise(alarmId, startMillis = maxOf(ringMillis - minutes * 60_000L, now), ringMillis)
        _sunrise.value = sunrise
        acquireWakeLock(ringMillis - now + RING_LIMIT_MS + STOP_TIMEOUT_MS)
        Log.i(TAG, "Sunrise: alarm $alarmId rings in ${(ringMillis - now) / 1000} s")
        sunriseJob = scope.launch { fadeLights(sunrise, app.alarms.actions.value.lights) }
    }

    private suspend fun fadeLights(sunrise: Sunrise, chosen: Set<String>) = coroutineScope {
        launch { app.home.state.collect {} }
        val state = app.home.state.first { it.status == ConnectionStatus.Connected }
        // Lights that are off now; any already on (someone's up) are left as they are.
        val lights = chosen.filter { state.entities[it]?.state == "off" }
        Log.i(TAG, "Sunrise: fading ${lights.joinToString()}; leaving ${(chosen - lights.toSet()).joinToString()}")
        val seenOn = mutableSetOf<String>()
        val sent = mutableMapOf<String, SunriseLevel>()
        while (true) {
            val now = System.currentTimeMillis()
            // Ring time: the alarm takes over (and by now it may already count as rung, so check this first).
            if (now >= sunrise.ringMillis - RING_MARGIN_MS) break
            val next = app.alarms.next()
            if (next == null || next.alarm.id != sunrise.alarmId ||
                next.at.toInstant().toEpochMilli() != sunrise.ringMillis
            ) {
                // The alarm was changed, turned off or deleted; the lights stay as they are.
                Log.i(TAG, "Sunrise: alarm ${sunrise.alarmId} no longer rings then; stopping")
                endSunrise()
                return@coroutineScope
            }
            val home = app.home.state.value
            if (home.status == ConnectionStatus.Connected) {
                val level = sunriseLevel(sunrise.progress(now))
                for (id in lights) {
                    if (id in sunriseDropped) continue
                    val entity = home.entities[id] ?: continue
                    when (entity.state) {
                        "on" -> seenOn += id
                        "off" -> if (id in seenOn) {
                            Log.i(TAG, "Sunrise: $id was turned off; leaving it off")
                            sunriseDropped += id
                            continue
                        }
                        else -> continue
                    }
                    if (sent[id] == level) continue
                    val data = sunriseCall(entity.sunriseLight(), level, (STEP_MS / 1000).toInt()) ?: continue
                    call("light", "turn_on", setOf(id), data)
                    sent[id] = level
                }
            }
            delay(minOf(STEP_MS, sunrise.ringMillis - now))
        }
        // The ring normally ends the sunrise; if it never comes (the alarm went at the last moment), stop anyway.
        delay(RING_GRACE_MS)
        if (_sunrise.value == sunrise) endSunrise()
    }

    /** "I'm up" during a sunrise: the lights come up to full, and this morning's ring is skipped. */
    private fun imUp() {
        val sunrise = _sunrise.value
        if (sunrise == null) {
            if (_ringing.value == null) finish()
            return
        }
        Log.i(TAG, "Sunrise: up before alarm ${sunrise.alarmId}")
        app.alarms.imUp(sunrise.alarmId, sunrise.ringMillis)
        val lights = app.alarms.actions.value.lights - sunriseDropped
        endSunrise(finishService = false)
        scope.launch {
            withTimeoutOrNull(STOP_TIMEOUT_MS) {
                val keep = launch { app.home.state.collect {} }
                val home = app.home.state.first { it.status == ConnectionStatus.Connected }
                lightsToFull(home, lights)
                keep.cancel()
            }
            if (_ringing.value == null && _sunrise.value == null) finish()
        }
    }

    private suspend fun lightsToFull(home: HomeState, lights: Set<String>) {
        val full = sunriseLevel(1f)
        for (id in lights) {
            val light = home.entities[id]?.sunriseLight()
            call("light", "turn_on", setOf(id), light?.let { sunriseCall(it, full, 2) } ?: JsonObject(emptyMap()))
        }
    }

    private fun endSunrise(finishService: Boolean = true) {
        sunriseJob?.cancel()
        sunriseJob = null
        _sunrise.value = null
        if (finishService && _ringing.value == null) finish()
    }

    private fun startRinging(alarm: Alarm) {
        // A second alarm while one rings replaces it.
        stopSound()
        ringJobs.forEach { it.cancel() }
        ringJobs.clear()
        // The sunrise, if there was one, has done its job: from here the alarm takes the lights to full.
        sunriseJob?.cancel()
        sunriseJob = null
        _sunrise.value = null

        _ringing.value = alarm
        startForeground(notification(alarm))
        acquireWakeLock(RING_LIMIT_MS + STOP_TIMEOUT_MS)

        val actions = app.alarms.actions.value
        if (actions.sound) startSound()
        if (actions.usesHomeAssistant) ringJobs += scope.launch { homeAssistantActions(actions) }
        ringJobs += scope.launch {
            delay(RING_LIMIT_MS)
            stop(stopSpeaker = true)
        }
        // Over the display when the app is showing; otherwise the full-screen notification brings it up.
        try {
            startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.i(TAG, "Alarm screen not opened directly; the notification will", e)
        }
    }

    private suspend fun homeAssistantActions(actions: AlarmActions) {
        // Keep the live connection open while ringing, and wait for it (it may be closed while the screen sleeps).
        ringJobs += scope.launch { app.home.state.collect {} }
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { app.home.state.first { it.status == ConnectionStatus.Connected } }
            ?: run {
                Log.w(TAG, "Home Assistant not reachable; alarm actions skipped")
                return
            }
        val lights = actions.lights - sunriseDropped
        if (lights.isNotEmpty()) {
            // After a sunrise, to full daylight; otherwise just on.
            if (actions.sunriseMinutes > 0) {
                lightsToFull(app.home.state.value, lights)
            } else {
                call("light", "turn_on", lights)
            }
        }
        val speaker = actions.speaker
        val sound = actions.soundId
        if (speaker != null && sound != null) {
            call(
                "media_player", "play_media", setOf(speaker),
                buildJsonObject {
                    put("media_content_id", sound)
                    put("media_content_type", "music")
                },
            )
        }
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
            Log.w(TAG, "Alarm action $domain.$service failed", e)
        }
    }

    private fun stop(stopSpeaker: Boolean) {
        stopSound()
        ringJobs.forEach { it.cancel() }
        ringJobs.clear()
        _ringing.value = null
        val speaker = app.alarms.actions.value.speaker
        if (stopSpeaker && speaker != null && app.alarms.actions.value.soundId != null) {
            // Stop the speaker too; give it a few seconds, then finish.
            scope.launch {
                withTimeoutOrNull(STOP_TIMEOUT_MS) {
                    val keep = launch { app.home.state.collect {} }
                    app.home.state.first { it.status == ConnectionStatus.Connected }
                    call("media_player", "media_stop", setOf(speaker))
                    keep.cancel()
                }
                finish()
            }
        } else {
            finish()
        }
    }

    private fun finish() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startSound() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Some tablets (Fire) have no alarm sounds installed; the screen still shows.
            Log.w(TAG, "Couldn't play the alarm sound", e)
            stopSound()
        }
    }

    private fun stopSound() {
        player?.runCatching {
            stop()
            release()
        }
        player = null
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartDisplay:alarm")
            .apply { acquire(timeoutMs) }
    }

    private fun notification(alarm: Alarm): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.alarm_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) }
        )
        val screen = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(alarm.label.ifBlank { getString(R.string.alarm_default_label) })
            .setContentText(alarmTimeText(this, alarm))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(0, getString(R.string.alarm_snooze), serviceIntent(ACTION_SNOOZE, 1))
            .addAction(0, getString(R.string.alarm_dismiss), serviceIntent(ACTION_DISMISS, 2))
            .build()
    }

    /** Quiet, unlike the ringing one: the sunrise is meant to wake gently, with a way to say "I'm up". */
    private fun sunriseNotification(alarm: Alarm?, ringMillis: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SUNRISE_CHANNEL_ID, getString(R.string.sunrise_channel), NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val time = alarm?.let { alarmTimeText(this, it) }.orEmpty()
        return NotificationCompat.Builder(this, SUNRISE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.sunrise_title))
            .setContentText(getString(R.string.sunrise_text, time))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setWhen(ringMillis)
            .addAction(0, getString(R.string.sunrise_im_up), serviceIntent(ACTION_AWAKE, 3))
            .build()
    }

    private fun serviceIntent(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, AlarmService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        stopSound()
        wakeLock?.takeIf { it.isHeld }?.release()
        _ringing.value = null
        _sunrise.value = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmService"
        private const val CHANNEL_ID = "alarms"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_RING = "dev.smartdisplay.app.alarm.RING"
        const val ACTION_SNOOZE = "dev.smartdisplay.app.alarm.SNOOZE"
        const val ACTION_DISMISS = "dev.smartdisplay.app.alarm.DISMISS"
        private const val ACTION_SUNRISE = "dev.smartdisplay.app.alarm.SUNRISE"
        const val ACTION_AWAKE = "dev.smartdisplay.app.alarm.AWAKE"
        private const val SUNRISE_CHANNEL_ID = "sunrise"
        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_RING_AT = "ring_at"
        /** An alarm nobody answers stops by itself after this long. */
        private const val RING_LIMIT_MS = 10 * 60_000L
        private const val CONNECT_TIMEOUT_MS = 60_000L
        private const val STOP_TIMEOUT_MS = 15_000L

        /** How often the sunrise brightens the lights (each step glides, on lights that can). */
        private const val STEP_MS = 30_000L

        /** The sunrise hands over to the ring this close to it, and gives up if no ring comes within the grace. */
        private const val RING_MARGIN_MS = 2_000L
        private const val RING_GRACE_MS = 60_000L

        private val _ringing = MutableStateFlow<Alarm?>(null)

        /** The alarm ringing now, for the alarm screen. */
        val ringing: StateFlow<Alarm?> = _ringing.asStateFlow()

        private val _sunrise = MutableStateFlow<Sunrise?>(null)

        /** The sunrise in progress before an alarm, for the ambient screen's glow and its "I'm up". */
        val sunrise: StateFlow<Sunrise?> = _sunrise.asStateFlow()

        fun ring(context: Context, alarmId: Int) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AlarmService::class.java).setAction(ACTION_RING).putExtra(EXTRA_ALARM_ID, alarmId),
            )
        }

        fun sunrise(context: Context, alarmId: Int, ringMillis: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AlarmService::class.java).setAction(ACTION_SUNRISE)
                    .putExtra(EXTRA_ALARM_ID, alarmId)
                    .putExtra(EXTRA_RING_AT, ringMillis),
            )
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, AlarmService::class.java).setAction(action))
        }
    }
}
