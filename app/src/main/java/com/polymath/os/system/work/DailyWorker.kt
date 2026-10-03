package com.polymath.os.system.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.polymath.os.data.repo.ComboRepository
import com.polymath.os.data.repo.JournalRepository
import com.polymath.os.data.repo.PlanRepository
import com.polymath.os.system.alarm.AlarmScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Daily housekeeping, all deterministic and offline: auto-plan once per day, write yesterday's
 * journal page, check for a combo suggestion, and reschedule the next 7 days of alerts. Never calls AI.
 */
@HiltWorker
class DailyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val plans: PlanRepository,
    private val journal: JournalRepository,
    private val combos: ComboRepository,
    private val scheduler: AlarmScheduler,
    private val clock: Clock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        val today = LocalDate.now(clock)
        plans.ensure(today)
        journal.write(today.minusDays(1))
        combos.evaluate(today)
        scheduler.rescheduleNext7Days()
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        private const val NAME = "polymath_daily"

        fun enqueue(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS, 3, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
