package com.mentat.os.system.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mentat.os.data.prefs.SettingsStore
import com.mentat.os.data.repo.ProfileRepository
import com.mentat.os.domain.DispatcherProvider
import com.mentat.os.system.notify.AlertType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the next 7 days of alerts so they fire even when the app is not running (Section 13).
 * Inexact windows by default; exact alarms only when the user enabled the toggle AND the system allows it.
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsStore,
    private val profile: ProfileRepository,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) {
    companion object {
        const val EXTRA_TYPE = "alert_type"
        const val EXTRA_DATE = "alert_date"
        private const val WINDOW_MS = 15 * 60_000L
    }

    private val am: AlarmManager get() = ctx.getSystemService(AlarmManager::class.java)

    fun canUseExact(): Boolean = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    suspend fun rescheduleNext7Days() = withContext(dispatchers.io) {
        val s = settings.current()
        val p = profile.get()
        val today = LocalDate.now(clock)
        val now = LocalDateTime.now(clock)
        cancelAll()
        if (!s.notificationsEnabled) return@withContext
        val sleepStart = runCatching { LocalTime.parse(p.sleepStart) }.getOrNull()
        for (offset in 0L until 7L) {
            val day = today.plusDays(offset)
            val plan = buildList {
                add(AlertType.PLAN_READY to s.morningTime)
                add(AlertType.RECALL_DUE to s.recallTime)
                add(AlertType.NUDGE to s.nudgeTime)
                if (day.dayOfWeek == DayOfWeek.SUNDAY) add(AlertType.WEEKLY_REVIEW to s.weeklyReviewTime)
                if (s.windDownEnabled && sleepStart != null) add(AlertType.WIND_DOWN to sleepStart.minusMinutes(s.windDownMinutes.toLong()))
            }
            for ((type, time) in plan) {
                val at = day.atTime(time)
                if (!at.isAfter(now)) continue
                val millis = at.atZone(clock.zone).toInstant().toEpochMilli()
                val pi = pending(type, day)
                if (s.exactAlarms && canUseExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                else am.setWindow(AlarmManager.RTC_WAKEUP, millis, WINDOW_MS, pi)
            }
        }
    }

    private fun cancelAll() {
        val today = LocalDate.now(clock)
        for (offset in -1L until 8L) for (t in AlertType.entries) am.cancel(pending(t, today.plusDays(offset)))
    }

    /** Explicit, immutable PendingIntent. Request code is unique per (day mod 9, type). */
    private fun pending(type: AlertType, day: LocalDate): PendingIntent {
        val intent = Intent(ctx, AlertReceiver::class.java).apply {
            action = "com.mentat.os.ALERT_${type.name}"
            putExtra(EXTRA_TYPE, type.name)
            putExtra(EXTRA_DATE, day.toString())
        }
        val code = (Math.floorMod(day.toEpochDay(), 9L).toInt()) * 10 + type.ordinal
        return PendingIntent.getBroadcast(ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
