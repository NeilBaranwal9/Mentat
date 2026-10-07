package com.mentat.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.mentat.BuildConfig
import com.mentat.data.db.AiUsageDao
import com.mentat.data.db.ComboDao
import com.mentat.data.db.CommitmentDao
import com.mentat.data.db.CuriosityDao
import com.mentat.data.db.EnergyDao
import com.mentat.data.db.GoalDao
import com.mentat.data.db.JournalDao
import com.mentat.data.db.MaintenanceDao
import com.mentat.data.db.PendingAiDao
import com.mentat.data.db.PlanDao
import com.mentat.data.db.MentatDatabase
import com.mentat.data.db.ProfileDao
import com.mentat.data.db.ProposalDao
import com.mentat.data.db.QuestLogDao
import com.mentat.data.db.RecallDao
import com.mentat.data.db.SkillDao
import com.mentat.data.db.WeaknessDao
import com.mentat.data.net.GroqApi
import com.mentat.data.net.GroqClient
import com.mentat.data.secret.KeystoreSecretStore
import com.mentat.data.secret.SecretStore
import com.mentat.domain.DefaultDispatcherProvider
import com.mentat.domain.DispatcherProvider
import com.mentat.domain.agent.AgentRunner
import com.mentat.domain.agent.LlmCaller
import com.mentat.domain.engine.Planner
import com.mentat.domain.engine.RecallScheduler
import com.mentat.domain.newId
import com.mentat.domain.validate.AgentJson
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SettingsPrefs
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SecretPrefs

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton fun clock(): Clock = Clock.systemDefaultZone()

    @Provides @Singleton fun dispatchers(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides @Singleton fun json(): Json = AgentJson.relaxed

    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): MentatDatabase =
        Room.databaseBuilder(ctx, MentatDatabase::class.java, MentatDatabase.NAME)
            .addMigrations(*MentatDatabase.MIGRATIONS)
            .build()

    @Provides fun profileDao(db: MentatDatabase): ProfileDao = db.profileDao()
    @Provides fun commitmentDao(db: MentatDatabase): CommitmentDao = db.commitmentDao()
    @Provides fun skillDao(db: MentatDatabase): SkillDao = db.skillDao()
    @Provides fun questLogDao(db: MentatDatabase): QuestLogDao = db.questLogDao()
    @Provides fun recallDao(db: MentatDatabase): RecallDao = db.recallDao()
    @Provides fun curiosityDao(db: MentatDatabase): CuriosityDao = db.curiosityDao()
    @Provides fun goalDao(db: MentatDatabase): GoalDao = db.goalDao()
    @Provides fun energyDao(db: MentatDatabase): EnergyDao = db.energyDao()
    @Provides fun weaknessDao(db: MentatDatabase): WeaknessDao = db.weaknessDao()
    @Provides fun planDao(db: MentatDatabase): PlanDao = db.planDao()
    @Provides fun proposalDao(db: MentatDatabase): ProposalDao = db.proposalDao()
    @Provides fun pendingAiDao(db: MentatDatabase): PendingAiDao = db.pendingAiDao()
    @Provides fun journalDao(db: MentatDatabase): JournalDao = db.journalDao()
    @Provides fun aiUsageDao(db: MentatDatabase): AiUsageDao = db.aiUsageDao()
    @Provides fun comboDao(db: MentatDatabase): ComboDao = db.comboDao()
    @Provides fun maintenanceDao(db: MentatDatabase): MaintenanceDao = db.maintenanceDao()

    @Provides @Singleton @SettingsPrefs
    fun settingsStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { ctx.preferencesDataStoreFile("settings") })

    @Provides @Singleton @SecretPrefs
    fun secretPrefs(@ApplicationContext ctx: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { ctx.preferencesDataStoreFile("secrets") })

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply { debugInterceptors().forEach { addInterceptor(it) } }
        .build()

    @Provides @Singleton
    fun groqApi(client: OkHttpClient, json: Json): GroqApi = Retrofit.Builder()
        .baseUrl(BuildConfig.GROQ_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GroqApi::class.java)

    @Provides @Singleton fun planner(): Planner = Planner()

    @Provides @Singleton fun recallScheduler(): RecallScheduler = RecallScheduler { newId() }

    @Provides @Singleton fun agentRunner(llm: LlmCaller): AgentRunner = AgentRunner(llm)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun secretStore(impl: KeystoreSecretStore): SecretStore
    @Binds abstract fun llmCaller(impl: GroqClient): LlmCaller
}
