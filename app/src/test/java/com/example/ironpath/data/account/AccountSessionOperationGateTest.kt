package com.example.ironpath.data.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSessionOperationGateTest {
    @Test
    fun `provider cleanup cancellation cannot fail a committed local sign out`() = runTest {
        var firebaseSessionCleared = false
        var providerCleanupAttempted = false

        val cleared =
            clearSessionWithProviderCleanup(
                signOutAndVerify = {
                    firebaseSessionCleared = true
                    true
                },
                clearProviderState = {
                    providerCleanupAttempted = true
                    throw CancellationException("provider cleanup cancelled")
                },
            )

        assertTrue(firebaseSessionCleared)
        assertTrue(providerCleanupAttempted)
        assertTrue(cleared)
    }

    @Test
    fun `detaching chooser host cancels attempt and rejects its late result until explicit retry`() {
        val fence = AccountCredentialRequestFence()
        assertTrue(fence.begin(4L))
        val detachedAttempt = checkNotNull(fence.beginAttempt(4L))

        assertTrue(fence.detach(4L))
        assertFalse(fence.isCurrent(detachedAttempt))
        assertFalse(fence.complete(detachedAttempt))

        assertTrue(fence.begin(5L))
        val retryAttempt = checkNotNull(fence.beginAttempt(5L))
        assertTrue(fence.complete(retryAttempt))
        assertTrue(fence.begin(6L))
    }

    @Test
    fun `replacing chooser host invalidates the old attempt until explicit retry`() {
        val fence = AccountCredentialRequestFence()
        assertTrue(fence.begin(8L))
        val oldHostAttempt = checkNotNull(fence.beginAttempt(8L))

        assertTrue(fence.replaceHost(8L))
        assertFalse(fence.isCurrent(oldHostAttempt))
        assertFalse(fence.complete(oldHostAttempt))

        assertTrue(fence.begin(9L))
        val explicitRetry = checkNotNull(fence.beginAttempt(9L))
        assertTrue(fence.complete(explicitRetry))
    }

    @Test
    fun `provider observation serializes before reconciliation without implicit epoch advance`() =
        runTest {
            val gate = AccountSessionOperationGate()
            val observationStarted = CompletableDeferred<Unit>()
            val releaseObservation = CompletableDeferred<Unit>()
            val manualStarted = CompletableDeferred<Unit>()

            val observation = async {
                gate.withSessionObservation { epoch ->
                    observationStarted.complete(Unit)
                    releaseObservation.await()
                    AccountSessionOperationGate.MutationResult(epoch, reopenAdmission = true)
                }
            }
            runCurrent()
            assertTrue(observationStarted.isCompleted)
            assertEquals(0L, gate.sessionEpoch)
            assertEquals(
                "unavailable",
                gate.withManualOperation(waitForTurn = false, unavailable = "unavailable") {
                    "late"
                },
            )
            val queued = async {
                gate.withManualOperation(waitForTurn = true, unavailable = "unavailable") {
                    manualStarted.complete(Unit)
                    "manual"
                }
            }
            runCurrent()
            assertFalse(manualStarted.isCompleted)

            releaseObservation.complete(Unit)
            runCurrent()
            assertEquals(0L, observation.await())
            assertEquals("unavailable", queued.await())
            assertEquals(0L, gate.sessionEpoch)
        }

    @Test
    fun `cancelled provider observation reopens an initially open gate`() = runTest {
        val gate = AccountSessionOperationGate()
        val observationStarted = CompletableDeferred<Unit>()
        val finishObservation = CompletableDeferred<Unit>()
        val observation = async {
            gate.withSessionObservation { epoch ->
                observationStarted.complete(Unit)
                finishObservation.await()
                AccountSessionOperationGate.MutationResult(epoch, reopenAdmission = true)
            }
        }

        runCurrent()
        assertTrue(observationStarted.isCompleted)
        observation.cancel()
        runCurrent()

        assertTrue(observation.isCancelled)
        assertEquals(
            "available",
            gate.withManualOperation(waitForTurn = false, unavailable = "unavailable") {
                "available"
            },
        )
    }

    @Test
    fun `session mutation closes admission before queued manual work can run`() = runTest {
        val gate = AccountSessionOperationGate()
        val manualStarted = CompletableDeferred<Unit>()
        val releaseManual = CompletableDeferred<Unit>()
        var queuedManualRan = false
        var mutationRan = false

        val currentManual = async {
            gate.withManualOperation(waitForTurn = false, unavailable = "unavailable") {
                manualStarted.complete(Unit)
                releaseManual.await()
                "current"
            }
        }
        runCurrent()
        assertTrue(manualStarted.isCompleted)

        val queuedManual = async {
            gate.withManualOperation(waitForTurn = true, unavailable = "unavailable") {
                queuedManualRan = true
                "queued"
            }
        }
        runCurrent()
        val mutation = async {
            gate.withSessionMutation { _, epoch ->
                mutationRan = true
                AccountSessionOperationGate.MutationResult(epoch, reopenAdmission = false)
            }
        }
        runCurrent()

        assertEquals(
            "unavailable",
            gate.withManualOperation(waitForTurn = false, unavailable = "unavailable") { "late" },
        )
        releaseManual.complete(Unit)
        runCurrent()

        assertEquals("current", currentManual.await())
        assertEquals("unavailable", queuedManual.await())
        assertEquals(1L, mutation.await())
        assertFalse(queuedManualRan)
        assertTrue(mutationRan)
    }
}
