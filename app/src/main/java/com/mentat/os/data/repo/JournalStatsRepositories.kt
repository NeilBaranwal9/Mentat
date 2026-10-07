package com.mentat.os.data.repo

import com.mentat.os.data.db.EnergyDao
import com.mentat.os.data.db.JournalDao
import com.mentat.os.data.db.JournalEntryEntity
import com.mentat.os.data.db.QuestLogDao
import com.mentat.os.data.db.RecallDao
import com.mentat.os.data.db.SkillDao
import com.mentat.os.domain.DispatcherProvider
import com.mentat.os.domain.engine.Consistency
import com.mentat.os.domain.engine.JournalInput
import com.mentat.os.domain.engine.JournalLogLine
import com.mentat.os.domain.engine.JournalWriter
import com.mentat.os.domain.engine.LevelCalculator
import com.mentat.os.domain.engine.LevelInput
import com.mentat.os.domain.engine.LevelResult
import com.mentat.os.domain.engine.NextActivitySelector
import com.mentat.os.domain.engine.WeeklyConsistency
import com.mentat.os.domain.model.QuestLog
import com.mentat.os.domain.model.RecallCheck
import com.mentat.os.domain.model.RecallResult
import com.mentat.os.domain.model.Skill
import com.mentat.os.domain.model.SkillStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JournalRepository @Inject constructor(
    private val dao: JournalDao,
    private val logDao: QuestLogDao,
    private val recallDao: RecallDao,
    private val skillDao: SkillDao,
    private val energyDao: EnergyDao,
    private val skills: SkillRepository,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) {
    val entries: Flow<List<JournalEntryEntity>> = dao.observeAll().flowOn(dispatchers.io)

    /** Fixed template page for [date] (Section 6.5). Keeps the user's own line. Only writes when there is something to say. */
    suspend fun write(date: LocalDate, force: Boolean = false) = withContext(dispatchers.io) {
        val logs = logDao.between(date, date)
        val recalls = recallDao.getAll()
        val answered = recalls.filter { it.answeredOn == date }
        val msNames = skillDao.getMiniSkills().associate { it.id to it.name }
        val skillNames = skillDao.getSkills().associate { it.id to it.name }
        val energy = energyDao.between(date.atStartOfDay(), date.plusDays(1).atStartOfDay()).map { it.level }
        val existing = dao.get(date)
        if (!force && logs.isEmpty() && answered.isEmpty() && energy.isEmpty() && existing == null) return@withContext
        val tomorrow = date.plusDays(1)
        val dueTomorrow = recalls.filter { it.result == RecallResult.PENDING && it.due == tomorrow }.map { "Recall: ${msNames[it.miniSkillId] ?: "check"}" }
        val nextUp = skills.allDomain().filter { it.status == SkillStatus.ACTIVE }.mapNotNull { s ->
            NextActivitySelector.next(s, tomorrow)?.let { "${s.name}: ${it.second.title}" }
        }
        val body = JournalWriter.write(
            JournalInput(
                date = date,
                logs = logs.map { JournalLogLine(it.title, skillNames[it.skillId] ?: "", it.state, it.actualMinutes, it.usedTinyVersion, it.tag?.name) },
                recallsAnswered = answered.map { "${msNames[it.miniSkillId] ?: "check"} (${it.result.name.lowercase()})" },
                tomorrowDue = dueTomorrow + nextUp,
                energyLevels = energy,
                userLine = existing?.userLine,
            ),
        )
        dao.upsert(JournalEntryEntity(date, body, existing?.userLine, java.time.LocalDateTime.now(clock)))
    }

    suspend fun setUserLine(date: LocalDate, line: String) = withContext(dispatchers.io) {
        val existing = dao.get(date)
        dao.upsert((existing ?: JournalEntryEntity(date, "", null, java.time.LocalDateTime.now(clock))).copy(userLine = line.ifBlank { null }))
        write(date, force = true)
    }
}

data class SkillLevel(val skillId: String, val result: LevelResult)

data class Stats(
    val overall: LevelResult,
    val perSkill: Map<String, LevelResult>,
    val consistency: WeeklyConsistency,
    /** Claimed skills from onboarding: name -> verified by a passed recall check in a matching skill. */
    val claims: List<Triple<String, Int, Boolean>>,
)

@Singleton
class StatsRepository @Inject constructor(
    private val skills: SkillRepository,
    private val logs: LogRepository,
    private val profile: ProfileRepository,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) {
    fun stats(today: LocalDate): Flow<Stats> = combine(skills.skills, logs.allLogs, logs.allRecalls, profile.profile) { s, l, r, p ->
        compute(today, s, l.map { it.toDomain() }, r.map { it.toDomain() }, p.freezeDaysPerWeek, profile.claimed(p).map { it.name to it.level })
    }.flowOn(dispatchers.default)

    fun compute(today: LocalDate, skills: List<Skill>, logs: List<QuestLog>, recalls: List<RecallCheck>, freeze: Int, claims: List<Pair<String, Int>>): Stats {
        fun idle(last: LocalDate?, created: LocalDate?) = (last ?: created)?.let { ChronoUnit.DAYS.between(it, today).toInt() } ?: 0
        val perSkill = skills.associate { s ->
            val sr = recalls.filter { it.skillId == s.id }
            s.id to LevelCalculator.compute(
                LevelInput(
                    completedMiniSkills = s.path.count { it.isComplete },
                    recallYes = sr.count { it.result == RecallResult.YES },
                    recallShaky = sr.count { it.result == RecallResult.SHAKY },
                    capstonesDone = if (s.capstoneDone) 1 else 0,
                    idleDays = idle(logs.filter { it.skillId == s.id }.maxOfOrNull { it.date }, s.createdOn),
                ),
            )
        }
        val overall = LevelCalculator.compute(
            LevelInput(
                completedMiniSkills = skills.sumOf { s -> s.path.count { it.isComplete } },
                recallYes = recalls.count { it.result == RecallResult.YES },
                recallShaky = recalls.count { it.result == RecallResult.SHAKY },
                capstonesDone = skills.count { it.capstoneDone },
                idleDays = idle(logs.maxOfOrNull { it.date }, skills.mapNotNull { it.createdOn }.minOrNull()),
            ),
        )
        val answeredDates = recalls.mapNotNull { it.answeredOn }.toSet()
        val consistency = Consistency.week(today, logs, answeredDates, freeze)
        val verifiedSkillIds = recalls.filter { it.result == RecallResult.YES }.map { it.skillId }.toSet()
        val claimRows = claims.map { (name, level) ->
            val verified = skills.any { it.id in verifiedSkillIds && com.mentat.os.domain.engine.ComboMatcher.matches(name, it.name) }
            Triple(name, level, verified)
        }
        return Stats(overall, perSkill, consistency, claimRows)
    }
}
