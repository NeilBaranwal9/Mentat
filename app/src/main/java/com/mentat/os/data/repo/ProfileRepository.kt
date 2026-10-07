package com.mentat.os.data.repo

import com.mentat.os.data.db.CommitmentDao
import com.mentat.os.data.db.CommitmentEntity
import com.mentat.os.data.db.ProfileDao
import com.mentat.os.data.db.ProfileEntity
import com.mentat.os.domain.DispatcherProvider
import com.mentat.os.domain.engine.IcsParser
import com.mentat.os.domain.model.CommitKind
import com.mentat.os.domain.model.Recurrence
import com.mentat.os.domain.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ClaimedSkill(val name: String, val level: Int)

@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
    private val dispatchers: DispatcherProvider,
    private val json: Json,
) {
    val profile: Flow<ProfileEntity> = dao.observe().map { it ?: ProfileEntity() }.flowOn(dispatchers.io)
    val onboarded: Flow<Boolean> = dao.observe().map { it?.onboarded == true }.flowOn(dispatchers.io)

    suspend fun get(): ProfileEntity = withContext(dispatchers.io) { dao.get() ?: ProfileEntity() }
    suspend fun save(p: ProfileEntity) = withContext(dispatchers.io) { dao.upsert(p) }
    suspend fun update(f: (ProfileEntity) -> ProfileEntity) = withContext(dispatchers.io) { dao.upsert(f(dao.get() ?: ProfileEntity())) }

    fun claimed(p: ProfileEntity): List<ClaimedSkill> =
        runCatching { json.decodeFromString(ListSerializer(ClaimedSkill.serializer()), p.claimedSkillsJson) }.getOrDefault(emptyList())

    fun encodeClaimed(list: List<ClaimedSkill>): String = json.encodeToString(ListSerializer(ClaimedSkill.serializer()), list)

    suspend fun setSleep(start: LocalTime, end: LocalTime) = update { it.copy(sleepStart = start.toString(), sleepEnd = end.toString()) }
    suspend fun setExamMode(start: LocalDate?, end: LocalDate?) = update { it.copy(examStart = start, examEnd = end) }
}

data class IcsImportSummary(val events: Int, val occurrences: Int, val skipped: Int)

@Singleton
class CommitmentRepository @Inject constructor(
    private val dao: CommitmentDao,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) {
    val all: Flow<List<CommitmentEntity>> = dao.observeAll().flowOn(dispatchers.io)
    val icsCount: Flow<Int> = dao.observeCountByOrigin(ORIGIN_ICS).flowOn(dispatchers.io)

    suspend fun allOnce() = withContext(dispatchers.io) { dao.getAll() }

    suspend fun add(title: String, kind: CommitKind, start: LocalDateTime, end: LocalDateTime, recurrence: Recurrence, origin: String = ORIGIN_MANUAL) =
        withContext(dispatchers.io) { dao.upsert(CommitmentEntity(newId(), title.trim(), kind, start, end, recurrence, origin)) }

    suspend fun update(c: CommitmentEntity) = withContext(dispatchers.io) { dao.upsert(c) }
    suspend fun delete(id: String) = withContext(dispatchers.io) { dao.delete(id) }

    /** Parses an .ics file on IO and replaces earlier calendar imports with 90 days of expanded events. */
    suspend fun importIcs(text: String): IcsImportSummary = withContext(dispatchers.io) {
        val today = LocalDate.now(clock)
        val result = IcsParser(clock.zone).parse(text, today, 90)
        dao.deleteByOrigin(ORIGIN_ICS)
        dao.insertAll(
            result.occurrences.map {
                val kind = if (it.title.contains("exam", ignoreCase = true)) CommitKind.EXAM else CommitKind.CLASS
                CommitmentEntity(newId(), it.title, kind, it.start, it.end, Recurrence.NONE, ORIGIN_ICS)
            },
        )
        IcsImportSummary(result.eventsRead, result.occurrences.size, result.skipped)
    }

    suspend fun clearIcs() = withContext(dispatchers.io) { dao.deleteByOrigin(ORIGIN_ICS) }

    companion object {
        const val ORIGIN_MANUAL = "MANUAL"
        const val ORIGIN_ICS = "ICS"
        const val ORIGIN_CHAT = "CHAT"
    }
}
