package com.example.ironpath.testutil

import com.example.ironpath.data.backup.InstallationSentinel
import com.example.ironpath.di.InstallationSentinelModule
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Inject
import javax.inject.Singleton

/** Each Hilt test component owns its sentinel, just as it owns its isolated Room database. */
@Singleton
class InMemoryInstallationSentinel @Inject constructor() : InstallationSentinel {
    private var installationId: String? = null

    override suspend fun readInstallationId() = installationId

    override suspend fun writeInstallationId(installationId: String): Boolean {
        this.installationId = installationId
        return true
    }
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [InstallationSentinelModule::class]
)
abstract class TestInstallationSentinelModule {
    @Binds
    @Singleton
    abstract fun bindSentinel(implementation: InMemoryInstallationSentinel): InstallationSentinel
}
