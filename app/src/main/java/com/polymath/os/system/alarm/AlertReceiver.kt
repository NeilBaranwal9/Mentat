package com.polymath.os.system.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.polymath.os.data.db.CuriosityDao
import com.polymath.os.data.db.QuestLogDao
import com.polymath.os.data.db.RecallDao
import com.polymath.os.data.prefs.SettingsStore
import com.polymath.os.data.repo.PlanRepository
import com.polymath.os.data.repo.ProfileRepository
import com.polymath.os.domain.DispatcherProvider
import com.polymath.os.domain.engine.minuteToLabel
import com.polymath.os.domain.model.QuestState
import com.polymath.os.system.notify.AlertType
import com.polymath.os.system.notify.Notifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Fires a templated alert after checking it is still relevant. Never calls AI. Max 4 alerts/day. */
@AndroidEntryPoint
class AlertReceiver : BroadcastReceiver() {
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var plans: PlanRepository
    @Inject lateinit var logDao: QuestLogDao
    @Inject lateinit var recallDao: RecallDao
    @Inject lateinit var curiosityDao: CuriosityDao
    @Inject lateinit var profile: ProfileRepository
    @Inject lateinit var clock: Clock
    @Inject lateinit var dispatchers: DispatcherProvider

    override fun onReceive(context: Context, intent: Intent) {
        val type = runCatching { AlertType.valueOf(intent.getStringExtra(AlarmScheduler.EXTRA_TYPE).orEmpty()) }.getOrNull() ?: return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            try {
                handle(type)
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun handle(type: AlertType) {
        val s = settings.current()
        if (!s.notificationsEnabled || !notifier.canPost()) return
        val today = LocalDate.now(clock)
        val message: Pair<String, String> = when (type) {
            AlertType.PLAN_READY -> {
                plans.ensure(today)
                val blocks = plans.observe(today).first()?.blocks.orEmpty()
                if (blocks.isEmpty()) return
                val first = blocks.firstOrNull { it.startMinute != null } ?: blocks.first()
                val at = first.startMinute?.let { " at ${minuteToLabel(it)}" } ?: ""
                "Today's plan is ready" to "${blocks.size} item${if (blocks.size == 1) "" else "s"} planned. First: ${first.title}$at."
            }
            AlertType.NUDGE -> {
                val logged = logDao.between(today, today).any { it.state != QuestState.SKIPPED } ||
                    recallDao.getAll().any { it.answeredOn == today }
                if (logged) return
                val tiny = plans.observe(today).first()?.blocks?.firstOrNull { it.isTiny }
                "One thing" to (tiny?.let { "\"${it.title}\" takes ${it.minutes} minutes and still counts today." }
                    ?: "One small logged item still counts today.")
            }
            AlertType.RECALL_DUE -> {
                val due = recallDao.due(today).size
                if (due == 0) return
                "Recall check${if (due == 1) "" else "s"} due" to "$due quick recall check${if (due == 1) " is" else "s are"} waiting. About 5 minutes each."
            }
            AlertType.WEEKLY_REVIEW -> {
                val inbox = curiosityDao.inbox().size
                val backup = s.lastExport?.let { ChronoUnit.DAYS.between(it, today) >= 7 } ?: true
                val extra = if (backup) " Also: export a backup (More > Export / Import)." else ""
                "Weekly review" to "Look back at your week and sort $inbox idea${if (inbox == 1) "" else "s"} in your inbox.$extra"
            }
            AlertType.WIND_DOWN -> {
                val p = profile.get()
                "Wind down" to "Your sleep window starts at ${p.sleepStart}. Good time to log today and put the phone away."
            }
        }
        if (!settings.tryConsumeAlert(today)) return
        notifier.post(type, message.first, message.second)
    }
}
