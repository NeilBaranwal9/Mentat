package com.polymath.os.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.polymath.os.BuildConfig
import com.polymath.os.data.db.AiUsageDao
import com.polymath.os.data.db.ComboDao
import com.polymath.os.data.db.CommitmentDao
import com.polymath.os.data.db.CuriosityDao
import com.polymath.os.data.db.EnergyDao
import com.polymath.os.data.db.GoalDao
import com.polymath.os.data.db.JournalDao
import com.polymath.os.data.db.MaintenanceDao
import com.polymath.os.data.db.PendingAiDao
import com.polymath.os.data.db.PlanDao
import com.polymath.os.data.db.PolymathDatabase
import com.polymath.os.data.db.ProfileDao
import com.polymath.os.data.db.ProposalDao
import com.polymath.os.data.db.QuestLogDao
import com.polymath.os.data.db.RecallDao
import com.polymath.os.data.db.SkillDao
import com.polymath.os.data.db.WeaknessDao
import com.polymath.os.data.net.GroqApi
import com.polymath.os.data.net.GroqClient
import com.polymath.os.data.secret.KeystoreSecretStore
import com.polymath.os.data.secret.SecretStore
import com.polymath.os.domain.DefaultDispatcherProvider
import com.polymath.os.domain.DispatcherProvider
import com.polymath.os.domain.agent.AgentRunner
import com.polymath.os.domain.agent.LlmCaller
import com.polymath.os.domain.engine.Planner
import com.polymath.os.domain.engine.RecallScheduler
import com.polymath.os.domain.newId
import com.polymath.os.domain.validate.AgentJson
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
    fun database(@ApplicationContext ctx: Context): PolymathDatabase =
        Room.databaseBuilder(ctx, PolymathDatabase::class.java, PolymathDatabase.NAME)
            .addMigrations(*PolymathDatabase.MIGRATIONS)
            .build()

    @Provides fun profileDao(db: PolymathDatabase): ProfileDao = db.profileDao()
    @Provides fun commitmentDao(db: PolymathDatabase): CommitmentDao = db.commitmentDao()
    @Provides fun skillDao(db: PolymathDatabase): SkillDao = db.skillDao()
    @Provides fun questLogDao(db: PolymathDatabase): QuestLogDao = db.questLogDao()
    @Provides fun recallDao(db: PolymathDatabase): RecallDao = db.recallDao()
    @Provides fun curiosityDao(db: PolymathDatabase): CuriosityDao = db.curiosityDao()
    @Provides fun goalDao(db: PolymathDatabase): GoalDao = db.goalDao()
    @Provides fun energyDao(db: PolymathDatabase): EnergyDao = db.energyDao()
    @Provides fun weaknessDao(db: PolymathDatabase): WeaknessDao = db.weaknessDao()
    @Provides fun planDao(db: PolymathDatabase): PlanDao = db.planDao()
    @Provides fun proposalDao(db: PolymathDatabase): ProposalDao = db.proposalDao()
    @Provides fun pendingAiDao(db: PolymathDatabase): PendingAiDao = db.pendingAiDao()
    @Provides fun journalDao(db: PolymathDatabase): JournalDao = db.journalDao()
    @Provides fun aiUsageDao(db: PolymathDatabase): AiUsageDao = db.aiUsageDao()
    @Provides fun comboDao(db: PolymathDatabase): ComboDao = db.comboDao()
    @Provides fun maintenanceDao(db: PolymathDatabase): MaintenanceDao = db.maintenanceDao()

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
