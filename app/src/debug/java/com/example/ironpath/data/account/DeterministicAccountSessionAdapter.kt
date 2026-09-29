package com.example.ironpath.data.account

import android.content.Context
import android.util.AtomicFile
import com.example.ironpath.data.backup.RemoteBackupRead
import com.example.ironpath.data.backup.RemoteBackupStore
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.account.AccountProfile
import com.example.ironpath.domain.account.AccountSessionAdapter
import com.example.ironpath.domain.account.CredentialResult
import com.example.ironpath.domain.account.RemoteSnapshotPresence
import com.example.ironpath.domain.account.UnreadableAccountSessionException
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.identity.UuidIdProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Debug credentials only. No Google/Firebase SDK, token, or network access. */
@Singleton
class DeterministicAccountSessionAdapter
@Inject
constructor(
    @ApplicationContext context: Context,
    private val remote: RemoteBackupStore,
    private val idProvider: IdProvider,
) : AccountSessionAdapter {
    private val sessionFile = AtomicFile(File(context.noBackupFilesDir, SESSION_FILE_NAME))
    private val registryFile =
        AtomicFile(File(context.noBackupFilesDir, ACCOUNT_REGISTRY_FILE_NAME))
    private val deletedAccountsFile =
        AtomicFile(File(context.noBackupFilesDir, DELETED_ACCOUNTS_FILE_NAME))

    internal constructor(
        context: Context,
        remote: RemoteBackupStore
    ) : this(context, remote, UuidIdProvider())

    override suspend fun requestGoogleCredential(): CredentialResult =
        withContext(Dispatchers.IO) {
            synchronized(LIFECYCLE_LOCK) {
                CredentialResult.Selected(profile(AccountId(currentAccountId())))
            }
        }

    override suspend fun readSession(): AccountProfile? =
        withContext(Dispatchers.IO) {
            val identifier =
                try {
                    sessionFile.readFully().toString(Charsets.UTF_8)
                } catch (missing: FileNotFoundException) {
                    if (sessionFile.baseFile.exists()) throw missing
                    return@withContext null
                }
            if (identifier.isEmpty()) return@withContext null
            if (identifier in deletedAccounts() || identifier != currentAccountId())
                throw UnreadableAccountSessionException()
            profile(AccountId(identifier))
        }

    override suspend fun saveSession(profile: AccountProfile): Boolean =
        withContext(Dispatchers.IO) {
            require(profile.displayName == PROFILE.displayName && profile.email == PROFILE.email)
            require(profile.id.opaqueValue == currentAccountId())
            require(profile.id.opaqueValue !in deletedAccounts())
            writeSession(profile.id.opaqueValue)
        }

    override suspend fun clearSession(): Boolean = withContext(Dispatchers.IO) { writeSession("") }

    override suspend fun clearUnreadableSession(): Boolean =
        withContext(Dispatchers.IO) {
            val identifier =
                try {
                    sessionFile.readFully().toString(Charsets.UTF_8)
                } catch (missing: FileNotFoundException) {
                    return@withContext !sessionFile.baseFile.exists()
                } catch (_: Exception) {
                    return@withContext false
                }
            if (identifier in deletedAccounts() || identifier == currentAccountId())
                return@withContext false
            if (identifier.isEmpty()) return@withContext true
            writeSession("")
        }

    override suspend fun deleteDemoAccount(accountId: AccountId): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(LIFECYCLE_LOCK) {
                val current = readCurrentAccountId()
                val deleted = deletedAccounts().toMutableSet()
                deleted += accountId.opaqueValue
                if (!writeAtomic(deletedAccountsFile, deleted.sorted().joinToString("\n")))
                    return@synchronized false
                if (current == accountId.opaqueValue) {
                    val next = idProvider.newId()
                    if (next.isBlank() || next == accountId.opaqueValue || next in deleted)
                        return@synchronized false
                    if (!writeAtomic(registryFile, next)) return@synchronized false
                }
                accountId.opaqueValue in deletedAccounts() &&
                    currentAccountId() != accountId.opaqueValue
            }
        }

    override suspend fun clearDeletedSession(accountId: AccountId): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(LIFECYCLE_LOCK) {
                val identifier =
                    try {
                        sessionFile.readFully().toString(Charsets.UTF_8)
                    } catch (missing: FileNotFoundException) {
                        return@synchronized !sessionFile.baseFile.exists()
                    } catch (_: Exception) {
                        return@synchronized false
                    }
                when {
                    identifier.isEmpty() -> true
                    identifier != accountId.opaqueValue -> false
                    identifier !in deletedAccounts() -> false
                    else -> writeSession("")
                }
            }
        }

    private fun currentAccountId(): String {
        val stored = readCurrentAccountId()
        check(stored !in deletedAccounts())
        return stored
    }

    private fun readCurrentAccountId(): String {
        val stored =
            try {
                registryFile.readFully().toString(Charsets.UTF_8)
            } catch (missing: FileNotFoundException) {
                if (registryFile.baseFile.exists()) throw missing
                LEGACY_ACCOUNT_ID
            }
        check(stored.isNotBlank())
        return stored
    }

    private fun deletedAccounts(): Set<String> {
        val stored =
            try {
                deletedAccountsFile.readFully().toString(Charsets.UTF_8)
            } catch (missing: FileNotFoundException) {
                if (deletedAccountsFile.baseFile.exists()) throw missing
                return emptySet()
            }
        return stored.lineSequence().filter(String::isNotBlank).toSet()
    }

    private fun writeAtomic(file: AtomicFile, value: String): Boolean {
        val output = file.startWrite()
        return try {
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            file.finishWrite(output)
            file.readFully().toString(Charsets.UTF_8) == value
        } catch (_: Exception) {
            file.failWrite(output)
            false
        }
    }

    private fun profile(id: AccountId) = PROFILE.copy(id = id)

    private fun writeSession(identifier: String): Boolean {
        val output = sessionFile.startWrite()
        return try {
            output.write(identifier.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            sessionFile.finishWrite(output)
            sessionFile.readFully().toString(Charsets.UTF_8) == identifier
        } catch (_: Exception) {
            sessionFile.failWrite(output)
            false
        }
    }

    override suspend fun remoteSnapshot(accountId: AccountId): RemoteSnapshotPresence =
        when (val result = remote.latest(accountId)) {
            is RemoteBackupRead.Absent -> RemoteSnapshotPresence.Absent
            is RemoteBackupRead.Complete ->
                RemoteSnapshotPresence.Complete(
                    result.backup.summary.backupId,
                    result.backup.generation,
                    result.backup.summary.sourceInstallationId,
                    result.backup.snapshot.contentDigest,
                )
            is RemoteBackupRead.Failed -> error("Demo backup state is unavailable")
        }

    companion object {
        const val SESSION_FILE_NAME = "ironpath-debug-account-session"
        const val ACCOUNT_REGISTRY_FILE_NAME = "ironpath-debug-account-registry"
        const val DELETED_ACCOUNTS_FILE_NAME = "ironpath-debug-deleted-accounts"
        private const val LEGACY_ACCOUNT_ID = "ironpath-demo-athlete"
        private val LIFECYCLE_LOCK = Any()
        val PROFILE =
            AccountProfile(
                AccountId("ironpath-demo-athlete"),
                "Demo Athlete",
                "athlete@example.invalid"
            )
    }
}
