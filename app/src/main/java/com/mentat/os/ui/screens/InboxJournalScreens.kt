package com.mentat.os.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.mentat.os.data.db.CuriosityEntity
import com.mentat.os.ui.components.ConfirmDialog
import com.mentat.os.ui.components.EmptyState
import com.mentat.os.ui.components.LoadingState
import com.mentat.os.ui.components.SectionCard
import com.mentat.os.ui.components.SnackbarEffect
import com.mentat.os.ui.components.TabScreen
import com.mentat.os.ui.components.rememberSnackbar
import com.mentat.os.ui.nav.Nav
import com.mentat.os.ui.nav.Routes
import com.mentat.os.vm.AiAvailability
import com.mentat.os.vm.InboxViewModel
import com.mentat.os.vm.JournalViewModel
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InboxScreen(nav: Nav, ai: AiAvailability, vm: InboxViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val t by vm.transient.collectAsStateWithLifecycle()
    val capture by vm.captureText.collectAsStateWithLifecycle()
    val snack = rememberSnackbar()
    var review by rememberSaveable { mutableStateOf(false) }
    var renaming by androidx.compose.runtime.remember { mutableStateOf<CuriosityEntity?>(null) }
    SnackbarEffect(snack, t.message ?: t.error, vm::consume)
    LaunchedEffect(t.openProposal, t.openSkill) {
        t.openProposal?.let { nav.to(Routes.proposal(it)) }
        t.openSkill?.let { nav.to(Routes.skill(it)) }
        if (t.openProposal != null || t.openSkill != null) vm.consume()
    }
    TabScreen(
        "Curiosity inbox",
        snackbar = snack,
        actions = { TextButton(onClick = { review = !review }) { Text(if (review) "Done" else "Weekly review") } },
    ) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(capture, vm::onCaptureChange, placeholder = { Text("Quick capture an idea") }, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::capture, enabled = capture.isNotBlank()) { Icon(Icons.Outlined.Lightbulb, "Save") }
                }
            }
        }
        if (review) item { Text("For each idea: promote it to a skill, try a sampler, or delete it.", style = MaterialTheme.typography.bodyMedium) }
        if (!ui.loaded) item { LoadingState() }
        else if (ui.items.isEmpty()) item { EmptyState(Icons.Outlined.Inbox, "Inbox is empty", "Capture anything you're curious about. Sort it during the weekly review.") }
        ui.items.forEach { c ->
            item(key = c.id) {
                SectionCard {
                    Text(c.normalizedTitle ?: c.rawText, style = MaterialTheme.typography.titleSmall)
                    if (c.normalizedTitle != null && c.normalizedTitle != c.rawText) Text(c.rawText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    c.hook?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text(listOfNotNull(c.domain, c.samplerMinutes?.let { "$it-min sampler" }, "added ${c.created}").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (review) {
                            AssistChip(onClick = { nav.to(Routes.create(name = c.normalizedTitle ?: c.rawText.take(60), curiosity = c.id)) }, label = { Text("Promote") })
                            AssistChip(onClick = { vm.sample(c) }, label = { Text("Sample") })
                            AssistChip(onClick = { vm.delete(c) }, label = { Text("Delete") })
                        } else {
                            if (c.normalizedTitle == null) {
                                AssistChip(
                                    onClick = { vm.normalize(c, ai.canCall) },
                                    leadingIcon = { if (t.busyId == c.id) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)) },
                                    label = { Text(if (ai.canCall) "Tidy with AI" else "Tidy later") },
                                    enabled = t.busyId == null && ai.hasKey,
                                )
                            }
                            AssistChip(onClick = { renaming = c }, label = { Text("Rename") })
                        }
                    }
                }
            }
        }
        if (ui.pending.isNotEmpty()) {
            item { Text("Pending AI (run only when you tap)", style = MaterialTheme.typography.titleMedium) }
            ui.pending.forEach { p ->
                item(key = p.id) {
                    SectionCard {
                        Text(p.label, style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.runPending(p) }, enabled = ai.canCall && t.busyId == null) { Text(if (t.busyId == p.id) "Running..." else "Run now") }
                            TextButton(onClick = { vm.discardPending(p) }) { Text("Discard") }
                        }
                    }
                }
            }
        }
        if (ui.items.isNotEmpty()) item { TextButton(onClick = { vm.askClear(true) }) { Text("Clear inbox") } }
    }
    renaming?.let { c ->
        var text by rememberSaveable(c.id) { mutableStateOf(c.normalizedTitle ?: c.rawText) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(text, { text = it.take(80) }, singleLine = true) },
            confirmButton = { Button(onClick = { vm.rename(c, text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    if (t.confirmClear) ConfirmDialog("Clear the inbox?", "All ideas in the inbox are deleted.", "Clear", vm::clear, { vm.askClear(false) }, destructive = true)
}

@Composable
fun JournalScreen(vm: JournalViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val line by vm.draftLine.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(vm.today) { vm.refreshToday() } // keyed by date; deterministic template, no network
    TabScreen("Journal") {
        item {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Daily pages") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Proof timeline") })
            }
        }
        if (!ui.loaded) { item { LoadingState() }; return@TabScreen }
        if (tab == 0) {
            item {
                SectionCard("Add a line to today") {
                    OutlinedTextField(line, vm::onLineChange, placeholder = { Text("One sentence about today (optional)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { vm.saveLine(vm.today) }, enabled = line.isNotBlank()) { Text("Save line") }
                }
            }
            if (ui.entries.isEmpty()) item { EmptyState(Icons.Outlined.AutoStories, "No pages yet", "A page is written automatically from what you log. No AI, just a fixed template.") }
            ui.entries.forEach { e ->
                item(key = e.date.toString()) {
                    SectionCard { Text(e.body, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        } else {
            ui.showAndTell?.let { pick ->
                item {
                    SectionCard("This week's show-and-tell") {
                        pick.proofPath?.let { name ->
                            AsyncImage(
                                model = vm.proofFile(name), contentDescription = pick.title, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)),
                            )
                        }
                        Text("${pick.title} · ${pick.actualMinutes} min · ${pick.date}", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { vm.shareIntent(pick)?.let { runCatching { ctx.startActivity(it) } } }) { Text("Share") }
                    }
                }
            }
            if (ui.timeline.isEmpty()) item { EmptyState(Icons.Outlined.PhotoLibrary, "No proofs yet", "Add a photo when you log something. Photos stay on your phone and are never scored.") }
            ui.timeline.forEach { (month, logs) ->
                item(key = month.toString()) {
                    SectionCard(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))) {
                        logs.chunked(3).forEach { rowLogs ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowLogs.forEach { l ->
                                    AsyncImage(
                                        model = vm.proofFile(l.proofPath!!), contentDescription = l.title, contentScale = ContentScale.Crop,
                                        modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                                    )
                                }
                                repeat(3 - rowLogs.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
