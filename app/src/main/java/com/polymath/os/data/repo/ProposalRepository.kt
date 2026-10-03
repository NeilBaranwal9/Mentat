package com.polymath.os.data.repo

import androidx.room.withTransaction
import com.polymath.os.data.db.CuriosityDao
import com.polymath.os.data.db.GoalDao
import com.polymath.os.data.db.GoalEntity
import com.polymath.os.data.db.GoalSkillEntity
import com.polymath.os.data.db.JsonLists
import com.polymath.os.data.db.PolymathDatabase
import com.polymath.os.data.db.ProposalDao
import com.polymath.os.data.db.ProposedChangeEntity
import com.polymath.os.data.db.SkillDao
import com.polymath.os.domain.DispatcherProvider
import com.polymath.os.domain.engine.ComboMatcher
import com.polymath.os.domain.model.CommitKind
import com.polymath.os.domain.model.CuriosityStatus
import com.polymath.os.domain.model.GoalMap
import com.polymath.os.domain.model.NormalizedCuriosity
import com.polymath.os.domain.model.ParsedCommand
import com.polymath.os.domain.model.ParsedCommands
import com.polymath.os.domain.model.ProposalStatus
import com.polymath.os.domain.model.Recurrence
import com.polymath.os.domain.model.RewrittenTask
import com.polymath.os.domain.model.SkillBlueprint
import com.polymath.os.domain.model.SkillDraft
import com.polymath.os.domain.model.SkillStatus
import com.polymath.os.domain.model.Source
import com.polymath.os.domain.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Commits a proposal only after the user accepted it (gate G1). Rejecting never touches domain data. */
@Singleton
class ProposalRepository @Inject constructor(
    private val db: PolymathDatabase,
    private val dao: ProposalDao,
    private val skillDao: SkillDao,
    private val curiosityDao: CuriosityDao,
    private val goalDao: GoalDao,
    private val skills: SkillRepository,
    private val logs: LogRepository,
    private val profile: ProfileRepository,
    private val commitments: CommitmentRepository,
    private val weakness: WeaknessRepository,
    private val plans: PlanRepository,
    private val json: Json,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) {
    fun observe(id: String): Flow<ProposedChangeEntity?> = dao.observe(id).flowOn(dispatchers.io)
    suspend fun get(id: String) = withContext(dispatchers.io) { dao.get(id) }

    fun context(p: ProposedChangeEntity): ProposalContext = runCatching { json.decodeFromString(ProposalContext.serializer(), p.contextJson) }.getOrDefault(ProposalContext())
    fun blueprint(p: ProposedChangeEntity): SkillBlueprint = json.decodeFromString(SkillBlueprint.serializer(), p.payloadJson)
    fun commands(p: ProposedChangeEntity): ParsedCommands = json.decodeFromString(ParsedCommands.serializer(), p.payloadJson)
    fun curiosity(p: ProposedChangeEntity): NormalizedCuriosity = json.decodeFromString(NormalizedCuriosity.serializer(), p.payloadJson)
    fun goalMap(p: ProposedChangeEntity): GoalMap = json.decodeFromString(GoalMap.serializer(), p.payloadJson)
    fun rewrite(p: ProposedChangeEntity): RewrittenTask = json.decodeFromString(RewrittenTask.serializer(), p.payloadJson)

    suspend fun reject(id: String) = withContext(dispatchers.io) { dao.resolve(id, ProposalStatus.REJECTED, LocalDateTime.now(clock)) }

    private suspend fun resolve(id: String, edited: Boolean) =
        dao.resolve(id, if (edited) ProposalStatus.EDITED else ProposalStatus.ACCEPTED, LocalDateTime.now(clock))

    /** Blueprint accept / edit. [freePath] = gate G6 chose the free alternatives (cost recorded as 0). */
    suspend fun commitSkill(id: String, draft: SkillDraft, edited: Boolean, freePath: Boolean): String = withContext(dispatchers.io) {
        val p = dao.get(id) ?: error("Proposal not found")
        val cost = if (freePath) 0 else draft.resources.sumOf { it.estCostInr }
        val skillId = skills.saveDraft(draft, Source.AI, cost)
        val ctx = context(p)
        ctx.curiosityId?.let { curiosityDao.setStatus(it, CuriosityStatus.PROMOTED) }
        ctx.goalSkillId?.let { goalDao.link(it, skillId) }
        resolve(id, edited)
        plans.markDirty()
        skillId
    }

    suspend fun commitCuriosity(id: String, value: NormalizedCuriosity, edited: Boolean, deleteAsDuplicate: Boolean) = withContext(dispatchers.io) {
        val p = dao.get(id) ?: return@withContext
        val itemId = context(p).itemId ?: return@withContext
        val item = curiosityDao.get(itemId) ?: return@withContext
        if (deleteAsDuplicate) {
            curiosityDao.setStatus(itemId, CuriosityStatus.DELETED)
        } else {
            curiosityDao.upsert(
                item.copy(
                    normalizedTitle = value.normalizedTitle, domain = value.domain, hook = value.hook,
                    samplerMinutes = value.samplerMinutes, relatedSkillsJson = JsonLists.encode(value.relatedActiveSkills),
                    duplicateOfId = value.duplicateOfId,
                ),
            )
        }
        resolve(id, edited)
    }

    suspend fun commitGoal(id: String, value: GoalMap, edited: Boolean): String = withContext(dispatchers.io) {
        val goalId = newId()
        db.withTransaction {
            val existing = skillDao.getSkills().filter { it.status != SkillStatus.DROPPED }
            goalDao.upsertGoal(
                GoalEntity(goalId, value.goalTitle.trim(), value.suggestedTargetDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, LocalDate.now(clock), JsonLists.encode(value.assumptions)),
            )
            goalDao.upsertSkills(
                value.requiredSkills.map { s ->
                    GoalSkillEntity(
                        id = newId(), goalId = goalId, name = s.name.trim(), importance = s.importance,
                        dependsOnJson = JsonLists.encode(s.dependsOn), estHours = s.estHours, reason = s.reason,
                        linkedSkillId = existing.firstOrNull { e -> ComboMatcher.matches(s.name, e.name) }?.id,
                    )
                },
            )
            resolve(id, edited)
        }
        plans.markDirty()
        goalId
    }

    suspend fun commitRewrite(id: String, value: RewrittenTask, edited: Boolean) = withContext(dispatchers.io) {
        val p = dao.get(id) ?: return@withContext
        val ctx = context(p)
        val act = ctx.activityId?.let { skillDao.getActivity(it) } ?: return@withContext
        skillDao.upsertActivities(
            listOf(
                act.copy(
                    title = value.title.trim(), estMinutes = value.estMinutes.coerceIn(5, 120), tinyTitle = value.tinyTitle.trim(),
                    tinyMinutes = value.tinyMinutes.coerceIn(3, 10), stepsJson = JsonLists.encode(value.steps),
                    selfCheckJson = JsonLists.encode(value.selfCheck), source = Source.AI,
                ),
            ),
        )
        ctx.weaknessEventId?.let { weakness.close(it, "unclear") }
        resolve(id, edited)
        plans.markDirty()
    }

    /** Applies the commands the user kept on the confirmation card. Returns one result line per command. */
    suspend fun commitCommands(id: String, selected: List<ParsedCommand>, edited: Boolean): List<String> = withContext(dispatchers.io) {
        val results = selected.map { applyCommand(it) }
        resolve(id, edited)
        plans.markDirty()
        results
    }

    private val isoMinute = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    private suspend fun applyCommand(c: ParsedCommand): String {
        fun s(k: String) = (c.params[k] as? JsonPrimitive)?.contentOrNull
        fun i(k: String) = (c.params[k] as? JsonPrimitive)?.intOrNull
        return runCatching {
            when (c.type) {
                "add_commitment" -> {
                    val kind = when (s("kind")) { "class" -> CommitKind.CLASS; "exam" -> CommitKind.EXAM; else -> CommitKind.OTHER }
                    val rec = when (s("recurrence")) { "daily" -> Recurrence.DAILY; "weekly" -> Recurrence.WEEKLY; else -> Recurrence.NONE }
                    commitments.add(s("title")!!, kind, LocalDateTime.parse(s("start_iso")!!, isoMinute), LocalDateTime.parse(s("end_iso")!!, isoMinute), rec, CommitmentRepository.ORIGIN_CHAT)
                    "Added \"${s("title")}\" to your schedule."
                }
                "add_exam_mode" -> {
                    profile.setExamMode(LocalDate.parse(s("start_date")!!), LocalDate.parse(s("end_date")!!))
                    "Exam mode set from ${s("start_date")} to ${s("end_date")}."
                }
                "end_exam_mode" -> { profile.setExamMode(null, null); "Exam mode ended." }
                "set_sleep_window" -> {
                    profile.setSleep(LocalTime.parse(s("start_time")!!), LocalTime.parse(s("end_time")!!))
                    "Sleep window set to ${s("start_time")}-${s("end_time")}."
                }
                "add_curiosity" -> {
                    curiosityDao.upsert(com.polymath.os.data.db.CuriosityEntity(newId(), s("text")!!.trim(), null, status = CuriosityStatus.INBOX, created = LocalDate.now(clock)))
                    "Added to your curiosity inbox."
                }
                "pause_skill" -> { skills.setStatus(s("skill_id")!!, SkillStatus.PAUSED); "Paused ${skillName(s("skill_id")!!)}." }
                "resume_skill" -> when (val r = skills.activate(s("skill_id")!!)) {
                    ActivateResult.Ok -> "Resumed ${skillName(s("skill_id")!!)}."
                    is ActivateResult.CapReached -> "Not resumed: you already have 3 active skills. Pause one first (Skills tab)."
                }
                "set_priority" -> { skills.setPriority(s("skill_id")!!, i("priority")!!); "Priority of ${skillName(s("skill_id")!!)} set to ${i("priority")}." }
                "request_task" -> {
                    val b = plans.suggestOne(i("minutes")!!)
                    if (b == null) "No task to suggest yet. Add a skill first." else "Try: ${b.title} (${b.minutes} min, ${b.skillName})."
                }
                "log_energy" -> { logs.logEnergy(i("level")!!); "Energy ${i("level")} logged." }
                else -> "Not a command: \"${s("text") ?: c.sourceQuote}\". Nothing changed."
            }
        }.getOrElse { "Could not apply \"${c.sourceQuote}\": a required value was missing." }
    }

    private suspend fun skillName(id: String) = skillDao.getSkill(id)?.name ?: "skill"
}
