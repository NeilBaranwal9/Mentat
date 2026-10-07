package com.mentat.os.vm

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mentat.os.data.db.ActivityEntity
import com.mentat.os.data.db.SkillEntity
import com.mentat.os.data.db.SkillTree
import com.mentat.os.data.repo.ActivateResult
import com.mentat.os.data.repo.AiOutcome
import com.mentat.os.data.repo.AiRepository
import com.mentat.os.data.repo.PendingKind
import com.mentat.os.data.repo.PendingPayload
import com.mentat.os.data.repo.PlanRepository
import com.mentat.os.data.repo.ProposalContext
import com.mentat.os.data.repo.SkillRepository
import com.mentat.os.data.repo.Stats
import com.mentat.os.data.repo.StatsRepository
import com.mentat.os.data.repo.toDraft
import com.mentat.os.domain.DispatcherProvider
import com.mentat.os.domain.model.SkillBlueprint
import com.mentat.os.domain.model.SkillDraft
import com.mentat.os.domain.model.SkillStatus
import com.mentat.os.domain.model.Source
import com.mentat.os.domain.model.userMessage
import com.mentat.os.domain.validate.AgentJson
import com.mentat.os.domain.validate.BlueprintValidator
import com.mentat.os.domain.validate.SafetyFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

data class SkillsUi(val trees: List<SkillTree> = emptyList(), val stats: Stats? = null, val loaded: Boolean = false) {
    val activeCount: Int get() = trees.count { it.skill.status == SkillStatus.ACTIVE }
}

@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val skills: SkillRepository,
    stats: StatsRepository,
    @ApplicationContext private val app: Context,
    private val dispatchers: DispatcherProvider,
    clock: Clock,
) : ViewModel() {
    val ui: StateFlow<SkillsUi> = combine(skills.trees, stats.stats(LocalDate.now(clock))) { t, s -> SkillsUi(t, s, true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkillsUi())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    /** Hand-seeded skills (Section 12 first launch, Section 19 open decision 1), read from a bundled asset on IO. */
    val seeds: StateFlow<List<SkillBlueprint>> = kotlinx.coroutines.flow.flow {
        emit(runCatching {
            withContext(dispatchers.io) {
                val text = app.assets.open("seed_skills.json").bufferedReader().use { it.readText() }
                AgentJson.relaxed.decodeFromString(ListSerializer(SkillBlueprint.serializer()), text)
            }
        }.getOrDefault(emptyList()))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addSeed(bp: SkillBlueprint) = viewModelScope.launch {
        skills.saveDraft(SkillDraft.fromBlueprint(bp), Source.SEED, 0)
        _message.value = "\"${bp.skillName}\" added in sampling mode. Try its sampler, then keep it."
    }
}

data class SkillDetailUi(
    val tree: SkillTree? = null,
    val loaded: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val confirm: String? = null,
    val g2Active: List<SkillEntity> = emptyList(),
    val openProposal: String? = null,
)

@HiltViewModel
class SkillDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val skills: SkillRepository,
    private val plans: PlanRepository,
    private val ai: AiRepository,
) : ViewModel() {
    val skillId: String = checkNotNull(handle["id"])
    private val _t = MutableStateFlow(SkillDetailUi())
    val ui: StateFlow<SkillDetailUi> = combine(skills.tree(skillId), _t) { tree, t -> t.copy(tree = tree, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkillDetailUi())

    fun activate() = viewModelScope.launch {
        when (val r = skills.activate(skillId)) {
            ActivateResult.Ok -> { plans.markDirty(); _t.update { it.copy(message = "Active. It will appear in your plan.") } }
            is ActivateResult.CapReached -> _t.update { it.copy(g2Active = r.active) }
        }
    }

    fun g2Swap(pauseId: String) = viewModelScope.launch {
        skills.setStatus(pauseId, SkillStatus.PAUSED)
        skills.activate(skillId)
        plans.markDirty()
        _t.update { it.copy(g2Active = emptyList(), message = "Swapped active skills.") }
    }

    fun g2Cancel() = _t.update { it.copy(g2Active = emptyList()) }

    fun setStatus(s: SkillStatus) = viewModelScope.launch {
        skills.setStatus(skillId, s)
        plans.markDirty()
        _t.update { it.copy(confirm = null, message = "Status: ${s.name.lowercase()}.") }
    }

    fun askConfirm(kind: String) = _t.update { it.copy(confirm = kind) }
    fun dismissConfirm() = _t.update { it.copy(confirm = null) }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        skills.deleteSkill(skillId)
        plans.markDirty()
        onDone()
    }

    fun setPriority(p: Int) = viewModelScope.launch { skills.setPriority(skillId, p); plans.markDirty() }
    fun setCapstoneDone(done: Boolean) = viewModelScope.launch { skills.setCapstoneDone(skillId, done) }
    fun toggleSelected(a: ActivityEntity) = viewModelScope.launch { skills.updateActivity(a.copy(selected = !a.selected)); plans.markDirty() }
    fun toggleTiny(a: ActivityEntity) = viewModelScope.launch { skills.updateActivity(a.copy(preferTiny = !a.preferTiny)); plans.markDirty() }
    fun toggleWeekend(a: ActivityEntity) = viewModelScope.launch { skills.updateActivity(a.copy(weekendOnly = !a.weekendOnly)); plans.markDirty() }
    fun markDone(a: ActivityEntity, done: Boolean) = viewModelScope.launch { skills.updateActivity(a.copy(completed = done)); plans.markDirty() }
    fun pin(a: ActivityEntity?) = viewModelScope.launch {
        skills.pinActivity(skillId, a?.id)
        plans.markDirty()
        _t.update { it.copy(message = if (a == null) "Unpinned." else "\"${a.title}\" will be planned next.") }
    }

    /** Explicit user action: rewrite one activity with Agent 4. Offline: queued. */
    fun rewrite(a: ActivityEntity, canCallAi: Boolean) = viewModelScope.launch {
        if (!canCallAi) {
            ai.savePending(PendingKind.REWRITE_TASK, "Rewrite \"${a.title.take(40)}\"", PendingPayload(activityId = a.id, tag = "unclear"))
            _t.update { it.copy(message = "Queued. Run it from Inbox > Pending AI when online.") }
            return@launch
        }
        _t.update { it.copy(busy = true) }
        when (val out = ai.rewriteTask(a.id, "unclear")) {
            is AiOutcome.Proposed -> _t.update { it.copy(busy = false, openProposal = out.proposalId) }
            is AiOutcome.Manual -> _t.update { it.copy(busy = false, error = "The rewrite didn't pass the checks after 2 repairs. Use Edit to change it by hand.") }
            is AiOutcome.Error -> _t.update { it.copy(busy = false, error = out.error.userMessage()) }
        }
    }

    fun consume() = _t.update { it.copy(message = null, error = null, openProposal = null) }
}

data class CreateUi(
    val busy: Boolean = false,
    val error: String? = null,
    val manualReason: String? = null,
    val manualErrors: List<String> = emptyList(),
    val openProposal: String? = null,
    val queued: Boolean = false,
)

/** Skill creation with Agent 2. Typed text lives in SavedStateHandle (Rule 3). */
@HiltViewModel
class SkillCreateViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val ai: AiRepository,
) : ViewModel() {
    val name: StateFlow<String> = handle.getStateFlow("skillName", handle.get<String>("name").orEmpty())
    val constraints: StateFlow<String> = handle.getStateFlow("constraints", "")
    private val context = ProposalContext(curiosityId = handle.get<String>("curiosity")?.ifBlank { null }, goalSkillId = handle.get<String>("goalSkill")?.ifBlank { null })

    private val _ui = MutableStateFlow(CreateUi())
    val ui: StateFlow<CreateUi> = _ui.asStateFlow()

    fun setName(v: String) { handle["skillName"] = v.take(80) }
    fun setConstraints(v: String) { handle["constraints"] = v.take(300) }

    fun generate() {
        if (_ui.value.busy || name.value.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            when (val out = ai.generateSkill(name.value.trim(), constraints.value.trim(), context)) {
                is AiOutcome.Proposed -> _ui.update { it.copy(busy = false, openProposal = out.proposalId) }
                is AiOutcome.Manual -> _ui.update { it.copy(busy = false, manualReason = out.reason, manualErrors = out.errors) }
                is AiOutcome.Error -> _ui.update { it.copy(busy = false, error = out.error.userMessage()) }
            }
        }
    }

    fun queue() = viewModelScope.launch {
        if (name.value.isBlank()) return@launch
        ai.savePending(PendingKind.GENERATE_SKILL, "Generate \"${name.value.trim().take(40)}\"", PendingPayload(name = name.value.trim(), constraints = constraints.value.trim(), context = context))
        _ui.update { it.copy(queued = true) }
    }

    fun consumeNavigation() = _ui.update { it.copy(openProposal = null) }
}

data class EditorUi(
    val draft: SkillDraft = SkillDraft(),
    val errors: List<String> = emptyList(),
    val saving: Boolean = false,
    val savedId: String? = null,
    val loaded: Boolean = false,
    val title: String = "New skill",
)

/**
 * Manual skill form: used for manual entry, editing an existing skill, the G1 "Edit" option for a
 * blueprint proposal (commits through ProposalRepository) and the G5 manual fallback.
 * The draft is kept in SavedStateHandle so typed text survives rotation and process death.
 */
@HiltViewModel
class SkillEditorViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val skills: SkillRepository,
    private val proposals: com.mentat.os.data.repo.ProposalRepository,
    private val ai: AiRepository,
    private val plans: PlanRepository,
) : ViewModel() {
    private val skillId: String? = handle.get<String>("id")?.ifBlank { null }
    val proposalId: String? = handle.get<String>("proposal")?.ifBlank { null }
    private val prefillName: String? = handle.get<String>("name")?.ifBlank { null }
    private val draftKey = "draft"

    private val _ui = MutableStateFlow(EditorUi())
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()

    /** G6 cost gate for edited AI blueprints. */
    private val _costGate = MutableStateFlow<Pair<Int, Int>?>(null)
    val costGate: StateFlow<Pair<Int, Int>?> = _costGate.asStateFlow()

    init {
        // Reads Room only (Rule 6).
        viewModelScope.launch {
            val saved = handle.get<String>(draftKey)?.let { runCatching { AgentJson.relaxed.decodeFromString(SkillDraft.serializer(), it) }.getOrNull() }
            val draft = saved ?: when {
                skillId != null -> skills.getTree(skillId)?.toDraft() ?: SkillDraft()
                proposalId != null -> proposals.get(proposalId)?.let { SkillDraft.fromBlueprint(proposals.blueprint(it)) } ?: SkillDraft()
                else -> SkillDraft(name = prefillName.orEmpty())
            }
            val title = when {
                skillId != null -> "Edit skill"
                proposalId != null -> "Edit AI proposal"
                else -> "New skill (manual)"
            }
            _ui.update { it.copy(draft = draft, loaded = true, title = title) }
        }
    }

    fun update(f: (SkillDraft) -> SkillDraft) {
        val d = f(_ui.value.draft)
        handle[draftKey] = AgentJson.relaxed.encodeToString(SkillDraft.serializer(), d)
        _ui.update { it.copy(draft = d, errors = emptyList()) }
    }

    private fun SkillDraft.cleaned() = copy(
        name = name.trim(),
        samplerSteps = samplerSteps.map { it.trim() }.filter { it.isNotEmpty() },
        miniSkills = miniSkills.map { m -> m.copy(recallQuestions = m.recallQuestions.map { it.trim() }.filter { it.isNotEmpty() }) },
    )

    fun validate(): List<String> {
        val d = _ui.value.draft.cleaned()
        val errs = BlueprintValidator.validate(d.toBlueprint(), aiRules = false).toMutableList()
        val texts = listOf(d.name, d.samplerTitle, d.capstoneTitle) + d.miniSkills.flatMap { m -> listOf(m.name) + m.activities.flatMap { listOf(it.title, it.tinyTitle) } }
        if (texts.any { !SafetyFilter.isClean(it) }) errs.add("Remove links, percentages and x/10 scores from the text")
        return errs.map(::humanize).distinct()
    }

    fun save(freePath: Boolean? = null) = viewModelScope.launch {
        val errs = validate()
        if (errs.isNotEmpty()) { _ui.update { it.copy(errors = errs) }; return@launch }
        val d = _ui.value.draft.cleaned()
        _ui.update { it.copy(saving = true) }
        val id = if (proposalId != null) {
            val cost = d.resources.sumOf { r -> r.estCostInr }
            val remaining = ai.remainingBudgetInr()
            if (freePath == null && cost > remaining) {
                _costGate.value = cost to remaining
                _ui.update { it.copy(saving = false) }
                return@launch
            }
            proposals.commitSkill(proposalId, d, edited = true, freePath = freePath == true)
        } else {
            skills.saveDraft(d, Source.MANUAL).also { plans.markDirty() }
        }
        handle.remove<String>(draftKey)
        _costGate.value = null
        _ui.update { it.copy(saving = false, savedId = id) }
    }

    fun dismissCostGate() { _costGate.value = null }

    /** "mini_skills[0].activities[1].tiny_title is empty" -> "Mini-skill 1 activity 2 tiny title is empty". */
    private fun humanize(e: String): String = e
        .replace(Regex("""mini_skills\[(\d+)]""")) { "mini-skill ${it.groupValues[1].toInt() + 1}" }
        .replace(Regex("""activities\[(\d+)]""")) { "activity ${it.groupValues[1].toInt() + 1}" }
        .replace("sampler_session", "sampler")
        .replace(Regex("""\.(?=[a-z])"""), " ")
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
}
