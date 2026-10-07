package com.mentat.data.repo

import androidx.room.withTransaction
import com.mentat.data.db.EnergyDao
import com.mentat.data.db.EnergyLogEntity
import com.mentat.data.db.MentatDatabase
import com.mentat.data.db.QuestLogDao
import com.mentat.data.db.QuestLogEntity
import com.mentat.data.db.RecallCheckEntity
import com.mentat.data.db.RecallDao
import com.mentat.data.db.SkillDao
import com.mentat.domain.DispatcherProvider
import com.mentat.domain.engine.RecallScheduler
import com.mentat.domain.model.FrictionTag
import com.mentat.domain.model.QuestState
import com.mentat.domain.model.RecallCheck
import com.mentat.domain.model.RecallResult
import com.mentat.domain.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

data class LogRequest(
    val activityId: String?,
    val skillId: String,
    val title: String,
    val state: QuestState,
    val usedTiny: Boolean,
    val actualMinutes: Int,
    val estMinutes: Int,
    val tag: FrictionTag?,
    val note: String?,
    val proofName: String?,
    /** Capstone sessions can be marked as finishing the capstone. */
    val capstoneFinished: Boolean = false,
)

data class LogOutcome(val miniSkillCompleted: String? = null, val samplerDone: Boolean = false)

@Singleton
class LogRepository @Inject constructor(
    private val db: MentatDatabase,
    private val logDao: QuestLogDao,
    private val recallDao: RecallDao,
    private val skillDao: SkillDao,
    private val energyDao: EnergyDao,
    private val scheduler: RecallScheduler,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) {
    private fun today() = LocalDate.now(clock)

    fun logsOn(date: LocalDate): Flow<List<QuestLogEntity>> = logDao.observeByDate(date).flowOn(dispatchers.io)
    fun logsBetween(from: LocalDate, to: LocalDate) = logDao.observeBetween(from, to).flowOn(dispatchers.io)
    val allLogs: Flow<List<QuestLogEntity>> = logDao.observeAll().flowOn(dispatchers.io)
    val proofLogs: Flow<List<QuestLogEntity>> = logDao.observeWithProof().flowOn(dispatchers.io)
    fun dueRecalls(today: LocalDate): Flow<List<RecallCheckEntity>> = recallDao.observeDue(today).flowOn(dispatchers.io)
    val allRecalls: Flow<List<RecallCheckEntity>> = recallDao.observeAll().flowOn(dispatchers.io)
    val energy = energyDao.observeAll().flowOn(dispatchers.io)

    suspend fun recentLogs(days: Long = 60) = withContext(dispatchers.io) { logDao.between(today().minusDays(days), today()).map { it.toDomain() } }
    suspend fun allRecallsOnce(): List<RecallCheck> = withContext(dispatchers.io) { recallDao.getAll().map { it.toDomain() } }

    /**
     * Writes one quest log. A full-size DONE completes the activity; completing the last selected
     * activity of a mini-skill schedules the +7/+30 recall checks (Section 6.4).
     */
    suspend fun log(req: LogRequest): LogOutcome = withContext(dispatchers.io) {
        db.withTransaction {
            val now = LocalDateTime.now(clock)
            val date = now.toLocalDate()
            logDao.insert(
                QuestLogEntity(
                    id = newId(), activityId = req.activityId, skillId = req.skillId, title = req.title, date = date,
                    state = req.state, usedTinyVersion = req.usedTiny, actualMinutes = req.actualMinutes.coerceAtLeast(0),
                    estMinutes = req.estMinutes, tag = req.tag, proofPath = req.proofName, note = req.note?.ifBlank { null }, loggedAt = now,
                ),
            )
            if (req.capstoneFinished) skillDao.getSkill(req.skillId)?.let { skillDao.upsertSkill(it.copy(capstoneDone = true)) }
            val actId = req.activityId ?: return@withTransaction LogOutcome()
            if (req.state != QuestState.DONE || req.usedTiny) return@withTransaction LogOutcome()
            val act = skillDao.getActivity(actId) ?: return@withTransaction LogOutcome()
            if (act.completed) return@withTransaction LogOutcome()
            skillDao.upsertActivities(listOf(act.copy(completed = true)))
            val skill = skillDao.getSkill(act.skillId)
            if (skill?.pinnedActivityId == actId) skillDao.upsertSkill(skill.copy(pinnedActivityId = null))
            val ms = skillDao.getMiniSkill(act.miniSkillId) ?: return@withTransaction LogOutcome()
            if (ms.isSampler) return@withTransaction LogOutcome(samplerDone = true)
            val siblings = skillDao.activitiesOf(ms.id)
            val complete = siblings.filter { it.selected }.all { it.completed || it.id == actId }
            if (!complete || ms.completedOn != null) return@withTransaction LogOutcome()
            skillDao.upsertMiniSkills(listOf(ms.copy(completedOn = date)))
            val created = scheduler.onMiniSkillCompleted(ms.skillId, ms.id, date, recallDao.forMiniSkill(ms.id).map { it.toDomain() })
            recallDao.upsertAll(created.map { it.toEntity() })
            LogOutcome(miniSkillCompleted = ms.name)
        }
    }

    suspend fun deleteLog(id: String) = withContext(dispatchers.io) { logDao.delete(id) }

    /** Self-reported recall result; teach-back text is stored locally and never graded. */
    suspend fun answerRecall(checkId: String, result: RecallResult, teachBackText: String?) = withContext(dispatchers.io) {
        db.withTransaction {
            val check = recallDao.get(checkId) ?: return@withTransaction
            val today = today()
            recallDao.upsert(check.copy(result = result, teachBackText = teachBackText?.ifBlank { null }, answeredOn = today))
            val ms = skillDao.getMiniSkill(check.miniSkillId)
            val completedOn = ms?.completedOn ?: check.due.minusDays(RecallScheduler.LADDER.first())
            val existing = recallDao.forMiniSkill(check.miniSkillId).map { it.toDomain() }
            val more = scheduler.onAnswered(check.toDomain(), result, today, completedOn, existing)
            recallDao.upsertAll(more.map { it.toEntity() })
        }
    }

    /** "Move one step back" for too_hard: a review check of an earlier mini-skill, due today. */
    suspend fun addReviewCheck(skillId: String, miniSkillId: String) = withContext(dispatchers.io) {
        val existing = recallDao.forMiniSkill(miniSkillId)
        if (existing.any { it.result == RecallResult.PENDING && !it.due.isAfter(today()) }) return@withContext
        val seq = (existing.maxOfOrNull { it.sequence } ?: -1) + 1
        recallDao.upsert(RecallCheckEntity(newId(), miniSkillId, skillId, today(), RecallScheduler.modeFor(seq), RecallResult.PENDING, null, seq))
    }

    suspend fun questionsFor(miniSkillId: String) = withContext(dispatchers.io) { skillDao.questionsOf(miniSkillId).map { it.text } }

    suspend fun logEnergy(level: Int) = withContext(dispatchers.io) {
        energyDao.insert(EnergyLogEntity(at = LocalDateTime.now(clock), level = level.coerceIn(1, 5)))
    }

    suspend fun energyLogs() = withContext(dispatchers.io) { energyDao.getAll().map { it.toDomain() } }
}

fun RecallCheck.toEntity() = RecallCheckEntity(id, miniSkillId, skillId, due, mode, result, teachBackText, sequence, answeredOn)
