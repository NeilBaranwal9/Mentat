package com.mentat.os.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mentat.os.domain.model.CuriosityStatus
import com.mentat.os.domain.model.ProposalStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime

// Rule 1: every DAO method is suspend or returns Flow. No main-thread queries.

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1") fun observe(): Flow<ProfileEntity?>
    @Query("SELECT * FROM profile WHERE id = 1") suspend fun get(): ProfileEntity?
    @Upsert suspend fun upsert(p: ProfileEntity)
}

@Dao
interface CommitmentDao {
    @Query("SELECT * FROM commitment ORDER BY start") fun observeAll(): Flow<List<CommitmentEntity>>
    @Query("SELECT * FROM commitment") suspend fun getAll(): List<CommitmentEntity>
    @Upsert suspend fun upsert(c: CommitmentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(list: List<CommitmentEntity>)
    @Query("DELETE FROM commitment WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM commitment WHERE origin = :origin") suspend fun deleteByOrigin(origin: String)
    @Query("SELECT COUNT(*) FROM commitment WHERE origin = :origin") fun observeCountByOrigin(origin: String): Flow<Int>
}

@Dao
interface SkillDao {
    @Transaction @Query("SELECT * FROM skill ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'SAMPLING' THEN 1 WHEN 'PAUSED' THEN 2 WHEN 'DONE' THEN 3 ELSE 4 END, priority DESC, name")
    fun observeTrees(): Flow<List<SkillTree>>

    @Transaction @Query("SELECT * FROM skill") suspend fun getTrees(): List<SkillTree>
    @Transaction @Query("SELECT * FROM skill WHERE id = :id") fun observeTree(id: String): Flow<SkillTree?>
    @Transaction @Query("SELECT * FROM skill WHERE id = :id") suspend fun getTree(id: String): SkillTree?
    @Query("SELECT * FROM skill WHERE id = :id") suspend fun getSkill(id: String): SkillEntity?
    @Query("SELECT * FROM skill") suspend fun getSkills(): List<SkillEntity>
    @Query("SELECT COUNT(*) FROM skill WHERE status = 'ACTIVE'") suspend fun activeCount(): Int

    @Upsert suspend fun upsertSkill(s: SkillEntity)
    @Upsert suspend fun upsertMiniSkills(list: List<MiniSkillEntity>)
    @Upsert suspend fun upsertActivities(list: List<ActivityEntity>)
    @Upsert suspend fun upsertQuestions(list: List<RecallQuestionEntity>)

    @Query("SELECT * FROM activity WHERE id = :id") suspend fun getActivity(id: String): ActivityEntity?
    @Query("SELECT * FROM activity") suspend fun getActivities(): List<ActivityEntity>
    @Query("SELECT * FROM mini_skill WHERE id = :id") suspend fun getMiniSkill(id: String): MiniSkillEntity?
    @Query("SELECT * FROM mini_skill") suspend fun getMiniSkills(): List<MiniSkillEntity>
    @Query("SELECT * FROM recall_question") suspend fun getQuestions(): List<RecallQuestionEntity>
    @Query("SELECT * FROM activity WHERE miniSkillId = :miniSkillId") suspend fun activitiesOf(miniSkillId: String): List<ActivityEntity>
    @Query("SELECT * FROM recall_question WHERE miniSkillId = :miniSkillId ORDER BY sortOrder") suspend fun questionsOf(miniSkillId: String): List<RecallQuestionEntity>

    @Query("DELETE FROM skill WHERE id = :id") suspend fun deleteSkill(id: String)
    @Query("DELETE FROM mini_skill WHERE id IN (:ids)") suspend fun deleteMiniSkills(ids: List<String>)
    @Query("DELETE FROM activity WHERE id IN (:ids)") suspend fun deleteActivities(ids: List<String>)
    @Query("DELETE FROM recall_question WHERE miniSkillId = :miniSkillId") suspend fun deleteQuestionsOf(miniSkillId: String)
}

@Dao
interface QuestLogDao {
    @Insert suspend fun insert(l: QuestLogEntity)
    @Query("SELECT * FROM quest_log WHERE date = :date ORDER BY loggedAt") fun observeByDate(date: LocalDate): Flow<List<QuestLogEntity>>
    @Query("SELECT * FROM quest_log WHERE date BETWEEN :from AND :to ORDER BY date, loggedAt") suspend fun between(from: LocalDate, to: LocalDate): List<QuestLogEntity>
    @Query("SELECT * FROM quest_log WHERE date BETWEEN :from AND :to ORDER BY date, loggedAt") fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<QuestLogEntity>>
    @Query("SELECT * FROM quest_log ORDER BY date, loggedAt") suspend fun getAll(): List<QuestLogEntity>
    @Query("SELECT * FROM quest_log ORDER BY date, loggedAt") fun observeAll(): Flow<List<QuestLogEntity>>
    @Query("SELECT * FROM quest_log WHERE proofPath IS NOT NULL ORDER BY date DESC, loggedAt DESC") fun observeWithProof(): Flow<List<QuestLogEntity>>
    @Query("SELECT MAX(date) FROM quest_log WHERE skillId = :skillId") suspend fun lastDateFor(skillId: String): LocalDate?
    @Query("SELECT MAX(date) FROM quest_log") suspend fun lastDate(): LocalDate?
    @Query("DELETE FROM quest_log WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface RecallDao {
    @Upsert suspend fun upsertAll(list: List<RecallCheckEntity>)
    @Upsert suspend fun upsert(r: RecallCheckEntity)
    @Query("SELECT * FROM recall_check WHERE id = :id") suspend fun get(id: String): RecallCheckEntity?
    @Query("SELECT * FROM recall_check WHERE result = 'PENDING' AND due <= :today ORDER BY due") fun observeDue(today: LocalDate): Flow<List<RecallCheckEntity>>
    @Query("SELECT * FROM recall_check WHERE result = 'PENDING' AND due <= :today ORDER BY due") suspend fun due(today: LocalDate): List<RecallCheckEntity>
    @Query("SELECT * FROM recall_check WHERE result = 'PENDING' AND due = :day") suspend fun dueOn(day: LocalDate): List<RecallCheckEntity>
    @Query("SELECT * FROM recall_check WHERE miniSkillId = :miniSkillId ORDER BY sequence") suspend fun forMiniSkill(miniSkillId: String): List<RecallCheckEntity>
    @Query("SELECT * FROM recall_check") suspend fun getAll(): List<RecallCheckEntity>
    @Query("SELECT * FROM recall_check") fun observeAll(): Flow<List<RecallCheckEntity>>
}

@Dao
interface CuriosityDao {
    @Query("SELECT * FROM curiosity WHERE status = 'INBOX' ORDER BY created DESC, id") fun observeInbox(): Flow<List<CuriosityEntity>>
    @Query("SELECT * FROM curiosity WHERE status = 'INBOX'") suspend fun inbox(): List<CuriosityEntity>
    @Query("SELECT * FROM curiosity WHERE id = :id") suspend fun get(id: String): CuriosityEntity?
    @Query("SELECT * FROM curiosity") suspend fun getAll(): List<CuriosityEntity>
    @Upsert suspend fun upsert(c: CuriosityEntity)
    @Query("UPDATE curiosity SET status = :status WHERE id = :id") suspend fun setStatus(id: String, status: CuriosityStatus)
    @Query("UPDATE curiosity SET status = 'DELETED' WHERE status = 'INBOX'") suspend fun clearInbox()
}

@Dao
interface GoalDao {
    @Transaction @Query("SELECT * FROM goal ORDER BY created DESC") fun observeAll(): Flow<List<GoalWithSkills>>
    @Transaction @Query("SELECT * FROM goal") suspend fun getAll(): List<GoalWithSkills>
    @Transaction @Query("SELECT * FROM goal WHERE id = :id") fun observe(id: String): Flow<GoalWithSkills?>
    @Upsert suspend fun upsertGoal(g: GoalEntity)
    @Upsert suspend fun upsertSkills(list: List<GoalSkillEntity>)
    @Query("UPDATE goal_skill SET linkedSkillId = :skillId WHERE id = :goalSkillId") suspend fun link(goalSkillId: String, skillId: String?)
    @Query("DELETE FROM goal WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface EnergyDao {
    @Insert suspend fun insert(e: EnergyLogEntity)
    @Query("SELECT * FROM energy_log ORDER BY at") suspend fun getAll(): List<EnergyLogEntity>
    @Query("SELECT * FROM energy_log ORDER BY at") fun observeAll(): Flow<List<EnergyLogEntity>>
    @Query("SELECT * FROM energy_log WHERE at >= :from AND at < :to ORDER BY at") suspend fun between(from: LocalDateTime, to: LocalDateTime): List<EnergyLogEntity>
}

@Dao
interface WeaknessDao {
    @Upsert suspend fun upsert(e: WeaknessEventEntity)
    @Query("SELECT * FROM weakness_event") suspend fun getAll(): List<WeaknessEventEntity>
    @Query("SELECT * FROM weakness_event WHERE firedOn = :day AND status = 'ASKED' LIMIT 1") fun observeOpen(day: LocalDate): Flow<WeaknessEventEntity?>
    @Query("SELECT * FROM weakness_event WHERE id = :id") suspend fun get(id: String): WeaknessEventEntity?
    @Query("SELECT * FROM weakness_event WHERE status = 'ASKED' AND firedOn < :day") suspend fun staleAsked(day: LocalDate): List<WeaknessEventEntity>
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plan_day WHERE date = :date") fun observe(date: LocalDate): Flow<PlanDayEntity?>
    @Query("SELECT * FROM plan_day WHERE date = :date") suspend fun get(date: LocalDate): PlanDayEntity?
    @Query("SELECT * FROM plan_day WHERE date >= :from ORDER BY date") suspend fun since(from: LocalDate): List<PlanDayEntity>
    @Query("SELECT * FROM plan_day") suspend fun getAll(): List<PlanDayEntity>
    @Upsert suspend fun upsert(p: PlanDayEntity)
}

@Dao
interface ProposalDao {
    @Insert suspend fun insert(p: ProposedChangeEntity)
    @Upsert suspend fun upsert(p: ProposedChangeEntity)
    @Query("SELECT * FROM proposed_change WHERE id = :id") suspend fun get(id: String): ProposedChangeEntity?
    @Query("SELECT * FROM proposed_change WHERE id = :id") fun observe(id: String): Flow<ProposedChangeEntity?>
    @Query("SELECT * FROM proposed_change WHERE status = 'PENDING' ORDER BY createdAt DESC") fun observePending(): Flow<List<ProposedChangeEntity>>
    @Query("UPDATE proposed_change SET status = :status, resolvedAt = :at WHERE id = :id") suspend fun resolve(id: String, status: ProposalStatus, at: LocalDateTime)
    @Query("SELECT * FROM proposed_change") suspend fun getAll(): List<ProposedChangeEntity>
}

@Dao
interface PendingAiDao {
    @Insert suspend fun insert(p: PendingAiActionEntity)
    @Query("SELECT * FROM pending_ai_action ORDER BY createdAt") fun observeAll(): Flow<List<PendingAiActionEntity>>
    @Query("SELECT * FROM pending_ai_action WHERE id = :id") suspend fun get(id: String): PendingAiActionEntity?
    @Query("SELECT * FROM pending_ai_action") suspend fun getAll(): List<PendingAiActionEntity>
    @Query("DELETE FROM pending_ai_action WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface JournalDao {
    @Upsert suspend fun upsert(j: JournalEntryEntity)
    @Query("SELECT * FROM journal_entry WHERE date = :date") suspend fun get(date: LocalDate): JournalEntryEntity?
    @Query("SELECT * FROM journal_entry ORDER BY date DESC") fun observeAll(): Flow<List<JournalEntryEntity>>
    @Query("SELECT * FROM journal_entry") suspend fun getAll(): List<JournalEntryEntity>
}

@Dao
interface AiUsageDao {
    @Query("SELECT * FROM ai_usage WHERE date = :date") suspend fun get(date: LocalDate): AiUsageEntity?
    @Upsert suspend fun upsert(u: AiUsageEntity)
    @Query("SELECT * FROM ai_usage ORDER BY date DESC LIMIT 14") fun observeRecent(): Flow<List<AiUsageEntity>>
    @Query("SELECT * FROM ai_usage") suspend fun getAll(): List<AiUsageEntity>
}

@Dao
interface ComboDao {
    @Query("SELECT * FROM combo") suspend fun getAll(): List<ComboEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSeeds(list: List<ComboEntity>)
    @Upsert suspend fun upsert(c: ComboEntity)
    @Query("SELECT * FROM combo WHERE status = 'SUGGESTED' ORDER BY lastSuggestedOn DESC LIMIT 1") fun observeSuggested(): Flow<ComboEntity?>
}

/** Used by Export/Import and "delete history" (gate G3). */
@Dao
interface MaintenanceDao {
    @Query("DELETE FROM profile") suspend fun clearProfile()
    @Query("DELETE FROM commitment") suspend fun clearCommitments()
    @Query("DELETE FROM skill") suspend fun clearSkills()
    @Query("DELETE FROM curiosity") suspend fun clearCuriosity()
    @Query("DELETE FROM goal") suspend fun clearGoals()
    @Query("DELETE FROM energy_log") suspend fun clearEnergy()
    @Query("DELETE FROM weakness_event") suspend fun clearWeakness()
    @Query("DELETE FROM plan_day") suspend fun clearPlans()
    @Query("DELETE FROM proposed_change") suspend fun clearProposals()
    @Query("DELETE FROM pending_ai_action") suspend fun clearPending()
    @Query("DELETE FROM journal_entry") suspend fun clearJournal()
    @Query("DELETE FROM ai_usage") suspend fun clearAiUsage()
    @Query("DELETE FROM combo") suspend fun clearCombos()
    @Query("DELETE FROM quest_log") suspend fun clearLogs()
    @Query("DELETE FROM recall_check") suspend fun clearRecalls()

    @Insert suspend fun insertProfile(list: List<ProfileEntity>)
    @Insert suspend fun insertCommitments(list: List<CommitmentEntity>)
    @Insert suspend fun insertSkills(list: List<SkillEntity>)
    @Insert suspend fun insertMiniSkills(list: List<MiniSkillEntity>)
    @Insert suspend fun insertActivities(list: List<ActivityEntity>)
    @Insert suspend fun insertQuestions(list: List<RecallQuestionEntity>)
    @Insert suspend fun insertLogs(list: List<QuestLogEntity>)
    @Insert suspend fun insertRecalls(list: List<RecallCheckEntity>)
    @Insert suspend fun insertCuriosity(list: List<CuriosityEntity>)
    @Insert suspend fun insertGoals(list: List<GoalEntity>)
    @Insert suspend fun insertGoalSkills(list: List<GoalSkillEntity>)
    @Insert suspend fun insertEnergy(list: List<EnergyLogEntity>)
    @Insert suspend fun insertWeakness(list: List<WeaknessEventEntity>)
    @Insert suspend fun insertPlans(list: List<PlanDayEntity>)
    @Insert suspend fun insertProposals(list: List<ProposedChangeEntity>)
    @Insert suspend fun insertPending(list: List<PendingAiActionEntity>)
    @Insert suspend fun insertJournal(list: List<JournalEntryEntity>)
    @Insert suspend fun insertAiUsage(list: List<AiUsageEntity>)
    @Insert suspend fun insertCombos(list: List<ComboEntity>)

    @Query("SELECT * FROM profile") suspend fun profiles(): List<ProfileEntity>
    @Query("SELECT * FROM goal_skill") suspend fun goalSkills(): List<GoalSkillEntity>
    @Query("SELECT * FROM goal") suspend fun goals(): List<GoalEntity>
}
