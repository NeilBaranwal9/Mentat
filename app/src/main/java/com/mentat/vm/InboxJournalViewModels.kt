package com.mentat.vm

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mentat.data.db.CuriosityEntity
import com.mentat.data.db.JournalEntryEntity
import com.mentat.data.db.PendingAiActionEntity
import com.mentat.data.db.QuestLogEntity
import com.mentat.data.files.FileStore
import com.mentat.data.repo.AiOutcome
import com.mentat.data.repo.AiRepository
import com.mentat.data.repo.InboxRepository
import com.mentat.data.repo.JournalRepository
import com.mentat.data.repo.LogRepository
import com.mentat.data.repo.PendingKind
import com.mentat.data.repo.PendingPayload
import com.mentat.data.repo.toDomain
import com.mentat.domain.engine.ProofTimeline
import com.mentat.domain.engine.ShowAndTell
import com.mentat.domain.model.userMessage
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
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class InboxUi(
    val items: List<CuriosityEntity> = emptyList(),
    val pending: List<PendingAiActionEntity> = emptyList(),
    val loaded: Boolean = false,
)

data class InboxTransient(
    val busyId: String? = null,
    val message: String? = null,
    val error: String? = null,
    val openProposal: String? = null,
    val openSkill: String? = null,
    val promote: CuriosityEntity? = null,
    val confirmClear: Boolean = false,
)

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val inbox: InboxRepository,
    private val ai: AiRepository,
) : ViewModel() {
    val captureText: StateFlow<String> = handle.getStateFlow("capture", "")
    fun onCaptureChange(t: String) { handle["capture"] = t.take(500) }

    val ui: StateFlow<InboxUi> = combine(inbox.inbox, ai.pending) { i, p -> InboxUi(i, p, true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUi())

    private val _t = MutableStateFlow(InboxTransient())
    val transient: StateFlow<InboxTransient> = _t.asStateFlow()

    fun capture() = viewModelScope.launch {
        val t = captureText.value.trim()
        if (t.isEmpty()) return@launch
        inbox.capture(t)
        handle["capture"] = ""
    }

    /** Optional A6 normalize (explicit button). Offline: queued. */
    fun normalize(item: CuriosityEntity, canCallAi: Boolean) = viewModelScope.launch {
        if (_t.value.busyId != null) return@launch
        if (!canCallAi) {
            ai.savePending(PendingKind.NORMALIZE_CURIOSITY, "Tidy \"${item.rawText.take(40)}\"", PendingPayload(itemId = item.id))
            _t.update { it.copy(message = "Queued. Tap \"Run now\" below when you're online.") }
            return@launch
        }
        _t.update { it.copy(busyId = item.id) }
        when (val out = ai.normalizeCuriosity(item.id)) {
            is AiOutcome.Proposed -> _t.update { it.copy(busyId = null, openProposal = out.proposalId) }
            is AiOutcome.Manual -> _t.update { it.copy(busyId = null, error = "The AI answer didn't pass the checks. You can rename the item yourself.") }
            is AiOutcome.Error -> _t.update { it.copy(busyId = null, error = out.error.userMessage()) }
        }
    }

    fun rename(item: CuriosityEntity, title: String) = viewModelScope.launch { if (title.isNotBlank()) inbox.rename(item.id, title) }
    fun delete(item: CuriosityEntity) = viewModelScope.launch { inbox.delete(item.id) }

    fun sample(item: CuriosityEntity) = viewModelScope.launch {
        val id = inbox.sample(item.id)
        _t.update { it.copy(openSkill = id, message = "Sampling skill created. Its sampler joins your plan as the weekly wildcard.") }
    }

    fun askPromote(item: CuriosityEntity?) = _t.update { it.copy(promote = item) }

    fun runPending(p: PendingAiActionEntity) = viewModelScope.launch {
        if (_t.value.busyId != null) return@launch
        _t.update { it.copy(busyId = p.id) }
        when (val out = ai.runPending(p.id)) {
            is AiOutcome.Proposed -> _t.update { it.copy(busyId = null, openProposal = out.proposalId) }
            is AiOutcome.Manual -> _t.update { it.copy(busyId = null, error = "The AI answer didn't pass the checks after 2 repairs. Use the manual forms instead.") }
            is AiOutcome.Error -> _t.update { it.copy(busyId = null, error = out.error.userMessage()) }
        }
    }

    fun discardPending(p: PendingAiActionEntity) = viewModelScope.launch { ai.discardPending(p.id) }

    fun askClear(show: Boolean) = _t.update { it.copy(confirmClear = show) }
    /** Gate G3 confirmed. */
    fun clear() = viewModelScope.launch { inbox.clear(); _t.update { it.copy(confirmClear = false, message = "Inbox cleared.") } }

    fun consume() = _t.update { it.copy(message = null, error = null, openProposal = null, openSkill = null) }
}

data class JournalUi(
    val entries: List<JournalEntryEntity> = emptyList(),
    val timeline: List<Pair<YearMonth, List<QuestLogEntity>>> = emptyList(),
    val showAndTell: QuestLogEntity? = null,
    val loaded: Boolean = false,
)

@HiltViewModel
class JournalViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val journal: JournalRepository,
    logs: LogRepository,
    private val files: FileStore,
    private val clock: Clock,
) : ViewModel() {
    val today: LocalDate = LocalDate.now(clock)
    val ui: StateFlow<JournalUi> = combine(journal.entries, logs.proofLogs) { e, proofs ->
        val domain = proofs.map { it.toDomain() }
        JournalUi(e, ProofTimeline.group(domain).map { (m, l) -> m to l.mapNotNull { d -> proofs.firstOrNull { it.id == d.id } } }, ShowAndTell.pick(today, domain)?.let { pick -> proofs.firstOrNull { it.id == pick.id } }, true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalUi())

    val draftLine: StateFlow<String> = handle.getStateFlow("line", "")
    fun onLineChange(t: String) { handle["line"] = t.take(300) }

    /** Keyed by date: refreshes today's auto page. */
    fun refreshToday() = viewModelScope.launch { journal.write(today) }

    fun saveLine(date: LocalDate) = viewModelScope.launch {
        journal.setUserLine(date, draftLine.value)
        handle["line"] = ""
    }

    fun proofFile(name: String): File = files.proofFile(name)

    /** System share sheet for one image (weekly show-and-tell). Nothing is uploaded by the app. */
    fun shareIntent(log: QuestLogEntity): Intent? {
        val name = log.proofPath ?: return null
        val uri = files.shareUri(name)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "This week: ${log.title} (${log.actualMinutes} min)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share your week's highlight")
    }
}
