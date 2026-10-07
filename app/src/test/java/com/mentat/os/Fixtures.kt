package com.mentat.os

import com.mentat.os.domain.model.Activity
import com.mentat.os.domain.model.ActivityOut
import com.mentat.os.domain.model.CapstoneOut
import com.mentat.os.domain.model.MiniSkill
import com.mentat.os.domain.model.MiniSkillOut
import com.mentat.os.domain.model.QuestLog
import com.mentat.os.domain.model.QuestState
import com.mentat.os.domain.model.ResourceOut
import com.mentat.os.domain.model.SamplerSession
import com.mentat.os.domain.model.Skill
import com.mentat.os.domain.model.SkillBlueprint
import com.mentat.os.domain.model.SkillStatus
import com.mentat.os.domain.model.Source
import com.mentat.os.domain.model.StyleOut
import com.mentat.os.domain.model.FrictionTag
import java.time.LocalDate

object Fixtures {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 7) // a Wednesday

    fun activity(id: String, ms: String, est: Int = 30, tiny: Int = 5, order: Int = 0, completed: Boolean = false, selected: Boolean = true) =
        Activity(id, ms, "Task $id", est, "Tiny $id", tiny, null, selected, Source.MANUAL, order, completed)

    fun miniSkill(id: String, order: Int, acts: List<Activity>, prereq: List<String> = emptyList(), sampler: Boolean = false) =
        MiniSkill(id, "Mini $id", order, prereq, 2.0, acts, listOf("q1", "q2", "q3"), "teach", sampler)

    fun skill(id: String, priority: Int = 3, status: SkillStatus = SkillStatus.ACTIVE, created: LocalDate = TODAY.minusDays(30), vararg ms: MiniSkill) =
        Skill(id, "Skill $id", status, priority, ms.toList(), "Capstone $id", false, 0, Source.MANUAL, 1, 2.0, null, created)

    /** Skill with two mini-skills of two activities each. */
    fun simpleSkill(id: String, priority: Int = 3, status: SkillStatus = SkillStatus.ACTIVE) = skill(
        id, priority, status, TODAY.minusDays(30),
        miniSkill("${id}_m1", 1, listOf(activity("${id}_a1", "${id}_m1", order = 0), activity("${id}_a2", "${id}_m1", order = 1))),
        miniSkill("${id}_m2", 2, listOf(activity("${id}_a3", "${id}_m2", order = 0), activity("${id}_a4", "${id}_m2", order = 1)), prereq = listOf("${id}_m1")),
    )

    fun log(id: String, activityId: String?, skillId: String, date: LocalDate, state: QuestState = QuestState.DONE, actual: Int = 30, est: Int = 30, tag: FrictionTag? = null, proof: String? = null, tiny: Boolean = false) =
        QuestLog(id, activityId, skillId, date, state, tiny, actual, tag, proof, null, est, date.atTime(12, 0))

    fun validBlueprint(): SkillBlueprint = SkillBlueprint(
        skillName = "Sketching",
        domain = "Art",
        samplerSession = SamplerSession("Draw a mug", 20, listOf("Look", "Draw"), "Outline only", 5),
        styles = (1..5).map { StyleOut("Style $it", "One line", "Why", 1, 0) },
        miniSkills = (1..4).map { i ->
            MiniSkillOut(
                "m$i", "Mini $i", i, if (i == 1) emptyList() else listOf("m${i - 1}"), 2.0,
                listOf(ActivityOut("Activity $i", 30, "Tiny $i", 5, null)),
                listOf("Q1", "Q2", "Q3"), "Explain it",
            )
        },
        capstone = CapstoneOut("Capstone", "Finish a drawing", 2.0),
        totalHours = 10.0,
        resources = listOf(ResourceOut("Pencil", "tool", 50, "Any pencil")),
        assumptions = listOf("Some"),
    )
}
