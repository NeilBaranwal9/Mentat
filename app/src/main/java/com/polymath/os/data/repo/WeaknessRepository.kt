package com.polymath.os.data.repo

import com.polymath.os.data.db.ActivityEntity
import com.polymath.os.data.db.JsonLists
import com.polymath.os.data.db.SkillDao
import com.polymath.os.data.db.WeaknessDao
import com.polymath.os.data.db.WeaknessEventEntity
import com.polymath.os.domain.DispatcherProvider
import com.polymath.os.domain.engine.WeaknessEngine
import com.polymath.os.domain.engine.WeaknessHistory
import com.polymath.os.domain.engine.WeaknessInput
import com.polymath.os.domain.engine.WeaknessSignal
import com.polymath.os.domain.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Persists weakness questions and applies the fixed answer-to-rule map (Section 6.2). No AI except "unclear". */
@Singleton
class WeaknessRepository @Inject constructor(
    private val dao: WeaknessDao,
    private val skillDao: SkillDao,
    private val skills: SkillRepository,
    private val logs: LogRepository,
    private val plans: PlanRepository,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) {
    private fun today() = LocalDate.now(clock)

    fun openQuestion(day: LocalDate): Flow<WeaknessEventEntity?> = dao.observeOpen(day).flowOn(dispatchers.io)

    /** Keyed by date: evaluates at most once per day. Unanswered questions from earlier days count as ignored. */
    suspend fun evaluate(day: LocalDate) = withContext(dispatchers.io) {
        dao.staleAsked(day).forEach { dao.upsert(it.copy(status = "IGNORED", snoozeUntil = it.firedOn.plusDays(WeaknessEngine.SNOOZE_DAYS))) }
        val history = dao.getAll()
        if (history.any { it.firedOn == day }) return@withContext
        val input = WeaknessInput(
            today = day,
            skills = skills.allDomain(),
            logs = logs.recentLogs(90),
            recalls = logs.allRecallsOnce(),
            lastSlotSkills = plans.lastSlotHistory(30),
            history = history.mapNotNull { h ->
                runCatching { WeaknessHistory(WeaknessSignal.valueOf(h.signal), h.targetId, h.firedOn, h.snoozeUntil) }.getOrNull()
            },
        )
        val q = withContext(dispatchers.default) { WeaknessEngine.evaluate(input) } ?: return@withContext
        dao.upsert(
            WeaknessEventEntity(
                id = newId(), signal = q.signal.name, targetId = q.targetId, skillId = q.skillId, activityId = q.activityId,
                kind = q.kind.name, text = q.text, firedOn = day, status = "ASKED",
            ),
        )
    }

    suspend fun event(id: String) = withContext(dispatchers.io) { dao.get(id) }

    suspend fun close(id: String, answer: String, freeText: String? = null) = withContext(dispatchers.io) {
        dao.get(id)?.let { dao.upsert(it.copy(status = "ANSWERED", answer = answer, freeText = freeText)) }
    }

    /** ignore -> snooze this signal for 7 days. */
    suspend fun ignore(id: String) = withContext(dispatchers.io) {
        dao.get(id)?.let { dao.upsert(it.copy(status = "IGNORED", answer = "ignore", snoozeUntil = today().plusDays(WeaknessEngine.SNOOZE_DAYS))) }
    }

    /** too_hard -> split the activity in two (halved estimates) and add a review of the previous mini-skill. */
    suspend fun applyTooHard(eventId: String, activityId: String): Boolean = withContext(dispatchers.io) {
        val act = skillDao.getActivity(activityId) ?: return@withContext false
        val split = WeaknessEngine.split(act.toDomain()) { newId() }
        if (split != null) {
            val (a, b) = split
            val siblings = skillDao.activitiesOf(act.miniSkillId).filter { it.id != act.id }
            val shifted = siblings.map { if (it.order > act.order) it.copy(order = it.order + 1) else it }
            skillDao.upsertActivities(shifted + listOf(a.toEntity(act), b.toEntity(act).copy(order = act.order + 1)))
        }
        val ms = skillDao.getMiniSkill(act.miniSkillId)
        if (ms != null) {
            val prereqs = JsonLists.decode(ms.prerequisiteIdsJson)
            val previous = prereqs.lastOrNull()?.let { skillDao.getMiniSkill(it) }
                ?: skillDao.getMiniSkills().filter { it.skillId == ms.skillId && !it.isSampler && it.order < ms.order }.maxByOrNull { it.order }
            if (previous?.completedOn != null) logs.addReviewCheck(previous.skillId, previous.id)
        }
        close(eventId, "too_hard")
        plans.markDirty()
        split != null
    }

    /** boring -> up to 3 alternative activities from the same skill. */
    suspend fun alternatives(activityId: String): List<ActivityEntity> = withContext(dispatchers.io) {
        val act = skillDao.getActivity(activityId) ?: return@withContext emptyList()
        skillDao.getActivities()
            .filter { it.skillId == act.skillId && it.id != act.id && it.selected && !it.completed }
            .sortedWith(compareBy({ it.order }, { it.id }))
            .take(3)
    }

    suspend fun applyBoringPick(eventId: String, skillId: String, pickedActivityId: String) {
        skills.pinActivity(skillId, pickedActivityId)
        close(eventId, "boring")
        plans.markDirty()
    }

    /** no_time -> always use the tiny version, or keep the activity for weekends. */
    suspend fun applyNoTime(eventId: String, activityId: String, weekend: Boolean) = withContext(dispatchers.io) {
        skillDao.getActivity(activityId)?.let {
            skillDao.upsertActivities(listOf(if (weekend) it.copy(weekendOnly = true) else it.copy(preferTiny = true)))
        }
        close(eventId, if (weekend) "no_time_weekend" else "no_time_tiny")
        plans.markDirty()
    }

    /** lost_interest -> PAUSED with a resume prompt in 30 days. */
    suspend fun applyLostInterest(eventId: String, skillId: String) {
        skills.pauseWithResumePrompt(skillId)
        close(eventId, "lost_interest")
        plans.markDirty()
    }

    private fun com.polymath.os.domain.model.Activity.toEntity(orig: ActivityEntity) = orig.copy(
        id = id, title = title, estMinutes = estMinutes, tinyMinutes = tinyMinutes, order = order, completed = false,
    )
}
