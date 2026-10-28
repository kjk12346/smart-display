package dev.smartdisplay.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.kiosk.WifiConfig
import dev.smartdisplay.app.kiosk.WifiSecurity
import dev.smartdisplay.app.kiosk.wifiQrText
import dev.smartdisplay.app.ui.common.PanelBody
import dev.smartdisplay.app.ui.common.QrCode

/**
 * The Wi-Fi guests join: its name, password and security, saved when Save is pressed, with the QR code it makes to
 * try with a phone. It shows on the guest controls (a Wi-Fi button) and on the dashboard's Wi-Fi card.
 */
@Composable
internal fun WifiSettings(wifi: WifiConfig, onSave: (WifiConfig) -> Unit) {
    var ssid by rememberSaveable(wifi) { mutableStateOf(wifi.ssid.orEmpty()) }
    var password by rememberSaveable(wifi) { mutableStateOf(wifi.password) }
    var security by rememberSaveable(wifi) { mutableStateOf(wifi.security) }
    var hidden by rememberSaveable(wifi) { mutableStateOf(wifi.hidden) }
    val typed = WifiConfig(ssid.trim().ifEmpty { null }, password, security, hidden)

    PanelBody(stringResource(R.string.settings_wifi_body), topPadding = 4.dp)
    OutlinedTextField(
        value = ssid,
        onValueChange = { ssid = it },
        label = { Text(stringResource(R.string.settings_wifi_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
        WifiSecurity.entries.forEach { choice ->
            FilterChip(
                selected = security == choice,
                onClick = { security = choice },
                label = {
                    Text(
                        stringResource(
                            when (choice) {
                                WifiSecurity.Wpa -> R.string.settings_wifi_wpa
                                WifiSecurity.Wep -> R.string.settings_wifi_wep
                                WifiSecurity.Open -> R.string.settings_wifi_open
                            },
                        ),
                    )
                },
            )
        }
    }
    if (security != WifiSecurity.Open) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.settings_wifi_password)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
    SwitchRow(
        title = stringResource(R.string.settings_wifi_hidden),
        description = stringResource(R.string.settings_wifi_hidden_body),
        checked = hidden,
        onCheckedChange = { hidden = it },
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
        Button(onClick = { onSave(typed) }, enabled = typed != wifi) {
            Text(stringResource(R.string.settings_wifi_save))
        }
        if (wifi.isSet) {
            TextButton(onClick = { onSave(WifiConfig()) }, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.settings_wifi_remove))
            }
        }
    }
    wifiQrText(typed)?.let { text ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            QrCode(text, Modifier.size(200.dp))
            Text(
                stringResource(R.string.settings_wifi_try),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
