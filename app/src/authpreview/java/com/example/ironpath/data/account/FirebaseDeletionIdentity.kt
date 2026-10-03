package com.example.ironpath.data.account

import androidx.credentials.CustomCredential
import com.example.ironpath.domain.account.AccountFailureReason
import com.example.ironpath.domain.account.AccountId
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.GoogleAuthProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Reauthentication never signs in a different user or creates another Firebase identity. */
@Singleton
class FirebaseDeletionIdentity
@Inject
constructor(
    private val runtime: AuthPreviewFirebaseRuntime,
    private val broker: GoogleCredentialActivityBroker,
    private val sessions: FirebaseAccountSessionAdapter,
    private val gate: AccountSessionOperationGate,
) : AccountDeletionIdentity {
    private var requestSequence = -1L

    override suspend fun currentAccount() = sessions.readSession()?.id

    override suspend fun clearDeletedSession(account: AccountId) =
        sessions.clearDeletedSession(account)

    override suspend fun reauthenticate(account: AccountId): DeletionReauthentication {
        val auth = runtime.auth ?: return failed()
        val user = auth.currentUser ?: return failed()
        if (user.uid != account.opaqueValue) return failed()
        val epoch = gate.sessionEpoch
        fun sameSession() =
            auth.currentUser?.uid == account.opaqueValue && gate.sessionEpoch == epoch
        return try {
            val selected =
                when (val requested = broker.request(requestSequence--)) {
                    GoogleCredentialRequestResult.Cancelled ->
                        return DeletionReauthentication.Cancelled
                    GoogleCredentialRequestResult.Failed ->
                        return failed(AccountFailureReason.ServiceUnavailable)
                    is GoogleCredentialRequestResult.Selected -> requested.credential
                }
            if (!sameSession()) return DeletionReauthentication.Cancelled
            if (
                selected !is CustomCredential ||
                    selected.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            )
                return failed()
            val googleToken = GoogleIdTokenCredential.createFrom(selected.data).idToken
            if (googleToken.isBlank()) return failed()
            val result =
                user
                    .reauthenticateAndRetrieveData(
                        GoogleAuthProvider.getCredential(googleToken, null)
                    )
                    .await()
            if (!sameSession() || result.user?.uid != account.opaqueValue) return failed()
            val token = user.getIdToken(true).await().token
            if (!sameSession() || token.isNullOrBlank()) return failed()
            DeletionReauthentication.Authenticated(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FirebaseNetworkException) {
            failed(AccountFailureReason.Offline)
        } catch (_: Exception) {
            failed()
        }
    }

    private fun failed(
        reason: AccountFailureReason = AccountFailureReason.ReauthenticationRequired
    ) = DeletionReauthentication.Failed(reason)
}
