package com.mentat.system.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mentat.domain.DispatcherProvider
import com.mentat.system.alarm.AlarmScheduler
import com.mentat.system.work.DailyWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Re-creates the next 7 days of alerts after a reboot. Handles nothing but BOOT_COMPLETED. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: AlarmScheduler
    @Inject lateinit var dispatchers: DispatcherProvider

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            try {
                scheduler.rescheduleNext7Days()
                DailyWorker.enqueue(context.applicationContext)
            } finally {
                result.finish()
            }
        }
    }
}
