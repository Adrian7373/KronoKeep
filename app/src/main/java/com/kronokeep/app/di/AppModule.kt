package com.kronokeep.app.di

import android.content.Context
import androidx.room.Room
import com.kronokeep.app.data.local.KronoDatabase
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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository
    @Binds abstract fun bindAssetRepository(impl: AssetRepositoryImpl): AssetRepository
}
