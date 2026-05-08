package nodomain.freeyourgadget.gadgetbridge.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import nodomain.freeyourgadget.gadgetbridge.data.db.AppDatabase
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.BatteryDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.DeviceDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.HeartRateDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.SleepDao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.SpO2Dao
import nodomain.freeyourgadget.gadgetbridge.data.db.dao.StepsDao
import nodomain.freeyourgadget.gadgetbridge.data.repository.DeviceRepository
import nodomain.freeyourgadget.gadgetbridge.data.repository.HealthRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Singleton
    @Provides
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "freeyourgadget.gadgetbridge.db")
            .fallbackToDestructiveMigration()
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
        batteryDao: BatteryDao
    ): HealthRepository = HealthRepository(heartRateDao, stepsDao, sleepDao, spo2Dao, batteryDao)
}
