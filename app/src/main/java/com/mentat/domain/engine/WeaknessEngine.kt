package com.mentat.domain.engine

import com.mentat.domain.model.Activity
import com.mentat.domain.model.FrictionTag
import com.mentat.domain.model.QuestLog
import com.mentat.domain.model.QuestState
import com.mentat.domain.model.RecallCheck
import com.mentat.domain.model.RecallResult
import com.mentat.domain.model.Skill
import com.mentat.domain.model.SkillStatus
import java.time.LocalDate

enum class WeaknessSignal { RECALL_NO, TOO_HARD_STREAK, SKIPPED_REPEAT, OVERRUN, UNTOUCHED, LAST_SLOT }

enum class QuestionKind { BLOCKER, KEEP_PAUSE_DROP }

/** Answers for "What's getting in the way of X?" (Section 6.2 rule map). */
enum class BlockerAnswer(val label: String) {
    TOO_HARD("Too hard"), BORING("Boring"), NO_TIME("No time"), UNCLEAR("Unclear"),
    LOST_INTEREST("Lost interest"), OTHER("Other"), IGNORE("Ignore"),
}

enum class KeepAnswer(val label: String) { KEEP("Keep"), PAUSE("Pause"), DROP("Drop"), IGNORE("Ignore") }

data class WeaknessQuestion(
    val signal: WeaknessSignal,
    /** Key for snoozing and evidence reset (activity, mini-skill or skill id). */
    val targetId: String,
    val skillId: String,
    /** Activity the answer actions apply to (null for keep/pause/drop questions). */
    val activityId: String?,
    val kind: QuestionKind,
    val text: String,
)

data class WeaknessHistory(
    val signal: WeaknessSignal,
    val targetId: String,
    val firedOn: LocalDate,
    val snoozeUntil: LocalDate?,
)

data class WeaknessInput(
    val today: LocalDate,
    val skills: List<Skill>,
    val logs: List<QuestLog>,
    val recalls: List<RecallCheck>,
    /** For each past plan day, the skill id of the last placed block. */
    val lastSlotSkills: List<Pair<LocalDate, String>>,
    val history: List<WeaknessHistory>,
)

/** Section 6.2. Deterministic; at most one question per day; ignored questions snooze 7 days. */
object WeaknessEngine {
    const val SKIP_THRESHOLD = 3
    const val TOO_HARD_STREAK = 2
    const val OVERRUN_RATIO = 1.5
    const val OVERRUN_COUNT = 3
    const val RECALL_NO_COUNT = 2
    const val UNTOUCHED_DAYS = 14L
    const val LAST_SLOT_COUNT = 5
    const val SNOOZE_DAYS = 7L

    fun evaluate(input: WeaknessInput): WeaknessQuestion? {
        val today = input.today
        if (input.history.any { it.firedOn == today }) return null

        fun snoozed(signal: WeaknessSignal, target: String) =
            input.history.any { it.signal == signal && it.targetId == target && it.snoozeUntil != null && it.snoozeUntil.isAfter(today) }

        fun lastFired(signal: WeaknessSignal, target: String): LocalDate? =
            input.history.filter { it.signal == signal && it.targetId == target }.maxOfOrNull { it.firedOn }

        fun after(signal: WeaknessSignal, target: String, d: LocalDate): Boolean {
            val lf = lastFired(signal, target) ?: return true
            return d.isAfter(lf)
        }

        val liveSkills = input.skills.filter { it.status == SkillStatus.ACTIVE || it.status == SkillStatus.SAMPLING }
        val activityIndex = mutableMapOf<String, Pair<Skill, Activity>>()
        liveSkills.forEach { s -> s.miniSkills.forEach { ms -> ms.activities.forEach { activityIndex[it.id] = s to it } } }
        val logsByActivity = input.logs.filter { it.activityId in activityIndex }
            .sortedWith(compareBy<QuestLog> { it.date }.thenBy { it.loggedAt }.thenBy { it.id })
            .groupBy { it.activityId!! }

        fun blocker(signal: WeaknessSignal, target: String, skill: Skill, activityId: String, subject: String) =
            WeaknessQuestion(signal, target, skill.id, activityId, QuestionKind.BLOCKER, "What's getting in the way of \"$subject\"?")

        // Recall NO twice on one mini-skill.
        for (s in liveSkills.sortedBy { it.id }) for (ms in s.path) {
            if (snoozed(WeaknessSignal.RECALL_NO, ms.id)) continue
            val nos = input.recalls.count {
                it.miniSkillId == ms.id && it.result == RecallResult.NO && (it.answeredOn ?: it.due).let { d -> after(WeaknessSignal.RECALL_NO, ms.id, d) }
            }
            val act = ms.activities.maxByOrNull { it.order } ?: continue
            if (nos >= RECALL_NO_COUNT) return blocker(WeaknessSignal.RECALL_NO, ms.id, s, act.id, ms.name)
        }

        for ((activityId, logs) in logsByActivity.toSortedMap()) {
            val (skill, act) = activityIndex.getValue(activityId)
            if (act.completed) continue
            // TOO_HARD on the last two consecutive logs.
            if (!snoozed(WeaknessSignal.TOO_HARD_STREAK, activityId)) {
                val recent = logs.filter { after(WeaknessSignal.TOO_HARD_STREAK, activityId, it.date) }.takeLast(TOO_HARD_STREAK)
                if (recent.size == TOO_HARD_STREAK && recent.all { it.tag == FrictionTag.TOO_HARD }) {
                    return blocker(WeaknessSignal.TOO_HARD_STREAK, activityId, skill, activityId, act.title)
                }
            }
            // Same activity skipped 3 times.
            if (!snoozed(WeaknessSignal.SKIPPED_REPEAT, activityId)) {
                val skips = logs.count { it.state == QuestState.SKIPPED && after(WeaknessSignal.SKIPPED_REPEAT, activityId, it.date) }
                if (skips >= SKIP_THRESHOLD) return blocker(WeaknessSignal.SKIPPED_REPEAT, activityId, skill, activityId, act.title)
            }
        }

        // Actual minutes > 1.5x estimate on 3 tasks of one skill.
        for (s in liveSkills.sortedBy { it.id }) {
            if (snoozed(WeaknessSignal.OVERRUN, s.id)) continue
            val over = input.logs.filter {
                it.skillId == s.id && !it.usedTinyVersion && it.state != QuestState.SKIPPED && it.estMinutes > 0 &&
                    it.actualMinutes > OVERRUN_RATIO * it.estMinutes && after(WeaknessSignal.OVERRUN, s.id, it.date)
            }.sortedWith(compareBy<QuestLog> { it.date }.thenBy { it.id })
            if (over.size >= OVERRUN_COUNT) {
                val last = over.last()
                val act = activityIndex[last.activityId]?.second ?: continue
                return blocker(WeaknessSignal.OVERRUN, s.id, s, act.id, act.title)
            }
        }

        // Skill untouched for 14 days.
        for (s in liveSkills.filter { it.status == SkillStatus.ACTIVE }.sortedBy { it.id }) {
            if (snoozed(WeaknessSignal.UNTOUCHED, s.id)) continue
            val lastLog = input.logs.filter { it.skillId == s.id }.maxOfOrNull { it.date }
            val anchor = listOfNotNull(lastLog, s.createdOn, lastFired(WeaknessSignal.UNTOUCHED, s.id)).maxOrNull() ?: continue
            if (!anchor.plusDays(UNTOUCHED_DAYS).isAfter(today)) {
                return WeaknessQuestion(WeaknessSignal.UNTOUCHED, s.id, s.id, null, QuestionKind.KEEP_PAUSE_DROP, "Keep, pause, or drop \"${s.name}\"?")
            }
        }

        // Skill always in the last slot, 5 times.
        for (s in liveSkills.filter { it.status == SkillStatus.ACTIVE }.sortedBy { it.id }) {
            if (snoozed(WeaknessSignal.LAST_SLOT, s.id)) continue
            val count = input.lastSlotSkills.count { (d, id) -> id == s.id && after(WeaknessSignal.LAST_SLOT, s.id, d) && d.isBefore(today) }
            if (count >= LAST_SLOT_COUNT) {
                return WeaknessQuestion(WeaknessSignal.LAST_SLOT, s.id, s.id, null, QuestionKind.KEEP_PAUSE_DROP, "Keep, pause, or drop \"${s.name}\"?")
            }
        }
        return null
    }

    /** "too_hard -> split activity in 2 (halve est_minutes)". Returns null when it is too small to split. */
    fun split(a: Activity, idFactory: () -> String): Pair<Activity, Activity>? {
        val half = a.estMinutes / 2
        if (half < 5) return null
        val tiny = a.tinyMinutes.coerceAtMost(half - 1).coerceAtLeast(3)
        if (tiny >= half) return null
        val first = a.copy(title = "${a.title} (part 1)", estMinutes = half, tinyMinutes = tiny, completed = false)
        val second = a.copy(id = idFactory(), title = "${a.title} (part 2)", estMinutes = a.estMinutes - half, tinyMinutes = tiny, order = a.order, completed = false)
        return first to second
    }
}
