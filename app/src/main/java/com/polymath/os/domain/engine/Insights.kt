package com.polymath.os.domain.engine

import com.polymath.os.domain.model.QuestLog
import com.polymath.os.domain.model.QuestState
import com.polymath.os.domain.model.Skill
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Seed row for the combo matcher (assets/combos.json). */
@Serializable
data class ComboSeed(
    val id: String,
    val requires: List<String>,
    val project: String,
    @SerialName("est_hours") val estHours: Double,
)

data class ComboSuggestion(val combo: ComboSeed, val matchedSkills: List<Skill>)

/** Section 6.5 combo matcher: >= 1 completed mini-skill in every required skill; max 1 suggestion per week. */
object ComboMatcher {
    fun matches(requirement: String, skillName: String): Boolean {
        val r = requirement.trim().lowercase()
        val s = skillName.trim().lowercase()
        return r.isNotEmpty() && (s.contains(r) || r.contains(s) && s.length >= 3)
    }

    fun suggest(
        today: LocalDate,
        combos: List<ComboSeed>,
        skills: List<Skill>,
        lastSuggestedOn: LocalDate?,
        excludedComboIds: Set<String>,
    ): ComboSuggestion? {
        if (lastSuggestedOn != null && ChronoUnit.DAYS.between(lastSuggestedOn, today) < 7) return null
        val qualified = skills.filter { s -> s.path.any { it.isComplete } }
        for (c in combos.sortedBy { it.id }) {
            if (c.id in excludedComboIds) continue
            val picked = c.requires.map { req -> qualified.firstOrNull { matches(req, it.name) } }
            if (picked.all { it != null } && picked.map { it!!.id }.toSet().size == c.requires.size) {
                return ComboSuggestion(c, picked.filterNotNull())
            }
        }
        return null
    }
}

/** Section 6.5 show-and-tell: this week's logs with a proof; highest actualMinutes, tie -> most recent. No AI. */
object ShowAndTell {
    fun pick(today: LocalDate, logs: List<QuestLog>): QuestLog? {
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return logs.filter { it.proofPath != null && !it.date.isBefore(weekStart) && !it.date.isAfter(today) }
            .sortedWith(
                compareByDescending<QuestLog> { it.actualMinutes }
                    .thenByDescending { it.date }
                    .thenByDescending { it.loggedAt }
                    .thenByDescending { it.id },
            )
            .firstOrNull()
    }
}

/** Proof timeline: logs with a proof, grouped by month, newest first. */
object ProofTimeline {
    fun group(logs: List<QuestLog>): List<Pair<YearMonth, List<QuestLog>>> =
        logs.filter { it.proofPath != null }
            .groupBy { YearMonth.from(it.date) }
            .toSortedMap(compareByDescending { it })
            .map { (m, l) -> m to l.sortedWith(compareByDescending<QuestLog> { it.date }.thenByDescending { it.loggedAt }) }
}

data class WeeklyConsistency(val loggedDays: Int, val freezeDays: Int, val target: Int, val days: List<Pair<LocalDate, Boolean>>)

/**
 * Weekly consistency replaces streaks: days with >= 1 logged item (tiny counts) in the current Mon-Sun week.
 * Freeze days lower the target (gap fill: default 1 per week).
 */
object Consistency {
    fun isLoggedDay(logs: List<QuestLog>, recallAnsweredDates: Set<LocalDate>, d: LocalDate) =
        logs.any { it.date == d && it.state != QuestState.SKIPPED } || d in recallAnsweredDates

    fun week(today: LocalDate, logs: List<QuestLog>, recallAnsweredDates: Set<LocalDate>, freezeDays: Int): WeeklyConsistency {
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val days = (0L..6L).map { start.plusDays(it) }.map { it to (!it.isAfter(today) && isLoggedDay(logs, recallAnsweredDates, it)) }
        val f = freezeDays.coerceIn(0, 3)
        return WeeklyConsistency(days.count { it.second }, f, 7 - f, days)
    }
}

/** Calibration ratio = running mean of actual/estimated minutes per skill (full-size completed logs only). */
object Calibration {
    fun ratios(logs: List<QuestLog>): Map<String, Double> =
        logs.filter { it.state == QuestState.DONE && !it.usedTinyVersion && it.estMinutes > 0 && it.actualMinutes > 0 }
            .groupBy { it.skillId }
            .mapValues { (_, l) -> l.map { it.actualMinutes.toDouble() / it.estMinutes }.average() }
}
