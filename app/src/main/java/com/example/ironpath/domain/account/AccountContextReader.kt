package com.example.ironpath.domain.account

import kotlinx.coroutines.flow.Flow

interface AccountContextReader {
    val changes: Flow<Unit>

    suspend fun read(): LocalAccountContext
}

data class LocalAccountContext(
    val ownerUid: String?,
    val localDataIsEmpty: Boolean,
    val conflict: PersistedConflictContext,
    val pendingSignOutUid: String? = null,
)
