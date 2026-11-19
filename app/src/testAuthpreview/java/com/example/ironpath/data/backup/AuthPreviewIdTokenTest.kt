package com.example.ironpath.data.backup

import com.example.ironpath.domain.backup.BackupFailureReason
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AuthPreviewIdTokenTest {
    @Test
    fun tokenServiceFailuresRemainTypedAndSanitized() = runTest {
        val cases =
            listOf(
                mockk<FirebaseNetworkException>() to BackupFailureReason.Offline,
                mockk<FirebaseTooManyRequestsException>() to BackupFailureReason.QuotaOrRateLimited,
                mockk<FirebaseAuthInvalidUserException>() to
                    BackupFailureReason.ReauthenticationRequired,
                IllegalStateException("private provider detail") to
                    BackupFailureReason.ServiceUnavailable
            )
        for ((failure, expected) in cases) {
            val result =
                runCatching { sanitizedPreviewIdToken { throw failure } }.exceptionOrNull()
                    as CloudBackupFailure
            assertEquals(expected, result.reason)
            assertNull(result.message)
            assertNull(result.cause)
        }
    }

    @Test
    fun blankIdentityAndCancellationFailWithoutSnapshotWarnings() = runTest {
        val blank =
            runCatching { sanitizedPreviewIdToken { "" } }.exceptionOrNull() as CloudBackupFailure
        assertEquals(BackupFailureReason.ReauthenticationRequired, blank.reason)
        val cancelled = CancellationException("cancelled")
        assertSame(
            cancelled,
            runCatching { sanitizedPreviewIdToken { throw cancelled } }.exceptionOrNull()
        )
        assertEquals("opaque-token", sanitizedPreviewIdToken { "opaque-token" })
    }
}
