package com.mentat.os.domain.model

import kotlinx.serialization.Serializable

/**
 * Editable form of a skill path. Used by the manual entry form, the G1 "Edit" option and the
 * G5 manual fallback. Ids are null for rows that do not exist in the database yet.
 */
@Serializable
data class ActivityDraft(
    val id: String? = null,
    val title: String = "",
    val estMinutes: Int = 25,
    val tinyTitle: String = "",
    val tinyMinutes: Int = 5,
    val styleTag: String? = null,
    val selected: Boolean = true,
    val steps: List<String> = emptyList(),
)

@Serializable
data class MiniSkillDraft(
    val id: String? = null,
    /** Stable key inside the draft ("m1", "m2", ...) used by prerequisites. */
    val key: String,
    val name: String = "",
    val estHours: Double = 2.0,
    val prerequisiteKeys: List<String> = emptyList(),
    val activities: List<ActivityDraft> = listOf(ActivityDraft()),
    val recallQuestions: List<String> = emptyList(),
    val teachBackPrompt: String = "",
)

@Serializable
data class SkillDraft(
    val id: String? = null,
    val name: String = "",
    val domain: String = "",
    val samplerTitle: String = "",
    val samplerMinutes: Int = 20,
    val samplerSteps: List<String> = emptyList(),
    val samplerTinyTitle: String = "",
    val samplerTinyMinutes: Int = 10,
    val miniSkills: List<MiniSkillDraft> = listOf(MiniSkillDraft(key = "m1")),
    val capstoneTitle: String = "",
    val capstoneDescription: String = "",
    val capstoneHours: Double = 3.0,
    val totalHours: Double = 0.0,
    val styles: List<StyleOut> = emptyList(),
    val resources: List<ResourceOut> = emptyList(),
    val assumptions: List<String> = emptyList(),
) {
    fun toBlueprint(): SkillBlueprint = SkillBlueprint(
        skillName = name,
        domain = domain,
        samplerSession = SamplerSession(samplerTitle, samplerMinutes, samplerSteps, samplerTinyTitle, samplerTinyMinutes),
        styles = styles,
        miniSkills = miniSkills.mapIndexed { i, m ->
            MiniSkillOut(
                localId = m.key, name = m.name, order = i + 1, prerequisiteLocalIds = m.prerequisiteKeys, estHours = m.estHours,
                activities = m.activities.map { ActivityOut(it.title, it.estMinutes, it.tinyTitle, it.tinyMinutes, it.styleTag) },
                recallQuestions = m.recallQuestions, teachBackPrompt = m.teachBackPrompt,
            )
        },
        capstone = CapstoneOut(capstoneTitle, capstoneDescription, capstoneHours),
        totalHours = if (totalHours > 0) totalHours else miniSkills.sumOf { it.estHours } + capstoneHours,
        resources = resources,
        assumptions = assumptions,
    )

    companion object {
        fun fromBlueprint(bp: SkillBlueprint): SkillDraft = SkillDraft(
            name = bp.skillName,
            domain = bp.domain,
            samplerTitle = bp.samplerSession.title,
            samplerMinutes = bp.samplerSession.minutes,
            samplerSteps = bp.samplerSession.steps,
            samplerTinyTitle = bp.samplerSession.tinyTitle,
            samplerTinyMinutes = bp.samplerSession.tinyMinutes,
            miniSkills = bp.miniSkills.sortedBy { it.order }.map { m ->
                MiniSkillDraft(
                    key = m.localId, name = m.name, estHours = m.estHours, prerequisiteKeys = m.prerequisiteLocalIds,
                    activities = m.activities.map { ActivityDraft(null, it.title, it.estMinutes, it.tinyTitle, it.tinyMinutes, it.styleTag) },
                    recallQuestions = m.recallQuestions, teachBackPrompt = m.teachBackPrompt,
                )
            },
            capstoneTitle = bp.capstone.title,
            capstoneDescription = bp.capstone.description,
            capstoneHours = bp.capstone.estHours,
            totalHours = bp.totalHours,
            styles = bp.styles,
            resources = bp.resources,
            assumptions = bp.assumptions,
        )
    }
}
