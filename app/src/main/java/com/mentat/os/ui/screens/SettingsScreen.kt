package com.mentat.os.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mentat.os.data.prefs.SettingsStore
import com.mentat.os.ui.components.LoadingState
import com.mentat.os.ui.components.NumberField
import com.mentat.os.ui.components.SectionCard
import com.mentat.os.ui.components.SnackbarEffect
import com.mentat.os.ui.components.SubScreen
import com.mentat.os.ui.components.TimeField
import com.mentat.os.ui.components.rememberSnackbar
import com.mentat.os.ui.nav.Nav
import com.mentat.os.vm.SettingsViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: Nav, vm: SettingsViewModel = hiltViewModel()) {
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val key by vm.keyInput.collectAsStateWithLifecycle()
    val s by vm.app.collectAsStateWithLifecycle()
    val p by vm.profileState.collectAsStateWithLifecycle()
    val calls by vm.callsToday.collectAsStateWithLifecycle()
    val t by vm.transient.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snack = rememberSnackbar()
    SnackbarEffect(snack, t.message ?: t.error, vm::consume)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onPermissionResult() }

    SubScreen("Settings", onBack = nav::back, snackbar = snack) {
        val app = s
        val prof = p
        if (app == null || prof == null) { item { LoadingState() }; return@SubScreen }
        item {
            SectionCard("Groq API key") {
                Text(if (hasKey) "Key saved (encrypted with the Android Keystore): ••••••••••••" else "No key yet. AI features stay off until you add one.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    key, vm::onKeyInput, label = { Text(if (hasKey) "Replace key" else "Paste key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::saveKey, enabled = key.isNotBlank()) { Text("Save key") }
                    if (hasKey) TextButton(onClick = vm::clearKey) { Text("Remove key") }
                }
            }
        }
        item {
            SectionCard("Model") {
                var model by rememberSaveable(app.model) { mutableStateOf(app.model) }
                OutlinedTextField(model, { model = it.trim().take(80) }, label = { Text("Groq model id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (t.models.ifEmpty { SettingsStore.SUGGESTED_MODELS }).take(12).forEach { m -> AssistChip(onClick = { model = m }, label = { Text(m) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.setModel(model) }, enabled = model != app.model && model.isNotBlank()) { Text("Use this model") }
                    OutlinedButton(onClick = vm::fetchModels, enabled = hasKey && !t.fetching) { Text(if (t.fetching) "Loading..." else "Fetch list from Groq") }
                }
                Text("Pick a model that supports JSON mode. Output is always validated on the phone.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SectionCard("Daily AI cap") {
                Text("$calls of ${app.dailyAiCap} calls used today. Keeps you inside Groq's free tier; check your real limits in the Groq console.", style = MaterialTheme.typography.bodySmall)
                NumberField("Max AI calls per day", app.dailyAiCap, vm::setCap, Modifier.fillMaxWidth(), 1..1000)
            }
        }
        item {
            SectionCard("Reminders") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Reminders on", modifier = Modifier.weight(1f))
                    Switch(app.notificationsEnabled, vm::setNotifications)
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    Text("Android needs your permission to show reminders.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
                }
                TimeField("Morning plan", app.morningTime, { vm.setTime("morning", it) })
                TimeField("Recall checks", app.recallTime, { vm.setTime("recall", it) })
                TimeField("Evening nudge", app.nudgeTime, { vm.setTime("nudge", it) })
                TimeField("Sunday review", app.weeklyReviewTime, { vm.setTime("weekly", it) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wind-down ${app.windDownMinutes} min before sleep", modifier = Modifier.weight(1f))
                    Switch(app.windDownEnabled, vm::setWindDown)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Exact alarm times")
                        Text("Off = battery-friendly windows (default).", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(app.exactAlarms, { on ->
                        vm.setExact(on)
                        if (on && !vm.canUseExact() && Build.VERSION.SDK_INT >= 31) {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${ctx.packageName}".toUri())) }
                        }
                    })
                }
            }
        }
        item {
            SectionCard("Battery help (OnePlus / OxygenOS)") {
                Text(
                    "If reminders arrive late: 1) Settings > Apps > Mentat OS > Battery > Don't optimize / allow background activity. 2) Allow auto-launch. 3) Lock the app in the recent-apps screen.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) { Text("Open battery optimization settings") }
            }
        }
        item {
            SectionCard("Profile") {
                var name by rememberSaveable(prof.name) { mutableStateOf(prof.name) }
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { vm.updateProfile { it.copy(name = name.trim()) } }, enabled = name != prof.name) { Text("Save name") }
                NumberField("Typical session (min)", prof.sessionMinutes, { v -> vm.updateProfile { it.copy(sessionMinutes = v) } }, Modifier.fillMaxWidth(), 10..180)
                NumberField("Monthly budget (INR)", prof.monthlyBudgetInr, { v -> vm.updateProfile { it.copy(monthlyBudgetInr = v) } }, Modifier.fillMaxWidth(), 0..1_000_000)
                NumberField("Freeze days per week", prof.freezeDaysPerWeek, { v -> vm.updateProfile { it.copy(freezeDaysPerWeek = v) } }, Modifier.fillMaxWidth(), 0..3)
                Text("Sleep window and exam mode are in More > Schedule.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
