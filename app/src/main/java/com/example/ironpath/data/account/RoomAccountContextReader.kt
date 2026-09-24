package com.example.ironpath.data.account

import androidx.room.withTransaction
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.domain.account.AccountContextReader
import com.example.ironpath.domain.account.LocalAccountContext
import com.example.ironpath.domain.account.PersistedConflictContext
import javax.inject.Inject
import kotlinx.coroutines.flow.map

class RoomAccountContextReader @Inject constructor(private val database: IronPathDatabase) :
    AccountContextReader {
    override val changes = database.backupDao().observeMetadata().map { Unit }

    override suspend fun read(): LocalAccountContext =
        database.withTransaction {
            val dao = database.backupDao()
            val metadata = checkNotNull(dao.getMetadata()) { "Account metadata is not initialized" }
            LocalAccountContext(
                ownerUid = metadata.ownerUid,
                localDataIsEmpty = !dao.hasIncludedData(),
                pendingSignOutUid = metadata.pendingSignOutUid,
                conflict =
                    PersistedConflictContext(
                        metadata.lastObservedRemoteBackupId,
                        metadata.lastObservedRemoteGeneration,
                        metadata.lastObservedRemoteDigest,
                        metadata.lastObservedSourceInstallationId,
                        metadata.installationId,
                        metadata.localChangeRevision,
                        metadata.lastCompleteLocalRevision,
                    ),
            )
        }
}
