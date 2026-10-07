package com.mentat.os.data.repo

import androidx.room.withTransaction
import com.mentat.os.data.db.ActivityEntity
import com.mentat.os.data.db.JsonLists
import com.mentat.os.data.db.MiniSkillEntity
import com.mentat.os.data.db.MentatDatabase
import com.mentat.os.data.db.RecallQuestionEntity
import com.mentat.os.data.db.SkillDao
import com.mentat.os.data.db.SkillEntity
import com.mentat.os.data.db.SkillTree
import com.mentat.os.domain.DispatcherProvider
import com.mentat.os.domain.engine.CandidateBuilder
import com.mentat.os.domain.model.Skill
import com.mentat.os.domain.model.SkillDraft
import com.mentat.os.domain.model.SkillStatus
import com.mentat.os.domain.model.Source
import com.mentat.os.domain.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ActivateResult {
    data object Ok : ActivateResult
    /** Gate G2: a 4th active skill needs another one paused first. */
    data class CapReached(val active: List<SkillEntity>) : ActivateResult
}

@Singleton
class SkillRepository @Inject constructor(
    private val db: MentatDatabase,
    private val dao: SkillDao,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) {
    val trees: Flow<List<SkillTree>> = dao.observeTrees().flowOn(dispatchers.io)
    val skills: Flow<List<Skill>> = trees.map { l -> l.map { it.toDomain() } }.flowOn(dispatchers.default)

    fun tree(id: String): Flow<SkillTree?> = dao.observeTree(id).flowOn(dispatchers.io)

    suspend fun getTree(id: String) = withContext(dispatchers.io) { dao.getTree(id) }
    suspend fun allDomain(): List<Skill> = withContext(dispatchers.io) { dao.getTrees().map { it.toDomain() } }
    suspend fun activity(id: String) = withContext(dispatchers.io) { dao.getActivity(id) }
    suspend fun miniSkill(id: String) = withContext(dispatchers.io) { dao.getMiniSkill(id) }

    private fun today() = LocalDate.now(clock)

    /**
     * Saves a draft. New skills start as SAMPLING (Section 11 sampler mode). Existing rows keep their
     * ids, completion state and flags so logs and recall checks survive edits.
     */
    suspend fun saveDraft(draft: SkillDraft, source: Source, estCostInr: Int? = null): String = withContext(dispatchers.io) {
        db.withTransaction {
            val existing = draft.id?.let { dao.getTree(it) }
            val skillId = existing?.skill?.id ?: newId()
            val now = today()
            val base = existing?.skill
            dao.upsertSkill(
                SkillEntity(
                    id = skillId,
                    name = draft.name.trim(),
                    status = base?.status ?: SkillStatus.SAMPLING,
                    priority = base?.priority ?: 3,
                    domain = draft.domain.ifBlank { null },
                    capstoneTitle = draft.capstoneTitle.ifBlank { null },
                    capstoneDescription = draft.capstoneDescription.ifBlank { null },
                    capstoneHours = draft.capstoneHours,
                    capstoneDone = base?.capstoneDone ?: false,
                    estCostInr = estCostInr ?: base?.estCostInr ?: draft.resources.sumOf { it.estCostInr },
                    source = base?.source ?: source,
                    version = (base?.version ?: 0) + 1,
                    stylesJson = encodeStyles(draft.styles),
                    resourcesJson = encodeResources(draft.resources),
                    assumptionsJson = JsonLists.encode(draft.assumptions),
                    totalHours = draft.toBlueprint().totalHours,
                    createdOn = base?.createdOn ?: now,
                    statusChangedOn = base?.statusChangedOn ?: now,
                    pinnedActivityId = base?.pinnedActivityId,
                    resumePromptOn = base?.resumePromptOn,
                ),
            )
            val oldActs = existing?.miniSkills?.flatMap { it.activities }?.associateBy { it.id } ?: emptyMap()
            val oldMs = existing?.miniSkills?.associateBy { it.miniSkill.id } ?: emptyMap()
            val keptMs = mutableSetOf<String>()
            val keptActs = mutableSetOf<String>()

            // Sampler session = mini-skill with order 0 and one activity.
            val oldSampler = existing?.miniSkills?.firstOrNull { it.miniSkill.isSampler }
            if (draft.samplerTitle.isNotBlank()) {
                val sid = oldSampler?.miniSkill?.id ?: newId()
                keptMs += sid
                dao.upsertMiniSkills(listOf(MiniSkillEntity(sid, skillId, "Sampler", 0, "[]", draft.samplerMinutes / 60.0, "", true, oldSampler?.miniSkill?.completedOn)))
                val old = oldSampler?.activities?.firstOrNull()
                val aid = old?.id ?: newId()
                keptActs += aid
                dao.upsertActivities(
                    listOf(
                        ActivityEntity(
                            id = aid, miniSkillId = sid, skillId = skillId, title = draft.samplerTitle.trim(),
                            estMinutes = draft.samplerMinutes, tinyTitle = draft.samplerTinyTitle.ifBlank { "First 10 minutes of ${draft.samplerTitle}" },
                            tinyMinutes = draft.samplerTinyMinutes.coerceIn(3, 10), styleTag = null, selected = true,
                            source = old?.source ?: source, order = 0, completed = old?.completed ?: false,
                            stepsJson = JsonLists.encode(draft.samplerSteps),
                        ),
                    ),
                )
            }

            val idForKey = draft.miniSkills.associate { it.key to (it.id?.takeIf { id -> id in oldMs } ?: newId()) }
            draft.miniSkills.forEachIndexed { i, m ->
                val msId = idForKey.getValue(m.key)
                keptMs += msId
                val prev = oldMs[msId]?.miniSkill
                dao.upsertMiniSkills(
                    listOf(
                        MiniSkillEntity(
                            id = msId, skillId = skillId, name = m.name.trim(), order = i + 1,
                            prerequisiteIdsJson = JsonLists.encode(m.prerequisiteKeys.mapNotNull { idForKey[it] }),
                            estHours = m.estHours, teachBackPrompt = m.teachBackPrompt.trim(), isSampler = false,
                            completedOn = prev?.completedOn,
                        ),
                    ),
                )
                dao.upsertActivities(
                    m.activities.mapIndexed { j, a ->
                        val prevA = a.id?.let { oldActs[it] }
                        val aid = prevA?.id ?: newId()
                        keptActs += aid
                        ActivityEntity(
                            id = aid, miniSkillId = msId, skillId = skillId, title = a.title.trim(), estMinutes = a.estMinutes,
                            tinyTitle = a.tinyTitle.trim(), tinyMinutes = a.tinyMinutes, styleTag = a.styleTag, selected = a.selected,
                            source = prevA?.source ?: source, order = j, completed = prevA?.completed ?: false,
                            preferTiny = prevA?.preferTiny ?: false, weekendOnly = prevA?.weekendOnly ?: false,
                            stepsJson = JsonLists.encode(a.steps), selfCheckJson = prevA?.selfCheckJson ?: "[]",
                        )
                    },
                )
                dao.deleteQuestionsOf(msId)
                dao.upsertQuestions(m.recallQuestions.filter { it.isNotBlank() }.mapIndexed { k, q -> RecallQuestionEntity(newId(), msId, q.trim(), k) })
            }
            val dropActs = oldActs.keys - keptActs
            if (dropActs.isNotEmpty()) dao.deleteActivities(dropActs.toList())
            val dropMs = oldMs.keys - keptMs
            if (dropMs.isNotEmpty()) dao.deleteMiniSkills(dropMs.toList())
            skillId
        }
    }

    suspend fun activate(skillId: String): ActivateResult = withContext(dispatchers.io) {
        db.withTransaction {
            val active = dao.getSkills().filter { it.status == SkillStatus.ACTIVE && it.id != skillId }
            if (active.size >= CandidateBuilder.ACTIVE_CAP) return@withTransaction ActivateResult.CapReached(active)
            setStatusInternal(skillId, SkillStatus.ACTIVE)
            ActivateResult.Ok
        }
    }

    suspend fun setStatus(skillId: String, status: SkillStatus) = withContext(dispatchers.io) {
        if (status == SkillStatus.ACTIVE) error("Use activate() so gate G2 is enforced")
        setStatusInternal(skillId, status)
    }

    private suspend fun setStatusInternal(skillId: String, status: SkillStatus, resumeOn: LocalDate? = null) {
        val s = dao.getSkill(skillId) ?: return
        dao.upsertSkill(s.copy(status = status, statusChangedOn = today(), resumePromptOn = resumeOn ?: if (status == SkillStatus.PAUSED) s.resumePromptOn else null))
    }

    /** lost_interest -> PAUSED with a resume prompt in 30 days. */
    suspend fun pauseWithResumePrompt(skillId: String) = withContext(dispatchers.io) {
        setStatusInternal(skillId, SkillStatus.PAUSED, today().plusDays(30))
    }

    suspend fun clearResumePrompt(skillId: String) = withContext(dispatchers.io) {
        dao.getSkill(skillId)?.let { dao.upsertSkill(it.copy(resumePromptOn = null)) }
    }

    suspend fun setPriority(skillId: String, priority: Int) = withContext(dispatchers.io) {
        dao.getSkill(skillId)?.let { dao.upsertSkill(it.copy(priority = priority.coerceIn(1, 5))) }
    }

    suspend fun pinActivity(skillId: String, activityId: String?) = withContext(dispatchers.io) {
        dao.getSkill(skillId)?.let { dao.upsertSkill(it.copy(pinnedActivityId = activityId)) }
    }

    suspend fun setCapstoneDone(skillId: String, done: Boolean) = withContext(dispatchers.io) {
        dao.getSkill(skillId)?.let { dao.upsertSkill(it.copy(capstoneDone = done)) }
    }

    suspend fun updateActivity(a: ActivityEntity) = withContext(dispatchers.io) { dao.upsertActivities(listOf(a)) }
    suspend fun upsertActivities(list: List<ActivityEntity>) = withContext(dispatchers.io) { dao.upsertActivities(list) }

    /** Gate G3: the caller must have shown an explicit confirmation. */
    suspend fun deleteSkill(skillId: String) = withContext(dispatchers.io) { dao.deleteSkill(skillId) }
}
