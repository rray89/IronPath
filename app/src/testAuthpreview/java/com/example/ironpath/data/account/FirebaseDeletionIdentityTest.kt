package com.example.ironpath.data.account

import android.os.Bundle
import androidx.credentials.CustomCredential
import com.example.ironpath.domain.account.AccountFailureReason
import com.example.ironpath.domain.account.AccountId
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FirebaseDeletionIdentityTest {
    private val account = AccountId("synthetic-owner")
    private val auth = mockk<FirebaseAuth>()
    private val user = mockk<FirebaseUser>()
    private val runtime = mockk<AuthPreviewFirebaseRuntime>()
    private val broker = mockk<GoogleCredentialActivityBroker>()
    private val sessions = mockk<FirebaseAccountSessionAdapter>()
    private val gate = AccountSessionOperationGate()
    private val subject = FirebaseDeletionIdentity(runtime, broker, sessions, gate)

    init {
        every { runtime.auth } returns auth
        every { auth.currentUser } returns user
        every { user.uid } returns account.opaqueValue
    }

    @After fun cleanup() = unmockkAll()

    @Test
    fun `cancelled chooser never exchanges credentials or signs out`() = runTest {
        coEvery { broker.request(any()) } returns GoogleCredentialRequestResult.Cancelled
        assertEquals(DeletionReauthentication.Cancelled, subject.reauthenticate(account))
        verify(exactly = 0) { user.reauthenticateAndRetrieveData(any()) }
        verify(exactly = 0) { auth.signInWithCredential(any()) }
        coVerify(exactly = 0) { sessions.clearSession() }
    }

    @Test
    fun `missing or different current user cannot open chooser`() = runTest {
        every { auth.currentUser } returns null
        assertEquals(
            DeletionReauthentication.Failed(AccountFailureReason.ReauthenticationRequired),
            subject.reauthenticate(account)
        )
        every { auth.currentUser } returns user
        every { user.uid } returns "other-owner"
        assertEquals(
            DeletionReauthentication.Failed(AccountFailureReason.ReauthenticationRequired),
            subject.reauthenticate(account)
        )
        coVerify(exactly = 0) { broker.request(any()) }
    }

    @Test
    fun `failed credential provider does not delete or replace account`() = runTest {
        coEvery { broker.request(any()) } returns GoogleCredentialRequestResult.Failed
        assertEquals(
            DeletionReauthentication.Failed(AccountFailureReason.ServiceUnavailable),
            subject.reauthenticate(account)
        )
        verify(exactly = 0) { auth.signInWithCredential(any()) }
    }

    @Test
    fun `reauthenticates same Firebase user and force refreshes token without sign in`() = runTest {
        val credential = selectGoogle()
        val result = mockk<AuthResult>()
        val token = mockk<GetTokenResult>()
        every { result.user } returns user
        every { token.token } returns "fresh-firebase-token"
        every { user.reauthenticateAndRetrieveData(credential) } returns Tasks.forResult(result)
        every { user.getIdToken(true) } returns Tasks.forResult(token)
        val outcome = subject.reauthenticate(account) as DeletionReauthentication.Authenticated
        assertEquals("fresh-firebase-token", outcome.token)
        verify(exactly = 1) { user.reauthenticateAndRetrieveData(credential) }
        verify(exactly = 1) { user.getIdToken(true) }
        verify(exactly = 0) { auth.signInWithCredential(any()) }
    }

    @Test
    fun `session epoch change during chooser rejects late credential before Firebase exchange`() =
        runTest {
            selectGoogle()
            coEvery { broker.request(any()) } coAnswers
                {
                    gate.advanceSessionEpoch()
                    GoogleCredentialRequestResult.Selected(mockk<CustomCredential>())
                }
            assertEquals(DeletionReauthentication.Cancelled, subject.reauthenticate(account))
            verify(exactly = 0) { user.reauthenticateAndRetrieveData(any()) }
        }

    @Test
    fun `network and wrong credential errors stay sanitized without switching identity`() =
        runTest {
            val credential = selectGoogle()
            every { user.reauthenticateAndRetrieveData(credential) } returns
                Tasks.forException(mockk<FirebaseNetworkException>())
            assertEquals(
                DeletionReauthentication.Failed(AccountFailureReason.Offline),
                subject.reauthenticate(account)
            )
            every { user.reauthenticateAndRetrieveData(credential) } returns
                Tasks.forException(IllegalArgumentException("private-token-not-for-ui"))
            assertEquals(
                DeletionReauthentication.Failed(AccountFailureReason.ReauthenticationRequired),
                subject.reauthenticate(account)
            )
            verify(exactly = 0) { user.getIdToken(any()) }
            verify(exactly = 0) { auth.signInWithCredential(any()) }
        }

    @Test
    fun `identity change during Firebase reauthentication never returns a token`() = runTest {
        val credential = selectGoogle()
        val other = mockk<FirebaseUser>()
        val result = mockk<AuthResult>()
        every { other.uid } returns "different-user"
        every { result.user } returns other
        every { user.reauthenticateAndRetrieveData(credential) } returns Tasks.forResult(result)
        assertEquals(
            DeletionReauthentication.Failed(AccountFailureReason.ReauthenticationRequired),
            subject.reauthenticate(account)
        )
        verify(exactly = 0) { user.getIdToken(any()) }
    }

    private fun selectGoogle(): AuthCredential {
        val selection = mockk<CustomCredential>()
        val bundle = mockk<Bundle>()
        val googleToken = mockk<GoogleIdTokenCredential>()
        val credential = mockk<AuthCredential>()
        mockkObject(GoogleIdTokenCredential.Companion)
        mockkStatic(GoogleAuthProvider::class)
        every { selection.type } returns GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        every { selection.data } returns bundle
        every { GoogleIdTokenCredential.createFrom(bundle) } returns googleToken
        every { googleToken.idToken } returns "synthetic-google-token"
        every { GoogleAuthProvider.getCredential("synthetic-google-token", null) } returns
            credential
        coEvery { broker.request(any()) } returns GoogleCredentialRequestResult.Selected(selection)
        return credential
    }
}
