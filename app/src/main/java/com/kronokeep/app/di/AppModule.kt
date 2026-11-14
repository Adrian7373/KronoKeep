package com.kronokeep.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.kronokeep.app.data.settings.DataStoreSettingsRepository
import com.kronokeep.app.domain.repository.SettingsRepository
import com.kronokeep.app.data.document.AndroidDocumentImporter
import com.kronokeep.app.data.document.DiskFileStore
import com.kronokeep.app.data.document.LocalFileStore
import com.kronokeep.app.data.local.KronoDatabase
import com.kronokeep.app.data.local.RoomTransactionRunner
import com.kronokeep.app.data.local.TransactionRunner
import com.kronokeep.app.data.remote.DocumentStorage
import com.kronokeep.app.data.remote.SupabaseDocumentStorage
import com.kronokeep.app.data.repository.DocumentRepositoryImpl
import com.kronokeep.app.data.repository.UploadScheduler
import com.kronokeep.app.data.repository.UserIdProvider
import com.kronokeep.app.data.sync.WorkManagerUploadScheduler
import com.kronokeep.app.domain.common.IdGenerator
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.DocumentImporter
import com.kronokeep.app.domain.repository.DocumentRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import com.kronokeep.app.data.local.dao.AssetDao
import com.kronokeep.app.data.local.dao.DocumentDao
import com.kronokeep.app.data.local.dao.MaintenanceScheduleDao
import com.kronokeep.app.data.local.dao.OdometerReadingDao
import com.kronokeep.app.data.local.dao.OutboxDao
import com.kronokeep.app.data.local.dao.ServiceLogDao
import com.kronokeep.app.data.repository.AssetRepositoryImpl
import com.kronokeep.app.data.repository.AuthRepositoryImpl
import com.kronokeep.app.domain.repository.AssetRepository
import com.kronokeep.app.domain.repository.AuthRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-wide scope for work that must outlive a screen (auth state, sync triggers). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Inject the clock instead of calling Instant.now(), so time-dependent logic stays testable. */
    @Provides @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KronoDatabase =
        Room.databaseBuilder(context, KronoDatabase::class.java, KronoDatabase.NAME).build()

    @Provides fun assetDao(db: KronoDatabase): AssetDao = db.assetDao()
    @Provides fun scheduleDao(db: KronoDatabase): MaintenanceScheduleDao = db.scheduleDao()
    @Provides fun serviceLogDao(db: KronoDatabase): ServiceLogDao = db.serviceLogDao()
    @Provides fun odometerDao(db: KronoDatabase): OdometerReadingDao = db.odometerDao()
    @Provides fun documentDao(db: KronoDatabase): DocumentDao = db.documentDao()
    @Provides fun outboxDao(db: KronoDatabase): OutboxDao = db.outboxDao()

    @Provides @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides @Singleton
    fun provideIdGenerator(): IdGenerator = IdGenerator.Random

    @Provides
    fun provideUserIdProvider(auth: AuthRepository): UserIdProvider =
        UserIdProvider { (auth.state.value as? AuthState.SignedIn)?.userId }

    /** Plain client for fetching signed URLs. OkHttp has no cache unless one is configured, which is what we want. */
    @Provides @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(OkHttp)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository
    @Binds abstract fun bindAssetRepository(impl: AssetRepositoryImpl): AssetRepository
    @Binds abstract fun bindDocumentRepository(impl: DocumentRepositoryImpl): DocumentRepository
    @Binds abstract fun bindDocumentImporter(impl: AndroidDocumentImporter): DocumentImporter
    @Binds abstract fun bindDocumentStorage(impl: SupabaseDocumentStorage): DocumentStorage
    @Binds abstract fun bindLocalFileStore(impl: DiskFileStore): LocalFileStore
    @Binds abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner
    @Binds abstract fun bindUploadScheduler(impl: WorkManagerUploadScheduler): UploadScheduler
    @Binds abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
}
