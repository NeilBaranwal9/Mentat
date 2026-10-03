package com.polymath.os.data.repo

import com.polymath.os.data.db.CuriosityDao
import com.polymath.os.data.db.PendingAiActionEntity
import com.polymath.os.data.db.PendingAiDao
import com.polymath.os.data.db.ProposalDao
import com.polymath.os.data.db.ProposedChangeEntity
import com.polymath.os.data.db.QuestLogDao
import com.polymath.os.data.db.SkillDao
import com.polymath.os.domain.DispatcherProvider
import com.polymath.os.domain.agent.AgentId
import com.polymath.os.domain.agent.AgentOutcome
import com.polymath.os.domain.agent.AgentRunner
import com.polymath.os.domain.agent.AgentSpec
import com.polymath.os.domain.agent.EventBudget
import com.polymath.os.domain.agent.Prompts
import com.polymath.os.domain.agent.Templates
import com.polymath.os.domain.model.AiError
import com.polymath.os.domain.model.GoalMap
import com.polymath.os.domain.model.NormalizedCuriosity
import com.polymath.os.domain.model.ParsedCommands
import com.polymath.os.domain.model.ProposalStatus
import com.polymath.os.domain.model.RewrittenTask
import com.polymath.os.domain.model.SkillBlueprint
import com.polymath.os.domain.model.SkillStatus
import com.polymath.os.domain.newId
import com.polymath.os.domain.validate.BlueprintValidator
import com.polymath.os.domain.validate.CommandValidator
import com.polymath.os.domain.validate.CuriosityValidator
import com.polymath.os.domain.validate.GoalMapValidator
import com.polymath.os.domain.validate.RewrittenTaskValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

object ProposalKind {
    const val SKILL_BLUEPRINT = "SKILL_BLUEPRINT"
    const val COMMANDS = "COMMANDS"
    const val CURIOSITY = "CURIOSITY"
    const val GOAL_MAP = "GOAL_MAP"
    const val TASK_REWRITE = "TASK_REWRITE"
}

object PendingKind {
    const val GENERATE_SKILL = "GENERATE_SKILL"
    const val NORMALIZE_CURIOSITY = "NORMALIZE_CURIOSITY"
    const val PARSE_COMMAND = "PARSE_COMMAND"
    const val MAP_GOAL = "MAP_GOAL"
    const val REWRITE_TASK = "REWRITE_TASK"
}

/** Non-AI context saved with a proposal so it can be committed later. */
@Serializable
data class ProposalContext(
    val activityId: String? = null,
    val itemId: String? = null,
    val curiosityId: String? = null,
    val goalSkillId: String? = null,
    val weaknessEventId: String? = null,
    val message: String? = null,
)

@Serializable
data class PendingPayload(
    val name: String? = null,
    val constraints: String? = null,
    val text: String? = null,
    val itemId: String? = null,
    val activityId: String? = null,
    val tag: String? = null,
    val context: ProposalContext = ProposalContext(),
)

sealed interface AiOutcome {
    data class Proposed(val proposalId: String) : AiOutcome
    /** Gate G5 (or budget exhausted inside the event): show the manual form. */
    data class Manual(val reason: String, val errors: List<String>) : AiOutcome
    data class Error(val error: AiError) : AiOutcome
}

/**
 * Builds minimal agent context, runs the agent pipeline and stores the result as a PENDING
 * ProposedChange (gate G1). Never writes domain data itself. Only the minimal text each agent needs
 * is sent; photos, journals and teach-back text never leave the device.
 */
@Singleton
class AiRepository @Inject constructor(
    private val runner: AgentRunner,
    private val proposalDao: ProposalDao,
    private val pendingDao: PendingAiDao,
    private val skillDao: SkillDao,
    private val curiosityDao: CuriosityDao,
    private val logDao: QuestLogDao,
    private val profile: ProfileRepository,
    private val clock: Clock,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) {
    val pending: Flow<List<PendingAiActionEntity>> = pendingDao.observeAll().flowOn(dispatchers.io)
    val pendingProposals: Flow<List<ProposedChangeEntity>> = proposalDao.observePending().flowOn(dispatchers.io)

    private fun today() = LocalDate.now(clock)

    suspend fun remainingBudgetInr(): Int = withContext(dispatchers.io) {
        val p = profile.get()
        val month = YearMonth.from(today())
        val spent = skillDao.getSkills().filter { YearMonth.from(it.createdOn) == month && it.status != SkillStatus.DROPPED }.sumOf { it.estCostInr }
        (p.monthlyBudgetInr - spent).coerceAtLeast(0)
    }

    // ---- Agents ----

    suspend fun generateSkill(name: String, constraints: String, context: ProposalContext = ProposalContext()): AiOutcome = withContext(dispatchers.io) {
        val p = profile.get()
        val existing = skillDao.getSkills().filter { it.status != SkillStatus.DROPPED }.map { it.name }
        val vars = mapOf(
            "skill_name" to Templates.sanitizeUserText(name),
            "constraints" to Templates.sanitizeUserText(constraints.ifBlank { "none" }),
            "existing_skills" to existing.joinToString().ifBlank { "none" },
            "budget_inr" to remainingBudgetInr().toString(),
            "session_minutes" to p.sessionMinutes.toString(),
        )
        val spec = AgentSpec(AgentId.A2_SKILL_ARCHITECT, Prompts.SKILL_ARCHITECT, SkillBlueprint.serializer(), 6000) { BlueprintValidator.validate(it, aiRules = true) }
        run(spec, vars, ProposalKind.SKILL_BLUEPRINT, SkillBlueprint.serializer(), context)
    }

    /** Ids the Command Parser may reference: every skill that is not dropped (paused ones are needed for resume_skill). */
    suspend fun commandSkills(): List<Pair<String, String>> = withContext(dispatchers.io) {
        skillDao.getSkills().filter { it.status != SkillStatus.DROPPED && it.status != SkillStatus.DONE }
            .sortedBy { it.name }
            .map { it.id to (it.name + if (it.status != SkillStatus.ACTIVE) " (${it.status.name.lowercase()})" else "") }
    }

    suspend fun parseCommand(message: String, budget: EventBudget = EventBudget()): AiOutcome = withContext(dispatchers.io) {
        val t = today()
        val skills = commandSkills()
        val vars = mapOf(
            "today" to t.toString(),
            "weekday" to t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
            "timezone" to clock.zone.id,
            "active_skills" to skills.joinToString("; ") { "${it.first}: ${it.second}" }.ifBlank { "none" },
            "message" to Templates.sanitizeUserText(message),
        )
        val ids = skills.map { it.first }.toSet()
        val spec = AgentSpec(AgentId.A5_COMMAND_PARSER, Prompts.COMMAND_PARSER, ParsedCommands.serializer(), 2000) { CommandValidator.validate(it, ids) }
        run(spec, vars, ProposalKind.COMMANDS, ParsedCommands.serializer(), ProposalContext(message = message), budget)
    }

    suspend fun normalizeCuriosity(itemId: String): AiOutcome = withContext(dispatchers.io) {
        val item = curiosityDao.get(itemId) ?: return@withContext AiOutcome.Error(AiError.UNKNOWN)
        val others = curiosityDao.inbox().filter { it.id != itemId }
        val active = skillDao.getSkills().filter { it.status == SkillStatus.ACTIVE }.map { it.name }
        val vars = mapOf(
            "raw_text" to Templates.sanitizeUserText(item.rawText),
            "inbox_items" to others.joinToString("; ") { "${it.id}: ${it.normalizedTitle ?: it.rawText.take(80)}" }.ifBlank { "none" },
            "active_skills" to active.joinToString().ifBlank { "none" },
        )
        val spec = AgentSpec(AgentId.A6_CURIOSITY_NORMALIZER, Prompts.CURIOSITY_NORMALIZER, NormalizedCuriosity.serializer(), 1500) {
            CuriosityValidator.validate(it, others.map { o -> o.id }.toSet(), active.toSet())
        }
        run(spec, vars, ProposalKind.CURIOSITY, NormalizedCuriosity.serializer(), ProposalContext(itemId = itemId))
    }

    suspend fun mapGoal(goalText: String): AiOutcome = withContext(dispatchers.io) {
        val existing = skillDao.getSkills().filter { it.status != SkillStatus.DROPPED }.map { it.name }
        val vars = mapOf(
            "goal_text" to Templates.sanitizeUserText(goalText),
            "existing_skills" to existing.joinToString().ifBlank { "none" },
            "today" to today().toString(),
        )
        val spec = AgentSpec(AgentId.A3_GOAL_MAPPER, Prompts.GOAL_MAPPER, GoalMap.serializer(), 3000) { GoalMapValidator.validate(it) }
        run(spec, vars, ProposalKind.GOAL_MAP, GoalMap.serializer(), ProposalContext(message = goalText))
    }

    /** Called ONCE for an "unclear" weakness answer (or from the activity menu). */
    suspend fun rewriteTask(activityId: String, tag: String, weaknessEventId: String? = null): AiOutcome = withContext(dispatchers.io) {
        val act = skillDao.getActivity(activityId) ?: return@withContext AiOutcome.Error(AiError.UNKNOWN)
        val ms = skillDao.getMiniSkill(act.miniSkillId)
        val tags = logDao.getAll().filter { it.activityId == activityId }.mapNotNull { it.tag?.name?.lowercase() }
        val vars = mapOf(
            "miniskill_name" to (ms?.name ?: "this skill"),
            "task_title" to Templates.sanitizeUserText(act.title),
            "est_minutes" to act.estMinutes.toString(),
            "friction_tag" to tag,
            "tag_history" to tags.joinToString().ifBlank { "none" },
        )
        val spec = AgentSpec(AgentId.A4_TASK_REWRITER, Prompts.TASK_REWRITER, RewrittenTask.serializer(), 2000) { RewrittenTaskValidator.validate(it, act.estMinutes) }
        run(spec, vars, ProposalKind.TASK_REWRITE, RewrittenTask.serializer(), ProposalContext(activityId = activityId, weaknessEventId = weaknessEventId))
    }

    private suspend fun <T> run(
        spec: AgentSpec<T>,
        vars: Map<String, String>,
        kind: String,
        serializer: KSerializer<T>,
        context: ProposalContext,
        budget: EventBudget = EventBudget(),
    ): AiOutcome = when (val out = runner.run(spec, vars, budget)) {
        is AgentOutcome.Valid -> {
            val id = newId()
            proposalDao.insert(
                ProposedChangeEntity(
                    id = id, producedBy = spec.id.code, kind = kind,
                    payloadJson = json.encodeToString(serializer, out.value),
                    contextJson = json.encodeToString(ProposalContext.serializer(), context),
                    status = ProposalStatus.PENDING, createdAt = LocalDateTime.now(clock),
                ),
            )
            AiOutcome.Proposed(id)
        }
        is AgentOutcome.ManualForm -> AiOutcome.Manual(out.reason, out.errors)
        is AgentOutcome.Failed -> AiOutcome.Error(out.error)
    }

    // ---- Offline queue (runs only when the user taps "Run now") ----

    suspend fun savePending(kind: String, label: String, payload: PendingPayload) = withContext(dispatchers.io) {
        pendingDao.insert(PendingAiActionEntity(newId(), kind, label, json.encodeToString(PendingPayload.serializer(), payload), LocalDateTime.now(clock)))
    }

    suspend fun discardPending(id: String) = withContext(dispatchers.io) { pendingDao.delete(id) }

    suspend fun runPending(id: String): AiOutcome = withContext(dispatchers.io) {
        val p = pendingDao.get(id) ?: return@withContext AiOutcome.Error(AiError.UNKNOWN)
        val payload = json.decodeFromString(PendingPayload.serializer(), p.payloadJson)
        val outcome = when (p.kind) {
            PendingKind.GENERATE_SKILL -> generateSkill(payload.name.orEmpty(), payload.constraints.orEmpty(), payload.context)
            PendingKind.NORMALIZE_CURIOSITY -> normalizeCuriosity(payload.itemId.orEmpty())
            PendingKind.PARSE_COMMAND -> parseCommand(payload.text.orEmpty())
            PendingKind.MAP_GOAL -> mapGoal(payload.text.orEmpty())
            PendingKind.REWRITE_TASK -> rewriteTask(payload.activityId.orEmpty(), payload.tag ?: "unclear", payload.context.weaknessEventId)
            else -> AiOutcome.Error(AiError.UNKNOWN)
        }
        // Keep it queued only for transient failures so the user can retry later.
        val transient = outcome is AiOutcome.Error && outcome.error in setOf(AiError.OFFLINE, AiError.TIMEOUT, AiError.SERVER, AiError.RATE_LIMITED, AiError.PAUSED, AiError.BUSY, AiError.BUDGET_EXHAUSTED, AiError.NO_KEY)
        if (!transient) pendingDao.delete(id)
        outcome
    }
}
