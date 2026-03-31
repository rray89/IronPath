package com.example.ironpath.di

import androidx.room.Room
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.data.repository.SessionRepository
import org.koin.dsl.module

val databaseModule = module {
    single {
        Room.databaseBuilder(
            get(),
            IronPathDatabase::class.java,
            "ironpath.db",
        ).build()
    }

    single { get<IronPathDatabase>().planDao() }
    single { get<IronPathDatabase>().sessionDao() }
    single { get<IronPathDatabase>().historyDao() }
    single { get<IronPathDatabase>().recordDao() }
}

val repositoryModule = module {
    single { PlanRepository(get()) }
    single { SessionRepository(get(), get(), get()) }
    single { HistoryRepository(get()) }
    single { RecordRepository(get()) }
}
