package com.polymath.os.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polymath.os.data.db.WeaknessEventEntity
import com.polymath.os.domain.engine.BlockerAnswer
import com.polymath.os.domain.engine.CandidateKind
import com.polymath.os.domain.engine.EnergyAnalyzer
import com.polymath.os.domain.engine.KeepAnswer
import com.polymath.os.domain.engine.PlanBlock
import com.polymath.os.domain.engine.QuestionKind
import com.polymath.os.domain.engine.minuteToLabel
import com.polymath.os.domain.model.FrictionTag
import com.polymath.os.domain.model.QuestState
import com.polymath.os.domain.model.RecallMode
import com.polymath.os.domain.model.RecallResult
import com.polymath.os.ui.components.ConfirmDialog
import com.polymath.os.ui.components.EmptyState
import com.polymath.os.ui.components.InfoCard
import com.polymath.os.ui.components.NumberField
import com.polymath.os.ui.components.Pill
import com.polymath.os.ui.components.SectionCard
import com.polymath.os.ui.components.SnackbarEffect
import com.polymath.os.ui.components.TabScreen
import com.polymath.os.ui.components.rememberSnackbar
import com.polymath.os.ui.nav.Nav
import com.polymath.os.ui.nav.Routes
import com.polymath.os.vm.AiAvailability
import com.polymath.os.vm.AppViewModel
import com.polymath.os.vm.BlockUi
import com.polymath.os.vm.LogTarget
import com.polymath.os.vm.RecallPrompt
import com.polymath.os.vm.TodayData
import com.polymath.os.vm.TodayViewModel
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(nav: Nav, ai: AiAvailability, vm: TodayViewModel = hiltViewModel(), app: AppViewModel = hiltViewModel()) {
    val d by vm.data.collectAsStateWithLifecycle()
    val t by vm.transient.collectAsStateWithLifecycle()
    val capture by vm.captureText.collectAsStateWithLifecycle()
    val proposals by app.pendingProposals.collectAsStateWithLifecycle()
    val snack = rememberSnackbar()
    var askMinutes by rememberSaveable { mutableStateOf(false) }
    var quickLog by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.refreshDate()
        onPauseOrDispose { }
    }
    // Keyed lifecycle effect (Rule 6): deterministic, offline, idempotent for this date.
    LaunchedEffect(d.today) { vm.ensureDay(d.today) }
    LaunchedEffect(t.openProposal, t.openSkill) {
        t.openProposal?.let { nav.to(Routes.proposal(it)) }
        t.openSkill?.let { nav.to(Routes.skill(it)) }
        if (t.openProposal != null || t.openSkill != null) vm.consumeNavigation()
    }
    SnackbarEffect(snack, t.message, vm::consumeMessage)
    SnackbarEffect(snack, t.error, vm::consumeError)

    TabScreen(
        title = d.today.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())),
        snackbar = snack,
        actions = { IconButton(onClick = { nav.to(Routes.CHAT) }) { Icon(Icons.AutoMirrored.Outlined.Chat, "Chat commands") } },
    ) {
        item { HeaderCard(d) }
        if (proposals.isNotEmpty()) {
            item { InfoCard("${proposals.size} AI suggestion${if (proposals.size == 1) "" else "s"} waiting for your OK", "Review") { nav.to(Routes.PROPOSALS) } }
        }
        item {
            Button(onClick = { askMinutes = true }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Outlined.Bolt, null)
                Spacer(Modifier.width(8.dp))
                Text("What should I do?", style = MaterialTheme.typography.titleMedium)
            }
        }
        item { EnergyRow(d, vm::logEnergy) }
        d.weakness?.let { w -> item { WeaknessCard(w, ai, vm) } }
        d.samplerDecisions.forEach { s ->
            item {
                SectionCard("You tried the ${s.name} sampler") {
                    Text("Keep it (becomes active), pause it, or drop it?", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.keepSkill(s.id) }) { Text("Keep") }
                        OutlinedButton(onClick = { vm.pauseSkill(s.id) }) { Text("Pause") }
                        TextButton(onClick = { vm.askDrop(s.id) }) { Text("Drop") }
                    }
                }
            }
        }
        d.resumePrompts.forEach { s ->
            item {
                SectionCard("Resume ${s.name}?") {
                    Text("You paused it a month ago.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.keepSkill(s.id); vm.notNowResume(s.id) }) { Text("Resume") }
                        OutlinedButton(onClick = { vm.notNowResume(s.id) }) { Text("Not now") }
                    }
                }
            }
        }
        d.combo?.let { c ->
            item {
                SectionCard("Cross-skill project idea") {
                    Text("${c.project} (about ${c.estHours.toInt()} h)", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { vm.acceptCombo(c) }) { Text("Add to inbox") }
                        TextButton(onClick = { vm.dismissCombo(c) }) { Text("Dismiss") }
                    }
                }
            }
        }
        if (d.dirty) item { InfoCard("Your skills or schedule changed.", "Recompute plan") { vm.recompute() } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today's plan", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (d.plan?.examMode == true) { Pill("Exam mode"); Spacer(Modifier.width(4.dp)) }
                val left = 5 - (d.plan?.recomputes ?: 0)
                TextButton(onClick = vm::recompute, enabled = left > 0) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                    Text(" Recompute ($left)")
                }
            }
        }
        if (d.loaded && d.blocks.isEmpty()) {
            item {
                EmptyState(Icons.Outlined.EventNote, "Nothing planned yet", "Add a skill and keep it after its sampler, or log something below.") {
                    OutlinedButton(onClick = { nav.tab(Routes.SKILLS) }) { Text("Go to Skills") }
                }
            }
        }
        d.blocks.forEach { b -> item(key = b.block.candidateId + b.block.minimumDay) { BlockCard(b, onLog = { vm.openLog(b.block) }, onTiny = { vm.openLog(b.block, forceTiny = true) }) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { quickLog = true }) { Text("Log something else") }
            }
        }
        if (d.activeSkills.isNotEmpty()) {
            item {
                SectionCard("Active skills (${d.activeSkills.size}/3)") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.activeSkills.forEach { s ->
                            val lvl = d.stats?.perSkill?.get(s.id)?.level ?: 1
                            AssistChip(onClick = { nav.to(Routes.skill(s.id)) }, label = { Text("${s.name} · L$lvl") })
                        }
                    }
                }
            }
        }
        item {
            SectionCard("Curiosity capture") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(capture, vm::onCaptureChange, placeholder = { Text("I want to learn about...") }, modifier = Modifier.weight(1f), singleLine = true)
                    IconButton(onClick = vm::capture, enabled = capture.isNotBlank()) { Icon(Icons.Outlined.Lightbulb, "Save to inbox") }
                }
            }
        }
    }

    if (askMinutes) WhatToDoDialog(t.busy, t.suggestion, t.suggestionAsked, onAsk = vm::askWhatToDo, onLog = { b -> askMinutes = false; vm.openLog(b) }, onClose = { askMinutes = false; vm.clearSuggestion() })
    if (quickLog) {
        val targets = vm.quickLogTargets()
        AlertDialog(
            onDismissRequest = { quickLog = false },
            title = { Text("Log something") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (targets.isEmpty()) Text("No skills to log yet.")
                    targets.forEach { tg ->
                        Text(tg.title, modifier = Modifier.fillMaxWidth().clickable { quickLog = false; vm.openQuickLog(tg) }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { quickLog = false }) { Text("Close") } },
        )
    }
    t.logTarget?.let { LogDialog(it, t.busy, onDismiss = vm::dismissLog, onSave = vm::submitLog) }
    t.recall?.let { RecallDialog(it, onAnswer = vm::answerRecall, onDismiss = vm::dismissRecall) }
    t.alternatives?.let { alts ->
        val w = d.weakness
        AlertDialog(
            onDismissRequest = vm::dismissAlternatives,
            title = { Text("Pick something different") },
            text = {
                Column {
                    alts.forEach { a ->
                        Text("${a.title} (${a.estMinutes} min)", modifier = Modifier.fillMaxWidth().clickable { if (w != null) vm.pickAlternative(w, a) }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissAlternatives) { Text("Cancel") } },
        )
    }
    if (t.noTimeChoice) {
        val w = d.weakness
        AlertDialog(
            onDismissRequest = vm::dismissNoTime,
            title = { Text("Short on time") },
            text = { Text("Use the 10-minute version from now on, or keep this task for weekends?") },
            confirmButton = { Button(onClick = { w?.let { vm.applyNoTime(it, weekend = false) } }) { Text("Tiny version") } },
            dismissButton = { OutlinedButton(onClick = { w?.let { vm.applyNoTime(it, weekend = true) } }) { Text("Weekends") } },
        )
    }
    if (t.otherText) {
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.showOther(false) },
            title = { Text("What's in the way?") },
            text = { OutlinedTextField(text, { text = it.take(300) }, minLines = 2) },
            confirmButton = { Button(onClick = { d.weakness?.let { vm.weaknessOther(it, text) } }) { Text("Save and open skill") } },
            dismissButton = { TextButton(onClick = { vm.showOther(false) }) { Text("Cancel") } },
        )
    }
    t.confirmDrop?.let {
        ConfirmDialog("Drop this skill?", "It leaves your plan. Logs and proofs are kept. You can reactivate it later from Skills.", "Drop", onConfirm = { vm.confirmDrop(d.weakness?.takeIf { w -> w.skillId == it }) }, onDismiss = vm::dismissDrop, destructive = true)
    }
    if (t.g2ForSkill != null) {
        G2Dialog(t.g2Active.map { it.id to it.name }, onPause = vm::g2PauseAndActivate, onCancel = vm::g2Cancel)
    }
}

@Composable
private fun HeaderCard(d: TodayData) {
    val st = d.stats
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Level ${st?.overall?.level ?: 1}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(10.dp))
                val toNext = st?.overall?.pointsToNext ?: 1
                Text("$toNext pt${if (toNext == 1) "" else "s"} to next level", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            val c = st?.consistency
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, label ->
                    val on = c?.days?.getOrNull(i)?.second == true
                    Box(
                        Modifier.size(30.dp).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.width(6.dp))
                Text("${c?.loggedDays ?: 0}/${c?.target ?: 6} days", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
            }
            Text("One logged item makes a day count. Tiny versions count too.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun EnergyRow(d: TodayData, onLog: (Int) -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Energy now", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            (1..5).forEach { lvl ->
                val selected = d.energyToday == lvl
                Surface(
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(horizontal = 3.dp).size(36.dp).clickable { onLog(lvl) },
                ) { Box(contentAlignment = Alignment.Center) { Text("$lvl", color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
        }
        val e = d.energy
        val hint = when {
            e == null -> null
            e.peaks != null -> "Your peak: ${e.peaks.joinToString { it.label }}. Bigger tasks go there."
            else -> "${(EnergyAnalyzer.MIN_LOGS - e.logCount).coerceAtLeast(0)} more taps until the planner uses your energy pattern."
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun BlockCard(b: BlockUi, onLog: () -> Unit, onTiny: () -> Unit) {
    val blk = b.block
    val time = blk.startMinute?.let { "${minuteToLabel(it)}-${minuteToLabel(it + blk.minutes)}" } ?: "Anytime"
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (b.done) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (blk.minimumDay) Pill("Minimum day")
                else if (blk.isTiny) Pill("Tiny")
                if (blk.kind == CandidateKind.RECALL) { Spacer(Modifier.width(4.dp)); Pill("Recall", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) }
                if (blk.kind == CandidateKind.WILDCARD) { Spacer(Modifier.width(4.dp)); Pill("Wildcard") }
                if (b.done) { Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.CheckCircle, "Done", tint = MaterialTheme.colorScheme.primary) }
            }
            Text(
                blk.title,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (b.done) TextDecoration.LineThrough else null,
            )
            Text("${blk.skillName} · ${blk.minutes} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(blk.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!b.done) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onLog) { Text(if (blk.kind == CandidateKind.RECALL) "Start check" else if (b.skipped) "Log again" else "Log") }
                    if (!blk.isTiny && blk.kind != CandidateKind.RECALL) OutlinedButton(onClick = onTiny) { Text("Do tiny version") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeaknessCard(w: WeaknessEventEntity, ai: AiAvailability, vm: TodayViewModel) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.secondary)
            Text("  ${w.text}", style = MaterialTheme.typography.titleSmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (w.kind == QuestionKind.BLOCKER.name) {
                BlockerAnswer.entries.forEach { a ->
                    AssistChip(onClick = {
                        when (a) {
                            BlockerAnswer.TOO_HARD -> vm.weaknessTooHard(w)
                            BlockerAnswer.BORING -> vm.weaknessBoring(w)
                            BlockerAnswer.NO_TIME -> vm.weaknessNoTime()
                            BlockerAnswer.UNCLEAR -> vm.weaknessUnclear(w, ai.canCall)
                            BlockerAnswer.LOST_INTEREST -> vm.weaknessLostInterest(w)
                            BlockerAnswer.OTHER -> vm.showOther(true)
                            BlockerAnswer.IGNORE -> vm.weaknessIgnore(w)
                        }
                    }, label = { Text(if (a == BlockerAnswer.UNCLEAR && !ai.canCall) "Unclear (queue AI)" else a.label) })
                }
            } else {
                KeepAnswer.entries.forEach { a ->
                    AssistChip(onClick = {
                        when (a) {
                            KeepAnswer.KEEP -> vm.weaknessKeep(w)
                            KeepAnswer.PAUSE -> vm.weaknessPause(w)
                            KeepAnswer.DROP -> vm.askDrop(w.skillId)
                            KeepAnswer.IGNORE -> vm.weaknessIgnore(w)
                        }
                    }, label = { Text(a.label) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatToDoDialog(busy: Boolean, suggestion: PlanBlock?, asked: Boolean, onAsk: (Int) -> Unit, onLog: (PlanBlock) -> Unit, onClose: () -> Unit) {
    var minutes by rememberSaveable { mutableStateOf(25) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("What should I do?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("How many minutes do you have?")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 15, 25, 45, 60, 90).forEach { m -> FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m") }) }
                }
                NumberField("Minutes", minutes, { minutes = it }, range = 1..600)
                if (asked) {
                    if (suggestion == null) Text("No task available yet. Add a skill first.")
                    else SectionCard(suggestion.title) {
                        Text("${suggestion.skillName} · ${suggestion.minutes} min${if (suggestion.isTiny) " · tiny" else ""}", style = MaterialTheme.typography.bodySmall)
                        Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (suggestion != null) Button(onClick = { onLog(suggestion) }) { Text("Log it") }
            else Button(onClick = { onAsk(minutes) }, enabled = !busy) { Text("Suggest") }
        },
        dismissButton = {
            Row {
                if (suggestion != null) TextButton(onClick = { onAsk(minutes) }) { Text("Again") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LogDialog(
    target: LogTarget,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (LogTarget, QuestState, Boolean, Int, FrictionTag?, String, Uri?, Boolean) -> Unit,
) {
    var state by rememberSaveable { mutableStateOf(QuestState.DONE) }
    var tiny by rememberSaveable { mutableStateOf(target.tiny) }
    var minutes by rememberSaveable { mutableStateOf(target.minutes) }
    var tag by rememberSaveable { mutableStateOf<FrictionTag?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var photo by rememberSaveable { mutableStateOf<Uri?>(null) }
    var capstoneDone by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { photo = it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(target.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    QuestState.entries.forEachIndexed { i, s ->
                        SegmentedButton(selected = state == s, onClick = { state = s }, shape = SegmentedButtonDefaults.itemShape(i, QuestState.entries.size)) {
                            Text(s.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
                if (state != QuestState.SKIPPED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tiny version", modifier = Modifier.weight(1f))
                        Switch(tiny, { tiny = it })
                    }
                    NumberField("Minutes spent", minutes, { minutes = it }, Modifier.fillMaxWidth(), 0..600)
                }
                Text("How did it feel? (optional)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FrictionTag.entries.forEach { f ->
                        FilterChip(selected = tag == f, onClick = { tag = if (tag == f) null else f }, label = { Text(f.name.lowercase().replace('_', ' ')) })
                    }
                }
                OutlinedTextField(note, { note = it.take(300) }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
                if (state != QuestState.SKIPPED) {
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Outlined.AddAPhoto, null)
                        Text(if (photo == null) "  Add proof photo" else "  Photo attached")
                    }
                    Text("Proof photos are memory aids only: never scored, never sent anywhere.", style = MaterialTheme.typography.bodySmall)
                }
                if (target.isCapstone && state == QuestState.DONE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(capstoneDone, { capstoneDone = it })
                        Text("I finished the capstone")
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(target, state, tiny, if (state == QuestState.SKIPPED) 0 else minutes, tag, note, photo, capstoneDone) }, enabled = !busy) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RecallDialog(p: RecallPrompt, onAnswer: (String, RecallResult, String?) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(p.checkId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (p.mode == RecallMode.TEACH_BACK) "Teach-back: ${p.miniSkillName}" else "Recall: ${p.miniSkillName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (p.mode == RecallMode.TEACH_BACK) {
                    Text(p.teachBackPrompt.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(text, { text = it.take(1500) }, minLines = 4, label = { Text("Your explanation") }, modifier = Modifier.fillMaxWidth())
                    Text("Stored only on this phone. Never graded.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(p.question.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    Text("Answer from memory first, then be honest with yourself.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Could you do it?", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAnswer(p.checkId, RecallResult.YES, text) }) { Text("Yes") }
                    OutlinedButton(onClick = { onAnswer(p.checkId, RecallResult.SHAKY, text) }) { Text("Shaky") }
                    OutlinedButton(onClick = { onAnswer(p.checkId, RecallResult.NO, text) }) { Text("No") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}

/** Gate G2: a 4th active skill needs another one paused first. */
@Composable
fun G2Dialog(active: List<Pair<String, String>>, onPause: (String) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("3 skills are already active") },
        text = {
            Column {
                Text("Pause one to make room, or cancel.")
                Spacer(Modifier.height(8.dp))
                active.forEach { (id, name) ->
                    Text("Pause $name", color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().clickable { onPause(id) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
