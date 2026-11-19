package com.example.ironpath.data.account

import androidx.credentials.CustomCredential
import com.example.ironpath.domain.account.AccountFailureReason
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.account.AccountProfile
import com.example.ironpath.domain.account.AccountSessionAdapter
import com.example.ironpath.domain.account.CredentialCommitResult
import com.example.ironpath.domain.account.CredentialResult
import com.example.ironpath.domain.account.PendingGoogleCredential
import com.example.ironpath.domain.account.RemoteSnapshotPresence
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.tasks.await

/** Firebase-backed identity for the explicitly selected authpreview application. */
@Singleton
class FirebaseAccountSessionAdapter
@Inject
constructor(
    private val runtime: AuthPreviewFirebaseRuntime,
    private val credentialBroker: GoogleCredentialActivityBroker,
) : AccountSessionAdapter {
    private val pendingCredentials =
        java.util.IdentityHashMap<PendingGoogleCredential, AuthCredential>()
    private val credentialLock = Any()

    private val auth: FirebaseAuth?
        get() = runtime.auth

    override val sessionChanges: Flow<Unit>
        get() =
            auth?.let { firebaseAuth ->
                callbackFlow {
                    val listener =
                        FirebaseAuth.AuthStateListener {
                            // The gateway rereads currentUser and deduplicates by UID while under
                            // its session gate; also forward the listener's initial callback.
                            trySend(Unit)
                        }
                    firebaseAuth.addAuthStateListener(listener)
                    awaitClose { firebaseAuth.removeAuthStateListener(listener) }
                }
            } ?: emptyFlow()

    override suspend fun requestGoogleCredential(requestId: Long): CredentialResult =
        when (val requested = credentialBroker.request(requestId)) {
            is GoogleCredentialRequestResult.Selected -> {
                val selected = requested.credential
                if (
                    selected !is CustomCredential ||
                        selected.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    CredentialResult.Failed(AccountFailureReason.Unknown)
                } else {
                    try {
                        val idToken = GoogleIdTokenCredential.createFrom(selected.data).idToken
                        if (idToken.isBlank()) {
                            CredentialResult.Failed(AccountFailureReason.Unknown)
                        } else {
                            val candidate = PendingGoogleCredential()
                            synchronized(credentialLock) {
                                pendingCredentials[candidate] =
                                    GoogleAuthProvider.getCredential(idToken, null)
                            }
                            CredentialResult.Selected(candidate)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        CredentialResult.Failed(AccountFailureReason.Unknown)
                    }
                }
            }
            GoogleCredentialRequestResult.Cancelled -> CredentialResult.Cancelled
            GoogleCredentialRequestResult.Failed ->
                CredentialResult.Failed(AccountFailureReason.ServiceUnavailable)
        }

    override suspend fun commitGoogleCredential(
        candidate: PendingGoogleCredential,
    ): CredentialCommitResult {
        val firebaseAuth =
            auth ?: return CredentialCommitResult.Failed(AccountFailureReason.ServiceUnavailable)
        val credential =
            synchronized(credentialLock) { pendingCredentials.remove(candidate) }
                ?: return CredentialCommitResult.Failed(AccountFailureReason.Unknown)
        return try {
            val result = firebaseAuth.signInWithCredential(credential).await()
            val user =
                result.user ?: return CredentialCommitResult.Failed(AccountFailureReason.Unknown)
            val persistedUid = firebaseAuth.currentUser?.uid
            if (user.uid.isBlank() || persistedUid != user.uid) {
                CredentialCommitResult.Failed(AccountFailureReason.LocalStateUnavailable)
            } else {
                CredentialCommitResult.Authenticated(user.toAccountProfile())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CredentialCommitResult.Failed(AccountFailureReason.ServiceUnavailable)
        }
    }

    override suspend fun discardGoogleCredential(candidate: PendingGoogleCredential) {
        synchronized(credentialLock) { pendingCredentials.remove(candidate) }
    }

    override fun cancelGoogleCredentialRequest(requestId: Long) {
        credentialBroker.cancel(requestId)
    }

    override suspend fun readSession(): AccountProfile? = auth?.currentUser?.toAccountProfile()

    override suspend fun clearSession(): Boolean {
        val firebaseAuth = auth ?: return false
        return try {
            clearSessionWithProviderCleanup(
                signOutAndVerify = {
                    firebaseAuth.signOut()
                    firebaseAuth.currentUser == null
                },
                clearProviderState = { credentialBroker.clearProviderCredentialState() },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            firebaseAuth.currentUser == null
        }
    }

    override suspend fun clearUnreadableSession(): Boolean = false

    override suspend fun remoteSnapshot(accountId: AccountId): RemoteSnapshotPresence =
        RemoteSnapshotPresence.Absent

    override suspend fun deleteDemoAccount(accountId: AccountId): Boolean = false

    override suspend fun clearDeletedSession(accountId: AccountId): Boolean {
        val current = readSession() ?: return true
        return current.id != accountId || clearSession()
    }

    private fun com.google.firebase.auth.FirebaseUser.toAccountProfile(): AccountProfile {
        val safeName =
            displayName?.takeIf(String::isNotBlank)
                ?: email?.takeIf(String::isNotBlank)
                ?: "Google account"
        return AccountProfile(AccountId(uid), safeName, email.orEmpty())
    }
}
