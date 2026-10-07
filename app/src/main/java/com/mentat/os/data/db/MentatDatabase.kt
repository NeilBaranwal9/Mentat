package com.mentat.os.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

@Database(
    entities = [
        ProfileEntity::class, CommitmentEntity::class, SkillEntity::class, MiniSkillEntity::class,
        ActivityEntity::class, RecallQuestionEntity::class, QuestLogEntity::class, RecallCheckEntity::class,
        CuriosityEntity::class, GoalEntity::class, GoalSkillEntity::class, EnergyLogEntity::class,
        WeaknessEventEntity::class, PlanDayEntity::class, ProposedChangeEntity::class,
        PendingAiActionEntity::class, JournalEntryEntity::class, AiUsageEntity::class, ComboEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MentatDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun commitmentDao(): CommitmentDao
    abstract fun skillDao(): SkillDao
    abstract fun questLogDao(): QuestLogDao
    abstract fun recallDao(): RecallDao
    abstract fun curiosityDao(): CuriosityDao
    abstract fun goalDao(): GoalDao
    abstract fun energyDao(): EnergyDao
    abstract fun weaknessDao(): WeaknessDao
    abstract fun planDao(): PlanDao
    abstract fun proposalDao(): ProposalDao
    abstract fun pendingAiDao(): PendingAiDao
    abstract fun journalDao(): JournalDao
    abstract fun aiUsageDao(): AiUsageDao
    abstract fun comboDao(): ComboDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        const val NAME = "mentat.db"

        /** Schema v1 is exported to app/schemas. Add Migration(n, n+1) objects here; destructive fallback is never used. */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
