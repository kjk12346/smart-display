package dev.smartdisplay.app.alarm

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.smartdisplay.app.R
import dev.smartdisplay.app.ui.theme.SmartDisplayTheme

/** The ringing alarm, full screen, over the lock screen: Snooze or Dismiss. Closes when the alarm stops. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            SmartDisplayTheme {
                val alarm by AlarmService.ringing.collectAsStateWithLifecycle()
                LaunchedEffect(alarm) { if (alarm == null) finish() }
                // Snooze or Dismiss only; Back mustn't silently leave an alarm ringing.
                BackHandler {}
                alarm?.let { current ->
                    RingingScreen(
                        alarm = current,
                        onSnooze = { AlarmService.send(this, AlarmService.ACTION_SNOOZE) },
                        onDismiss = { AlarmService.send(this, AlarmService.ACTION_DISMISS) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RingingScreen(alarm: Alarm, onSnooze: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .safeDrawingPadding()
                .padding(32.dp),
        ) {
            Text(
                text = alarm.label.ifBlank { stringResource(R.string.alarm_default_label) },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            val (digits, marker) = alarmTimeParts(context, alarm)
            Row(modifier = Modifier.padding(top = 8.dp, bottom = 48.dp)) {
                Text(
                    text = digits,
                    fontSize = 96.sp,
                    lineHeight = 104.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                if (marker != null) {
                    Text(
                        text = marker,
                        fontSize = 28.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .alignByBaseline()
                            .padding(start = 8.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                FilledTonalButton(
                    onClick = onSnooze,
                    modifier = Modifier.height(72.dp).widthIn(min = 180.dp),
                ) {
                    Text(stringResource(R.string.alarm_snooze_minutes, Alarms.SNOOZE_MINUTES), fontSize = 20.sp)
                }
                Button(onClick = onDismiss, modifier = Modifier.height(72.dp).widthIn(min = 180.dp)) {
                    Text(stringResource(R.string.alarm_dismiss), fontSize = 20.sp)
                }
            }
        }
    }
}
