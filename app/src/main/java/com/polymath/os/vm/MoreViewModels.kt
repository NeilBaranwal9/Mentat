package com.polymath.os.vm

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polymath.os.AppInitializer
import com.polymath.os.data.db.CommitmentEntity
import com.polymath.os.data.db.GoalWithSkills
import com.polymath.os.data.db.JsonLists
import com.polymath.os.data.db.ProfileEntity
import com.polymath.os.data.db.SkillEntity
import com.polymath.os.data.files.FileStore
import com.polymath.os.data.repo.AiOutcome
import com.polymath.os.data.repo.AiRepository
import com.polymath.os.data.repo.BackupRepository
import com.polymath.os.data.repo.CommitmentRepository
import com.polymath.os.data.repo.GoalRepository
import com.polymath.os.data.repo.PendingKind
import com.polymath.os.data.repo.PendingPayload
import com.polymath.os.data.repo.PlanRepository
import com.polymath.os.data.repo.ProfileRepository
import com.polymath.os.data.repo.SkillRepository
import com.polymath.os.domain.model.CommitKind
import com.polymath.os.domain.model.Recurrence
import com.polymath.os.domain.model.userMessage
import com.polymath.os.domain.validate.hasCycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

// ---------------- Goals ----------------

data class GoalsTransient(val busy: Boolean = false, val error: String? = null, val message: String? = null, val openProposal: String? = null, val manual: Boolean = false)

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val goals: GoalRepository,
    private val ai: AiRepository,
) : ViewModel() {
    val list: StateFlow<List<GoalWithSkills>?> = goals.goals.map<List<GoalWithSkills>, List<GoalWithSkills>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val goalText: StateFlow<String> = handle.getStateFlow("goal", "")
    fun onGoalText(t: String) { handle["goal"] = t.take(300) }

    private val _t = MutableStateFlow(GoalsTransient())
    val transient: StateFlow<GoalsTransient> = _t.asStateFlow()

    /** Explicit action: Agent 3 builds a dependency list -> G1. */
    fun mapGoal(canCallAi: Boolean) = viewModelScope.launch {
        val text = goalText.value.trim()
        if (text.isEmpty() || _t.value.busy) return@launch
        if (!canCallAi) {
            ai.savePending(PendingKind.MAP_GOAL, "Map goal \"${text.take(40)}\"", PendingPayload(text = text))
            handle["goal"] = ""
            _t.update { it.copy(message = "Queued. Run it from Inbox > Pending AI when online.") }
            return@launch
        }
        _t.update { it.copy(busy = true, error = null) }
        when (val out = ai.mapGoal(text)) {
            is AiOutcome.Proposed -> { handle["goal"] = ""; _t.update { it.copy(busy = false, openProposal = out.proposalId) } }
            is AiOutcome.Manual -> _t.update { it.copy(busy = false, manual = true, error = "The AI answer didn't pass the checks. Add the goal by hand instead.") }
            is AiOutcome.Error -> _t.update { it.copy(busy = false, error = out.error.userMessage()) }
        }
    }

    fun delete(id: String) = viewModelScope.launch { goals.delete(id) }
    fun consume() = _t.update { it.copy(error = null, message = null, openProposal = null) }
}

/** Manual goal entry (G5 fallback for Agent 3): one skill per line, "core:", "supporting:" or "optional:" prefix optional. */
@HiltViewModel
class ManualGoalViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val goalDao: com.polymath.os.data.db.GoalDao,
    private val skillRepo: SkillRepository,
    private val clock: java.time.Clock,
) : ViewModel() {
    val title: StateFlow<String> = handle.getStateFlow("title", "")
    val lines: StateFlow<String> = handle.getStateFlow("lines", "")
    fun setTitle(t: String) { handle["title"] = t.take(120) }
    fun setLines(t: String) { handle["lines"] = t.take(2000) }
    private val _saved = MutableStateFlow<String?>(null)
    val saved: StateFlow<String?> = _saved.asStateFlow()

    fun save() = viewModelScope.launch {
        val t = title.value.trim()
        if (t.isEmpty()) return@launch
        val id = com.polymath.os.domain.newId()
        val existing = skillRepo.allDomain()
        goalDao.upsertGoal(com.polymath.os.data.db.GoalEntity(id, t, null, LocalDate.now(clock), "[]"))
        goalDao.upsertSkills(
            lines.value.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                val imp = listOf("core", "supporting", "optional").firstOrNull { line.lowercase().startsWith("$it:") }
                val name = if (imp != null) line.substringAfter(':').trim() else line
                com.polymath.os.data.db.GoalSkillEntity(
                    com.polymath.os.domain.newId(), id, name, imp ?: "core", "[]", 5.0, "",
                    existing.firstOrNull { com.polymath.os.domain.engine.ComboMatcher.matches(name, it.name) }?.id,
                )
            },
        )
        _saved.value = id
    }
}

data class LadderRow(val goalSkillId: String, val name: String, val importance: String, val depth: Int, val dependsOn: List<String>, val estHours: Double, val reason: String, val linked: SkillEntity?)

@HiltViewModel
class GoalDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val goals: GoalRepository,
    skills: SkillRepository,
) : ViewModel() {
    private val goalId: String = checkNotNull(handle["id"])

    val goal: StateFlow<GoalWithSkills?> = goals.goal(goalId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val skillList: StateFlow<List<SkillEntity>> = skills.trees.map { t -> t.map { it.skill } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Goal ladder: rows ordered by dependency depth (foundations first). */
    val ladder: StateFlow<List<LadderRow>> = combine(goal, skillList) { g, s ->
        if (g == null) return@combine emptyList()
        val byName = g.skills.associateBy { it.name.lowercase() }
        val deps = g.skills.associate { it.name.lowercase() to JsonLists.decode(it.dependsOnJson).map { d -> d.lowercase() }.filter { d -> d in byName } }
        val memo = mutableMapOf<String, Int>()
        fun depth(n: String, seen: Set<String> = emptySet()): Int = memo[n] ?: run {
            if (n in seen || hasCycle(deps)) 0 else (deps[n].orEmpty().maxOfOrNull { depth(it, seen + n) + 1 } ?: 0)
        }.also { memo[n] = it }
        g.skills.map { gs ->
            LadderRow(gs.id, gs.name, gs.importance, depth(gs.name.lowercase()), JsonLists.decode(gs.dependsOnJson), gs.estHours, gs.reason, s.firstOrNull { it.id == gs.linkedSkillId })
        }.sortedWith(compareBy<LadderRow> { it.depth }.thenBy { listOf("core", "supporting", "optional").indexOf(it.importance) }.thenBy { it.name })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun link(goalSkillId: String, skillId: String?) = viewModelScope.launch { goals.link(goalSkillId, skillId) }
}

// ---------------- Commitments / schedule ----------------

data class ScheduleTransient(val message: String? = null, val error: String? = null, val busy: Boolean = false, val confirmClearIcs: Boolean = false)

@HiltViewModel
class CommitmentsViewModel @Inject constructor(
    private val repo: CommitmentRepository,
    private val profile: ProfileRepository,
    private val files: FileStore,
    private val plans: PlanRepository,
    private val init: AppInitializer,
) : ViewModel() {
    val commitments: StateFlow<List<CommitmentEntity>?> = repo.all.map<List<CommitmentEntity>, List<CommitmentEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profileState: StateFlow<ProfileEntity?> = profile.profile.map<ProfileEntity, ProfileEntity?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _t = MutableStateFlow(ScheduleTransient())
    val transient: StateFlow<ScheduleTransient> = _t.asStateFlow()

    fun add(title: String, kind: CommitKind, start: LocalDateTime, end: LocalDateTime, rec: Recurrence) = viewModelScope.launch {
        if (title.isBlank() || !end.isAfter(start)) { _t.update { it.copy(error = "Give it a title and an end after the start.") }; return@launch }
        repo.add(title, kind, start, end, rec)
        plans.markDirty()
        _t.update { it.copy(message = "Added.") }
    }

    fun delete(c: CommitmentEntity) = viewModelScope.launch { repo.delete(c.id); plans.markDirty() }

    fun saveSleep(start: java.time.LocalTime, end: java.time.LocalTime) = viewModelScope.launch {
        profile.setSleep(start, end)
        plans.markDirty()
        init.reschedule()
        _t.update { it.copy(message = "Sleep window saved.") }
    }

    fun setExam(start: LocalDate?, end: LocalDate?) = viewModelScope.launch {
        if (start != null && end != null && end.isBefore(start)) { _t.update { it.copy(error = "Exam mode must end after it starts.") }; return@launch }
        profile.setExamMode(start, end)
        plans.markDirty()
        _t.update { it.copy(message = if (start == null) "Exam mode off." else "Exam mode saved.") }
    }

    /** .ics via the system file picker; parsing runs on Dispatchers.IO. */
    fun importIcs(uri: Uri) = viewModelScope.launch {
        _t.update { it.copy(busy = true) }
        runCatching { repo.importIcs(files.readText(uri)) }
            .onSuccess { s -> plans.markDirty(); _t.update { it.copy(busy = false, message = "Imported ${s.events} events as ${s.occurrences} blocks for the next 90 days${if (s.skipped > 0) " (${s.skipped} all-day or unreadable skipped)" else ""}.") } }
            .onFailure { e -> _t.update { it.copy(busy = false, error = "Could not import: ${e.message ?: "unreadable file"}") } }
    }

    fun askClearIcs(show: Boolean) = _t.update { it.copy(confirmClearIcs = show) }
    fun clearIcs() = viewModelScope.launch { repo.clearIcs(); plans.markDirty(); _t.update { it.copy(confirmClearIcs = false, message = "Calendar import removed.") } }
    fun consume() = _t.update { it.copy(message = null, error = null) }
}

// ---------------- Chat commands ----------------

data class ChatLine(val fromUser: Boolean, val text: String)
data class ChatTransient(val busy: Boolean = false, val error: String? = null, val openProposal: String? = null)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val ai: AiRepository,
) : ViewModel() {
    val input: StateFlow<String> = handle.getStateFlow("input", "")
    fun onInput(t: String) { handle["input"] = t.take(500) }
    val history: StateFlow<List<String>> = handle.getStateFlow("history", emptyList())

    private val _t = MutableStateFlow(ChatTransient())
    val transient: StateFlow<ChatTransient> = _t.asStateFlow()

    /** A5 parse -> confirmation card -> apply. Never applies directly. */
    fun send(canCallAi: Boolean) = viewModelScope.launch {
        val msg = input.value.trim()
        if (msg.isEmpty() || _t.value.busy) return@launch
        handle["history"] = (history.value + msg).takeLast(20)
        handle["input"] = ""
        if (!canCallAi) {
            ai.savePending(PendingKind.PARSE_COMMAND, "Command \"${msg.take(40)}\"", PendingPayload(text = msg))
            _t.update { it.copy(error = "Offline: saved. Run it from Inbox > Pending AI when you're back online.") }
            return@launch
        }
        _t.update { it.copy(busy = true, error = null) }
        when (val out = ai.parseCommand(msg)) {
            is AiOutcome.Proposed -> _t.update { it.copy(busy = false, openProposal = out.proposalId) }
            is AiOutcome.Manual -> _t.update { it.copy(busy = false, error = "I couldn't turn that into a valid command. Use the forms in More (Schedule, Inbox, Skills) instead.") }
            is AiOutcome.Error -> _t.update { it.copy(busy = false, error = out.error.userMessage()) }
        }
    }

    fun consume() = _t.update { it.copy(error = null, openProposal = null) }
}

// ---------------- Backup ----------------

data class BackupUi(val busy: Boolean = false, val message: String? = null, val error: String? = null, val confirmImport: Uri? = null, val confirmDeleteHistory: Boolean = false)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backup: BackupRepository,
    private val init: AppInitializer,
    private val plans: PlanRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(BackupUi())
    val ui: StateFlow<BackupUi> = _ui.asStateFlow()

    fun export(uri: Uri) = viewModelScope.launch {
        _ui.update { it.copy(busy = true) }
        runCatching { backup.export(uri) }
            .onSuccess { s -> _ui.update { it.copy(busy = false, message = "Exported ${s.skills} skills, ${s.logs} logs and ${s.photos} photos.") } }
            .onFailure { e -> _ui.update { it.copy(busy = false, error = "Export failed: ${e.message}") } }
    }

    fun askImport(uri: Uri?) = _ui.update { it.copy(confirmImport = uri) }

    /** Gate G3 confirmed: replaces all local data. */
    fun import() = viewModelScope.launch {
        val uri = _ui.value.confirmImport ?: return@launch
        _ui.update { it.copy(busy = true, confirmImport = null) }
        runCatching { backup.import(uri) }
            .onSuccess { s -> plans.markDirty(); init.reschedule(); _ui.update { it.copy(busy = false, message = "Restored ${s.skills} skills, ${s.logs} logs and ${s.photos} photos.") } }
            .onFailure { e -> _ui.update { it.copy(busy = false, error = "Restore failed, nothing was changed: ${e.message}") } }
    }

    fun askDeleteHistory(show: Boolean) = _ui.update { it.copy(confirmDeleteHistory = show) }
    fun deleteHistory() = viewModelScope.launch {
        backup.deleteHistory()
        _ui.update { it.copy(confirmDeleteHistory = false, message = "History deleted. Skills and settings were kept.") }
    }

    fun consume() = _ui.update { it.copy(message = null, error = null) }
}
