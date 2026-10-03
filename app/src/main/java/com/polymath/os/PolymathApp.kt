package com.polymath.os

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.polymath.os.data.repo.ComboRepository
import com.polymath.os.domain.DispatcherProvider
import com.polymath.os.system.StrictModeSetup
import com.polymath.os.system.alarm.AlarmScheduler
import com.polymath.os.system.notify.Notifier
import com.polymath.os.system.work.DailyWorker
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class PolymathApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var initializer: AppInitializer

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        if (BuildConfig.DEBUG) StrictModeSetup.install()
        super.onCreate()
        initializer.start()
    }
}

/**
 * Process-start housekeeping, all off the main thread: notification channels, combo seeds,
 * the daily worker and the next 7 days of alerts ("on every app open", Section 13). No network.
 */
@Singleton
class AppInitializer @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val notifier: Notifier,
    private val combos: ComboRepository,
    private val scheduler: AlarmScheduler,
    private val dispatchers: DispatcherProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    fun start() {
        scope.launch {
            notifier.createChannels()
            runCatching { combos.seedIfNeeded(ctx.assets.open("combos.json").bufferedReader().use { it.readText() }) }
            DailyWorker.enqueue(ctx)
            scheduler.rescheduleNext7Days()
        }
    }

    fun reschedule() {
        scope.launch { scheduler.rescheduleNext7Days() }
    }
}
