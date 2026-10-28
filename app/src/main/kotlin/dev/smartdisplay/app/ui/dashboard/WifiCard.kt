package dev.smartdisplay.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.kiosk.WifiConfig
import dev.smartdisplay.app.kiosk.wifiQrText
import dev.smartdisplay.app.ui.common.QrCode
import dev.smartdisplay.app.ui.common.WifiDetails

/**
 * The Wi-Fi's QR code, to scan and join, with its name and password. As big as the card allows: the details beside it
 * in a wide card, under it otherwise. [bounded] is a card of fixed size on the grid.
 */
@Composable
internal fun WifiCard(wifi: WifiConfig, modifier: Modifier, bounded: Boolean) {
    TitledCard(stringResource(R.string.card_wifi), modifier, scroll = false) {
        val text = wifiQrText(wifi)
        if (text == null) {
            Text(
                stringResource(R.string.card_wifi_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@TitledCard
        }
        BoxWithConstraints(if (bounded) Modifier.weight(1f) else Modifier) {
            val height = if (bounded) maxHeight else STACKED_QR + DETAILS_HEIGHT
            val width = maxWidth
            if (width >= height * 1.6f) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    QrCode(text, Modifier.size(height))
                    WifiDetails(wifi, alignment = Alignment.Start)
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    QrCode(text, Modifier.size(minOf(width, height - DETAILS_HEIGHT).coerceAtLeast(MIN_QR)))
                    WifiDetails(wifi)
                }
            }
        }
    }
}

/** Room for the name and password under the code. */
private val DETAILS_HEIGHT = 76.dp
private val MIN_QR = 96.dp

/** The code's size stacked on a phone, where the card isn't given a height. */
private val STACKED_QR = 200.dp
