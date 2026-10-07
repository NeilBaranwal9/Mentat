package com.mentat.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mentat.di.SettingsPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val model: String = SettingsStore.DEFAULT_MODEL,
    val dailyAiCap: Int = SettingsStore.DEFAULT_DAILY_CAP,
    val morningTime: LocalTime = LocalTime.of(7, 30),
    val nudgeTime: LocalTime = LocalTime.of(20, 30),
    val recallTime: LocalTime = LocalTime.of(18, 0),
    val weeklyReviewTime: LocalTime = LocalTime.of(19, 0),
    val windDownEnabled: Boolean = false,
    val windDownMinutes: Int = 30,
    val exactAlarms: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val lastExport: LocalDate? = null,
)

/** Non-secret preferences (Rule 5: the model name lives here, editable in Settings). */
@Singleton
class SettingsStore @Inject constructor(@SettingsPrefs private val store: DataStore<Preferences>) {

    companion object {
        const val DEFAULT_MODEL = "openai/gpt-oss-120b"
        const val DEFAULT_DAILY_CAP = 40
        val SUGGESTED_MODELS = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant")

        private val MODEL = stringPreferencesKey("model")
        private val CAP = intPreferencesKey("daily_ai_cap")
        private val MORNING = stringPreferencesKey("morning_time")
        private val NUDGE = stringPreferencesKey("nudge_time")
        private val RECALL = stringPreferencesKey("recall_time")
        private val WEEKLY = stringPreferencesKey("weekly_time")
        private val WIND = booleanPreferencesKey("wind_down")
        private val WIND_MIN = intPreferencesKey("wind_down_minutes")
        private val EXACT = booleanPreferencesKey("exact_alarms")
        private val NOTIFS = booleanPreferencesKey("notifications")
        private val LAST_EXPORT = stringPreferencesKey("last_export")
        private val AI_DATE = stringPreferencesKey("ai_calls_date")
        private val AI_COUNT = intPreferencesKey("ai_calls_count")
        private val ALERT_DATE = stringPreferencesKey("alerts_date")
        private val ALERT_COUNT = intPreferencesKey("alerts_count")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            model = p[MODEL] ?: DEFAULT_MODEL,
            dailyAiCap = p[CAP] ?: DEFAULT_DAILY_CAP,
            morningTime = p[MORNING].toTime(LocalTime.of(7, 30)),
            nudgeTime = p[NUDGE].toTime(LocalTime.of(20, 30)),
            recallTime = p[RECALL].toTime(LocalTime.of(18, 0)),
            weeklyReviewTime = p[WEEKLY].toTime(LocalTime.of(19, 0)),
            windDownEnabled = p[WIND] ?: false,
            windDownMinutes = p[WIND_MIN] ?: 30,
            exactAlarms = p[EXACT] ?: false,
            notificationsEnabled = p[NOTIFS] ?: true,
            lastExport = p[LAST_EXPORT]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setModel(m: String) = store.edit { it[MODEL] = m.trim() }
    suspend fun setDailyCap(c: Int) = store.edit { it[CAP] = c.coerceIn(1, 1000) }
    suspend fun setMorning(t: LocalTime) = store.edit { it[MORNING] = t.toString() }
    suspend fun setNudge(t: LocalTime) = store.edit { it[NUDGE] = t.toString() }
    suspend fun setRecall(t: LocalTime) = store.edit { it[RECALL] = t.toString() }
    suspend fun setWeekly(t: LocalTime) = store.edit { it[WEEKLY] = t.toString() }
    suspend fun setWindDown(enabled: Boolean) = store.edit { it[WIND] = enabled }
    suspend fun setExactAlarms(enabled: Boolean) = store.edit { it[EXACT] = enabled }
    suspend fun setNotifications(enabled: Boolean) = store.edit { it[NOTIFS] = enabled }
    suspend fun setLastExport(d: LocalDate) = store.edit { it[LAST_EXPORT] = d.toString() }

    /** Daily AI call counter (Rule 6). */
    fun aiCallsToday(today: LocalDate): Flow<Int> = store.data.map { p -> if (p[AI_DATE] == today.toString()) p[AI_COUNT] ?: 0 else 0 }

    suspend fun incrementAiCalls(today: LocalDate): Int {
        var n = 0
        store.edit { p ->
            n = (if (p[AI_DATE] == today.toString()) p[AI_COUNT] ?: 0 else 0) + 1
            p[AI_DATE] = today.toString()
            p[AI_COUNT] = n
        }
        return n
    }

    /** Max 4 alerts per day (Section 13). Returns false when the cap is reached. */
    suspend fun tryConsumeAlert(today: LocalDate, max: Int = 4): Boolean {
        var ok = false
        store.edit { p ->
            val n = if (p[ALERT_DATE] == today.toString()) p[ALERT_COUNT] ?: 0 else 0
            if (n < max) {
                ok = true
                p[ALERT_DATE] = today.toString()
                p[ALERT_COUNT] = n + 1
            }
        }
        return ok
    }

    private fun String?.toTime(default: LocalTime) = this?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: default
}
