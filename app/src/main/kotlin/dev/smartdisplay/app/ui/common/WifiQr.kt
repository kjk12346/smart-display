package dev.smartdisplay.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.smartdisplay.app.R
import dev.smartdisplay.app.kiosk.WifiConfig
import dev.smartdisplay.app.kiosk.WifiSecurity
import dev.smartdisplay.app.kiosk.wifiQrText

/** [text] as a QR code: black on white whatever the theme (cameras want it that way), with a quiet border. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    Canvas(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White),
    ) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                // A hair bigger than the cell, so no seams show between neighbours.
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** The network's name and (unless it's open) password, for typing in by hand. */
@Composable
fun WifiDetails(wifi: WifiConfig, alignment: Alignment.Horizontal = Alignment.CenterHorizontally) {
    val textAlign = if (alignment == Alignment.Start) TextAlign.Start else TextAlign.Center
    Column(horizontalAlignment = alignment, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.wifi_scan),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = textAlign,
        )
        Text(
            stringResource(R.string.wifi_network, wifi.ssid.orEmpty()),
            style = MaterialTheme.typography.titleMedium,
            textAlign = textAlign,
        )
        if (wifi.security != WifiSecurity.Open && wifi.password.isNotEmpty()) {
            Text(
                stringResource(R.string.wifi_password, wifi.password),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                textAlign = textAlign,
            )
        }
    }
}

/** A dialog with the Wi-Fi's QR code, big enough to scan from across a desk, and its details. */
@Composable
fun WifiDialog(wifi: WifiConfig, onDismiss: () -> Unit) {
    val text = wifiQrText(wifi) ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wifi_join)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
                QrCode(text, Modifier.size(WIFI_QR_BIG))
                Row(Modifier.padding(top = 16.dp)) { WifiDetails(wifi) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
    )
}

private val WIFI_QR_BIG: Dp = 260.dp
