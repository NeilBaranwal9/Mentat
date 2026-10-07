package com.mentat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mentat.data.db.ActivityEntity
import com.mentat.data.db.JsonLists
import com.mentat.data.repo.decodeResources
import com.mentat.data.repo.decodeStyles
import com.mentat.domain.model.SkillStatus
import com.mentat.ui.components.BusyButton
import com.mentat.ui.components.ConfirmDialog
import com.mentat.ui.components.EmptyState
import com.mentat.ui.components.ErrorCard
import com.mentat.ui.components.InfoCard
import com.mentat.ui.components.LoadingState
import com.mentat.ui.components.Pill
import com.mentat.ui.components.SectionCard
import com.mentat.ui.components.SnackbarEffect
import com.mentat.ui.components.SubScreen
import com.mentat.ui.components.TabScreen
import com.mentat.ui.components.rememberSnackbar
import com.mentat.ui.nav.Nav
import com.mentat.ui.nav.Routes
import com.mentat.vm.AiAvailability
import com.mentat.vm.SkillCreateViewModel
import com.mentat.vm.SkillDetailViewModel
import com.mentat.vm.SkillsViewModel

@Composable
fun StatusPill(s: SkillStatus) {
    val (bg, fg) = when (s) {
        SkillStatus.ACTIVE -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        SkillStatus.SAMPLING -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        SkillStatus.PAUSED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        SkillStatus.DROPPED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        SkillStatus.DONE -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    Pill(s.name.lowercase().replaceFirstChar { it.uppercase() }, bg, fg)
}

@Composable
fun SkillsScreen(nav: Nav, vm: SkillsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val seeds by vm.seeds.collectAsStateWithLifecycle()
    val snack = rememberSnackbar()
    var showSeeds by rememberSaveable { mutableStateOf(false) }
    SnackbarEffect(snack, msg, vm::consumeMessage)
    TabScreen(
        title = "Skills",
        snackbar = snack,
        floating = { ExtendedFloatingActionButton(onClick = { nav.to(Routes.create()) }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New skill") }) },
    ) {
        item { Text("${ui.activeCount}/3 active. New skills start with a short sampler.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val claims = ui.stats?.claims.orEmpty()
        if (claims.isNotEmpty()) {
            item {
                SectionCard("Skills you said you have") {
                    claims.forEach { (name, level, verified) ->
                        Text("$name: $level/10 self-estimate · ${if (verified) "verified by a recall check" else "unverified claim"}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Self-estimates never add to your level.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!ui.loaded) item { LoadingState() }
        else if (ui.trees.isEmpty()) {
            item {
                EmptyState(Icons.Outlined.School, "No skills yet", "Create one with AI or by hand, or start from a hand-made path.") {
                    OutlinedButton(onClick = { showSeeds = true }) { Text("Starter paths") }
                }
            }
        }
        ui.trees.forEach { t ->
            item(key = t.skill.id) {
                val path = t.miniSkills.filter { !it.miniSkill.isSampler }
                val done = path.count { m -> m.activities.any { it.selected } && m.activities.filter { it.selected }.all { it.completed } }
                val lvl = ui.stats?.perSkill?.get(t.skill.id)?.level ?: 1
                Card(
                    onClick = { nav.to(Routes.skill(t.skill.id)) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.skill.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusPill(t.skill.status)
                        }
                        Text(
                            "Level $lvl · priority ${t.skill.priority} · $done/${path.size} mini-skills${if (t.skill.capstoneDone) " · capstone done" else ""}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (path.isNotEmpty()) LinearProgressIndicator(progress = { done / path.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (ui.trees.isNotEmpty()) item { TextButton(onClick = { showSeeds = true }) { Text("Add a starter path") } }
    }
    if (showSeeds) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSeeds = false },
            title = { Text("Starter paths") },
            text = {
                Column {
                    seeds.forEach { bp ->
                        Text(
                            "${bp.skillName} (${bp.totalHours.toInt()} h)",
                            modifier = Modifier.fillMaxWidth().clickable { vm.addSeed(bp); showSeeds = false }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSeeds = false }) { Text("Close") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillDetailScreen(nav: Nav, ai: AiAvailability, vm: SkillDetailViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snack = rememberSnackbar()
    SnackbarEffect(snack, ui.message ?: ui.error, vm::consume)
    LaunchedEffect(ui.openProposal) { ui.openProposal?.let { nav.to(Routes.proposal(it)); vm.consume() } }
    val tree = ui.tree
    SubScreen(
        title = tree?.skill?.name ?: "Skill",
        onBack = nav::back,
        snackbar = snack,
        actions = {
            if (tree != null) IconButton(onClick = { nav.to(Routes.editor(id = tree.skill.id)) }) { Icon(Icons.Outlined.Edit, "Edit") }
        },
    ) {
        if (!ui.loaded) { item { LoadingState() }; return@SubScreen }
        if (tree == null) { item { ErrorCard("This skill no longer exists.") }; return@SubScreen }
        val s = tree.skill
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(s.status)
                    Spacer(Modifier.width(8.dp))
                    Text(listOfNotNull(s.domain, "${s.totalHours.toInt()} h to basic competence", s.source.name.lowercase()).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                var prio by rememberSaveable(s.priority) { mutableFloatStateOf(s.priority.toFloat()) }
                Text("Priority ${prio.toInt()}", style = MaterialTheme.typography.labelLarge)
                Slider(value = prio, onValueChange = { prio = it }, onValueChangeFinished = { vm.setPriority(prio.toInt()) }, valueRange = 1f..5f, steps = 3)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (s.status) {
                        SkillStatus.SAMPLING -> { Button(onClick = vm::activate) { Text("Keep (activate)") }; OutlinedButton(onClick = { vm.setStatus(SkillStatus.PAUSED) }) { Text("Pause") } }
                        SkillStatus.ACTIVE -> { OutlinedButton(onClick = { vm.setStatus(SkillStatus.PAUSED) }) { Text("Pause") }; OutlinedButton(onClick = { vm.setStatus(SkillStatus.DONE) }) { Text("Mark done") } }
                        SkillStatus.PAUSED -> Button(onClick = vm::activate) { Text("Resume") }
                        SkillStatus.DROPPED, SkillStatus.DONE -> Button(onClick = vm::activate) { Text("Reactivate") }
                    }
                    if (s.status != SkillStatus.DROPPED) TextButton(onClick = { vm.askConfirm("drop") }) { Text("Drop") }
                    TextButton(onClick = { vm.askConfirm("delete") }) { Text("Delete") }
                }
            }
        }
        val sampler = tree.miniSkills.firstOrNull { it.miniSkill.isSampler }
        sampler?.activities?.firstOrNull()?.let { a ->
            item {
                SectionCard("Sampler · ${a.estMinutes} min") {
                    Text(a.title, style = MaterialTheme.typography.bodyLarge)
                    JsonLists.decode(a.stepsJson).forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium) }
                    Text("Tiny version: ${a.tinyTitle} (${a.tinyMinutes} min)", style = MaterialTheme.typography.bodySmall)
                    if (a.completed) Text("Sampler done.", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        val styles = decodeStyles(s.stylesJson)
        if (styles.isNotEmpty()) {
            item {
                SectionCard("Ways to start") {
                    styles.forEach { st ->
                        Text("${st.name} · difficulty ${st.difficulty}${if (st.minCostInr > 0) " · from ₹${st.minCostInr}" else " · free"}", style = MaterialTheme.typography.titleSmall)
                        Text(st.oneLine, style = MaterialTheme.typography.bodyMedium)
                        Text(st.whyStartHere, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val path = tree.miniSkills.filter { !it.miniSkill.isSampler }.sortedBy { it.miniSkill.order }
        if (path.isEmpty()) {
            item {
                InfoCard("No learning path yet.", "Add path") { nav.to(Routes.editor(id = s.id)) }
            }
        }
        path.forEachIndexed { i, m ->
            item(key = m.miniSkill.id) {
                val complete = m.activities.any { it.selected } && m.activities.filter { it.selected }.all { it.completed }
                SectionCard(
                    title = "${i + 1}. ${m.miniSkill.name}",
                    trailing = { if (complete) Icon(Icons.Outlined.CheckCircle, "Complete", tint = MaterialTheme.colorScheme.primary) },
                ) {
                    val prereqs = JsonLists.decode(m.miniSkill.prerequisiteIdsJson).mapNotNull { id -> path.firstOrNull { it.miniSkill.id == id }?.miniSkill?.name }
                    Text("~${m.miniSkill.estHours} h" + if (prereqs.isNotEmpty()) " · after ${prereqs.joinToString()}" else "", style = MaterialTheme.typography.bodySmall)
                    m.activities.sortedBy { it.order }.forEach { a -> ActivityRow(a, a.id == s.pinnedActivityId, ai, vm) }
                    var showQ by rememberSaveable { mutableStateOf(false) }
                    TextButton(onClick = { showQ = !showQ }) { Text(if (showQ) "Hide recall questions" else "Recall questions (${m.questions.size})") }
                    if (showQ) {
                        m.questions.sortedBy { it.order }.forEach { q -> Text("• ${q.text}", style = MaterialTheme.typography.bodyMedium) }
                        if (m.miniSkill.teachBackPrompt.isNotBlank()) Text("Teach-back: ${m.miniSkill.teachBackPrompt}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (!s.capstoneTitle.isNullOrBlank()) {
            item {
                SectionCard("Capstone") {
                    Text(s.capstoneTitle, style = MaterialTheme.typography.titleSmall)
                    s.capstoneDescription?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text("~${s.capstoneHours} h", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Capstone finished", modifier = Modifier.weight(1f))
                        Switch(s.capstoneDone, vm::setCapstoneDone)
                    }
                }
            }
        }
        val resources = decodeResources(s.resourcesJson)
        if (resources.isNotEmpty()) {
            item {
                SectionCard("Resources") {
                    resources.forEach { r ->
                        Text("${r.title} (${r.kind}) · ₹${r.estCostInr}", style = MaterialTheme.typography.bodyMedium)
                        r.freeAlternative?.let { Text("Free option: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        val assumptions = JsonLists.decode(s.assumptionsJson)
        if (assumptions.isNotEmpty()) item { SectionCard("Assumptions") { assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } } }
    }

    when (ui.confirm) {
        "drop" -> ConfirmDialog("Drop ${tree?.skill?.name}?", "It leaves your plan. Logs and proofs are kept.", "Drop", { vm.setStatus(SkillStatus.DROPPED) }, vm::dismissConfirm, destructive = true)
        "delete" -> ConfirmDialog(
            "Delete ${tree?.skill?.name}?", "This permanently deletes the skill with all its logs, recall checks and proof entries. This can't be undone.",
            "Delete forever", { vm.delete { nav.back() } }, vm::dismissConfirm, destructive = true,
        )
    }
    if (ui.g2Active.isNotEmpty()) G2Dialog(ui.g2Active.map { it.id to it.name }, onPause = vm::g2Swap, onCancel = vm::g2Cancel)
}

@Composable
private fun ActivityRow(a: ActivityEntity, pinned: Boolean, ai: AiAvailability, vm: SkillDetailViewModel) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Column {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = a.selected, onCheckedChange = { vm.toggleSelected(a) })
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (a.completed) Icon(Icons.Outlined.CheckCircle, "Done", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    else Icon(Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(6.dp))
                    Text(a.title, style = MaterialTheme.typography.bodyLarge)
                    if (pinned) { Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.PushPin, "Next", Modifier.size(14.dp)) }
                }
                Text(
                    "${a.estMinutes} min · tiny: ${a.tinyTitle} (${a.tinyMinutes} min)" +
                        (if (a.preferTiny) " · always tiny" else "") + (if (a.weekendOnly) " · weekends" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                JsonLists.decode(a.stepsJson).forEachIndexed { i, st -> Text("${i + 1}. $st", style = MaterialTheme.typography.bodySmall) }
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Do this next") }, onClick = { menu = false; vm.pin(if (pinned) null else a) })
                DropdownMenuItem(text = { Text(if (a.completed) "Mark not done" else "Mark done") }, onClick = { menu = false; vm.markDone(a, !a.completed) })
                DropdownMenuItem(text = { Text(if (a.preferTiny) "Use full version" else "Always use tiny version") }, onClick = { menu = false; vm.toggleTiny(a) })
                DropdownMenuItem(text = { Text(if (a.weekendOnly) "Any day" else "Weekends only") }, onClick = { menu = false; vm.toggleWeekend(a) })
                DropdownMenuItem(
                    text = { Text(if (ai.canCall) "Rewrite with AI (unclear)" else "Rewrite with AI (queue)") },
                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                    onClick = { menu = false; vm.rewrite(a, ai.canCall) },
                )
            }
        }
    }
}

@Composable
fun CreateSkillScreen(nav: Nav, ai: AiAvailability, vm: SkillCreateViewModel = hiltViewModel()) {
    val name by vm.name.collectAsStateWithLifecycle()
    val constraints by vm.constraints.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.openProposal) { ui.openProposal?.let { nav.replace(Routes.proposal(it)); vm.consumeNavigation() } }
    SubScreen("New skill", onBack = nav::back) {
        item {
            Text("Say what you want to learn. The AI drafts the path once; you review and edit it before anything is saved.", style = MaterialTheme.typography.bodyMedium)
        }
        item { OutlinedTextField(name, vm::setName, label = { Text("I want to learn...") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item {
            OutlinedTextField(
                constraints, vm::setConstraints, label = { Text("Constraints (optional)") },
                placeholder = { Text("e.g. only a phone and pencil, 20 minutes a day") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
        }
        ui.error?.let { item { ErrorCard(it) } }
        if (ui.manualReason != null) {
            item {
                SectionCard("AI draft didn't pass the checks") {
                    Text("After 2 repair attempts the draft still broke these rules, so nothing was saved:", style = MaterialTheme.typography.bodyMedium)
                    ui.manualErrors.take(6).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { nav.replace(Routes.editor(name = name)) }) { Text("Fill in the manual form") }
                }
            }
        }
        if (ui.queued) item { InfoCard("Saved. Run it from Inbox > Pending AI when you're online.", "Inbox") { nav.tab(Routes.INBOX) } }
        item {
            when {
                ai.canCall -> BusyButton(if (ui.busy) "Drafting (can take ~20 s)..." else "Draft with AI", ui.busy, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = vm::generate)
                !ai.hasKey -> InfoCard("Add a Groq key in Settings to draft with AI.", "Settings") { nav.to(Routes.SETTINGS) }
                else -> OutlinedButton(onClick = vm::queue, enabled = name.isNotBlank() && !ui.queued, modifier = Modifier.fillMaxWidth()) { Text("Offline: save to run later") }
            }
        }
        item {
            OutlinedButton(onClick = { nav.replace(Routes.editor(name = name)) }, modifier = Modifier.fillMaxWidth()) { Text("Enter the path by hand") }
        }
    }
}
