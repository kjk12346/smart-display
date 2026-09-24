package dev.smartdisplay.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.smartdisplay.app.R
import dev.smartdisplay.app.kiosk.ExitPin
import dev.smartdisplay.app.kiosk.PinCheck
import kotlinx.coroutines.launch

private const val MAX_PIN_LENGTH = 8

/** Asks for the exit PIN before opening settings. */
@Composable
fun EnterPinDialog(check: suspend (String) -> PinCheck, onCorrect: () -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    val submit: () -> Unit = submit@{
        if (checking || pin.length < 4) return@submit
        checking = true
        scope.launch {
            when (val result = check(pin)) {
                PinCheck.Correct -> onCorrect()
                PinCheck.Wrong -> {
                    error = resources.getString(R.string.pin_wrong)
                    pin = ""
                }
                is PinCheck.LockedOut -> {
                    val seconds = ((result.millis + 999) / 1000).toInt()
                    error = resources.getQuantityString(R.plurals.pin_locked_out, seconds, seconds)
                    pin = ""
                }
            }
            checking = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_enter_title)) },
        text = {
            PinField(
                value = pin,
                onValueChange = {
                    pin = it
                    error = null
                },
                label = stringResource(R.string.pin_label),
                error = error,
                onDone = submit,
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = pin.length >= 4 && !checking) {
                Text(stringResource(R.string.pin_open_settings))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Sets or changes the exit PIN: typed twice. */
@Composable
fun SetPinDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var firstError by remember { mutableStateOf<String?>(null) }
    var secondError by remember { mutableStateOf<String?>(null) }
    val resources = LocalResources.current

    val save: () -> Unit = {
        when {
            !ExitPin.isValid(first) -> firstError = resources.getString(R.string.pin_format)
            first != second -> secondError = resources.getString(R.string.pin_mismatch)
            else -> onSave(first)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_set_title)) },
        text = {
            Column {
                PinField(
                    value = first,
                    onValueChange = {
                        first = it
                        firstError = null
                    },
                    label = stringResource(R.string.pin_new_label),
                    error = firstError,
                    imeAction = ImeAction.Next,
                )
                PinField(
                    value = second,
                    onValueChange = {
                        second = it
                        secondError = null
                    },
                    label = stringResource(R.string.pin_again_label),
                    error = secondError,
                    onDone = save,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = save) { Text(stringResource(R.string.pin_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(MAX_PIN_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier,
    )
}
