package dev.smartdisplay.app.ui.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.smartdisplay.app.R
import dev.smartdisplay.app.discovery.DiscoveredServer
import dev.smartdisplay.app.server.ServerProblem
import dev.smartdisplay.app.ui.common.Panel
import dev.smartdisplay.app.ui.theme.SmartDisplayTheme

/** First-run screen: pick a Home Assistant server found on the network, or type its address. */
@Composable
fun SetupScreen(viewModel: SetupViewModel = viewModel()) {
    LifecycleStartEffect(viewModel) {
        viewModel.startDiscovery()
        onStopOrDispose { viewModel.stopDiscovery() }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    SetupContent(
        state = state,
        onServerClick = viewModel::connectTo,
        onAddressChange = viewModel::onAddressChange,
        onConnectAddress = viewModel::connectToAddress,
    )
}

@Composable
private fun SetupContent(
    state: SetupUiState,
    onServerClick: (DiscoveredServer) -> Unit,
    onAddressChange: (String) -> Unit,
    onConnectAddress: () -> Unit,
) {
    Panel(eyebrow = stringResource(R.string.setup_eyebrow), title = stringResource(R.string.setup_title)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            state.servers.forEach { server ->
                val target = ConnectTarget.Discovered(server.serviceName)
                ServerCard(
                    server = server,
                    connecting = state.connecting == target,
                    enabled = state.connecting == null,
                    problem = state.problem.takeIf { state.problemTarget == target },
                    onClick = { onServerClick(server) },
                )
            }
            SearchStatus(state)
        }

        Text(
            text = stringResource(R.string.setup_type_address),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 40.dp),
        )
        val typedProblem = state.problem.takeIf { state.problemTarget == ConnectTarget.Typed }
        OutlinedTextField(
            value = state.address,
            onValueChange = onAddressChange,
            placeholder = { Text(stringResource(R.string.setup_address_hint)) },
            singleLine = true,
            isError = typedProblem != null,
            supportingText = typedProblem?.let { { Text(stringResource(it.message)) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { onConnectAddress() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        Button(
            onClick = onConnectAddress,
            enabled = state.address.isNotBlank() && state.connecting == null,
            modifier = Modifier.padding(top = 12.dp),
        ) {
            if (state.connecting == ConnectTarget.Typed) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Text(stringResource(R.string.setup_connect))
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: DiscoveredServer,
    connecting: Boolean,
    enabled: Boolean,
    problem: ServerProblem?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                val details = listOfNotNull(server.urls.first(), server.version?.let { "v$it" })
                Text(
                    text = details.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (problem != null) {
                    Text(
                        text = stringResource(problem.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (connecting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = stringResource(R.string.setup_connect),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchStatus(state: SetupUiState) {
    val message = when {
        state.discoveryFailed -> R.string.setup_search_failed
        state.servers.isNotEmpty() -> null
        state.searchedAWhile -> R.string.setup_search_nothing
        else -> R.string.setup_searching
    }
    if (message == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!state.discoveryFailed) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@get:StringRes
private val ServerProblem.message: Int
    get() = when (this) {
        ServerProblem.InvalidAddress -> R.string.problem_invalid_address
        ServerProblem.NotFound -> R.string.problem_not_found
        ServerProblem.NoAnswer -> R.string.problem_no_answer
        ServerProblem.Certificate -> R.string.problem_certificate
        ServerProblem.NotHomeAssistant -> R.string.problem_not_home_assistant
        ServerProblem.NotOnboarded -> R.string.problem_not_onboarded
    }

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun SetupPreview() {
    SmartDisplayTheme {
        SetupContent(
            state = SetupUiState(
                servers = listOf(
                    DiscoveredServer(
                        serviceName = "Home",
                        name = "Home",
                        version = "2026.9.1",
                        uuid = null,
                        urls = listOf("http://192.168.1.20:8123"),
                    ),
                ),
                address = "ha.local",
                problem = ServerProblem.NotFound,
                problemTarget = ConnectTarget.Typed,
            ),
            onServerClick = {},
            onAddressChange = {},
            onConnectAddress = {},
        )
    }
}
