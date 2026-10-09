package dev.smartdisplay.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import dev.smartdisplay.app.MainActivity
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * What happens when an alarm rings on this display, chosen by the owner in Settings. Any combination; the alarm
 * screen always shows.
 */
data class AlarmActions(
    /** Ring on the display itself. */
    val sound: Boolean = true,
    /** Lights to turn on (Home Assistant entity IDs). */
    val lights: Set<String> = emptySet(),
    /** A media player to play [soundId] on. */
    val speaker: String? = null,
    /** A `media-source://` sound from Home Assistant's media browser. */
    val soundId: String? = null,
    val soundTitle: String? = null,
    /** Minutes before the alarm that the lights and screen start to brighten, like a sunrise; 0 is off. */
    val sunriseMinutes: Int = DEFAULT_SUNRISE_MINUTES,
) {
    val usesHomeAssistant: Boolean get() = lights.isNotEmpty() || (speaker != null && soundId != null)
}

/**
 * The display's alarms: kept on the device, and scheduled with Android's alarm clock so they ring on time even
 * with the screen off or the app closed. Only the next ring is scheduled; each ring schedules the one after.
 */
class Alarms(private val context: Context) {
    private val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    private val _alarms = MutableStateFlow(loadAlarms())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    private val _snooze = MutableStateFlow(loadSnooze())
    val snooze: StateFlow<Snooze?> = _snooze.asStateFlow()

    private val _actions = MutableStateFlow(loadActions())
    val actions: StateFlow<AlarmActions> = _actions.asStateFlow()

    private val _skip = MutableStateFlow(loadSkip())

    /** A ring that won't happen, because someone was up before it. */
    val skip: StateFlow<Skip?> = _skip.asStateFlow()

    private val _windDown = MutableStateFlow(loadWindDown())

    /** The evening wind-down's settings. */
    val windDown: StateFlow<WindDownSettings> = _windDown.asStateFlow()

    /** Nights with a bedtime up to this were already wound down, or the wind-down was stopped. */
    private var windDownDoneUntil = prefs.getLong(KEY_WIND_DOWN_DONE, 0)

    fun add(hour: Int, minute: Int, days: Set<DayOfWeek>, label: String): Alarm {
        val id = prefs.getInt(KEY_NEXT_ID, 1)
        val alarm = Alarm(id, hour, minute, days, label.trim())
        prefs.edit { putInt(KEY_NEXT_ID, id + 1) }
        saveAlarms(_alarms.value + alarm)
        return alarm
    }

    fun update(alarm: Alarm) {
        saveAlarms(_alarms.value.map { if (it.id == alarm.id) alarm else it })
    }

    fun delete(id: Int) {
        saveAlarms(_alarms.value.filterNot { it.id == id })
    }

    /** For between guests: removes every alarm and any snooze. */
    fun clearAll() {
        setSnooze(null, reschedule = false)
        setSkip(null, reschedule = false)
        saveAlarms(emptyList())
    }

    fun setActions(actions: AlarmActions) {
        prefs.edit {
            putBoolean(KEY_SOUND, actions.sound)
            putStringSet(KEY_LIGHTS, actions.lights)
            putString(KEY_SPEAKER, actions.speaker)
            putString(KEY_SOUND_ID, actions.soundId)
            putString(KEY_SOUND_TITLE, actions.soundTitle)
            putInt(KEY_SUNRISE, actions.sunriseMinutes)
        }
        val sunriseChanged = actions.sunriseMinutes != _actions.value.sunriseMinutes
        _actions.value = actions
        if (sunriseChanged) reschedule()
    }

    fun setWindDown(settings: WindDownSettings) {
        prefs.edit {
            putBoolean(KEY_WD_ENABLED, settings.enabled)
            putStringSet(KEY_WD_LIGHTS, settings.lights)
            putInt(KEY_WD_MINUTES, settings.minutes)
            putInt(KEY_WD_SLEEP, settings.sleepMinutes)
            putInt(KEY_WD_BEDTIME, settings.fallbackBedtime?.toSecondOfDay()?.div(60) ?: -1)
            putStringSet(KEY_WD_HOUSE_OFF, settings.houseOff)
        }
        _windDown.value = settings
        reschedule()
    }

    /** The next wind-down that should start by itself (or one under way), if any. */
    fun nextWindDown(now: ZonedDateTime = ZonedDateTime.now()): WindDown? =
        nextWindDown(_alarms.value, _skip.value, _windDown.value, now, windDownDoneUntil)

    /**
     * A wind-down was started from its button, or stopped, or is over: tonight's doesn't start (again) by itself.
     * Covers [bedtimeMillis], and tonight's own bedtime if that's within the next 12 hours.
     */
    fun windDownDone(bedtimeMillis: Long, now: ZonedDateTime = ZonedDateTime.now()) {
        val tonight = nextWindDown(now)?.bedtimeMillis
            ?.takeIf { it - now.toInstant().toEpochMilli() < 12 * 60 * 60_000L }
        windDownDoneUntil = maxOf(windDownDoneUntil, bedtimeMillis, tonight ?: 0)
        prefs.edit { putLong(KEY_WIND_DOWN_DONE, windDownDoneUntil) }
        reschedule()
    }

    /** Called when [alarm] starts ringing: a one-time alarm turns itself off, and the next ring is scheduled. */
    fun onRang(alarm: Alarm) {
        _snooze.value?.takeIf { it.alarmId == alarm.id }?.let { setSnooze(null, reschedule = false) }
        if (!alarm.repeats) {
            update(alarm.copy(enabled = false))
        } else {
            reschedule()
        }
    }

    fun snooze(alarmId: Int, minutes: Int = SNOOZE_MINUTES) {
        setSnooze(Snooze(alarmId, System.currentTimeMillis() + minutes * 60_000L))
    }

    fun clearSnooze() = setSnooze(null)

    /**
     * Someone was up before [alarmId] rang at [ringMillis] ("I'm up" during its sunrise): a one-time alarm turns off,
     * and a repeating one skips just this ring.
     */
    fun imUp(alarmId: Int, ringMillis: Long) {
        val alarm = alarm(alarmId) ?: return
        if (alarm.repeats) setSkip(Skip(alarmId, ringMillis)) else update(alarm.copy(enabled = false))
    }

    fun next(now: ZonedDateTime = ZonedDateTime.now()): NextRing? =
        nextRing(_alarms.value, _snooze.value, now, _skip.value)

    /** Schedules the next ring with Android (or cancels, if there's none). Safe to call any time. */
    fun reschedule() {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(fireIntent())
        manager.cancel(sunriseIntent())
        manager.cancel(windDownIntent())
        scheduleWindDown(manager)
        val next = next() ?: return
        val at = next.at.toInstant().toEpochMilli()
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fireIntent(next))
        } catch (e: SecurityException) {
            // No exact-alarm permission (it's normally granted to alarm apps): ring close to the time instead.
            Log.w(TAG, "Exact alarms not allowed; scheduling inexactly", e)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fireIntent(next))
        }
        // The sunrise starts that long before (straight away if the alarm is sooner than that). Not for a snooze:
        // the lights are already up.
        val sunrise = _actions.value.sunriseMinutes
        if (sunrise > 0 && !next.snoozed) {
            val start = maxOf(at - sunrise * 60_000L, System.currentTimeMillis())
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, start, sunriseIntent(next))
            } catch (e: SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, start, sunriseIntent(next))
            }
        }
    }

    fun alarm(id: Int): Alarm? = _alarms.value.firstOrNull { it.id == id }

    /** Starts the next wind-down at its time (straight away, if it should already be under way). */
    private fun scheduleWindDown(manager: AlarmManager) {
        val windDown = nextWindDown() ?: return
        val start = maxOf(windDown.startMillis, System.currentTimeMillis())
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, start, windDownIntent(windDown))
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, start, windDownIntent(windDown))
        }
    }

    private fun windDownIntent(windDown: WindDown? = null): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_WIND_DOWN)
        if (windDown != null) intent.putExtra(AlarmReceiver.EXTRA_BEDTIME, windDown.bedtimeMillis)
        return PendingIntent.getBroadcast(
            context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun loadWindDown() = WindDownSettings(
        enabled = prefs.getBoolean(KEY_WD_ENABLED, false),
        lights = prefs.getStringSet(KEY_WD_LIGHTS, null)?.toSet().orEmpty(),
        minutes = prefs.getInt(KEY_WD_MINUTES, DEFAULT_WIND_DOWN_MINUTES),
        sleepMinutes = prefs.getInt(KEY_WD_SLEEP, DEFAULT_SLEEP_MINUTES),
        fallbackBedtime = when (val minutes = prefs.getInt(KEY_WD_BEDTIME, DEFAULT_FALLBACK_BEDTIME.hour * 60)) {
            in 0 until 24 * 60 -> LocalTime.of(minutes / 60, minutes % 60)
            else -> null
        },
        houseOff = prefs.getStringSet(KEY_WD_HOUSE_OFF, null)?.toSet().orEmpty(),
    )

    private fun fireIntent(next: NextRing? = null): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE)
        if (next != null) intent.putExtra(AlarmReceiver.EXTRA_ALARM_ID, next.alarm.id)
        return PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun sunriseIntent(next: NextRing? = null): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_SUNRISE)
        if (next != null) {
            intent.putExtra(AlarmReceiver.EXTRA_ALARM_ID, next.alarm.id)
            intent.putExtra(AlarmReceiver.EXTRA_RING_AT, next.at.toInstant().toEpochMilli())
        }
        return PendingIntent.getBroadcast(
            context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun setSkip(skip: Skip?, reschedule: Boolean = true) {
        prefs.edit {
            if (skip == null) {
                remove(KEY_SKIP_ID)
                remove(KEY_SKIP_AT)
            } else {
                putInt(KEY_SKIP_ID, skip.alarmId)
                putLong(KEY_SKIP_AT, skip.atMillis)
            }
        }
        _skip.value = skip
        if (reschedule) reschedule()
    }

    private fun loadSkip(): Skip? {
        if (!prefs.contains(KEY_SKIP_ID)) return null
        return Skip(prefs.getInt(KEY_SKIP_ID, 0), prefs.getLong(KEY_SKIP_AT, 0))
    }

    private fun setSnooze(snooze: Snooze?, reschedule: Boolean = true) {
        prefs.edit {
            if (snooze == null) {
                remove(KEY_SNOOZE_ID)
                remove(KEY_SNOOZE_AT)
            } else {
                putInt(KEY_SNOOZE_ID, snooze.alarmId)
                putLong(KEY_SNOOZE_AT, snooze.atMillis)
            }
        }
        _snooze.value = snooze
        if (reschedule) reschedule()
    }

    private fun saveAlarms(alarms: List<Alarm>) {
        val sorted = alarms.sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))
        prefs.edit { putString(KEY_ALARMS, encode(sorted)) }
        _alarms.value = sorted
        reschedule()
    }

    private fun loadAlarms(): List<Alarm> = prefs.getString(KEY_ALARMS, null)?.let(::decode).orEmpty()

    private fun loadSnooze(): Snooze? {
        if (!prefs.contains(KEY_SNOOZE_ID)) return null
        return Snooze(prefs.getInt(KEY_SNOOZE_ID, 0), prefs.getLong(KEY_SNOOZE_AT, 0))
    }

    private fun loadActions() = AlarmActions(
        sound = prefs.getBoolean(KEY_SOUND, true),
        lights = prefs.getStringSet(KEY_LIGHTS, null)?.toSet().orEmpty(),
        speaker = prefs.getString(KEY_SPEAKER, null),
        soundId = prefs.getString(KEY_SOUND_ID, null),
        soundTitle = prefs.getString(KEY_SOUND_TITLE, null),
        sunriseMinutes = prefs.getInt(KEY_SUNRISE, DEFAULT_SUNRISE_MINUTES),
    )

    companion object {
        private const val TAG = "Alarms"
        const val SNOOZE_MINUTES = 10
        private const val KEY_ALARMS = "alarms"
        private const val KEY_NEXT_ID = "next_id"
        private const val KEY_SNOOZE_ID = "snooze_id"
        private const val KEY_SNOOZE_AT = "snooze_at"
        private const val KEY_SOUND = "action_sound"
        private const val KEY_LIGHTS = "action_lights"
        private const val KEY_SPEAKER = "action_speaker"
        private const val KEY_SOUND_ID = "action_sound_id"
        private const val KEY_SOUND_TITLE = "action_sound_title"
        private const val KEY_SUNRISE = "action_sunrise_minutes"
        private const val KEY_SKIP_ID = "skip_id"
        private const val KEY_SKIP_AT = "skip_at"
        private const val KEY_WD_ENABLED = "wind_down_enabled"
        private const val KEY_WD_LIGHTS = "wind_down_lights"
        private const val KEY_WD_MINUTES = "wind_down_minutes"
        private const val KEY_WD_SLEEP = "wind_down_sleep_minutes"
        private const val KEY_WD_BEDTIME = "wind_down_bedtime_minutes"
        private const val KEY_WD_HOUSE_OFF = "wind_down_house_off"
        private const val KEY_WIND_DOWN_DONE = "wind_down_done_until"

        internal fun encode(alarms: List<Alarm>): String = buildJsonArray {
            alarms.forEach { alarm ->
                add(buildJsonObject {
                    put("id", alarm.id)
                    put("hour", alarm.hour)
                    put("minute", alarm.minute)
                    put("days", buildJsonArray { alarm.days.sorted().forEach { add(JsonPrimitive(it.value)) } })
                    put("label", alarm.label)
                    put("enabled", alarm.enabled)
                })
            }
        }.toString()

        internal fun decode(text: String): List<Alarm> =
            (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray).orEmpty().mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                fun int(key: String) = (obj[key] as? JsonPrimitive)?.intOrNull
                Alarm(
                    id = int("id") ?: return@mapNotNull null,
                    hour = int("hour")?.takeIf { it in 0..23 } ?: return@mapNotNull null,
                    minute = int("minute")?.takeIf { it in 0..59 } ?: return@mapNotNull null,
                    days = (obj["days"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.intOrNull?.takeIf { day -> day in 1..7 } }
                        .map(DayOfWeek::of).toSet(),
                    label = (obj["label"] as? JsonPrimitive)?.content.orEmpty(),
                    enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                )
            }
    }
}
