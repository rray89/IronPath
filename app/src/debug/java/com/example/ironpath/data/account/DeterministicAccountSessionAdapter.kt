package com.example.ironpath.data.account

import android.content.Context
import android.util.AtomicFile
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.account.AccountProfile
import com.example.ironpath.domain.account.AccountSessionAdapter
import com.example.ironpath.domain.account.CredentialResult
import com.example.ironpath.domain.account.RemoteSnapshotPresence
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Debug credentials only. No Google/Firebase SDK, token, or network access. */
@Singleton
class DeterministicAccountSessionAdapter @Inject constructor(@ApplicationContext context: Context) :
    AccountSessionAdapter {
    private val sessionFile = AtomicFile(File(context.noBackupFilesDir, SESSION_FILE_NAME))

    override suspend fun requestGoogleCredential(): CredentialResult =
        CredentialResult.Selected(PROFILE)

    override suspend fun readSession(): AccountProfile? =
        withContext(Dispatchers.IO) {
            val identifier =
                try {
                    sessionFile.readFully().toString(Charsets.UTF_8)
                } catch (missing: FileNotFoundException) {
                    if (sessionFile.baseFile.exists()) throw missing
                    return@withContext null
                }
            when (identifier) {
                PROFILE.id.opaqueValue -> PROFILE
                "" -> null
                else -> error("Unknown deterministic session")
            }
        }

    override suspend fun saveSession(profile: AccountProfile): Boolean =
        withContext(Dispatchers.IO) {
            require(profile == PROFILE)
            writeSession(profile.id.opaqueValue)
        }

    override suspend fun clearSession(): Boolean = withContext(Dispatchers.IO) { writeSession("") }

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
        RemoteSnapshotPresence.Absent

    companion object {
        const val SESSION_FILE_NAME = "ironpath-debug-account-session"
        val PROFILE =
            AccountProfile(
                AccountId("ironpath-demo-athlete"),
                "Demo Athlete",
                "athlete@example.invalid"
            )
    }
}
