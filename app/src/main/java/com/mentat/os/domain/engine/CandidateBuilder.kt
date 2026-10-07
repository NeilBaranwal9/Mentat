package com.mentat.os.domain.engine

import com.mentat.os.domain.model.Activity
import com.mentat.os.domain.model.MiniSkill
import com.mentat.os.domain.model.QuestLog
import com.mentat.os.domain.model.QuestState
import com.mentat.os.domain.model.RecallCheck
import com.mentat.os.domain.model.RecallMode
import com.mentat.os.domain.model.RecallResult
import com.mentat.os.domain.model.Skill
import com.mentat.os.domain.model.SkillStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Goal-ladder facts about one skill, used for deadline_urgency and goal_ladder_core. */
data class GoalLink(val goalTitle: String, val core: Boolean, val targetDate: LocalDate?)

object NextActivitySelector {

    /** A mini-skill with no selected activities never blocks the path. */
    fun passable(ms: MiniSkill) = ms.isComplete || ms.activities.none { it.selected }

    fun next(skill: Skill, today: LocalDate): Pair<MiniSkill, Activity>? {
        val weekend = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY
        val path = skill.path
        val done = path.filter { passable(it) }.map { it.id }.toSet()
        fun usable(a: Activity) = a.selected && !a.completed && (weekend || !a.weekendOnly)
        fun unlocked(ms: MiniSkill) = ms.prerequisiteIds.all { it in done || path.none { p -> p.id == it } }

        skill.pinnedActivityId?.let { pin ->
            for (ms in path) {
                val a = ms.activities.firstOrNull { it.id == pin }
                if (a != null && usable(a) && unlocked(ms)) return ms to a
            }
        }
        for (ms in path) {
            if (ms.id in done || !unlocked(ms)) continue
            val a = ms.activities.filter { usable(it) }.sortedWith(compareBy<Activity> { it.order }.thenBy { it.id }).firstOrNull()
            if (a != null) return ms to a
        }
        return null
    }

    fun isLastInMiniSkill(ms: MiniSkill, a: Activity): Boolean =
        ms.activities.count { it.selected && !it.completed } == 1 && !a.completed

    fun pathComplete(skill: Skill): Boolean = skill.path.isNotEmpty() && skill.path.all { passable(it) }
}

/** Turns the user's data into planner candidates (Section 6.1 step 4). Pure. */
object CandidateBuilder {
    const val ACTIVE_CAP = 3
    const val RECALL_MINUTES = 5

    fun activeSkills(skills: List<Skill>): List<Skill> =
        skills.filter { it.status == SkillStatus.ACTIVE }
            .sortedWith(compareByDescending<Skill> { it.priority }.thenBy { it.id })
            .take(ACTIVE_CAP)

    fun build(
        today: LocalDate,
        skills: List<Skill>,
        recalls: List<RecallCheck>,
        recentLogs: List<QuestLog>,
        goalLinks: Map<String, GoalLink> = emptyMap(),
    ): List<PlanCandidate> {
        val out = mutableListOf<PlanCandidate>()
        val bySkill = skills.associateBy { it.id }
        val practisedRecently = recentLogs
            .filter { it.state != QuestState.SKIPPED && !it.date.isBefore(today.minusDays(1)) && !it.date.isAfter(today) }
            .map { it.skillId }.toSet()

        fun goalFields(skillId: String): Triple<Double, Boolean, String?> {
            val g = goalLinks[skillId] ?: return Triple(0.0, false, null)
            val urgency = g.targetDate?.let {
                val left = ChronoUnit.DAYS.between(today, it).toDouble()
                (1.0 - left / 60.0).coerceIn(0.0, 1.0)
            } ?: 0.0
            return Triple(urgency, g.core, g.goalTitle)
        }

        for (skill in activeSkills(skills)) {
            val (urgency, core, goalTitle) = goalFields(skill.id)
            val next = NextActivitySelector.next(skill, today)
            if (next != null) {
                val (ms, a) = next
                out += PlanCandidate(
                    id = a.id, kind = CandidateKind.ACTIVITY, skillId = skill.id, skillName = skill.name,
                    title = a.title, estMinutes = a.estMinutes, tinyTitle = a.tinyTitle, tinyMinutes = a.tinyMinutes,
                    priority = skill.priority, deadlineUrgency = urgency, goalCore = core, goalTitle = goalTitle,
                    unlocksNextStep = NextActivitySelector.isLastInMiniSkill(ms, a),
                    recentRepetition = skill.id in practisedRecently, forceTiny = a.preferTiny, activityId = a.id,
                )
            } else if (NextActivitySelector.pathComplete(skill) && skill.capstoneTitle != null && !skill.capstoneDone) {
                val minutes = if (skill.capstoneHours > 0) (skill.capstoneHours * 60).toInt().coerceIn(20, 90) else 45
                out += PlanCandidate(
                    id = "capstone:${skill.id}", kind = CandidateKind.CAPSTONE, skillId = skill.id, skillName = skill.name,
                    title = "Capstone: ${skill.capstoneTitle}", estMinutes = minutes,
                    tinyTitle = "Capstone: 10-minute push on ${skill.capstoneTitle}", tinyMinutes = 10,
                    priority = skill.priority, deadlineUrgency = urgency, goalCore = core, goalTitle = goalTitle,
                    recentRepetition = skill.id in practisedRecently,
                )
            }
        }

        for (r in recalls) {
            if (r.result != RecallResult.PENDING || r.due.isAfter(today)) continue
            val skill = bySkill[r.skillId] ?: continue
            if (skill.status == SkillStatus.DROPPED || skill.status == SkillStatus.PAUSED) continue
            val ms = skill.miniSkills.firstOrNull { it.id == r.miniSkillId } ?: continue
            val label = if (r.mode == RecallMode.TEACH_BACK) "Teach-back" else "Recall check"
            out += PlanCandidate(
                id = r.id, kind = CandidateKind.RECALL, skillId = skill.id, skillName = skill.name,
                title = "$label: ${ms.name}", estMinutes = RECALL_MINUTES,
                tinyTitle = "$label: ${ms.name}", tinyMinutes = RECALL_MINUTES,
                priority = skill.priority, recallOverdueDays = ChronoUnit.DAYS.between(r.due, today).toInt(),
                recallCheckId = r.id,
            )
        }

        wildcard(today, skills, recentLogs)?.let { out += it }
        return out
    }

    /** One weekly wildcard: the sampler of the oldest SAMPLING skill, if no sampler was logged this week. */
    fun wildcard(today: LocalDate, skills: List<Skill>, recentLogs: List<QuestLog>): PlanCandidate? {
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val samplerIds = skills.flatMap { s -> s.sampler?.activities?.map { it.id } ?: emptyList() }.toSet()
        val usedThisWeek = recentLogs.any { it.activityId in samplerIds && !it.date.isBefore(weekStart) && it.state != QuestState.SKIPPED }
        if (usedThisWeek) return null
        val skill = skills.filter { it.status == SkillStatus.SAMPLING }
            .sortedWith(compareBy<Skill> { it.createdOn ?: LocalDate.MIN }.thenBy { it.id })
            .firstOrNull { s -> s.sampler?.activities?.any { !it.completed } == true } ?: return null
        val a = skill.sampler!!.activities.first { !it.completed }
        return PlanCandidate(
            id = a.id, kind = CandidateKind.WILDCARD, skillId = skill.id, skillName = skill.name,
            title = "Sampler: ${a.title}", estMinutes = a.estMinutes, tinyTitle = a.tinyTitle, tinyMinutes = a.tinyMinutes,
            priority = skill.priority, activityId = a.id,
        )
    }
}
