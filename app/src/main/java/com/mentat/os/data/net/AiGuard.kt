package com.mentat.os.data.net

import com.mentat.os.data.db.AiUsageDao
import com.mentat.os.data.db.AiUsageEntity
import com.mentat.os.data.prefs.SettingsStore
import com.mentat.os.domain.model.AiError
import com.mentat.os.domain.model.AiResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class GuardState(
    val sessionCalls: Int = 0,
    val consecutiveFailures: Int = 0,
    val pausedUntilMillis: Long = 0,
    val inFlight: Boolean = false,
    val lastError: AiError? = null,
)

/**
 * Rule 6 hard backstops: max 1 in-flight call, min 3 s between calls, daily cap from DataStore,
 * circuit breaker (3 consecutive failures pause AI for 5 minutes).
 */
@Singleton
class AiGuard @Inject constructor(
    private val settings: SettingsStore,
    private val usageDao: AiUsageDao,
    private val clock: Clock,
) {
    companion object {
        const val MIN_GAP_MS = 3_000L
        const val BREAKER_THRESHOLD = 3
        const val BREAKER_PAUSE_MS = 5 * 60_000L
    }

    private val mutex = Mutex()
    private var lastCallEnd = 0L
    private val _state = MutableStateFlow(GuardState())
    val state: StateFlow<GuardState> = _state.asStateFlow()

    suspend fun <T> guarded(block: suspend () -> AiResult<T>): AiResult<T> {
        if (!mutex.tryLock()) return AiResult.Failure(AiError.BUSY)
        try {
            val now = clock.millis()
            if (now < _state.value.pausedUntilMillis) return AiResult.Failure(AiError.PAUSED)
            val today = LocalDate.now(clock)
            val used = settings.aiCallsToday(today).first()
            if (used >= settings.current().dailyAiCap) return AiResult.Failure(AiError.BUDGET_EXHAUSTED)
            val wait = lastCallEnd + MIN_GAP_MS - now
            if (wait > 0) delay(wait)

            settings.incrementAiCalls(today)
            val row = usageDao.get(today)
            usageDao.upsert(AiUsageEntity(today, (row?.calls ?: 0) + 1))
            _state.update { it.copy(sessionCalls = it.sessionCalls + 1, inFlight = true) }

            val result = block()
            lastCallEnd = clock.millis()
            _state.update { s ->
                when (result) {
                    is AiResult.Success -> s.copy(consecutiveFailures = 0, inFlight = false, lastError = null)
                    is AiResult.Failure -> {
                        val n = s.consecutiveFailures + 1
                        if (n >= BREAKER_THRESHOLD) s.copy(consecutiveFailures = 0, pausedUntilMillis = lastCallEnd + BREAKER_PAUSE_MS, inFlight = false, lastError = result.reason)
                        else s.copy(consecutiveFailures = n, inFlight = false, lastError = result.reason)
                    }
                }
            }
            return result
        } finally {
            _state.update { it.copy(inFlight = false) }
            mutex.unlock()
        }
    }

    /** Debug-only helper for the Diagnostics screen to verify the breaker. */
    fun recordSyntheticFailure() {
        _state.update { s ->
            val n = s.consecutiveFailures + 1
            if (n >= BREAKER_THRESHOLD) s.copy(consecutiveFailures = 0, pausedUntilMillis = clock.millis() + BREAKER_PAUSE_MS, lastError = AiError.UNKNOWN)
            else s.copy(consecutiveFailures = n, lastError = AiError.UNKNOWN)
        }
    }

    fun resetBreaker() = _state.update { it.copy(consecutiveFailures = 0, pausedUntilMillis = 0) }
}
