package com.mentat.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mentat.data.db.CuriosityDao
import com.mentat.data.db.ProposedChangeEntity
import com.mentat.data.repo.AiOutcome
import com.mentat.data.repo.AiRepository
import com.mentat.data.repo.ProposalKind
import com.mentat.data.repo.ProposalRepository
import com.mentat.data.repo.SkillRepository
import com.mentat.domain.DispatcherProvider
import com.mentat.domain.agent.EventBudget
import com.mentat.domain.model.GoalMap
import com.mentat.domain.model.NormalizedCuriosity
import com.mentat.domain.model.ParsedCommand
import com.mentat.domain.model.ProposalStatus
import com.mentat.domain.model.RewrittenTask
import com.mentat.domain.model.SkillBlueprint
import com.mentat.domain.model.SkillDraft
import com.mentat.domain.model.userMessage
import com.mentat.domain.validate.CommandValidator
import com.mentat.domain.validate.RewrittenTaskValidator
import com.mentat.domain.validate.SafetyFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import javax.inject.Inject

data class CommandRow(val cmd: ParsedCommand, val include: Boolean, val edited: Boolean = false, val errors: List<String> = emptyList())

sealed interface ProposalNav {
    data class Skill(val id: String) : ProposalNav
    data class Goal(val id: String) : ProposalNav
    data class Proposal(val id: String) : ProposalNav
    data object Back : ProposalNav
}

data class ProposalUi(
    val loaded: Boolean = false,
    val proposal: ProposedChangeEntity? = null,
    val blueprint: SkillBlueprint? = null,
    val commands: List<CommandRow> = emptyList(),
    val clarifying: String? = null,
    val curiosity: NormalizedCuriosity? = null,
    val duplicateTitle: String? = null,
    val goal: GoalMap? = null,
    val goalExcluded: Set<Int> = emptySet(),
    val rewrite: RewrittenTask? = null,
    val originalTitle: String? = null,
    val originalEst: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val validation: List<String> = emptyList(),
    val results: List<String>? = null,
    val costGate: Pair<Int, Int>? = null,
    val nav: ProposalNav? = null,
)

/** Gate G1 for every agent output (Accept / Edit / Reject), plus G4 (low confidence) and G6 (cost). */
@HiltViewModel
class ProposalViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: ProposalRepository,
    private val ai: AiRepository,
    private val skills: SkillRepository,
    private val curiosityDao: CuriosityDao,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    val id: String = checkNotNull(handle["id"])
    private val _ui = MutableStateFlow(ProposalUi())
    val ui: StateFlow<ProposalUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val p = repo.get(id)
            if (p == null) { _ui.update { it.copy(loaded = true, error = "This proposal no longer exists.") }; return@launch }
            var s = ProposalUi(loaded = true, proposal = p)
            runCatching {
                when (p.kind) {
                    ProposalKind.SKILL_BLUEPRINT -> s = s.copy(blueprint = repo.blueprint(p))
                    ProposalKind.COMMANDS -> {
                        val pc = repo.commands(p)
                        s = s.copy(commands = pc.commands.map { CommandRow(it, include = it.confidence != "low" && it.type != "unknown") }, clarifying = pc.clarifyingQuestion)
                    }
                    ProposalKind.CURIOSITY -> {
                        val c = repo.curiosity(p)
                        val dup = c.duplicateOfId?.let { withContext(dispatchers.io) { curiosityDao.get(it) } }
                        s = s.copy(curiosity = c, duplicateTitle = dup?.let { it.normalizedTitle ?: it.rawText })
                    }
                    ProposalKind.GOAL_MAP -> s = s.copy(goal = repo.goalMap(p))
                    ProposalKind.TASK_REWRITE -> {
                        val act = repo.context(p).activityId?.let { skills.activity(it) }
                        s = s.copy(rewrite = repo.rewrite(p), originalTitle = act?.title, originalEst = act?.estMinutes ?: 0)
                    }
                }
            }.onFailure { s = s.copy(error = "Could not read this proposal.") }
            _ui.value = s
        }
    }

    private val open get() = _ui.value.proposal?.status == ProposalStatus.PENDING

    fun reject() = viewModelScope.launch {
        repo.reject(id)
        _ui.update { it.copy(nav = ProposalNav.Back) }
    }

    // ---- Blueprint ----

    fun acceptBlueprint(freePath: Boolean? = null) = viewModelScope.launch {
        val bp = _ui.value.blueprint ?: return@launch
        if (!open) return@launch
        val cost = bp.resources.sumOf { it.estCostInr }
        val remaining = ai.remainingBudgetInr()
        if (freePath == null && cost > remaining) {
            _ui.update { it.copy(costGate = cost to remaining) }
            return@launch
        }
        _ui.update { it.copy(busy = true, costGate = null) }
        val skillId = repo.commitSkill(id, SkillDraft.fromBlueprint(bp), edited = false, freePath = freePath == true)
        _ui.update { it.copy(busy = false, nav = ProposalNav.Skill(skillId)) }
    }

    fun dismissCostGate() = _ui.update { it.copy(costGate = null) }

    // ---- Commands (A5) ----

    fun toggleCommand(i: Int) = _ui.update { s ->
        s.copy(commands = s.commands.mapIndexed { j, r -> if (j == i) r.copy(include = !r.include) else r })
    }

    fun editParam(i: Int, key: String, value: String) = _ui.update { s ->
        s.copy(
            commands = s.commands.mapIndexed { j, r ->
                if (j != i) r else {
                    val prim = value.toIntOrNull()?.takeIf { key in setOf("priority", "minutes", "level") }?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
                    val params = JsonObject(r.cmd.params + (key to prim))
                    r.copy(cmd = r.cmd.copy(params = params, confidence = "high"), edited = true, errors = emptyList())
                }
            },
        )
    }

    fun acceptCommands() = viewModelScope.launch {
        if (!open) return@launch
        val ids = ai.commandSkills().map { it.first }.toSet()
        val rows = _ui.value.commands
        val checked = rows.map { r ->
            if (!r.include) r else r.copy(errors = CommandValidator.paramErrors(r.cmd.type, r.cmd.params, ids, low = false) +
                r.cmd.params.values.flatMap { v -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { SafetyFilter.check(it) }.orEmpty() })
        }
        if (checked.any { it.include && it.errors.isNotEmpty() }) { _ui.update { it.copy(commands = checked) }; return@launch }
        val chosen = checked.filter { it.include }.map { it.cmd }
        if (chosen.isEmpty()) { _ui.update { it.copy(error = "Nothing selected. Tick a command or reject.") }; return@launch }
        _ui.update { it.copy(busy = true) }
        val results = repo.commitCommands(id, chosen, edited = checked.any { it.edited })
        _ui.update { it.copy(busy = false, results = results) }
    }

    /** Gate G4: answer the clarifying question; re-runs A5 once as a new explicit action. */
    fun clarify(answer: String, canCallAi: Boolean) = viewModelScope.launch {
        val p = _ui.value.proposal ?: return@launch
        if (!canCallAi) { _ui.update { it.copy(error = "Offline: answer again when you're back online, or edit the values instead.") }; return@launch }
        val message = repo.context(p).message.orEmpty()
        _ui.update { it.copy(busy = true) }
        when (val out = ai.parseCommand("$message\nClarification: $answer", EventBudget())) {
            is AiOutcome.Proposed -> { repo.reject(id); _ui.update { it.copy(busy = false, nav = ProposalNav.Proposal(out.proposalId)) } }
            is AiOutcome.Manual -> _ui.update { it.copy(busy = false, error = "Still unclear after 2 repairs. Edit the values below, or use the forms in More.") }
            is AiOutcome.Error -> _ui.update { it.copy(busy = false, error = out.error.userMessage()) }
        }
    }

    // ---- Curiosity (A6) ----

    fun editCuriosityTitle(t: String) = _ui.update { s -> s.copy(curiosity = s.curiosity?.copy(normalizedTitle = t.take(80))) }

    fun acceptCuriosity(deleteAsDuplicate: Boolean) = viewModelScope.launch {
        val c = _ui.value.curiosity ?: return@launch
        if (!open) return@launch
        if (c.normalizedTitle.isBlank() || !SafetyFilter.isClean(c.normalizedTitle)) { _ui.update { it.copy(validation = listOf("Title must be non-empty and contain no links or percentages")) }; return@launch }
        repo.commitCuriosity(id, c, edited = c != _ui.value.proposal?.let { repo.curiosity(it) }, deleteAsDuplicate = deleteAsDuplicate)
        _ui.update { it.copy(nav = ProposalNav.Back) }
    }

    // ---- Goal map (A3) ----

    fun toggleGoalSkill(i: Int) = _ui.update { s -> s.copy(goalExcluded = if (i in s.goalExcluded) s.goalExcluded - i else s.goalExcluded + i) }

    fun acceptGoal() = viewModelScope.launch {
        val g = _ui.value.goal ?: return@launch
        if (!open) return@launch
        val excluded = _ui.value.goalExcluded
        val keep = g.requiredSkills.filterIndexed { i, _ -> i !in excluded }
        if (keep.isEmpty()) { _ui.update { it.copy(validation = listOf("Keep at least one skill")) }; return@launch }
        val names = keep.map { it.name.lowercase() }.toSet()
        val cleaned = g.copy(requiredSkills = keep.map { s -> s.copy(dependsOn = s.dependsOn.filter { it.lowercase() in names }) })
        val goalId = repo.commitGoal(id, cleaned, edited = excluded.isNotEmpty())
        _ui.update { it.copy(nav = ProposalNav.Goal(goalId)) }
    }

    // ---- Task rewrite (A4) ----

    fun editRewrite(f: (RewrittenTask) -> RewrittenTask) = _ui.update { s -> s.copy(rewrite = s.rewrite?.let(f), validation = emptyList()) }

    fun acceptRewrite() = viewModelScope.launch {
        val r = _ui.value.rewrite ?: return@launch
        if (!open) return@launch
        val errs = RewrittenTaskValidator.validate(r, _ui.value.originalEst.coerceAtLeast(1)) +
            (listOf(r.title, r.tinyTitle) + r.steps + r.selfCheck).flatMap { SafetyFilter.check(it) }
        if (errs.isNotEmpty()) { _ui.update { it.copy(validation = errs) }; return@launch }
        val original = _ui.value.proposal?.let { repo.rewrite(it) }
        repo.commitRewrite(id, r, edited = r != original)
        _ui.update { it.copy(nav = ProposalNav.Back) }
    }

    fun consumeNav() = _ui.update { it.copy(nav = null) }
    fun consumeError() = _ui.update { it.copy(error = null) }

    companion object {
        fun paramInt(p: JsonObject, k: String) = (p[k] as? JsonPrimitive)?.intOrNull
    }
}
