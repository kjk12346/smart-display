package dev.smartdisplay.app.ui.setup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Android 17 (API 37) makes talking to devices on the local network a runtime permission. It's enforced for apps
 * that declare it even before they target 37 (seen on a Pixel 6 running the Android 17 beta, where NSD discovery
 * failed with FAILURE_PERMISSION_DENIED). Below API 37 the permission doesn't exist and access is always allowed.
 */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private const val API_LOCAL_NETWORK_PERMISSION = 37

class LocalNetworkAccess(
    val granted: Boolean,
    /** True once Android will no longer show its dialog, so [request] opens the app's settings instead. */
    val mustUseSettings: Boolean,
    val request: () -> Unit,
)

@Composable
fun rememberLocalNetworkAccess(): LocalNetworkAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var granted by remember { mutableStateOf(hasLocalNetworkAccess(context)) }
    var denials by rememberSaveable { mutableStateOf(0) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        if (!result) denials++
    }
    // Picks up a change made in Settings while the app was in the background.
    LifecycleResumeEffect(Unit) {
        granted = hasLocalNetworkAccess(context)
        onPauseOrDispose {}
    }

    // After a denial Android shows a rationale-eligible dialog once more; after that it stops showing it at all.
    val mustUseSettings = denials > 0 && activity?.shouldShowRequestPermissionRationale(LOCAL_NETWORK) == false
    return LocalNetworkAccess(
        granted = granted,
        mustUseSettings = mustUseSettings,
        request = {
            if (mustUseSettings) openAppSettings(context) else launcher.launch(LOCAL_NETWORK)
        },
    )
}

private fun hasLocalNetworkAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT < API_LOCAL_NETWORK_PERMISSION ||
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
