package dev.smartdisplay.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.smartdisplay.app.SmartDisplayApp

/**
 * Android's alarm clock calls this when an alarm is due, and when its sunrise should start; it also reschedules after
 * a reboot, a clock or time zone change, or an app update, since Android forgets scheduled alarms then.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarms = (context.applicationContext as SmartDisplayApp).alarms
        when (intent.action) {
            ACTION_FIRE -> {
                val alarm = alarms.alarm(intent.getIntExtra(EXTRA_ALARM_ID, -1))
                if (alarm == null) {
                    alarms.reschedule()
                    return
                }
                alarms.onRang(alarm)
                AlarmService.ring(context, alarm.id)
            }
            ACTION_SUNRISE -> {
                val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
                val ringAt = intent.getLongExtra(EXTRA_RING_AT, 0)
                // Only if it's still the next ring (the alarm may have changed since this was set).
                val next = alarms.next()
                if (next != null && next.alarm.id == alarmId && next.at.toInstant().toEpochMilli() == ringAt) {
                    AlarmService.sunrise(context, alarmId, ringAt)
                }
            }
            else -> alarms.reschedule()
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.smartdisplay.app.alarm.FIRE"
        const val ACTION_SUNRISE = "dev.smartdisplay.app.alarm.SUNRISE"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_RING_AT = "ring_at"
    }
}
