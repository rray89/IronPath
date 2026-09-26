package com.example.ironpath.data.account

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
