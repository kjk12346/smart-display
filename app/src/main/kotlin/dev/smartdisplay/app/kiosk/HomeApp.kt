package dev.smartdisplay.app.kiosk

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import dev.smartdisplay.app.MainActivity

/**
 * "Use as Home app": the manifest's `HomeAlias` (MainActivity with the HOME category) is off until turned on here,
 * so the display only appears in Android's Home app choice when the owner wants it to.
 */
object HomeApp {
    private fun alias(context: Context) =
        ComponentName(context, MainActivity::class.java.name.substringBeforeLast('.') + ".HomeAlias")

    fun setEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    /** Opens Android's Home app choice. False if the device has none (Fire tablets don't offer one). */
    fun openHomeChooser(context: Context): Boolean = try {
        context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
