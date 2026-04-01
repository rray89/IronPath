package com.example.ironpath

import android.app.Application
import com.example.ironpath.di.databaseModule
import com.example.ironpath.di.domainModule
import com.example.ironpath.di.repositoryModule
import com.example.ironpath.di.viewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class IronPathApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@IronPathApplication)
            modules(databaseModule, repositoryModule, domainModule, viewModelModule)
        }
    }
}
