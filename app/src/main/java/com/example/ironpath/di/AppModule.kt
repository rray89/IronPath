package com.example.ironpath.di

import androidx.room.Room
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.dev.DevToolsSeeder
import com.example.ironpath.domain.planner.PlanGenerator
import com.example.ironpath.ui.screens.active.ActiveViewModel
import com.example.ironpath.ui.screens.devtools.DevToolsViewModel
import com.example.ironpath.ui.screens.history.HistoryViewModel
import com.example.ironpath.ui.screens.home.HomeViewModel
import com.example.ironpath.ui.screens.plan.PlanViewModel
import org.koin.core.module.dsl.viewModel
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

val domainModule = module {
    single { PlanGenerator() }
}

val devModule = module {
    single { DevToolsSeeder(get(), get(), get(), get()) }
    viewModel { DevToolsViewModel(get()) }
}

val viewModelModule = module {
    viewModel { HomeViewModel(get(), get()) }
    viewModel { PlanViewModel(get(), get(), get()) }
    viewModel { ActiveViewModel(get(), get()) }
    viewModel { HistoryViewModel(get(), get(), get()) }
}
