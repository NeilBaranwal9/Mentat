package com.mentat.data.repo

import com.mentat.data.db.GoalDao
import com.mentat.data.db.PlanDao
import com.mentat.data.db.PlanDayEntity
import com.mentat.domain.DispatcherProvider
import com.mentat.domain.engine.Calibration
import com.mentat.domain.engine.CandidateBuilder
import com.mentat.domain.engine.EnergyAnalyzer
import com.mentat.domain.engine.GoalLink
import com.mentat.domain.engine.PlanBlock
import com.mentat.domain.engine.Planner
import com.mentat.domain.engine.PlannerInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

data class StoredPlan(val date: LocalDate, val blocks: List<PlanBlock>, val recomputes: Int, val examMode: Boolean, val generatedAt: LocalDateTime)

sealed interface RecomputeResult {
    data object Ok : RecomputeResult
    data object LimitReached : RecomputeResult
}

@Singleton
class PlanRepository @Inject constructor(
    private val planDao: PlanDao,
    private val goalDao: GoalDao,
    private val skills: SkillRepository,
    private val logs: LogRepository,
    private val profile: ProfileRepository,
    private val commitments: CommitmentRepository,
    private val planner: Planner,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val json: Json,
) {
    companion object {
        const val MAX_MANUAL_RECOMPUTES = 5
    }

    private val blockList = ListSerializer(PlanBlock.serializer())
    private val _dirty = MutableStateFlow(false)
    /** Set after a confirmed change so Today can offer "Recompute" (never recomputes on its own). */
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    fun markDirty() { _dirty.value = true }

    fun observe(date: LocalDate): Flow<StoredPlan?> = planDao.observe(date).map { it?.toStored() }.flowOn(dispatchers.io)

    private fun PlanDayEntity.toStored() = StoredPlan(date, runCatching { json.decodeFromString(blockList, blocksJson) }.getOrDefault(emptyList()), recomputeCount, examMode, generatedAt)

    /** Auto-run once per day: only writes when no plan exists for [date]. Idempotent. */
    suspend fun ensure(date: LocalDate) = withContext(dispatchers.io) {
        if (planDao.get(date) == null) generate(date, recomputeCount = 0)
    }

    /** Manual recompute, max 5 per day (Section 4 loop breaker). */
    suspend fun recompute(date: LocalDate): RecomputeResult = withContext(dispatchers.io) {
        val existing = planDao.get(date)
        val count = existing?.recomputeCount ?: 0
        if (existing != null && count >= MAX_MANUAL_RECOMPUTES) return@withContext RecomputeResult.LimitReached
        generate(date, recomputeCount = if (existing == null) 0 else count + 1)
        _dirty.value = false
        RecomputeResult.Ok
    }

    private suspend fun generate(date: LocalDate, recomputeCount: Int) {
        val input = input(date)
        val plan = withContext(dispatchers.default) { planner.plan(input) }
        planDao.upsert(PlanDayEntity(date, json.encodeToString(blockList, plan.blocks), LocalDateTime.now(clock), recomputeCount, plan.examMode))
    }

    /** "What should I do?" mode. Pure read; writes nothing. */
    suspend fun suggestOne(minutes: Int): PlanBlock? = withContext(dispatchers.io) {
        val today = LocalDate.now(clock)
        val input = input(today)
        withContext(dispatchers.default) { planner.suggestOne(input, minutes, LocalTime.now(clock)) }
    }

    suspend fun input(date: LocalDate): PlannerInput {
        val today = LocalDate.now(clock)
        val all = skills.allDomain()
        val recent = logs.recentLogs(60)
        val recalls = logs.allRecallsOnce()
        val p = profile.get()
        val energy = EnergyAnalyzer.analyze(logs.energyLogs())
        val goalLinks = goalDao.getAll().flatMap { g ->
            g.skills.filter { it.linkedSkillId != null }.map { it.linkedSkillId!! to GoalLink(g.goal.title, it.importance == "core", g.goal.targetDate) }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, links) ->
            links.sortedWith(compareByDescending<GoalLink> { it.core }.thenBy { it.targetDate ?: LocalDate.MAX }).first()
        }
        val now = LocalTime.now(clock)
        val rounded = LocalTime.of(now.hour, (now.minute / 5) * 5).plusMinutes(5)
        val notBefore = if (date == today) (if (rounded.isAfter(now)) rounded else LocalTime.of(23, 59)) else null
        return PlannerInput(
            today = date,
            commitments = commitments.allOnce().map { it.toDomain() },
            sleep = p.sleepWindow(),
            examModes = p.examRanges(),
            candidates = CandidateBuilder.build(date, all, recalls, recent, goalLinks),
            calibration = Calibration.ratios(recent),
            peakBuckets = energy.peaks,
            notBefore = notBefore,
        )
    }

    /** For the weakness engine's "always in the last slot" signal. */
    suspend fun lastSlotHistory(days: Long = 30): List<Pair<LocalDate, String>> = withContext(dispatchers.io) {
        planDao.since(LocalDate.now(clock).minusDays(days)).mapNotNull { e ->
            val blocks = e.toStored().blocks.filter { it.startMinute != null && !it.minimumDay }
            if (blocks.size < 2) null else e.date to blocks.maxBy { it.startMinute!! }.skillId
        }
    }
}
