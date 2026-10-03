package com.polymath.os.data.repo

import com.polymath.os.data.db.ActivityEntity
import com.polymath.os.data.db.CommitmentEntity
import com.polymath.os.data.db.EnergyLogEntity
import com.polymath.os.data.db.JsonLists
import com.polymath.os.data.db.ProfileEntity
import com.polymath.os.data.db.QuestLogEntity
import com.polymath.os.data.db.RecallCheckEntity
import com.polymath.os.data.db.SkillTree
import com.polymath.os.domain.model.Activity
import com.polymath.os.domain.model.ActivityDraft
import com.polymath.os.domain.model.Commitment
import com.polymath.os.domain.model.DateRange
import com.polymath.os.domain.model.EnergyLog
import com.polymath.os.domain.model.MiniSkill
import com.polymath.os.domain.model.MiniSkillDraft
import com.polymath.os.domain.model.QuestLog
import com.polymath.os.domain.model.RecallCheck
import com.polymath.os.domain.model.ResourceOut
import com.polymath.os.domain.model.Skill
import com.polymath.os.domain.model.SkillDraft
import com.polymath.os.domain.model.SleepWindow
import com.polymath.os.domain.model.StyleOut
import com.polymath.os.domain.validate.AgentJson
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalTime

fun ActivityEntity.toDomain() = Activity(
    id = id, miniSkillId = miniSkillId, title = title, estMinutes = estMinutes, tinyTitle = tinyTitle,
    tinyMinutes = tinyMinutes, styleTag = styleTag, selected = selected, source = source, order = order,
    completed = completed, preferTiny = preferTiny, weekendOnly = weekendOnly, steps = JsonLists.decode(stepsJson),
)

fun SkillTree.toDomain() = Skill(
    id = skill.id, name = skill.name, status = skill.status, priority = skill.priority,
    miniSkills = miniSkills.map { m ->
        MiniSkill(
            id = m.miniSkill.id, name = m.miniSkill.name, order = m.miniSkill.order,
            prerequisiteIds = JsonLists.decode(m.miniSkill.prerequisiteIdsJson), estHours = m.miniSkill.estHours,
            activities = m.activities.sortedWith(compareBy({ it.order }, { it.id })).map { it.toDomain() },
            recallQuestions = m.questions.sortedBy { it.order }.map { it.text },
            teachBackPrompt = m.miniSkill.teachBackPrompt, isSampler = m.miniSkill.isSampler,
        )
    }.sortedBy { it.order },
    capstoneTitle = skill.capstoneTitle, capstoneDone = skill.capstoneDone, estCostInr = skill.estCostInr,
    source = skill.source, version = skill.version, capstoneHours = skill.capstoneHours,
    pinnedActivityId = skill.pinnedActivityId, createdOn = skill.createdOn,
)

fun QuestLogEntity.toDomain() = QuestLog(
    id = id, activityId = activityId, skillId = skillId, date = date, state = state, usedTinyVersion = usedTinyVersion,
    actualMinutes = actualMinutes, tag = tag, proofPath = proofPath, note = note, estMinutes = estMinutes, loggedAt = loggedAt,
)

fun RecallCheckEntity.toDomain() = RecallCheck(
    id = id, miniSkillId = miniSkillId, skillId = skillId, due = due, mode = mode, result = result,
    teachBackText = teachBackText, sequence = sequence, answeredOn = answeredOn,
)

fun CommitmentEntity.toDomain() = Commitment(id, title, kind, start, end, recurrence)
fun EnergyLogEntity.toDomain() = EnergyLog(at, level)

fun ProfileEntity.sleepWindow(): SleepWindow? = runCatching { SleepWindow(LocalTime.parse(sleepStart), LocalTime.parse(sleepEnd)) }.getOrNull()
fun ProfileEntity.examRanges(): List<DateRange> =
    if (examStart != null && examEnd != null && !examEnd.isBefore(examStart)) listOf(DateRange(examStart, examEnd)) else emptyList()

private val styleList = ListSerializer(StyleOut.serializer())
private val resourceList = ListSerializer(ResourceOut.serializer())

fun encodeStyles(s: List<StyleOut>): String = AgentJson.relaxed.encodeToString(styleList, s)
fun decodeStyles(s: String): List<StyleOut> = runCatching { AgentJson.relaxed.decodeFromString(styleList, s) }.getOrDefault(emptyList())
fun encodeResources(r: List<ResourceOut>): String = AgentJson.relaxed.encodeToString(resourceList, r)
fun decodeResources(s: String): List<ResourceOut> = runCatching { AgentJson.relaxed.decodeFromString(resourceList, s) }.getOrDefault(emptyList())

/** Builds an editable draft from a stored skill, preserving ids so logs survive edits. */
fun SkillTree.toDraft(): SkillDraft {
    val sampler = miniSkills.firstOrNull { it.miniSkill.isSampler }
    val samplerAct = sampler?.activities?.firstOrNull()
    val path = miniSkills.filter { !it.miniSkill.isSampler }.sortedBy { it.miniSkill.order }
    val keyOf = path.mapIndexed { i, m -> m.miniSkill.id to "m${i + 1}" }.toMap()
    return SkillDraft(
        id = skill.id,
        name = skill.name,
        domain = skill.domain.orEmpty(),
        samplerTitle = samplerAct?.title.orEmpty(),
        samplerMinutes = samplerAct?.estMinutes ?: 20,
        samplerSteps = samplerAct?.let { JsonLists.decode(it.stepsJson) } ?: emptyList(),
        samplerTinyTitle = samplerAct?.tinyTitle.orEmpty(),
        samplerTinyMinutes = samplerAct?.tinyMinutes ?: 10,
        miniSkills = path.map { m ->
            MiniSkillDraft(
                id = m.miniSkill.id, key = keyOf.getValue(m.miniSkill.id), name = m.miniSkill.name, estHours = m.miniSkill.estHours,
                prerequisiteKeys = JsonLists.decode(m.miniSkill.prerequisiteIdsJson).mapNotNull { keyOf[it] },
                activities = m.activities.sortedWith(compareBy({ it.order }, { it.id })).map { a ->
                    ActivityDraft(a.id, a.title, a.estMinutes, a.tinyTitle, a.tinyMinutes, a.styleTag, a.selected, JsonLists.decode(a.stepsJson))
                },
                recallQuestions = m.questions.sortedBy { it.order }.map { it.text },
                teachBackPrompt = m.miniSkill.teachBackPrompt,
            )
        },
        capstoneTitle = skill.capstoneTitle.orEmpty(),
        capstoneDescription = skill.capstoneDescription.orEmpty(),
        capstoneHours = skill.capstoneHours,
        totalHours = skill.totalHours,
        styles = decodeStyles(skill.stylesJson),
        resources = decodeResources(skill.resourcesJson),
        assumptions = JsonLists.decode(skill.assumptionsJson),
    )
}
