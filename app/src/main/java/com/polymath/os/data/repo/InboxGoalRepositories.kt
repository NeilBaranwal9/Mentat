package com.polymath.os.data.repo

import com.polymath.os.data.db.ComboDao
import com.polymath.os.data.db.ComboEntity
import com.polymath.os.data.db.CuriosityDao
import com.polymath.os.data.db.CuriosityEntity
import com.polymath.os.data.db.GoalDao
import com.polymath.os.data.db.GoalWithSkills
import com.polymath.os.data.db.JsonLists
import com.polymath.os.domain.DispatcherProvider
import com.polymath.os.domain.engine.ComboMatcher
import com.polymath.os.domain.engine.ComboSeed
import com.polymath.os.domain.model.CuriosityStatus
import com.polymath.os.domain.model.SkillDraft
import com.polymath.os.domain.model.Source
import com.polymath.os.domain.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InboxRepository @Inject constructor(
    private val dao: CuriosityDao,
    private val skills: SkillRepository,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) {
    val inbox: Flow<List<CuriosityEntity>> = dao.observeInbox().flowOn(dispatchers.io)

    suspend fun capture(text: String): String = withContext(dispatchers.io) {
        val id = newId()
        dao.upsert(CuriosityEntity(id, text.trim(), null, status = CuriosityStatus.INBOX, created = LocalDate.now(clock)))
        id
    }

    suspend fun get(id: String) = withContext(dispatchers.io) { dao.get(id) }
    suspend fun delete(id: String) = withContext(dispatchers.io) { dao.setStatus(id, CuriosityStatus.DELETED) }
    suspend fun markPromoted(id: String) = withContext(dispatchers.io) { dao.setStatus(id, CuriosityStatus.PROMOTED) }
    suspend fun rename(id: String, title: String) = withContext(dispatchers.io) { dao.get(id)?.let { dao.upsert(it.copy(normalizedTitle = title.trim())) } }

    /** Gate G3: explicit confirmation required by the caller. */
    suspend fun clear() = withContext(dispatchers.io) { dao.clearInbox() }

    /**
     * "Sample": creates a SAMPLING skill that only has a sampler session, without AI.
     * The user can keep it later and add a path manually or with the Skill Architect.
     */
    suspend fun sample(id: String): String? = withContext(dispatchers.io) {
        val item = dao.get(id) ?: return@withContext null
        val title = item.normalizedTitle ?: item.rawText.take(60)
        val minutes = (item.samplerMinutes ?: 25).coerceIn(15, 30)
        val skillId = skills.saveDraft(
            SkillDraft(
                name = title, domain = item.domain.orEmpty(),
                samplerTitle = item.hook ?: "Explore $title for $minutes minutes and note what you find interesting",
                samplerMinutes = minutes, samplerTinyTitle = "Spend 10 minutes on $title", samplerTinyMinutes = 10,
                miniSkills = emptyList(), capstoneTitle = "", capstoneHours = 0.0,
            ),
            Source.MANUAL,
        )
        dao.setStatus(id, CuriosityStatus.SAMPLED)
        skillId
    }
}

@Singleton
class GoalRepository @Inject constructor(
    private val dao: GoalDao,
    private val dispatchers: DispatcherProvider,
) {
    val goals: Flow<List<GoalWithSkills>> = dao.observeAll().flowOn(dispatchers.io)
    fun goal(id: String): Flow<GoalWithSkills?> = dao.observe(id).flowOn(dispatchers.io)
    suspend fun link(goalSkillId: String, skillId: String?) = withContext(dispatchers.io) { dao.link(goalSkillId, skillId) }
    suspend fun delete(id: String) = withContext(dispatchers.io) { dao.delete(id) }
}

/** Cross-skill projects from the hand-seeded combo table (assets/combos.json). Max 1 suggestion per week. */
@Singleton
class ComboRepository @Inject constructor(
    private val dao: ComboDao,
    private val skills: SkillRepository,
    private val inbox: InboxRepository,
    private val clock: Clock,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) {
    val suggested: Flow<ComboEntity?> = dao.observeSuggested().flowOn(dispatchers.io)

    suspend fun seedIfNeeded(seedJson: String) = withContext(dispatchers.io) {
        val seeds = json.decodeFromString(ListSerializer(ComboSeed.serializer()), seedJson)
        dao.insertSeeds(seeds.map { ComboEntity(it.id, JsonLists.encode(it.requires), it.project, it.estHours) })
    }

    /** Keyed by date. Writes at most one new suggestion per 7 days. */
    suspend fun evaluate(today: LocalDate) = withContext(dispatchers.io) {
        val rows = dao.getAll()
        if (rows.any { it.status == "SUGGESTED" }) return@withContext
        val last = rows.mapNotNull { it.lastSuggestedOn }.maxOrNull()
        val seeds = rows.map { ComboSeed(it.id, JsonLists.decode(it.requiresJson), it.project, it.estHours) }
        val excluded = rows.filter { it.status != "NEW" }.map { it.id }.toSet()
        val s = ComboMatcher.suggest(today, seeds, skills.allDomain(), last, excluded) ?: return@withContext
        rows.first { it.id == s.combo.id }.let { dao.upsert(it.copy(status = "SUGGESTED", lastSuggestedOn = today)) }
    }

    suspend fun accept(c: ComboEntity) = withContext(dispatchers.io) {
        inbox.capture("Project idea: ${c.project}")
        dao.upsert(c.copy(status = "ACCEPTED"))
    }

    suspend fun dismiss(c: ComboEntity) = withContext(dispatchers.io) { dao.upsert(c.copy(status = "DISMISSED")) }
}
