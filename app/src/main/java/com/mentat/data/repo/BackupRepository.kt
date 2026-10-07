package com.mentat.data.repo

import android.net.Uri
import androidx.room.withTransaction
import com.mentat.data.db.AiUsageDao
import com.mentat.data.db.AiUsageEntity
import com.mentat.data.db.ActivityEntity
import com.mentat.data.db.ComboDao
import com.mentat.data.db.ComboEntity
import com.mentat.data.db.CommitmentDao
import com.mentat.data.db.CommitmentEntity
import com.mentat.data.db.CuriosityDao
import com.mentat.data.db.CuriosityEntity
import com.mentat.data.db.EnergyDao
import com.mentat.data.db.EnergyLogEntity
import com.mentat.data.db.GoalEntity
import com.mentat.data.db.GoalSkillEntity
import com.mentat.data.db.JournalDao
import com.mentat.data.db.JournalEntryEntity
import com.mentat.data.db.MaintenanceDao
import com.mentat.data.db.MiniSkillEntity
import com.mentat.data.db.PendingAiActionEntity
import com.mentat.data.db.PendingAiDao
import com.mentat.data.db.PlanDao
import com.mentat.data.db.PlanDayEntity
import com.mentat.data.db.MentatDatabase
import com.mentat.data.db.ProfileEntity
import com.mentat.data.db.ProposalDao
import com.mentat.data.db.ProposedChangeEntity
import com.mentat.data.db.QuestLogDao
import com.mentat.data.db.QuestLogEntity
import com.mentat.data.db.RecallCheckEntity
import com.mentat.data.db.RecallDao
import com.mentat.data.db.RecallQuestionEntity
import com.mentat.data.db.SkillDao
import com.mentat.data.db.SkillEntity
import com.mentat.data.db.WeaknessDao
import com.mentat.data.db.WeaknessEventEntity
import com.mentat.data.files.FileStore
import com.mentat.data.prefs.SettingsStore
import com.mentat.domain.DispatcherProvider
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupSnapshot(
    val format: Int = 1,
    val exportedOn: String,
    val profile: List<ProfileEntity>,
    val commitments: List<CommitmentEntity>,
    val skills: List<SkillEntity>,
    val miniSkills: List<MiniSkillEntity>,
    val activities: List<ActivityEntity>,
    val questions: List<RecallQuestionEntity>,
    val logs: List<QuestLogEntity>,
    val recalls: List<RecallCheckEntity>,
    val curiosity: List<CuriosityEntity>,
    val goals: List<GoalEntity>,
    val goalSkills: List<GoalSkillEntity>,
    val energy: List<EnergyLogEntity>,
    val weakness: List<WeaknessEventEntity>,
    val plans: List<PlanDayEntity>,
    val proposals: List<ProposedChangeEntity>,
    val pending: List<PendingAiActionEntity>,
    val journal: List<JournalEntryEntity>,
    val aiUsage: List<AiUsageEntity>,
    val combos: List<ComboEntity>,
)

data class BackupSummary(val skills: Int, val logs: Int, val photos: Int)

/** "Export JSON + photos (zip)" via SAF, and restore on a fresh install (Section 14). The Groq key is never exported. */
@Singleton
class BackupRepository @Inject constructor(
    private val db: MentatDatabase,
    private val m: MaintenanceDao,
    private val commitmentDao: CommitmentDao,
    private val skillDao: SkillDao,
    private val logDao: QuestLogDao,
    private val recallDao: RecallDao,
    private val curiosityDao: CuriosityDao,
    private val energyDao: EnergyDao,
    private val weaknessDao: WeaknessDao,
    private val planDao: PlanDao,
    private val proposalDao: ProposalDao,
    private val pendingDao: PendingAiDao,
    private val journalDao: JournalDao,
    private val aiUsageDao: AiUsageDao,
    private val comboDao: ComboDao,
    private val files: FileStore,
    private val settings: SettingsStore,
    private val clock: Clock,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun export(uri: Uri): BackupSummary = withContext(dispatchers.io) {
        val snap = BackupSnapshot(
            exportedOn = LocalDate.now(clock).toString(),
            profile = m.profiles(), commitments = commitmentDao.getAll(), skills = skillDao.getSkills(),
            miniSkills = skillDao.getMiniSkills(), activities = skillDao.getActivities(), questions = skillDao.getQuestions(),
            logs = logDao.getAll(), recalls = recallDao.getAll(), curiosity = curiosityDao.getAll(), goals = m.goals(),
            goalSkills = m.goalSkills(), energy = energyDao.getAll(), weakness = weaknessDao.getAll(), plans = planDao.getAll(),
            proposals = proposalDao.getAll(), pending = pendingDao.getAll(), journal = journalDao.getAll(),
            aiUsage = aiUsageDao.getAll(), combos = comboDao.getAll(),
        )
        val photos = snap.logs.mapNotNull { it.proofPath }.distinct().map { files.proofFile(it) }.filter { it.exists() }
        files.withOutput(uri) { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("data.json"))
                zip.write(json.encodeToString(BackupSnapshot.serializer(), snap).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                photos.forEach { f ->
                    zip.putNextEntry(ZipEntry("proofs/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        settings.setLastExport(LocalDate.now(clock))
        BackupSummary(snap.skills.size, snap.logs.size, photos.size)
    }

    /** Replaces ALL local data with the backup (gate G3 must be confirmed by the caller). */
    suspend fun import(uri: Uri): BackupSummary = withContext(dispatchers.io) {
        var snap: BackupSnapshot? = null
        val staged = File(files.proofDir.parentFile, "restore_tmp").apply { deleteRecursively(); mkdirs() }
        files.withInput(uri) { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = e.name
                    when {
                        name == "data.json" -> snap = json.decodeFromString(BackupSnapshot.serializer(), zip.readBytes().toString(Charsets.UTF_8))
                        name.startsWith("proofs/") && !name.contains("..") && name.length > 7 -> {
                            File(staged, name.removePrefix("proofs/").substringAfterLast('/')).outputStream().use { zip.copyTo(it) }
                        }
                    }
                    e = zip.nextEntry
                }
            }
        }
        val s = snap ?: error("This file is not a Mentat backup (data.json missing)")
        require(s.format == 1) { "Unsupported backup format ${s.format}" }
        db.withTransaction {
            m.clearLogs(); m.clearRecalls(); m.clearSkills(); m.clearGoals(); m.clearProfile(); m.clearCommitments()
            m.clearCuriosity(); m.clearEnergy(); m.clearWeakness(); m.clearPlans(); m.clearProposals(); m.clearPending()
            m.clearJournal(); m.clearAiUsage(); m.clearCombos()
            m.insertProfile(s.profile); m.insertCommitments(s.commitments); m.insertSkills(s.skills)
            m.insertMiniSkills(s.miniSkills); m.insertActivities(s.activities); m.insertQuestions(s.questions)
            m.insertLogs(s.logs); m.insertRecalls(s.recalls); m.insertCuriosity(s.curiosity); m.insertGoals(s.goals)
            m.insertGoalSkills(s.goalSkills); m.insertEnergy(s.energy); m.insertWeakness(s.weakness); m.insertPlans(s.plans)
            m.insertProposals(s.proposals); m.insertPending(s.pending); m.insertJournal(s.journal); m.insertAiUsage(s.aiUsage)
            m.insertCombos(s.combos)
        }
        val photos = staged.listFiles().orEmpty()
        photos.forEach { it.copyTo(File(files.proofDir, it.name), overwrite = true) }
        staged.deleteRecursively()
        BackupSummary(s.skills.size, s.logs.size, photos.size)
    }

    /** Gate G3 "delete history": removes logs, recall results, journal and energy, keeps skills and settings. */
    suspend fun deleteHistory() = withContext(dispatchers.io) {
        db.withTransaction {
            m.clearLogs(); m.clearJournal(); m.clearEnergy(); m.clearWeakness(); m.clearPlans()
        }
        files.proofDir.listFiles()?.forEach { it.delete() }
        Unit
    }
}
