package com.polymath.os.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class SkillStatus { SAMPLING, ACTIVE, PAUSED, DROPPED, DONE }
enum class QuestState { DONE, PARTIAL, SKIPPED }
enum class FrictionTag { TOO_HARD, TOO_EASY, BORING, UNCLEAR, NO_ENERGY, NO_TIME }
enum class Source { AI, SEED, MANUAL }
enum class RecallMode { YES_SHAKY_NO, TEACH_BACK }
enum class RecallResult { YES, SHAKY, NO, PENDING }
enum class CommitKind { CLASS, EXAM, SLEEP, OTHER }
enum class Recurrence { NONE, DAILY, WEEKLY }
enum class CuriosityStatus { INBOX, PROMOTED, SAMPLED, DELETED }
enum class ProposalStatus { PENDING, ACCEPTED, EDITED, REJECTED }

/** A learnable unit of work. [miniSkillId]/[order]/[completed] etc. fill gaps left by the spec model. */
data class Activity(
    val id: String,
    val miniSkillId: String,
    val title: String,
    val estMinutes: Int,
    val tinyTitle: String,
    val tinyMinutes: Int,
    val styleTag: String?,
    val selected: Boolean = true,
    val source: Source,
    val order: Int = 0,
    val completed: Boolean = false,
    val preferTiny: Boolean = false,
    val weekendOnly: Boolean = false,
    val steps: List<String> = emptyList(),
)

data class MiniSkill(
    val id: String,
    val name: String,
    val order: Int,
    val prerequisiteIds: List<String>,
    val estHours: Double,
    val activities: List<Activity>,
    val recallQuestions: List<String>,
    val teachBackPrompt: String,
    /** The sampler session is stored as a special mini-skill with order 0. */
    val isSampler: Boolean = false,
) {
    val isComplete: Boolean
        get() = activities.any { it.selected } && activities.filter { it.selected }.all { it.completed }
}

data class Skill(
    val id: String,
    val name: String,
    val status: SkillStatus,
    val priority: Int,
    val miniSkills: List<MiniSkill>,
    val capstoneTitle: String?,
    val capstoneDone: Boolean,
    val estCostInr: Int,
    val source: Source,
    val version: Int,
    val capstoneHours: Double = 0.0,
    val pinnedActivityId: String? = null,
    val createdOn: LocalDate? = null,
) {
    val sampler: MiniSkill? get() = miniSkills.firstOrNull { it.isSampler }
    val path: List<MiniSkill> get() = miniSkills.filter { !it.isSampler }.sortedBy { it.order }
}

data class QuestLog(
    val id: String,
    val activityId: String?,
    val skillId: String,
    val date: LocalDate,
    val state: QuestState,
    val usedTinyVersion: Boolean,
    val actualMinutes: Int,
    val tag: FrictionTag?,
    /** Memory aid only, NEVER scored. */
    val proofPath: String?,
    val note: String?,
    val estMinutes: Int = 0,
    val loggedAt: LocalDateTime? = null,
)

data class RecallCheck(
    val id: String,
    val miniSkillId: String,
    val skillId: String,
    val due: LocalDate,
    val mode: RecallMode,
    val result: RecallResult,
    /** Local only, never graded. */
    val teachBackText: String?,
    val sequence: Int = 0,
    val answeredOn: LocalDate? = null,
)

data class Commitment(
    val id: String,
    val title: String,
    val kind: CommitKind,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val recurrence: Recurrence,
)

data class CuriosityItem(
    val id: String,
    val rawText: String,
    val normalizedTitle: String?,
    val status: CuriosityStatus,
    val created: LocalDate,
)

data class Goal(val id: String, val title: String, val targetDate: LocalDate?, val requiredSkillNames: List<String>)
data class EnergyLog(val at: LocalDateTime, val level: Int)

data class ProposedChange(
    val id: String,
    val producedBy: String,
    val kind: String,
    val payloadJson: String,
    val status: ProposalStatus,
)

/** Sleep window as clock times; may wrap past midnight (e.g. 23:30 to 07:00). */
data class SleepWindow(val start: LocalTime, val end: LocalTime)

data class DateRange(val start: LocalDate, val end: LocalDate) {
    operator fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
}
