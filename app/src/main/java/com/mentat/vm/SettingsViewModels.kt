package com.mentat.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mentat.AppInitializer
import com.mentat.data.db.AiUsageDao
import com.mentat.data.db.ProfileEntity
import com.mentat.data.net.AiGuard
import com.mentat.data.net.GroqClient
import com.mentat.data.prefs.AppSettings
import com.mentat.data.prefs.SettingsStore
import com.mentat.data.repo.ClaimedSkill
import com.mentat.data.repo.PlanRepository
import com.mentat.data.repo.ProfileRepository
import com.mentat.data.secret.SecretStore
import com.mentat.domain.model.AiResult
import com.mentat.domain.model.userMessage
import com.mentat.system.alarm.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

data class SettingsTransient(
    val message: String? = null,
    val error: String? = null,
    val models: List<String> = emptyList(),
    val fetching: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secrets: SecretStore,
    private val settings: SettingsStore,
    private val profile: ProfileRepository,
    private val groq: GroqClient,
    private val init: AppInitializer,
    private val alarms: AlarmScheduler,
    private val plans: PlanRepository,
    clock: Clock,
) : ViewModel() {
    val hasKey: StateFlow<Boolean> = secrets.hasGroqKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val app: StateFlow<AppSettings?> = settings.settings.map<AppSettings, AppSettings?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profileState: StateFlow<ProfileEntity?> = profile.profile.map<ProfileEntity, ProfileEntity?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val callsToday: StateFlow<Int> = settings.aiCallsToday(LocalDate.now(clock)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * The key being typed is kept only in ViewModel memory (survives rotation). It is deliberately
     * NOT put in SavedStateHandle, because saved state can be written to disk (Rule 5 beats Rule 3 here).
     */
    private val _keyInput = MutableStateFlow("")
    val keyInput: StateFlow<String> = _keyInput.asStateFlow()
    fun onKeyInput(t: String) { _keyInput.value = t.trim() }

    private val _t = MutableStateFlow(SettingsTransient())
    val transient: StateFlow<SettingsTransient> = _t.asStateFlow()

    fun saveKey() = viewModelScope.launch {
        val k = _keyInput.value
        if (k.length < 20) { _t.update { it.copy(error = "That doesn't look like a complete Groq API key.") }; return@launch }
        secrets.setGroqKey(k)
        _keyInput.value = ""
        _t.update { it.copy(message = "Key saved and encrypted on this phone.") }
    }

    fun clearKey() = viewModelScope.launch { secrets.clearGroqKey(); _t.update { it.copy(message = "Key removed.") } }

    fun setModel(m: String) = viewModelScope.launch { if (m.isNotBlank()) settings.setModel(m) }

    /** Explicit user action only. */
    fun fetchModels() = viewModelScope.launch {
        _t.update { it.copy(fetching = true) }
        when (val r = groq.listModels()) {
            is AiResult.Success -> _t.update { it.copy(fetching = false, models = r.value) }
            is AiResult.Failure -> _t.update { it.copy(fetching = false, error = r.reason.userMessage()) }
        }
    }

    fun setCap(c: Int) = viewModelScope.launch { settings.setDailyCap(c) }

    fun setTime(which: String, t: LocalTime) = viewModelScope.launch {
        when (which) {
            "morning" -> settings.setMorning(t)
            "nudge" -> settings.setNudge(t)
            "recall" -> settings.setRecall(t)
            "weekly" -> settings.setWeekly(t)
        }
        init.reschedule()
    }

    fun setNotifications(on: Boolean) = viewModelScope.launch { settings.setNotifications(on); init.reschedule() }
    fun setWindDown(on: Boolean) = viewModelScope.launch { settings.setWindDown(on); init.reschedule() }
    fun setExact(on: Boolean) = viewModelScope.launch { settings.setExactAlarms(on); init.reschedule() }
    fun canUseExact(): Boolean = alarms.canUseExact()

    fun updateProfile(f: (ProfileEntity) -> ProfileEntity) = viewModelScope.launch {
        profile.update(f)
        plans.markDirty()
    }

    fun onPermissionResult() = init.reschedule()
    fun consume() = _t.update { it.copy(message = null, error = null) }
}

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val guard: AiGuard,
    settings: SettingsStore,
    usage: AiUsageDao,
    clock: Clock,
) : ViewModel() {
    val guardState = guard.state
    val callsToday: StateFlow<Int> = settings.aiCallsToday(LocalDate.now(clock)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val history = usage.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun simulateFailure() = guard.recordSyntheticFailure()
    fun resetBreaker() = guard.resetBreaker()
}

/** First launch: key (optional) -> plain onboarding form (A1 replaced by a form, Section 16 scope control) -> first skill. */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val secrets: SecretStore,
    private val profile: ProfileRepository,
    private val init: AppInitializer,
) : ViewModel() {
    val step: StateFlow<Int> = handle.getStateFlow("step", 0)
    fun go(s: Int) { handle["step"] = s }

    val name = handle.getStateFlow("name", "")
    val education = handle.getStateFlow("education", "")
    val interests = handle.getStateFlow("interests", "")
    val weeklyHours = handle.getStateFlow("hours", "10")
    val sleepStart = handle.getStateFlow("sleepStart", "23:00")
    val sleepEnd = handle.getStateFlow("sleepEnd", "07:00")
    val session = handle.getStateFlow("session", "30")
    val budget = handle.getStateFlow("budget", "500")
    val claimed = handle.getStateFlow("claimed", "")

    fun set(key: String, v: String) { handle[key] = v.take(500) }

    private val _keyInput = MutableStateFlow("")
    val keyInput: StateFlow<String> = _keyInput.asStateFlow()
    fun onKeyInput(t: String) { _keyInput.value = t.trim() }
    val hasKey = secrets.hasGroqKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun saveKeyAndContinue() = viewModelScope.launch {
        val k = _keyInput.value
        if (k.isNotEmpty()) {
            if (k.length < 20) { _error.value = "That doesn't look like a complete Groq API key."; return@launch }
            secrets.setGroqKey(k)
            _keyInput.value = ""
        }
        _error.value = null
        go(1)
    }

    fun validTime(s: String) = runCatching { LocalTime.parse(s) }.isSuccess

    fun saveProfile() = viewModelScope.launch {
        if (!validTime(sleepStart.value) || !validTime(sleepEnd.value)) { _error.value = "Sleep times must look like 23:00 and 07:00."; return@launch }
        val claims = claimed.value.lines().mapNotNull { l ->
            val parts = l.split(':', '-', ',').map { it.trim() }.filter { it.isNotEmpty() }
            val n = parts.firstOrNull() ?: return@mapNotNull null
            ClaimedSkill(n, parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 10) ?: 1)
        }
        profile.update {
            it.copy(
                name = name.value.trim(), education = education.value.trim(), interests = interests.value.trim(),
                weeklyFreeHours = weeklyHours.value.toDoubleOrNull() ?: 10.0, sleepStart = sleepStart.value, sleepEnd = sleepEnd.value,
                sessionMinutes = session.value.toIntOrNull()?.coerceIn(10, 180) ?: 30,
                monthlyBudgetInr = budget.value.toIntOrNull()?.coerceAtLeast(0) ?: 500,
                claimedSkillsJson = profile.encodeClaimed(claims),
            )
        }
        _error.value = null
        go(2)
    }

    fun finish() = viewModelScope.launch {
        profile.update { it.copy(onboarded = true) }
        init.reschedule()
    }
}
