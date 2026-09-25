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
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * [RING_LIMIT_MS] pass.
 */
class AlarmService : Service() {
    private val app get() = application as SmartDisplayApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringJobs = mutableListOf<Job>()

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
            else -> stop(stopSpeaker = false)
        }
        return START_NOT_STICKY
    }

    private fun startRinging(alarm: Alarm) {
        // A second alarm while one rings replaces it.
        stopSound()
        ringJobs.forEach { it.cancel() }
        ringJobs.clear()

        _ringing.value = alarm
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(alarm),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0,
        )
        acquireWakeLock()

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
        if (actions.lights.isNotEmpty()) {
            call("light", "turn_on", actions.lights)
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

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartDisplay:alarm")
            .apply { acquire(RING_LIMIT_MS + STOP_TIMEOUT_MS) }
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

    private fun serviceIntent(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, AlarmService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        stopSound()
        wakeLock?.takeIf { it.isHeld }?.release()
        _ringing.value = null
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
        private const val EXTRA_ALARM_ID = "alarm_id"
        /** An alarm nobody answers stops by itself after this long. */
        private const val RING_LIMIT_MS = 10 * 60_000L
        private const val CONNECT_TIMEOUT_MS = 60_000L
        private const val STOP_TIMEOUT_MS = 15_000L

        private val _ringing = MutableStateFlow<Alarm?>(null)

        /** The alarm ringing now, for the alarm screen. */
        val ringing: StateFlow<Alarm?> = _ringing.asStateFlow()

        fun ring(context: Context, alarmId: Int) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AlarmService::class.java).setAction(ACTION_RING).putExtra(EXTRA_ALARM_ID, alarmId),
            )
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, AlarmService::class.java).setAction(action))
        }
    }
}
