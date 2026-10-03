@file:UseSerializers(LocalDateSerializer::class, LocalDateTimeSerializer::class)

package com.polymath.os.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.polymath.os.domain.model.CommitKind
import com.polymath.os.domain.model.CuriosityStatus
import com.polymath.os.domain.model.FrictionTag
import com.polymath.os.domain.model.ProposalStatus
import com.polymath.os.domain.model.QuestState
import com.polymath.os.domain.model.RecallMode
import com.polymath.os.domain.model.RecallResult
import com.polymath.os.domain.model.Recurrence
import com.polymath.os.domain.model.SkillStatus
import com.polymath.os.domain.model.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import java.time.LocalDateTime

// Entities are @Serializable so Export/Import can write them directly as JSON.

@Serializable
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val name: String = "",
    val education: String = "",
    val weeklyFreeHours: Double = 10.0,
    val sleepStart: String = "23:00",
    val sleepEnd: String = "07:00",
    val sessionMinutes: Int = 30,
    val monthlyBudgetInr: Int = 500,
    val examStart: LocalDate? = null,
    val examEnd: LocalDate? = null,
    val freezeDaysPerWeek: Int = 1,
    /** JSON list of {name, level}. Unverified claims: they never feed level points. */
    val claimedSkillsJson: String = "[]",
    val interests: String = "",
    val onboarded: Boolean = false,
)

@Serializable
@Entity(tableName = "commitment")
data class CommitmentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: CommitKind,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val recurrence: Recurrence,
    /** MANUAL, ICS or CHAT. */
    val origin: String,
)

@Serializable
@Entity(tableName = "skill")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val status: SkillStatus,
    val priority: Int,
    val domain: String?,
    val capstoneTitle: String?,
    val capstoneDescription: String?,
    val capstoneHours: Double,
    val capstoneDone: Boolean,
    val estCostInr: Int,
    val source: Source,
    val version: Int,
    val stylesJson: String,
    val resourcesJson: String,
    val assumptionsJson: String,
    val totalHours: Double,
    val createdOn: LocalDate,
    val statusChangedOn: LocalDate,
    val pinnedActivityId: String? = null,
    val resumePromptOn: LocalDate? = null,
)

@Serializable
@Entity(
    tableName = "mini_skill",
    foreignKeys = [ForeignKey(entity = SkillEntity::class, parentColumns = ["id"], childColumns = ["skillId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("skillId")],
)
data class MiniSkillEntity(
    @PrimaryKey val id: String,
    val skillId: String,
    val name: String,
    @ColumnInfo(name = "sortOrder") val order: Int,
    val prerequisiteIdsJson: String,
    val estHours: Double,
    val teachBackPrompt: String,
    val isSampler: Boolean,
    val completedOn: LocalDate? = null,
)

@Serializable
@Entity(
    tableName = "activity",
    foreignKeys = [ForeignKey(entity = MiniSkillEntity::class, parentColumns = ["id"], childColumns = ["miniSkillId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("miniSkillId"), Index("skillId")],
)
data class ActivityEntity(
    @PrimaryKey val id: String,
    val miniSkillId: String,
    val skillId: String,
    val title: String,
    val estMinutes: Int,
    val tinyTitle: String,
    val tinyMinutes: Int,
    val styleTag: String?,
    val selected: Boolean,
    val source: Source,
    @ColumnInfo(name = "sortOrder") val order: Int,
    val completed: Boolean = false,
    val preferTiny: Boolean = false,
    val weekendOnly: Boolean = false,
    val stepsJson: String = "[]",
    val selfCheckJson: String = "[]",
)

@Serializable
@Entity(
    tableName = "recall_question",
    foreignKeys = [ForeignKey(entity = MiniSkillEntity::class, parentColumns = ["id"], childColumns = ["miniSkillId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("miniSkillId")],
)
data class RecallQuestionEntity(
    @PrimaryKey val id: String,
    val miniSkillId: String,
    val text: String,
    @ColumnInfo(name = "sortOrder") val order: Int,
)

@Serializable
@Entity(
    tableName = "quest_log",
    foreignKeys = [ForeignKey(entity = ActivityEntity::class, parentColumns = ["id"], childColumns = ["activityId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("date"), Index("activityId"), Index("skillId")],
)
data class QuestLogEntity(
    @PrimaryKey val id: String,
    val activityId: String?,
    val skillId: String,
    val title: String,
    val date: LocalDate,
    val state: QuestState,
    val usedTinyVersion: Boolean,
    val actualMinutes: Int,
    val estMinutes: Int,
    val tag: FrictionTag?,
    /** Memory aid only, NEVER scored and never sent to a model. */
    val proofPath: String?,
    val note: String?,
    val loggedAt: LocalDateTime,
)

@Serializable
@Entity(
    tableName = "recall_check",
    foreignKeys = [ForeignKey(entity = MiniSkillEntity::class, parentColumns = ["id"], childColumns = ["miniSkillId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("due"), Index("miniSkillId")],
)
data class RecallCheckEntity(
    @PrimaryKey val id: String,
    val miniSkillId: String,
    val skillId: String,
    val due: LocalDate,
    val mode: RecallMode,
    val result: RecallResult,
    /** Local only, never graded. */
    val teachBackText: String?,
    val sequence: Int,
    val answeredOn: LocalDate? = null,
)

@Serializable
@Entity(tableName = "curiosity")
data class CuriosityEntity(
    @PrimaryKey val id: String,
    val rawText: String,
    val normalizedTitle: String?,
    val domain: String? = null,
    val hook: String? = null,
    val samplerMinutes: Int? = null,
    val status: CuriosityStatus,
    val created: LocalDate,
    val relatedSkillsJson: String = "[]",
    val duplicateOfId: String? = null,
)

@Serializable
@Entity(tableName = "goal")
data class GoalEntity(
    @PrimaryKey val id: String,
    val title: String,
    val targetDate: LocalDate?,
    val created: LocalDate,
    val assumptionsJson: String = "[]",
)

@Serializable
@Entity(
    tableName = "goal_skill",
    foreignKeys = [ForeignKey(entity = GoalEntity::class, parentColumns = ["id"], childColumns = ["goalId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("goalId")],
)
data class GoalSkillEntity(
    @PrimaryKey val id: String,
    val goalId: String,
    val name: String,
    /** core, supporting or optional. */
    val importance: String,
    val dependsOnJson: String,
    val estHours: Double,
    val reason: String,
    val linkedSkillId: String? = null,
)

@Serializable
@Entity(tableName = "energy_log", indices = [Index("at")])
data class EnergyLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: LocalDateTime,
    val level: Int,
)

@Serializable
@Entity(tableName = "weakness_event", indices = [Index("firedOn")])
data class WeaknessEventEntity(
    @PrimaryKey val id: String,
    val signal: String,
    val targetId: String,
    val skillId: String,
    val activityId: String?,
    val kind: String,
    val text: String,
    val firedOn: LocalDate,
    /** ASKED, ANSWERED or IGNORED. */
    val status: String,
    val answer: String? = null,
    val freeText: String? = null,
    val snoozeUntil: LocalDate? = null,
)

@Serializable
@Entity(tableName = "plan_day")
data class PlanDayEntity(
    @PrimaryKey val date: LocalDate,
    val blocksJson: String,
    val generatedAt: LocalDateTime,
    val recomputeCount: Int,
    val examMode: Boolean,
)

@Serializable
@Entity(tableName = "proposed_change")
data class ProposedChangeEntity(
    @PrimaryKey val id: String,
    val producedBy: String,
    val kind: String,
    val payloadJson: String,
    /** Extra non-AI context needed to commit (for example the activity id being rewritten). */
    val contextJson: String,
    val status: ProposalStatus,
    val createdAt: LocalDateTime,
    val resolvedAt: LocalDateTime? = null,
)

@Serializable
@Entity(tableName = "pending_ai_action")
data class PendingAiActionEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val label: String,
    val payloadJson: String,
    val createdAt: LocalDateTime,
)

@Serializable
@Entity(tableName = "journal_entry")
data class JournalEntryEntity(
    @PrimaryKey val date: LocalDate,
    val body: String,
    val userLine: String?,
    val updatedAt: LocalDateTime,
)

@Serializable
@Entity(tableName = "ai_usage")
data class AiUsageEntity(
    @PrimaryKey val date: LocalDate,
    val calls: Int,
)

@Serializable
@Entity(tableName = "combo")
data class ComboEntity(
    @PrimaryKey val id: String,
    val requiresJson: String,
    val project: String,
    val estHours: Double,
    val lastSuggestedOn: LocalDate? = null,
    /** NEW, SUGGESTED, ACCEPTED or DISMISSED. */
    val status: String = "NEW",
)

// ---- Relations ----

data class MiniSkillWithChildren(
    @Embedded val miniSkill: MiniSkillEntity,
    @Relation(parentColumn = "id", entityColumn = "miniSkillId") val activities: List<ActivityEntity>,
    @Relation(parentColumn = "id", entityColumn = "miniSkillId") val questions: List<RecallQuestionEntity>,
)

data class SkillTree(
    @Embedded val skill: SkillEntity,
    @Relation(entity = MiniSkillEntity::class, parentColumn = "id", entityColumn = "skillId") val miniSkills: List<MiniSkillWithChildren>,
)

data class GoalWithSkills(
    @Embedded val goal: GoalEntity,
    @Relation(parentColumn = "id", entityColumn = "goalId") val skills: List<GoalSkillEntity>,
)
