package com.mentat.os.vm

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mentat.os.data.db.ActivityEntity
import com.mentat.os.data.db.ComboEntity
import com.mentat.os.data.db.QuestLogEntity
import com.mentat.os.data.db.RecallCheckEntity
import com.mentat.os.data.db.SkillEntity
import com.mentat.os.data.db.WeaknessEventEntity
import com.mentat.os.data.files.FileStore
import com.mentat.os.data.repo.ActivateResult
import com.mentat.os.data.repo.AiOutcome
import com.mentat.os.data.repo.AiRepository
import com.mentat.os.data.repo.ComboRepository
import com.mentat.os.data.repo.InboxRepository
import com.mentat.os.data.repo.JournalRepository
import com.mentat.os.data.repo.LogRepository
import com.mentat.os.data.repo.LogRequest
import com.mentat.os.data.repo.PendingKind
import com.mentat.os.data.repo.PendingPayload
import com.mentat.os.data.repo.PlanRepository
import com.mentat.os.data.repo.ProposalContext
import com.mentat.os.data.repo.RecomputeResult
import com.mentat.os.data.repo.SkillRepository
import com.mentat.os.data.repo.Stats
import com.mentat.os.data.repo.StatsRepository
import com.mentat.os.data.repo.StoredPlan
import com.mentat.os.data.repo.WeaknessRepository
import com.mentat.os.domain.engine.CandidateKind
import com.mentat.os.domain.engine.EnergyAnalyzer
import com.mentat.os.domain.engine.EnergyProfile
import com.mentat.os.domain.engine.NextActivitySelector
import com.mentat.os.domain.engine.PlanBlock
import com.mentat.os.domain.model.AiError
import com.mentat.os.domain.model.FrictionTag
import com.mentat.os.domain.model.QuestState
import com.mentat.os.domain.model.RecallMode
import com.mentat.os.domain.model.RecallResult
import com.mentat.os.domain.model.Skill
import com.mentat.os.domain.model.SkillStatus
import com.mentat.os.domain.model.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

data class BlockUi(val block: PlanBlock, val done: Boolean, val skipped: Boolean)

/** Something the user can log right now (plan block, quick log or "what should I do"). */
data class LogTarget(
    val activityId: String?,
    val skillId: String,
    val title: String,
    val minutes: Int,
    val estMinutes: Int,
    val tiny: Boolean,
    val isCapstone: Boolean = false,
    val isSampler: Boolean = false,
)

data class RecallPrompt(
    val checkId: String,
    val mode: RecallMode,
    val miniSkillName: String,
    val skillName: String,
    val question: String?,
    val teachBackPrompt: String?,
)

data class TodayData(
    val today: LocalDate,
    val stats: Stats? = null,
    val activeSkills: List<Skill> = emptyList(),
    val allSkills: List<Skill> = emptyList(),
    val plan: StoredPlan? = null,
    val blocks: List<BlockUi> = emptyList(),
    val logs: List<QuestLogEntity> = emptyList(),
    val dueRecalls: List<RecallCheckEntity> = emptyList(),
    val weakness: WeaknessEventEntity? = null,
    val combo: ComboEntity? = null,
    val resumePrompts: List<SkillEntity> = emptyList(),
    val samplerDecisions: List<Skill> = emptyList(),
    val energy: EnergyProfile? = null,
    val energyToday: Int? = null,
    val dirty: Boolean = false,
    val loaded: Boolean = false,
)

data class TodayTransient(
    val suggestion: PlanBlock? = null,
    val suggestionAsked: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val logTarget: LogTarget? = null,
    val recall: RecallPrompt? = null,
    val alternatives: List<ActivityEntity>? = null,
    val noTimeChoice: Boolean = false,
    val otherText: Boolean = false,
    val confirmDrop: String? = null,
    val g2ForSkill: String? = null,
    val g2Active: List<SkillEntity> = emptyList(),
    val openProposal: String? = null,
    val openSkill: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val plans: PlanRepository,
    private val logs: LogRepository,
    private val skills: SkillRepository,
    private val weakness: WeaknessRepository,
    private val combos: ComboRepository,
    private val inbox: InboxRepository,
    private val journal: JournalRepository,
    private val ai: AiRepository,
    private val files: FileStore,
    stats: StatsRepository,
    private val clock: Clock,
    private val handle: SavedStateHandle,
) : ViewModel() {

    private val day = MutableStateFlow(LocalDate.now(clock))

    /** Unsaved quick-capture text survives rotation and process death (Rule 3). */
    val captureText: StateFlow<String> = handle.getStateFlow("capture", "")
    fun onCaptureChange(t: String) { handle["capture"] = t }

    private val _t = MutableStateFlow(TodayTransient())
    val transient: StateFlow<TodayTransient> = _t.asStateFlow()

    private val base = day.flatMapLatest { d ->
        combine(plans.observe(d), logs.logsOn(d), logs.dueRecalls(d), skills.skills, weakness.openQuestion(d)) { plan, l, r, s, w ->
            TodayData(today = d, plan = plan, logs = l, dueRecalls = r, allSkills = s, weakness = w, loaded = true)
        }
    }

    private val extras = day.flatMapLatest { d ->
        combine(stats.stats(d), combos.suggested, skills.trees, logs.energy, plans.dirty) { st, c, trees, energy, dirty ->
            Extras(st, c, trees.map { it.skill }.filter { it.status == SkillStatus.PAUSED && it.resumePromptOn != null && !it.resumePromptOn.isAfter(d) }, EnergyAnalyzer.analyze(energy.map { e -> com.mentat.os.domain.model.EnergyLog(e.at, e.level) }), energy.lastOrNull { it.at.toLocalDate() == d }?.level, dirty)
        }
    }

    private data class Extras(val stats: Stats, val combo: ComboEntity?, val resume: List<SkillEntity>, val energy: EnergyProfile, val energyToday: Int?, val dirty: Boolean)

    val data: StateFlow<TodayData> = combine(base, extras) { b, e ->
        val loggedActs = b.logs.filter { it.state != QuestState.SKIPPED }.mapNotNull { it.activityId }.toSet()
        val skippedActs = b.logs.filter { it.state == QuestState.SKIPPED }.mapNotNull { it.activityId }.toSet()
        val dueIds = b.dueRecalls.map { it.id }.toSet()
        val blocks = b.plan?.blocks.orEmpty().map { blk ->
            val done = when (blk.kind) {
                CandidateKind.RECALL -> blk.recallCheckId !in dueIds
                CandidateKind.CAPSTONE -> b.logs.any { it.skillId == blk.skillId && it.activityId == null && it.state != QuestState.SKIPPED }
                else -> blk.activityId in loggedActs
            }
            BlockUi(blk, done, !done && blk.activityId in skippedActs)
        }
        val samplerDecisions = b.allSkills.filter { s -> s.status == SkillStatus.SAMPLING && s.sampler?.activities?.any { it.completed } == true }
        b.copy(
            stats = e.stats, combo = e.combo, resumePrompts = e.resume, energy = e.energy, energyToday = e.energyToday, dirty = e.dirty,
            blocks = blocks, activeSkills = b.allSkills.filter { it.status == SkillStatus.ACTIVE }, samplerDecisions = samplerDecisions,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayData(LocalDate.now(clock)))

    // ---- Keyed lifecycle effect (Rule 6): deterministic, offline, idempotent per date ----

    /** On resume: only re-reads the clock; writes nothing. */
    fun refreshDate() {
        val d = LocalDate.now(clock)
        if (d != day.value) day.value = d
    }

    /** Called from LaunchedEffect(date). Every write here is idempotent for that date. */
    fun ensureDay(d: LocalDate) {
        viewModelScope.launch {
            plans.ensure(d)
            weakness.evaluate(d)
            combos.evaluate(d)
        }
    }

    // ---- Plan ----

    fun recompute() = viewModelScope.launch {
        when (plans.recompute(day.value)) {
            RecomputeResult.Ok -> _t.update { it.copy(message = "Plan recomputed.") }
            RecomputeResult.LimitReached -> _t.update { it.copy(error = "You've recomputed 5 times today. The plan is locked until tomorrow.") }
        }
    }

    fun askWhatToDo(minutes: Int) = viewModelScope.launch {
        _t.update { it.copy(busy = true) }
        val s = plans.suggestOne(minutes)
        _t.update { it.copy(busy = false, suggestion = s, suggestionAsked = true) }
    }

    fun clearSuggestion() = _t.update { it.copy(suggestion = null, suggestionAsked = false) }

    // ---- Logging ----

    fun openLog(block: PlanBlock, forceTiny: Boolean? = null) = viewModelScope.launch {
        if (block.kind == CandidateKind.RECALL) {
            openRecall(block.recallCheckId ?: return@launch)
            return@launch
        }
        val act = block.activityId?.let { skills.activity(it) }
        val tiny = forceTiny ?: block.isTiny
        val minutes = if (tiny) act?.tinyMinutes ?: block.minutes else if (act != null && !block.isTiny) block.minutes else act?.estMinutes ?: block.minutes
        _t.update {
            it.copy(
                logTarget = LogTarget(
                    activityId = block.activityId, skillId = block.skillId,
                    title = if (tiny) act?.tinyTitle ?: block.title else act?.title ?: block.title,
                    minutes = minutes, estMinutes = act?.estMinutes ?: block.minutes, tiny = tiny,
                    isCapstone = block.kind == CandidateKind.CAPSTONE, isSampler = block.kind == CandidateKind.WILDCARD,
                ),
                suggestion = null, suggestionAsked = false,
            )
        }
    }

    /** Quick log: next activity of an active or sampling skill. */
    fun quickLogTargets(): List<LogTarget> {
        val d = data.value
        return d.allSkills.filter { it.status == SkillStatus.ACTIVE || it.status == SkillStatus.SAMPLING }.mapNotNull { s ->
            val next = NextActivitySelector.next(s, d.today)?.second
                ?: s.sampler?.activities?.firstOrNull()?.takeIf { s.status == SkillStatus.SAMPLING }
            next?.let { LogTarget(it.id, s.id, it.title, it.estMinutes, it.estMinutes, false, isSampler = s.sampler?.activities?.any { a -> a.id == it.id } == true) }
        }
    }

    fun openQuickLog(t: LogTarget) = _t.update { it.copy(logTarget = t) }
    fun dismissLog() = _t.update { it.copy(logTarget = null) }

    fun submitLog(target: LogTarget, state: QuestState, tiny: Boolean, minutes: Int, tag: FrictionTag?, note: String, proof: Uri?, capstoneFinished: Boolean) =
        viewModelScope.launch {
            _t.update { it.copy(busy = true) }
            val proofName = proof?.let { runCatching { files.importProof(it) }.getOrNull() }
            val outcome = logs.log(
                LogRequest(
                    activityId = target.activityId, skillId = target.skillId, title = target.title, state = state, usedTiny = tiny,
                    actualMinutes = minutes, estMinutes = target.estMinutes, tag = tag, note = note, proofName = proofName,
                    capstoneFinished = capstoneFinished,
                ),
            )
            journal.write(day.value, force = true)
            val msg = when {
                outcome.miniSkillCompleted != null -> "Mini-skill \"${outcome.miniSkillCompleted}\" complete. Recall checks scheduled for +7 and +30 days."
                outcome.samplerDone -> "Sampler done. Decide below whether to keep this skill."
                state == QuestState.SKIPPED -> "Skip logged."
                else -> "Logged. That counts for today."
            }
            _t.update { it.copy(busy = false, logTarget = null, message = msg, error = if (proof != null && proofName == null) "The photo could not be saved." else null) }
        }

    // ---- Recall ----

    fun openRecall(checkId: String) = viewModelScope.launch {
        val check = data.value.dueRecalls.firstOrNull { it.id == checkId } ?: return@launch
        val ms = skills.miniSkill(check.miniSkillId) ?: return@launch
        val qs = logs.questionsFor(ms.id)
        val skill = data.value.allSkills.firstOrNull { it.id == check.skillId }
        val question = if (qs.isEmpty()) null else qs[check.sequence % qs.size]
        val teach = ms.teachBackPrompt.ifBlank { "Explain ${ms.name} in about 3 sentences, as if to a friend." }
        _t.update {
            it.copy(
                recall = RecallPrompt(
                    check.id, check.mode, ms.name, skill?.name.orEmpty(),
                    question ?: "From memory, redo one small piece of ${ms.name}.", teach,
                ),
            )
        }
    }

    fun dismissRecall() = _t.update { it.copy(recall = null) }

    fun answerRecall(checkId: String, result: RecallResult, text: String?) = viewModelScope.launch {
        logs.answerRecall(checkId, result, text)
        journal.write(day.value, force = true)
        _t.update {
            it.copy(
                recall = null,
                message = if (result == RecallResult.YES) "Recall logged." else "Recall logged. Another check is scheduled in 3 days.",
            )
        }
    }

    // ---- Energy & capture ----

    fun logEnergy(level: Int) = viewModelScope.launch {
        logs.logEnergy(level)
        _t.update { it.copy(message = "Energy $level logged.") }
    }

    fun capture() = viewModelScope.launch {
        val text = captureText.value.trim()
        if (text.isEmpty()) return@launch
        inbox.capture(text)
        handle["capture"] = ""
        _t.update { it.copy(message = "Saved to your curiosity inbox.") }
    }

    // ---- Weakness question (Section 6.2 rule map) ----

    fun weaknessTooHard(e: WeaknessEventEntity) = viewModelScope.launch {
        val split = e.activityId?.let { weakness.applyTooHard(e.id, it) } ?: false
        _t.update { it.copy(message = if (split) "Split into two smaller parts. A quick review of the previous step was added." else "Noted. A review of the previous step was added where possible.") }
    }

    fun weaknessBoring(e: WeaknessEventEntity) = viewModelScope.launch {
        val alts = e.activityId?.let { weakness.alternatives(it) }.orEmpty()
        if (alts.isEmpty()) {
            weakness.close(e.id, "boring")
            _t.update { it.copy(message = "No other activities in this skill yet. Try editing the path in Skills.") }
        } else {
            _t.update { it.copy(alternatives = alts) }
        }
    }

    fun pickAlternative(e: WeaknessEventEntity, a: ActivityEntity) = viewModelScope.launch {
        weakness.applyBoringPick(e.id, a.skillId, a.id)
        _t.update { it.copy(alternatives = null, message = "\"${a.title}\" is now next for this skill.") }
    }

    fun dismissAlternatives() = _t.update { it.copy(alternatives = null) }
    fun weaknessNoTime() = _t.update { it.copy(noTimeChoice = true) }
    fun dismissNoTime() = _t.update { it.copy(noTimeChoice = false) }

    fun applyNoTime(e: WeaknessEventEntity, weekend: Boolean) = viewModelScope.launch {
        e.activityId?.let { weakness.applyNoTime(e.id, it, weekend) }
        _t.update { it.copy(noTimeChoice = false, message = if (weekend) "Kept for weekend slots." else "The tiny version will be used from now on.") }
    }

    /** unclear -> Agent 4 ONCE; result goes through validation + G1. Offline: queued for "Run now". */
    fun weaknessUnclear(e: WeaknessEventEntity, canCallAi: Boolean) = viewModelScope.launch {
        val actId = e.activityId ?: return@launch
        if (!canCallAi) {
            ai.savePending(PendingKind.REWRITE_TASK, "Rewrite an unclear task", PendingPayload(activityId = actId, tag = "unclear", context = ProposalContext(weaknessEventId = e.id)))
            weakness.close(e.id, "unclear_queued")
            _t.update { it.copy(message = "Saved. Open Inbox > Pending AI and tap \"Run now\" when you're online.") }
            return@launch
        }
        _t.update { it.copy(busy = true) }
        when (val out = ai.rewriteTask(actId, "unclear", e.id)) {
            is AiOutcome.Proposed -> _t.update { it.copy(busy = false, openProposal = out.proposalId) }
            is AiOutcome.Manual -> {
                weakness.close(e.id, "unclear_manual")
                _t.update { it.copy(busy = false, openSkill = e.skillId, error = "The AI rewrite didn't pass the checks. Edit the task by hand in the skill page.") }
            }
            is AiOutcome.Error -> _t.update { it.copy(busy = false, error = out.error.userMessage()) }
        }
    }

    fun weaknessLostInterest(e: WeaknessEventEntity) = viewModelScope.launch {
        weakness.applyLostInterest(e.id, e.skillId)
        _t.update { it.copy(message = "Paused. You'll be asked about resuming in 30 days.") }
    }

    fun weaknessOther(e: WeaknessEventEntity, text: String) = viewModelScope.launch {
        weakness.close(e.id, "other", text)
        _t.update { it.copy(otherText = false, openSkill = e.skillId) }
    }

    fun showOther(show: Boolean) = _t.update { it.copy(otherText = show) }
    fun weaknessIgnore(e: WeaknessEventEntity) = viewModelScope.launch { weakness.ignore(e.id); _t.update { it.copy(message = "Snoozed for 7 days.") } }
    fun weaknessKeep(e: WeaknessEventEntity) = viewModelScope.launch { weakness.close(e.id, "keep"); _t.update { it.copy(message = "Kept.") } }

    fun weaknessPause(e: WeaknessEventEntity) = viewModelScope.launch {
        skills.setStatus(e.skillId, SkillStatus.PAUSED)
        weakness.close(e.id, "pause")
        plans.markDirty()
        _t.update { it.copy(message = "Paused.") }
    }

    fun askDrop(skillId: String) = _t.update { it.copy(confirmDrop = skillId) }
    fun dismissDrop() = _t.update { it.copy(confirmDrop = null) }

    /** Gate G3 confirmed. */
    fun confirmDrop(e: WeaknessEventEntity?) = viewModelScope.launch {
        val id = _t.value.confirmDrop ?: return@launch
        skills.setStatus(id, SkillStatus.DROPPED)
        e?.let { weakness.close(it.id, "drop") }
        plans.markDirty()
        _t.update { it.copy(confirmDrop = null, message = "Dropped. Its history is kept.") }
    }

    // ---- Sampler decision, resume prompts, G2 ----

    fun keepSkill(skillId: String) = viewModelScope.launch {
        when (val r = skills.activate(skillId)) {
            ActivateResult.Ok -> { plans.markDirty(); _t.update { it.copy(message = "Activated.") } }
            is ActivateResult.CapReached -> _t.update { it.copy(g2ForSkill = skillId, g2Active = r.active) }
        }
    }

    /** Gate G2 "Pause one": pause [pauseId] then activate the pending skill. */
    fun g2PauseAndActivate(pauseId: String) = viewModelScope.launch {
        val target = _t.value.g2ForSkill ?: return@launch
        skills.setStatus(pauseId, SkillStatus.PAUSED)
        skills.activate(target)
        plans.markDirty()
        _t.update { it.copy(g2ForSkill = null, g2Active = emptyList(), message = "Swapped active skills.") }
    }

    fun g2Cancel() = _t.update { it.copy(g2ForSkill = null, g2Active = emptyList()) }

    fun pauseSkill(skillId: String) = viewModelScope.launch { skills.setStatus(skillId, SkillStatus.PAUSED); plans.markDirty() }
    fun notNowResume(skillId: String) = viewModelScope.launch { skills.clearResumePrompt(skillId) }

    fun acceptCombo(c: ComboEntity) = viewModelScope.launch { combos.accept(c); _t.update { it.copy(message = "Project idea added to your inbox.") } }
    fun dismissCombo(c: ComboEntity) = viewModelScope.launch { combos.dismiss(c) }

    fun consumeMessage() = _t.update { it.copy(message = null) }
    fun consumeError() = _t.update { it.copy(error = null) }
    fun consumeNavigation() = _t.update { it.copy(openProposal = null, openSkill = null) }
    fun showError(e: AiError) = _t.update { it.copy(error = e.userMessage()) }
}
