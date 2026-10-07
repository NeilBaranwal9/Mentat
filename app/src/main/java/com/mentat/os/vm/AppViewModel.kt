package com.mentat.os.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mentat.os.data.net.AiGuard
import com.mentat.os.data.repo.AiRepository
import com.mentat.os.data.repo.ProfileRepository
import com.mentat.os.data.secret.SecretStore
import com.mentat.os.system.connectivity.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Shared, read-only app state. Reads Room/DataStore only, never Groq (Rule 6). */
data class AiAvailability(val online: Boolean, val hasKey: Boolean, val busy: Boolean) {
    val canCall: Boolean get() = online && hasKey && !busy
}

@HiltViewModel
class AppViewModel @Inject constructor(
    profile: ProfileRepository,
    network: NetworkMonitor,
    secrets: SecretStore,
    guard: AiGuard,
    aiRepo: AiRepository,
) : ViewModel() {
    /** null while loading. */
    val onboarded: StateFlow<Boolean?> = profile.onboarded.map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val online: StateFlow<Boolean> = network.isOnline.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val ai: StateFlow<AiAvailability> = combine(network.isOnline, secrets.hasGroqKey, guard.state) { on, key, g -> AiAvailability(on, key, g.inFlight) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiAvailability(true, false, false))

    val pendingProposals = aiRepo.pendingProposals.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
