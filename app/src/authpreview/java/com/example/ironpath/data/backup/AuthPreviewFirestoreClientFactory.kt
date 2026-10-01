package com.example.ironpath.data.backup

import com.example.ironpath.data.account.AccountSessionOperationGate
import com.example.ironpath.data.account.AuthPreviewFirebaseRuntime
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.backup.BackupFailureReason
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.tasks.await

/** Only the explicit named Auth preview app can supply this private project's ID tokens. */
@Singleton
class AuthPreviewFirestoreClientFactory
@Inject
constructor(
    private val runtime: AuthPreviewFirebaseRuntime,
    private val gate: AccountSessionOperationGate,
) : FirestoreBackupClientFactory {
    override suspend fun forAccount(account: AccountId): FirestoreBackupClient {
        val auth = runtime.auth ?: throw CloudBackupFailure(BackupFailureReason.ServiceUnavailable)
        val project =
            auth.app.options.projectId
                ?: throw CloudBackupFailure(BackupFailureReason.ServiceUnavailable)
        val epoch = gate.sessionEpoch
        val job = currentCoroutineContext()[Job]
        fun authorized() =
            job?.isActive != false &&
                gate.sessionEpoch == epoch &&
                auth.currentUser?.uid == account.opaqueValue
        if (!authorized()) throw CloudBackupFailure(BackupFailureReason.ReauthenticationRequired)
        return FirestoreBackupRestClient(
            project,
            token = {
                try {
                    if (!authorized())
                        throw CloudBackupFailure(BackupFailureReason.ReauthenticationRequired)
                    val token = auth.currentUser?.getIdToken(false)?.await()?.token
                    if (!authorized() || token.isNullOrBlank())
                        throw CloudBackupFailure(BackupFailureReason.ReauthenticationRequired)
                    token
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: FirebaseAuthInvalidUserException) {
                    throw CloudBackupFailure(BackupFailureReason.ReauthenticationRequired)
                }
            },
            authorized = ::authorized
        )
    }
}
