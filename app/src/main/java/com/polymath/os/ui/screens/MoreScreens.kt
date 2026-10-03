package com.polymath.os.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polymath.os.data.repo.CommitmentRepository
import com.polymath.os.domain.model.CommitKind
import com.polymath.os.domain.model.Recurrence
import com.polymath.os.ui.components.BusyButton
import com.polymath.os.ui.components.ConfirmDialog
import com.polymath.os.ui.components.DateField
import com.polymath.os.ui.components.EmptyState
import com.polymath.os.ui.components.ErrorCard
import com.polymath.os.ui.components.InfoCard
import com.polymath.os.ui.components.LoadingState
import com.polymath.os.ui.components.Pill
import com.polymath.os.ui.components.SectionCard
import com.polymath.os.ui.components.SnackbarEffect
import com.polymath.os.ui.components.SubScreen
import com.polymath.os.ui.components.TabScreen
import com.polymath.os.ui.components.TimeField
import com.polymath.os.ui.components.rememberSnackbar
import com.polymath.os.ui.nav.Nav
import com.polymath.os.ui.nav.Routes
import com.polymath.os.vm.AiAvailability
import com.polymath.os.vm.BackupViewModel
import com.polymath.os.vm.ChatViewModel
import com.polymath.os.vm.CommitmentsViewModel
import com.polymath.os.vm.DiagnosticsViewModel
import com.polymath.os.vm.GoalDetailViewModel
import com.polymath.os.vm.GoalsViewModel
import com.polymath.os.vm.ManualGoalViewModel
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun MoreScreen(nav: Nav, debug: Boolean) {
    TabScreen("More") {
        item { MoreRow(Icons.Outlined.Flag, "Goals", "Goal ladders: which skills a goal needs") { nav.to(Routes.GOALS) } }
        item { MoreRow(Icons.Outlined.CalendarMonth, "Schedule", "Classes, exams, sleep window, exam mode, calendar import") { nav.to(Routes.SCHEDULE) } }
        item { MoreRow(Icons.AutoMirrored.Outlined.Chat, "Chat commands", "Type requests; you confirm every change") { nav.to(Routes.CHAT) } }
        item { MoreRow(Icons.Outlined.Settings, "Settings", "Groq key, model, reminders, profile") { nav.to(Routes.SETTINGS) } }
        item { MoreRow(Icons.Outlined.Inventory2, "Export / Import", "Backup to a zip, restore, delete history") { nav.to(Routes.BACKUP) } }
        if (debug) item { MoreRow(Icons.Outlined.BugReport, "Diagnostics (debug)", "AI call counter and circuit breaker") { nav.to(Routes.DIAGNOSTICS) } }
        item {
            Text(
                "Polymath OS keeps everything on this phone. The only network traffic is to Groq, and only when you tap an AI button.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, leadingContent = { Icon(icon, null) })
    }
}

// ---------------- Goals ----------------

@Composable
fun GoalsScreen(nav: Nav, ai: AiAvailability, vm: GoalsViewModel = hiltViewModel()) {
    val list by vm.list.collectAsStateWithLifecycle()
    val text by vm.goalText.collectAsStateWithLifecycle()
    val t by vm.transient.collectAsStateWithLifecycle()
    val snack = rememberSnackbar()
    SnackbarEffect(snack, t.message, vm::consume)
    LaunchedEffect(t.openProposal) { t.openProposal?.let { nav.to(Routes.proposal(it)); vm.consume() } }
    SubScreen("Goals", onBack = nav::back, snackbar = snack) {
        item {
            SectionCard("New goal") {
                OutlinedTextField(text, vm::onGoalText, label = { Text("e.g. Build a small robot by March") }, modifier = Modifier.fillMaxWidth())
                t.error?.let { ErrorCard(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ai.hasKey) BusyButton(if (ai.canCall) "Map skills with AI" else "Save for later", t.busy, enabled = text.isNotBlank()) { vm.mapGoal(ai.canCall) }
                    OutlinedButton(onClick = { nav.to(Routes.GOAL_MANUAL) }) { Text("Enter by hand") }
                }
            }
        }
        when {
            list == null -> item { LoadingState() }
            list!!.isEmpty() -> item { EmptyState(Icons.Outlined.Flag, "No goals yet", "A goal ladder shows which skills a goal depends on. Core skills get a small planner bonus.") }
            else -> list!!.forEach { g ->
                item(key = g.goal.id) {
                    Card(onClick = { nav.to(Routes.goal(g.goal.id)) }) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(g.goal.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { vm.delete(g.goal.id) }) { Icon(Icons.Outlined.Delete, "Delete goal") }
                            }
                            val linked = g.skills.count { it.linkedSkillId != null }
                            Text("${g.skills.size} skills · $linked linked${g.goal.targetDate?.let { " · by $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ManualGoalScreen(nav: Nav, vm: ManualGoalViewModel = hiltViewModel()) {
    val title by vm.title.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    LaunchedEffect(saved) { saved?.let { nav.replace(Routes.goal(it)) } }
    SubScreen("Goal (manual)", onBack = nav::back) {
        item { OutlinedTextField(title, vm::setTitle, label = { Text("Goal") }, modifier = Modifier.fillMaxWidth()) }
        item {
            OutlinedTextField(
                lines, vm::setLines, minLines = 5, modifier = Modifier.fillMaxWidth(),
                label = { Text("Skills it needs, one per line") },
                supportingText = { Text("Optional prefix: core:, supporting: or optional:") },
            )
        }
        item { Button(onClick = vm::save, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save goal") } }
    }
}

@Composable
fun GoalDetailScreen(nav: Nav, vm: GoalDetailViewModel = hiltViewModel()) {
    val goal by vm.goal.collectAsStateWithLifecycle()
    val ladder by vm.ladder.collectAsStateWithLifecycle()
    val skills by vm.skillList.collectAsStateWithLifecycle()
    SubScreen(goal?.goal?.title ?: "Goal", onBack = nav::back) {
        if (goal == null) { item { LoadingState() }; return@SubScreen }
        item { Text("Goal ladder: foundations first. Link each step to one of your skills, or create it.", style = MaterialTheme.typography.bodyMedium) }
        ladder.forEach { row ->
            item(key = row.goalSkillId) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width((row.depth * 16).dp))
                        Text("Step ${row.depth + 1}: ${row.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Pill(row.importance)
                    }
                    if (row.dependsOn.isNotEmpty()) Text("After: ${row.dependsOn.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    if (row.reason.isNotBlank()) Text(row.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var menu by remember { mutableStateOf(false) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (row.linked != null) {
                            OutlinedButton(onClick = { nav.to(Routes.skill(row.linked.id)) }) { Text("Open ${row.linked.name}") }
                            TextButton(onClick = { vm.link(row.goalSkillId, null) }) { Text("Unlink") }
                        } else {
                            Button(onClick = { nav.to(Routes.create(name = row.name, goalSkill = row.goalSkillId)) }) { Text("Create skill") }
                            Column {
                                TextButton(onClick = { menu = true }, enabled = skills.isNotEmpty()) { Text("Link existing") }
                                DropdownMenu(menu, { menu = false }) {
                                    skills.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { menu = false; vm.link(row.goalSkillId, s.id) }) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Schedule ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen(nav: Nav, vm: CommitmentsViewModel = hiltViewModel()) {
    val list by vm.commitments.collectAsStateWithLifecycle()
    val p by vm.profileState.collectAsStateWithLifecycle()
    val t by vm.transient.collectAsStateWithLifecycle()
    val snack = rememberSnackbar()
    var adding by rememberSaveable { mutableStateOf(false) }
    SnackbarEffect(snack, t.message ?: t.error, vm::consume)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importIcs) }
    SubScreen("Schedule", onBack = nav::back, snackbar = snack) {
        val prof = p
        if (prof == null || list == null) { item { LoadingState() }; return@SubScreen }
        item {
            SectionCard("Sleep window") {
                TimeField("Sleep", runCatching { LocalTime.parse(prof.sleepStart) }.getOrDefault(LocalTime.of(23, 0)), { vm.saveSleep(it, LocalTime.parse(prof.sleepEnd)) })
                TimeField("Wake", runCatching { LocalTime.parse(prof.sleepEnd) }.getOrDefault(LocalTime.of(7, 0)), { vm.saveSleep(LocalTime.parse(prof.sleepStart), it) })
            }
        }
        item {
            SectionCard("Exam mode") {
                Text("Only priority-5 skills and recall checks run full-size; everything else uses tiny versions.", style = MaterialTheme.typography.bodySmall)
                DateField("From", prof.examStart, { vm.setExam(it, prof.examEnd ?: it) })
                DateField("To", prof.examEnd, { vm.setExam(prof.examStart ?: it, it) })
                if (prof.examStart != null) TextButton(onClick = { vm.setExam(null, null) }) { Text("Turn off exam mode") }
            }
        }
        item {
            SectionCard("Calendar import (.ics)") {
                Text("Pick a timetable exported as .ics. Recurring events are expanded for 90 days. Re-importing replaces the previous import.", style = MaterialTheme.typography.bodySmall)
                val ics = list!!.count { it.origin == CommitmentRepository.ORIGIN_ICS }
                if (ics > 0) Text("$ics imported blocks", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BusyButton("Import .ics", t.busy) { picker.launch(arrayOf("text/calendar", "text/x-vcalendar", "application/octet-stream", "*/*")) }
                    if (ics > 0) TextButton(onClick = { vm.askClearIcs(true) }) { Text("Remove import") }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fixed commitments", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(onClick = { adding = true }) { Text("Add") }
            }
        }
        val manual = list!!.filter { it.origin != CommitmentRepository.ORIGIN_ICS }
        if (manual.isEmpty()) item { Text("None yet. Classes, jobs and exams block time in your plan.", style = MaterialTheme.typography.bodySmall) }
        manual.forEach { c ->
            item(key = c.id) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.titleSmall)
                            val rec = when (c.recurrence) { Recurrence.NONE -> c.start.toLocalDate().toString(); Recurrence.DAILY -> "daily"; Recurrence.WEEKLY -> "every ${c.start.dayOfWeek.name.lowercase()}" }
                            Text("${c.kind.name.lowercase()} · $rec · ${c.start.toLocalTime()}-${c.end.toLocalTime()}", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { vm.delete(c) }) { Icon(Icons.Outlined.Delete, "Delete") }
                    }
                }
            }
        }
    }
    if (adding) {
        var title by rememberSaveable { mutableStateOf("") }
        var kind by rememberSaveable { mutableStateOf(CommitKind.CLASS) }
        var rec by rememberSaveable { mutableStateOf(Recurrence.WEEKLY) }
        var date by rememberSaveable { mutableStateOf(LocalDate.now()) }
        var start by rememberSaveable { mutableStateOf(LocalTime.of(9, 0)) }
        var end by rememberSaveable { mutableStateOf(LocalTime.of(10, 0)) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add commitment") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it.take(80) }, label = { Text("Title") }, singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(CommitKind.CLASS, CommitKind.EXAM, CommitKind.OTHER).forEach { k -> FilterChip(kind == k, { kind = k }, label = { Text(k.name.lowercase()) }) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Recurrence.entries.forEach { r -> FilterChip(rec == r, { rec = r }, label = { Text(if (r == Recurrence.NONE) "once" else r.name.lowercase()) }) }
                    }
                    DateField(if (rec == Recurrence.WEEKLY) "First day" else "Date", date, { date = it })
                    TimeField("Starts", start, { start = it })
                    TimeField("Ends", end, { end = it })
                }
            },
            confirmButton = {
                Button(onClick = {
                    val s = date.atTime(start)
                    val e = if (end.isAfter(start)) date.atTime(end) else date.plusDays(1).atTime(end)
                    vm.add(title, kind, s, e, rec)
                    adding = false
                }, enabled = title.isNotBlank()) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    if (t.confirmClearIcs) ConfirmDialog("Remove the calendar import?", "All imported calendar blocks are removed.", "Remove", vm::clearIcs, { vm.askClearIcs(false) }, destructive = true)
}

// ---------------- Chat ----------------

@Composable
fun ChatScreen(nav: Nav, ai: AiAvailability, vm: ChatViewModel = hiltViewModel()) {
    val input by vm.input.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val t by vm.transient.collectAsStateWithLifecycle()
    LaunchedEffect(t.openProposal) { t.openProposal?.let { nav.to(Routes.proposal(it)); vm.consume() } }
    SubScreen("Chat commands", onBack = nav::back) {
        item {
            Text(
                "Examples: \"Physics class every Monday 10 to 11\", \"exams from 12 to 20 Nov\", \"pause guitar\", \"I have 20 minutes\". " +
                    "The AI only describes changes; you confirm each one.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!ai.hasKey) item { InfoCard("Add a Groq key in Settings to use chat commands.", "Settings") { nav.to(Routes.SETTINGS) } }
        history.takeLast(8).forEach { h -> item { SectionCard { Text(h, style = MaterialTheme.typography.bodyMedium) } } }
        t.error?.let { item { ErrorCard(it, vm::consume) } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, vm::onInput, placeholder = { Text("Type a request") }, modifier = Modifier.weight(1f), enabled = !t.busy)
                IconButton(onClick = { vm.send(ai.canCall) }, enabled = input.isNotBlank() && !t.busy && ai.hasKey) { Icon(Icons.AutoMirrored.Outlined.Send, "Send") }
            }
        }
        if (t.busy) item { LoadingState("Parsing...") }
    }
}

// ---------------- Backup ----------------

@Composable
fun BackupScreen(nav: Nav, vm: BackupViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snack = rememberSnackbar()
    SnackbarEffect(snack, ui.message ?: ui.error, vm::consume)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::export) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.askImport(it) } }
    SubScreen("Export / Import", onBack = nav::back, snackbar = snack) {
        item {
            SectionCard("Export") {
                Text("Saves all your data and proof photos as one zip file wherever you choose. Your Groq key is never included.", style = MaterialTheme.typography.bodyMedium)
                BusyButton("Export JSON + photos (zip)", ui.busy) { exporter.launch("polymath-backup-${LocalDate.now()}.zip") }
            }
        }
        item {
            SectionCard("Import") {
                Text("Restores a backup. This replaces everything currently in the app.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !ui.busy) { Text("Import a backup") }
            }
        }
        item {
            SectionCard("Delete history") {
                Text("Removes logs, journal pages, energy taps and photos. Skills, goals and settings stay.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.askDeleteHistory(true) }) { Text("Delete history") }
            }
        }
    }
    ui.confirmImport?.let { ConfirmDialog("Replace all data?", "Everything in the app is replaced by the backup. Export first if unsure.", "Replace", vm::import, { vm.askImport(null) }, destructive = true) }
    if (ui.confirmDeleteHistory) ConfirmDialog("Delete history?", "Logs, journal, energy and photos are deleted permanently.", "Delete", vm::deleteHistory, { vm.askDeleteHistory(false) }, destructive = true)
}

// ---------------- Diagnostics (debug only) ----------------

@Composable
fun DiagnosticsScreen(nav: Nav, vm: DiagnosticsViewModel = hiltViewModel()) {
    val g by vm.guardState.collectAsStateWithLifecycle()
    val today by vm.callsToday.collectAsStateWithLifecycle()
    val hist by vm.history.collectAsStateWithLifecycle()
    SubScreen("Diagnostics", onBack = nav::back) {
        item {
            SectionCard("Groq calls (Rule 6 gate)") {
                Text("This app session: ${g.sessionCalls}", style = MaterialTheme.typography.headlineSmall)
                Text("Today (all sessions): $today")
                Text("In flight: ${g.inFlight} · consecutive failures: ${g.consecutiveFailures}")
                val paused = g.pausedUntilMillis > System.currentTimeMillis()
                Text(if (paused) "Circuit breaker OPEN: AI paused" else "Circuit breaker closed")
                g.lastError?.let { Text("Last error: $it") }
                Text("Open every screen 10 times and rotate: the session counter must stay at 0.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SectionCard("Breaker test") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::simulateFailure) { Text("Simulate failure") }
                    TextButton(onClick = vm::resetBreaker) { Text("Reset") }
                }
            }
        }
        item { SectionCard("Last 14 days") { hist.forEach { Text("${it.date}: ${it.calls} calls") }; if (hist.isEmpty()) Text("No calls yet.") } }
    }
}
