package com.example.ironpath.testutil

import android.content.Context
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.PlanDao
import com.example.ironpath.data.local.dao.RecordDao
import com.example.ironpath.data.local.dao.SessionDao
import com.example.ironpath.di.DatabaseModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DatabaseModule::class],
)
object TestDatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): IronPathDatabase =
        TestDatabaseRegistry.open(context)

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
