package com.mentat.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mentat.ui.components.ErrorCard
import com.mentat.ui.components.SectionCard
import com.mentat.ui.components.TimeField
import com.mentat.vm.AppViewModel
import com.mentat.vm.OnboardingViewModel
import com.mentat.vm.SkillsViewModel
import java.time.LocalTime

@Composable
fun OnboardingScreen(@Suppress("UNUSED_PARAMETER") app: AppViewModel, vm: OnboardingViewModel = hiltViewModel()) {
    val step by vm.step.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LinearProgressIndicator(progress = { (step + 1) / 4f }, modifier = Modifier.fillMaxWidth())
        Text("Mentat", style = MaterialTheme.typography.headlineMedium)
        error?.let { ErrorCard(it) }
        when (step) {
            0 -> KeyStep(vm)
            1 -> ProfileStep(vm)
            2 -> NotificationStep(vm)
            else -> FirstSkillStep(vm)
        }
    }
}

@Composable
private fun KeyStep(vm: OnboardingViewModel) {
    val key by vm.keyInput.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    Text("Learn many short skills. An AI drafts each skill path once; everything after that runs on your phone.", style = MaterialTheme.typography.bodyLarge)
    SectionCard("Groq API key (optional now)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null)
            Text(
                "  Stored encrypted on this phone only. It is never logged, exported or sent anywhere except Groq. You can add it later in Settings.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (hasKey) Text("A key is already saved.", color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = key, onValueChange = vm::onKeyInput, label = { Text("Paste your Groq key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(onClick = vm::saveKeyAndContinue, modifier = Modifier.fillMaxWidth()) { Text(if (key.isBlank()) "Continue without a key" else "Save key and continue") }
}

@Composable
private fun ProfileStep(vm: OnboardingViewModel) {
    val name by vm.name.collectAsStateWithLifecycle()
    val education by vm.education.collectAsStateWithLifecycle()
    val interests by vm.interests.collectAsStateWithLifecycle()
    val hours by vm.weeklyHours.collectAsStateWithLifecycle()
    val sleepStart by vm.sleepStart.collectAsStateWithLifecycle()
    val sleepEnd by vm.sleepEnd.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val budget by vm.budget.collectAsStateWithLifecycle()
    val claimed by vm.claimed.collectAsStateWithLifecycle()
    Text("About you", style = MaterialTheme.typography.titleLarge)
    Text("A plain form instead of a chat. Everything stays on this phone.", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(name, { vm.set("name", it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(education, { vm.set("education", it) }, label = { Text("Education (e.g. 2nd year B.Tech)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(interests, { vm.set("interests", it) }, label = { Text("Interests") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(hours, { vm.set("hours", it.filter { c -> c.isDigit() || c == '.' }) }, label = { Text("Free hours/week") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(session, { vm.set("session", it.filter(Char::isDigit)) }, label = { Text("Session min") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    TimeField("Sleep starts", runCatching { LocalTime.parse(sleepStart) }.getOrDefault(LocalTime.of(23, 0)), { vm.set("sleepStart", it.toString()) })
    TimeField("Wake up", runCatching { LocalTime.parse(sleepEnd) }.getOrDefault(LocalTime.of(7, 0)), { vm.set("sleepEnd", it.toString()) })
    OutlinedTextField(budget, { vm.set("budget", it.filter(Char::isDigit)) }, label = { Text("Monthly learning budget (INR)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        claimed, { vm.set("claimed", it) }, minLines = 2,
        label = { Text("Skills you already have, one per line: \"Python: 6\"") },
        supportingText = { Text("Your own 1-10 estimate. Shown as an unverified claim; it never adds to your level.") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.go(0) }) { Text("Back") }
        Button(onClick = vm::saveProfile, modifier = Modifier.weight(1f)) { Text("Continue") }
    }
}

@Composable
private fun NotificationStep(vm: OnboardingViewModel) {
    val ctx = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked = true }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.NotificationsActive, null)
            Text("  Reminders", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "Up to 4 short reminders a day: your morning plan, recall checks, an evening nudge only if nothing was logged, and a Sunday review. Texts are fixed templates, never AI.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }, enabled = !asked) { Text(if (asked) "Done" else "Allow reminders") }
        }
    }
    SectionCard("OnePlus / OxygenOS tip") {
        Text(
            "Battery savers can delay reminders. Set battery to \"Don't optimize\" for Mentat, allow background activity, and lock the app in recents.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) { Text("Open battery settings") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.go(1) }) { Text("Back") }
        Button(onClick = { vm.go(3) }, modifier = Modifier.weight(1f)) { Text("Continue") }
    }
}

@Composable
private fun FirstSkillStep(vm: OnboardingViewModel, skills: SkillsViewModel = hiltViewModel()) {
    val seeds by skills.seeds.collectAsStateWithLifecycle()
    var added by rememberSaveable { mutableStateOf(listOf<String>()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.School, null)
        Text("  Your first skill", style = MaterialTheme.typography.titleLarge)
    }
    Text(
        "Pick a hand-made starter path, or skip and create your own from the Skills tab (with AI if you added a key, or by hand). New skills start in sampling mode: try the short sampler first, then decide.",
        style = MaterialTheme.typography.bodyMedium,
    )
    seeds.forEach { bp ->
        SectionCard(bp.skillName) {
            Text("${bp.domain} · ${bp.miniSkills.size} mini-skills · about ${bp.totalHours.toInt()} h to basic competence", style = MaterialTheme.typography.bodySmall)
            Text("Sampler: ${bp.samplerSession.title} (${bp.samplerSession.minutes} min)", style = MaterialTheme.typography.bodyMedium)
            if (bp.skillName in added) Text("Added", color = MaterialTheme.colorScheme.primary)
            else OutlinedButton(onClick = { skills.addSeed(bp); added = added + bp.skillName }) { Text("Add this skill") }
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { vm.go(2) }) { Text("Back") }
        Button(onClick = vm::finish, modifier = Modifier.weight(1f)) { Text(if (added.isEmpty()) "Skip for now" else "Start") }
    }
}
