package com.example.ironpath.di

import android.content.Context
import androidx.room.Room
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.PlanDao
import com.example.ironpath.data.local.dao.RecordDao
import com.example.ironpath.data.local.dao.SessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): IronPathDatabase =
        Room.databaseBuilder(
                context,
                IronPathDatabase::class.java,
                "ironpath.db",
            )
            .addMigrations(IronPathDatabase.MIGRATION_1_2, IronPathDatabase.MIGRATION_2_3)
            .build()

    @Provides
    @Singleton
    fun providePlanDao(database: IronPathDatabase): PlanDao = database.planDao()

    @Provides
    @Singleton
    fun provideSessionDao(database: IronPathDatabase): SessionDao = database.sessionDao()

    @Provides
    @Singleton
    fun provideHistoryDao(database: IronPathDatabase): HistoryDao = database.historyDao()

    @Provides
    @Singleton
    fun provideRecordDao(database: IronPathDatabase): RecordDao = database.recordDao()
}
