package com.example.ironpath.data.backup

interface InstallationGuard {
    suspend fun validate(): InstallationValidationResult
}

interface InstallationSentinel {
    suspend fun readInstallationId(): String?

    suspend fun writeInstallationId(installationId: String): Boolean
}

enum class InstallationValidationResult {
    Validated,
    Initialized,
    Transferred,
    Failed,
}
