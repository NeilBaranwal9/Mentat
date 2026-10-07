package com.mentat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mentat.domain.model.ActivityDraft
import com.mentat.domain.model.MiniSkillDraft
import com.mentat.ui.components.BusyButton
import com.mentat.ui.components.DecimalField
import com.mentat.ui.components.ErrorCard
import com.mentat.ui.components.LoadingState
import com.mentat.ui.components.NumberField
import com.mentat.ui.components.SectionCard
import com.mentat.ui.components.SubScreen
import com.mentat.ui.nav.Nav
import com.mentat.ui.nav.Routes
import com.mentat.vm.SkillEditorViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillEditorScreen(nav: Nav, vm: SkillEditorViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val gate by vm.costGate.collectAsStateWithLifecycle()
    LaunchedEffect(ui.savedId) { ui.savedId?.let { nav.replace(Routes.skill(it)) } }
    val d = ui.draft
    SubScreen(
        title = ui.title,
        onBack = nav::back,
        actions = { TextButton(onClick = { vm.save() }, enabled = !ui.saving && ui.loaded) { Text("Save") } },
    ) {
        if (!ui.loaded) { item { LoadingState() }; return@SubScreen }
        if (ui.errors.isNotEmpty()) {
            item { ErrorCard("Fix these first:\n" + ui.errors.take(8).joinToString("\n") { "• $it" }) }
        }
        item {
            SectionCard("Basics") {
                OutlinedTextField(d.name, { v -> vm.update { it.copy(name = v.take(80)) } }, label = { Text("Skill name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.domain, { v -> vm.update { it.copy(domain = v.take(60)) } }, label = { Text("Domain (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            SectionCard("Sampler session (15-30 min)") {
                OutlinedTextField(d.samplerTitle, { v -> vm.update { it.copy(samplerTitle = v.take(160)) } }, label = { Text("What you'll try") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Minutes", d.samplerMinutes, { v -> vm.update { it.copy(samplerMinutes = v) } }, Modifier.weight(1f), 1..120)
                    NumberField("Tiny min", d.samplerTinyMinutes, { v -> vm.update { it.copy(samplerTinyMinutes = v) } }, Modifier.weight(1f), 1..30)
                }
                OutlinedTextField(d.samplerTinyTitle, { v -> vm.update { it.copy(samplerTinyTitle = v.take(160)) } }, label = { Text("Tiny version (<=10 min)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    d.samplerSteps.joinToString("\n"), { v -> vm.update { it.copy(samplerSteps = v.split("\n")) } },
                    label = { Text("Steps, one per line") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        d.miniSkills.forEachIndexed { i, m ->
            item(key = "ms_${m.key}") {
                SectionCard(
                    title = "Mini-skill ${i + 1}",
                    trailing = {
                        IconButton(onClick = { vm.update { it.copy(miniSkills = it.miniSkills.filterIndexed { j, _ -> j != i }.map { x -> x.copy(prerequisiteKeys = x.prerequisiteKeys - m.key) }) } }) {
                            Icon(Icons.Outlined.Delete, "Remove mini-skill")
                        }
                    },
                ) {
                    fun upd(f: (MiniSkillDraft) -> MiniSkillDraft) = vm.update { dd -> dd.copy(miniSkills = dd.miniSkills.mapIndexed { j, x -> if (j == i) f(x) else x }) }
                    OutlinedTextField(m.name, { v -> upd { it.copy(name = v.take(100)) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    DecimalField("Estimated hours", m.estHours, { v -> upd { it.copy(estHours = v) } }, Modifier.fillMaxWidth())
                    val earlier = d.miniSkills.take(i)
                    if (earlier.isNotEmpty()) {
                        Text("Needs first:", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            earlier.forEach { e ->
                                val on = e.key in m.prerequisiteKeys
                                FilterChip(
                                    selected = on,
                                    onClick = { upd { it.copy(prerequisiteKeys = if (on) it.prerequisiteKeys - e.key else it.prerequisiteKeys + e.key) } },
                                    label = { Text(e.name.ifBlank { e.key }) },
                                )
                            }
                        }
                    }
                    m.activities.forEachIndexed { k, a ->
                        fun updA(f: (ActivityDraft) -> ActivityDraft) = upd { mm -> mm.copy(activities = mm.activities.mapIndexed { z, x -> if (z == k) f(x) else x }) }
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Activity ${k + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { upd { mm -> mm.copy(activities = mm.activities.filterIndexed { z, _ -> z != k }) } }) { Icon(Icons.Outlined.Delete, "Remove activity") }
                        }
                        OutlinedTextField(a.title, { v -> updA { it.copy(title = v.take(160)) } }, label = { Text("Task") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("Minutes", a.estMinutes, { v -> updA { it.copy(estMinutes = v) } }, Modifier.weight(1f), 1..240)
                            NumberField("Tiny min", a.tinyMinutes, { v -> updA { it.copy(tinyMinutes = v) } }, Modifier.weight(1f), 1..30)
                        }
                        OutlinedTextField(a.tinyTitle, { v -> updA { it.copy(tinyTitle = v.take(160)) } }, label = { Text("Tiny version (3-10 min, still makes something)") }, modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { upd { it.copy(activities = it.activities + ActivityDraft()) } }) { Text("+ Add activity") }
                    OutlinedTextField(
                        m.recallQuestions.joinToString("\n"), { v -> upd { it.copy(recallQuestions = v.split("\n")) } },
                        label = { Text("Recall questions, one per line") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(m.teachBackPrompt, { v -> upd { it.copy(teachBackPrompt = v.take(300)) } }, label = { Text("Teach-back prompt") }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            OutlinedButton(onClick = {
                vm.update { dd ->
                    val used = dd.miniSkills.map { it.key }.toSet()
                    val key = generateSequence(dd.miniSkills.size + 1) { it + 1 }.map { "m$it" }.first { it !in used }
                    dd.copy(miniSkills = dd.miniSkills + MiniSkillDraft(key = key))
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("+ Add mini-skill") }
        }
        item {
            SectionCard("Capstone (optional)") {
                OutlinedTextField(d.capstoneTitle, { v -> vm.update { it.copy(capstoneTitle = v.take(160)) } }, label = { Text("A small finished thing that proves basic competence") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.capstoneDescription, { v -> vm.update { it.copy(capstoneDescription = v.take(500)) } }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                DecimalField("Capstone hours", d.capstoneHours, { v -> vm.update { it.copy(capstoneHours = v) } }, Modifier.fillMaxWidth())
            }
        }
        item { BusyButton("Save", ui.saving, modifier = Modifier.fillMaxWidth()) { vm.save() } }
    }
    gate?.let { (cost, remaining) ->
        AlertDialog(
            onDismissRequest = vm::dismissCostGate,
            title = { Text("Over your monthly budget") },
            text = { Text("The listed resources cost about ₹$cost but ₹$remaining is left this month. Use the free alternatives, or override?") },
            confirmButton = { Button(onClick = { vm.save(freePath = true) }) { Text("Free path") } },
            dismissButton = { TextButton(onClick = { vm.save(freePath = false) }) { Text("Override") } },
        )
    }
}
