package dev.smartdisplay.app.ui.setup

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.smartdisplay.app.SmartDisplayApp
import dev.smartdisplay.app.discovery.DiscoveredServer
import dev.smartdisplay.app.discovery.HomeAssistantDiscovery
import dev.smartdisplay.app.server.CheckResult
import dev.smartdisplay.app.server.SavedServer
import dev.smartdisplay.app.server.ServerCheck
import dev.smartdisplay.app.server.ServerProblem
import dev.smartdisplay.app.server.addressCandidates
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a connection attempt is for: a found server (by service name), or the typed address. */
sealed interface ConnectTarget {
    data class Discovered(val serviceName: String) : ConnectTarget
    data object Typed : ConnectTarget
}

data class SetupUiState(
    val servers: List<DiscoveredServer> = emptyList(),
    /** True once the search has run a while, so an empty list means "nothing found" rather than "still looking". */
    val searchedAWhile: Boolean = false,
    val discoveryFailed: Boolean = false,
    val address: String = "",
    val connecting: ConnectTarget? = null,
    val problem: ServerProblem? = null,
    val problemTarget: ConnectTarget? = null,
)

class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SmartDisplayApp
    private val discovery = HomeAssistantDiscovery(application)
    private val serverCheck = ServerCheck(app.http)

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private var discoveryJob: Job? = null
    private var connectJob: Job? = null

    /** Searches until [stopDiscovery]; the screen runs it only while visible. */
    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        _state.update { it.copy(discoveryFailed = false) }
        discoveryJob = viewModelScope.launch {
            launch {
                delay(QUIET_AFTER_MS)
                _state.update { it.copy(searchedAWhile = true) }
            }
            discovery.servers()
                .catch { e ->
                    Log.w(TAG, "Discovery failed", e)
                    _state.update { it.copy(discoveryFailed = true) }
                }
                .collect { servers -> _state.update { it.copy(servers = servers) } }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    fun onAddressChange(address: String) {
        _state.update {
            it.copy(
                address = address,
                problem = if (it.problemTarget == ConnectTarget.Typed) null else it.problem,
            )
        }
    }

    fun connectTo(server: DiscoveredServer) {
        connect(ConnectTarget.Discovered(server.serviceName), server.urls, server.name, server.uuid)
    }

    fun connectToAddress() {
        val urls = addressCandidates(_state.value.address)
        if (urls.isEmpty()) {
            _state.update { it.copy(problem = ServerProblem.InvalidAddress, problemTarget = ConnectTarget.Typed) }
            return
        }
        connect(ConnectTarget.Typed, urls, name = null, uuid = null)
    }

    private fun connect(target: ConnectTarget, urls: List<String>, name: String?, uuid: String?) {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            _state.update { it.copy(connecting = target, problem = null, problemTarget = null) }
            var firstProblem: ServerProblem? = null
            for (url in urls) {
                when (val result = serverCheck.check(url)) {
                    is CheckResult.Ok -> {
                        _state.update { it.copy(connecting = null) }
                        app.serverStore.save(SavedServer(result.url, name, uuid))
                        return@launch
                    }
                    is CheckResult.Failed -> if (firstProblem == null) firstProblem = result.problem
                }
            }
            _state.update {
                it.copy(
                    connecting = null,
                    problem = firstProblem ?: ServerProblem.NoAnswer,
                    problemTarget = target,
                )
            }
        }
    }

    private companion object {
        const val TAG = "SetupViewModel"
        const val QUIET_AFTER_MS = 8_000L
    }
}
