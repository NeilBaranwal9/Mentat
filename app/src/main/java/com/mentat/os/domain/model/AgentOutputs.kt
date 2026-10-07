package com.mentat.os.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Exact field names from Section 9/10. Decoded with ignoreUnknownKeys = false.

@Serializable
data class SkillBlueprint(
    @SerialName("skill_name") val skillName: String,
    val domain: String,
    @SerialName("sampler_session") val samplerSession: SamplerSession,
    val styles: List<StyleOut>,
    @SerialName("mini_skills") val miniSkills: List<MiniSkillOut>,
    val capstone: CapstoneOut,
    @SerialName("total_hours_to_basic_competence") val totalHours: Double,
    val resources: List<ResourceOut>,
    val assumptions: List<String>,
)

@Serializable
data class SamplerSession(
    val title: String,
    val minutes: Int,
    val steps: List<String>,
    @SerialName("tiny_title") val tinyTitle: String,
    @SerialName("tiny_minutes") val tinyMinutes: Int,
)

@Serializable
data class StyleOut(
    val name: String,
    @SerialName("one_line") val oneLine: String,
    @SerialName("why_start_here") val whyStartHere: String,
    val difficulty: Int,
    @SerialName("min_cost_inr") val minCostInr: Int,
)

@Serializable
data class MiniSkillOut(
    @SerialName("local_id") val localId: String,
    val name: String,
    val order: Int,
    @SerialName("prerequisite_local_ids") val prerequisiteLocalIds: List<String>,
    @SerialName("est_hours") val estHours: Double,
    val activities: List<ActivityOut>,
    @SerialName("recall_questions") val recallQuestions: List<String>,
    @SerialName("teach_back_prompt") val teachBackPrompt: String,
)

@Serializable
data class ActivityOut(
    val title: String,
    @SerialName("est_minutes") val estMinutes: Int,
    @SerialName("tiny_title") val tinyTitle: String,
    @SerialName("tiny_minutes") val tinyMinutes: Int,
    @SerialName("style_tag") val styleTag: String? = null,
)

@Serializable
data class CapstoneOut(
    val title: String,
    val description: String,
    @SerialName("est_hours") val estHours: Double,
)

@Serializable
data class ResourceOut(
    val title: String,
    val kind: String,
    @SerialName("est_cost_inr") val estCostInr: Int,
    @SerialName("free_alternative") val freeAlternative: String? = null,
)

@Serializable
data class ParsedCommands(
    val commands: List<ParsedCommand>,
    @SerialName("clarifying_question") val clarifyingQuestion: String? = null,
)

@Serializable
data class ParsedCommand(
    val type: String,
    val params: JsonObject,
    @SerialName("source_quote") val sourceQuote: String,
    val confidence: String,
)

@Serializable
data class RewrittenTask(
    val title: String,
    val steps: List<String>,
    @SerialName("est_minutes") val estMinutes: Int,
    @SerialName("tiny_title") val tinyTitle: String,
    @SerialName("tiny_minutes") val tinyMinutes: Int,
    @SerialName("self_check") val selfCheck: List<String>,
)

@Serializable
data class NormalizedCuriosity(
    @SerialName("normalized_title") val normalizedTitle: String,
    val domain: String,
    val hook: String,
    @SerialName("sampler_minutes") val samplerMinutes: Int,
    @SerialName("duplicate_of_id") val duplicateOfId: String? = null,
    @SerialName("related_active_skills") val relatedActiveSkills: List<String>,
)

@Serializable
data class GoalMap(
    @SerialName("goal_title") val goalTitle: String,
    @SerialName("suggested_target_date") val suggestedTargetDate: String? = null,
    @SerialName("required_skills") val requiredSkills: List<GoalSkillOut>,
    val assumptions: List<String>,
)

@Serializable
data class GoalSkillOut(
    val name: String,
    val importance: String,
    @SerialName("depends_on") val dependsOn: List<String>,
    @SerialName("est_hours") val estHours: Double,
    val reason: String,
)
