package com.mentat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mentat.data.repo.ProposalKind
import com.mentat.domain.model.ProposalStatus
import com.mentat.ui.components.EmptyState
import com.mentat.ui.components.ErrorCard
import com.mentat.ui.components.InfoCard
import com.mentat.ui.components.LoadingState
import com.mentat.ui.components.NumberField
import com.mentat.ui.components.Pill
import com.mentat.ui.components.SectionCard
import com.mentat.ui.components.SubScreen
import com.mentat.ui.nav.Nav
import com.mentat.ui.nav.Routes
import com.mentat.vm.AiAvailability
import com.mentat.vm.AppViewModel
import com.mentat.vm.ProposalNav
import com.mentat.vm.ProposalViewModel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val agentNames = mapOf("A2" to "Skill Architect", "A3" to "Goal Mapper", "A4" to "Task Rewriter", "A5" to "Command Parser", "A6" to "Curiosity Normalizer")

@Composable
fun ProposalsListScreen(nav: Nav, app: AppViewModel) {
    val list by app.pendingProposals.collectAsStateWithLifecycle()
    SubScreen("Waiting for your OK", onBack = nav::back) {
        if (list.isEmpty()) item { EmptyState(Icons.Outlined.CheckCircle, "All clear", "No AI suggestions are waiting.") }
        list.forEach { p ->
            item(key = p.id) {
                Card(onClick = { nav.to(Routes.proposal(p.id)) }) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${agentNames[p.producedBy] ?: p.producedBy}: ${p.kind.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.titleSmall)
                        Text("${p.createdAt.toLocalDate()} ${"%02d:%02d".format(p.createdAt.hour, p.createdAt.minute)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun ProposalScreen(nav: Nav, ai: AiAvailability, vm: ProposalViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.nav) {
        when (val n = ui.nav) {
            is ProposalNav.Skill -> nav.replace(Routes.skill(n.id))
            is ProposalNav.Goal -> nav.replace(Routes.goal(n.id))
            is ProposalNav.Proposal -> nav.replace(Routes.proposal(n.id))
            ProposalNav.Back -> nav.back()
            null -> Unit
        }
        if (ui.nav != null) vm.consumeNav()
    }
    val p = ui.proposal
    SubScreen("Review AI suggestion", onBack = nav::back) {
        if (!ui.loaded) { item { LoadingState() }; return@SubScreen }
        ui.error?.let { item { ErrorCard(it, vm::consumeError) } }
        if (p == null) return@SubScreen
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("From the ${agentNames[p.producedBy] ?: p.producedBy}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Pill(p.status.name.lowercase())
            }
        }
        if (p.status != ProposalStatus.PENDING) {
            item { InfoCard("This suggestion was already ${p.status.name.lowercase()}.") }
        }
        item { Text("Nothing changes until you accept. You can also edit or reject.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (ui.validation.isNotEmpty()) item { ErrorCard(ui.validation.joinToString("\n") { "• $it" }) }
        when (p.kind) {
            ProposalKind.SKILL_BLUEPRINT -> blueprint(ui, vm, nav)
            ProposalKind.COMMANDS -> commands(ui, vm, ai)
            ProposalKind.CURIOSITY -> curiosity(ui, vm)
            ProposalKind.GOAL_MAP -> goal(ui, vm)
            ProposalKind.TASK_REWRITE -> rewrite(ui, vm)
        }
    }
    ui.costGate?.let { (cost, remaining) ->
        AlertDialog(
            onDismissRequest = vm::dismissCostGate,
            title = { Text("Over your monthly budget") },
            text = { Text("The listed resources cost about ₹$cost, but ₹$remaining is left this month. Follow the free alternatives, or override?") },
            confirmButton = { Button(onClick = { vm.acceptBlueprint(freePath = true) }) { Text("Free path") } },
            dismissButton = { TextButton(onClick = { vm.acceptBlueprint(freePath = false) }) { Text("Override") } },
        )
    }
}

@Composable
private fun Decision(pending: Boolean, busy: Boolean, onAccept: () -> Unit, onReject: () -> Unit, onEdit: (() -> Unit)? = null, acceptLabel: String = "Accept") {
    if (!pending) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onAccept, enabled = !busy) { Text(acceptLabel) }
        if (onEdit != null) OutlinedButton(onClick = onEdit, enabled = !busy) { Text("Edit") }
        TextButton(onClick = onReject, enabled = !busy) { Text("Reject") }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.blueprint(ui: com.mentat.vm.ProposalUi, vm: ProposalViewModel, nav: Nav) {
    val bp = ui.blueprint ?: return
    val pending = ui.proposal?.status == ProposalStatus.PENDING
    item {
        SectionCard(bp.skillName) {
            Text("${bp.domain} · about ${bp.totalHours} h to basic competence", style = MaterialTheme.typography.bodyMedium)
            Text("Sampler: ${bp.samplerSession.title} (${bp.samplerSession.minutes} min; tiny: ${bp.samplerSession.tinyTitle}, ${bp.samplerSession.tinyMinutes} min)", style = MaterialTheme.typography.bodySmall)
        }
    }
    item {
        SectionCard("Ways to start") {
            bp.styles.forEach { s -> Text("• ${s.name}: ${s.oneLine}", style = MaterialTheme.typography.bodyMedium) }
        }
    }
    bp.miniSkills.sortedBy { it.order }.forEachIndexed { i, m ->
        item {
            SectionCard("${i + 1}. ${m.name} (~${m.estHours} h)") {
                m.activities.forEach { a -> Text("• ${a.title} (${a.estMinutes} min) · tiny: ${a.tinyTitle} (${a.tinyMinutes} min)", style = MaterialTheme.typography.bodyMedium) }
                Text("${m.recallQuestions.size} recall questions · teach-back: ${m.teachBackPrompt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    item {
        SectionCard("Capstone") {
            Text("${bp.capstone.title} (~${bp.capstone.estHours} h)", style = MaterialTheme.typography.titleSmall)
            Text(bp.capstone.description, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (bp.resources.isNotEmpty()) {
        item {
            SectionCard("Resources · total ₹${bp.resources.sumOf { it.estCostInr }}") {
                bp.resources.forEach { r -> Text("• ${r.title} (₹${r.estCostInr})${r.freeAlternative?.let { " · free: $it" } ?: ""}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    if (bp.assumptions.isNotEmpty()) item { SectionCard("Assumptions") { bp.assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } } }
    item { Decision(pending, ui.busy, onAccept = { vm.acceptBlueprint() }, onReject = vm::reject, onEdit = { nav.to(Routes.editor(proposal = vm.id)) }) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.commands(ui: com.mentat.vm.ProposalUi, vm: ProposalViewModel, ai: AiAvailability) {
    val pending = ui.proposal?.status == ProposalStatus.PENDING
    val anyLow = ui.commands.any { it.cmd.confidence == "low" && !it.edited }
    if (anyLow && pending) {
        item {
            SectionCard("Needs a detail (low confidence)") {
                Text(ui.clarifying ?: "Some details were missing. Answer, or edit the values below.", style = MaterialTheme.typography.bodyLarge)
                var answer by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(answer, { answer = it.take(200) }, label = { Text("Your answer") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.clarify(answer, ai.canCall) }, enabled = answer.isNotBlank() && !ui.busy) { Text("Send answer") }
            }
        }
    }
    ui.results?.let { r -> item { SectionCard("Applied") { r.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) } } } }
    ui.commands.forEachIndexed { i, row ->
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pending && ui.results == null) Checkbox(row.include, { vm.toggleCommand(i) }, enabled = row.cmd.type != "unknown" && (row.cmd.confidence != "low" || row.edited))
                    Text(row.cmd.type.replace('_', ' '), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Pill(row.cmd.confidence)
                }
                Text("\"${row.cmd.sourceQuote}\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (row.cmd.type == "unknown") Text("Not a command. Nothing will change.", style = MaterialTheme.typography.bodySmall)
                else if (pending && ui.results == null) {
                    paramKeys(row.cmd.type).forEach { k ->
                        val v = (row.cmd.params[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        OutlinedTextField(v, { vm.editParam(i, k, it) }, label = { Text(k) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                row.errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (ui.results == null) item { Decision(pending, ui.busy, onAccept = vm::acceptCommands, onReject = vm::reject, acceptLabel = "Apply selected") }
}

private fun paramKeys(type: String): List<String> = when (type) {
    "add_commitment" -> listOf("title", "kind", "start_iso", "end_iso", "recurrence")
    "add_exam_mode" -> listOf("start_date", "end_date")
    "set_sleep_window" -> listOf("start_time", "end_time")
    "add_curiosity" -> listOf("text")
    "pause_skill", "resume_skill" -> listOf("skill_id")
    "set_priority" -> listOf("skill_id", "priority")
    "request_task" -> listOf("minutes")
    "log_energy" -> listOf("level")
    else -> emptyList()
}

private fun androidx.compose.foundation.lazy.LazyListScope.curiosity(ui: com.mentat.vm.ProposalUi, vm: ProposalViewModel) {
    val c = ui.curiosity ?: return
    val pending = ui.proposal?.status == ProposalStatus.PENDING
    item {
        SectionCard {
            OutlinedTextField(c.normalizedTitle, vm::editCuriosityTitle, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = pending)
            Text("Domain: ${c.domain}", style = MaterialTheme.typography.bodyMedium)
            Text(c.hook, style = MaterialTheme.typography.bodyMedium)
            Text("Sampler: ${c.samplerMinutes} min", style = MaterialTheme.typography.bodySmall)
            if (c.relatedActiveSkills.isNotEmpty()) Text("Related to: ${c.relatedActiveSkills.joinToString()}", style = MaterialTheme.typography.bodySmall)
        }
    }
    ui.duplicateTitle?.let { dup ->
        item {
            SectionCard("Looks like a duplicate") {
                Text("Same topic as \"$dup\" in your inbox.", style = MaterialTheme.typography.bodyMedium)
                if (pending) OutlinedButton(onClick = { vm.acceptCuriosity(deleteAsDuplicate = true) }) { Text("Delete this capture") }
            }
        }
    }
    item { Decision(pending, ui.busy, onAccept = { vm.acceptCuriosity(deleteAsDuplicate = false) }, onReject = vm::reject) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.goal(ui: com.mentat.vm.ProposalUi, vm: ProposalViewModel) {
    val g = ui.goal ?: return
    val pending = ui.proposal?.status == ProposalStatus.PENDING
    item {
        SectionCard(g.goalTitle) {
            g.suggestedTargetDate?.let { Text("Target date: $it", style = MaterialTheme.typography.bodyMedium) }
            Text("Untick skills you don't want on the ladder.", style = MaterialTheme.typography.bodySmall)
        }
    }
    g.requiredSkills.forEachIndexed { i, s ->
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pending) Checkbox(i !in ui.goalExcluded, { vm.toggleGoalSkill(i) })
                    Text(s.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Pill(s.importance)
                }
                Text("~${s.estHours} h${if (s.dependsOn.isNotEmpty()) " · after ${s.dependsOn.joinToString()}" else ""}", style = MaterialTheme.typography.bodySmall)
                Text(s.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (g.assumptions.isNotEmpty()) item { SectionCard("Assumptions") { g.assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } } }
    item { Decision(pending, ui.busy, onAccept = vm::acceptGoal, onReject = vm::reject) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rewrite(ui: com.mentat.vm.ProposalUi, vm: ProposalViewModel) {
    val r = ui.rewrite ?: return
    val pending = ui.proposal?.status == ProposalStatus.PENDING
    ui.originalTitle?.let { item { SectionCard("Before") { Text("$it (${ui.originalEst} min)", style = MaterialTheme.typography.bodyMedium) } } }
    item {
        SectionCard("After") {
            OutlinedTextField(r.title, { v -> vm.editRewrite { it.copy(title = v.take(160)) } }, label = { Text("Task") }, enabled = pending, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                r.steps.joinToString("\n"), { v -> vm.editRewrite { it.copy(steps = v.split("\n")) } },
                label = { Text("Steps, one per line") }, enabled = pending, minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Minutes", r.estMinutes, { v -> vm.editRewrite { it.copy(estMinutes = v) } }, Modifier.weight(1f), 1..240)
                NumberField("Tiny min", r.tinyMinutes, { v -> vm.editRewrite { it.copy(tinyMinutes = v) } }, Modifier.weight(1f), 1..30)
            }
            OutlinedTextField(r.tinyTitle, { v -> vm.editRewrite { it.copy(tinyTitle = v.take(160)) } }, label = { Text("Tiny version") }, enabled = pending, modifier = Modifier.fillMaxWidth())
            Text("Self-checks (answer yes/no yourself):", style = MaterialTheme.typography.labelLarge)
            r.selfCheck.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
    item {
        Decision(pending, ui.busy, onAccept = {
            vm.editRewrite { it.copy(steps = it.steps.map(String::trim).filter(String::isNotEmpty)) }
            vm.acceptRewrite()
        }, onReject = vm::reject)
    }
}
