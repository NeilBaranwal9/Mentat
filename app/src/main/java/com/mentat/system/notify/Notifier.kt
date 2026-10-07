package com.mentat.system.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mentat.MainActivity
import com.mentat.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One channel per alert type (Section 13). Texts come from fixed templates, never from AI. */
enum class AlertType(val channelId: String, val channelName: String, val description: String) {
    PLAN_READY("plan_ready", "Plan ready", "Morning message when today's plan is ready"),
    NUDGE("one_thing", "One thing nudge", "Evening nudge, only if nothing was logged today"),
    RECALL_DUE("recall_due", "Recall checks", "Reminder when recall checks are due"),
    WEEKLY_REVIEW("weekly_review", "Weekly review", "Sunday review of your inbox and week"),
    WIND_DOWN("wind_down", "Wind-down", "Optional reminder before your sleep window"),
}

@Singleton
class Notifier @Inject constructor(@ApplicationContext private val ctx: Context) {

    fun createChannels() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        AlertType.entries.forEach { t ->
            nm.createNotificationChannel(
                NotificationChannel(t.channelId, t.channelName, NotificationManager.IMPORTANCE_DEFAULT).apply { description = t.description },
            )
        }
    }

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    fun post(type: AlertType, title: String, text: String) {
        if (!canPost()) return
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_DESTINATION, type.name)
        }
        val pi = PendingIntent.getActivity(ctx, type.ordinal, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, type.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(type.ordinal + 1, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }
}
