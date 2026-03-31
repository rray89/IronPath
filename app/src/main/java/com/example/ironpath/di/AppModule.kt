package com.example.ironpath.di

import androidx.room.Room
import com.example.ironpath.data.local.IronPathDatabase
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
