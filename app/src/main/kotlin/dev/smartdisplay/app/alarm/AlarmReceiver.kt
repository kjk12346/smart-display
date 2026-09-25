package dev.smartdisplay.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.smartdisplay.app.SmartDisplayApp

/**
 * Android's alarm clock calls this when an alarm is due; it also reschedules after a reboot, a clock or time zone
 * change, or an app update, since Android forgets scheduled alarms then.
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
            else -> alarms.reschedule()
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.smartdisplay.app.alarm.FIRE"
        const val EXTRA_ALARM_ID = "alarm_id"
    }
}
