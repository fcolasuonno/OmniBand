package com.omniband.di

import android.content.Context
import androidx.room.Room
import com.omniband.data.db.AppDatabase
import com.omniband.data.db.dao.*
import com.omniband.data.repository.DeviceRepository
import com.omniband.data.repository.HealthRepository
import com.omniband.data.repository.UserPreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Singleton
    @Provides
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "omniband.db")
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides fun provideDeviceDao(db: AppDatabase): DeviceDao = db.deviceDao()
    @Provides fun provideHeartRateDao(db: AppDatabase): HeartRateDao = db.heartRateDao()
    @Provides fun provideStepsDao(db: AppDatabase): StepsDao = db.stepsDao()
    @Provides fun provideSleepDao(db: AppDatabase): SleepDao = db.sleepDao()
    @Provides fun provideSpO2Dao(db: AppDatabase): SpO2Dao = db.spo2Dao()
    @Provides fun provideBatteryDao(db: AppDatabase): BatteryDao = db.batteryDao()
}

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Singleton
    @Provides
    fun provideDeviceRepository(deviceDao: DeviceDao): DeviceRepository =
        DeviceRepository(deviceDao)

    @Singleton
    @Provides
    fun provideHealthRepository(
        heartRateDao: HeartRateDao,
        stepsDao: StepsDao,
        sleepDao: SleepDao,
        spo2Dao: SpO2Dao,
        batteryDao: BatteryDao,
    ): HealthRepository = HealthRepository(heartRateDao, stepsDao, sleepDao, spo2Dao, batteryDao)
}
