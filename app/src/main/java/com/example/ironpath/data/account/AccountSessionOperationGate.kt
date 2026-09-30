package com.example.ironpath.data.account

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

/** Serializes account-session changes with debug manual backup operations. */
@Singleton
class AccountSessionOperationGate @Inject constructor() {
    private val mutex = Mutex()
    private val admissionLock = Any()

    @Volatile private var currentSessionEpoch = 0L
    @Volatile private var admissionClosed = false
    private var pendingSessionMutations = 0

    val sessionEpoch: Long
        get() = currentSessionEpoch

    suspend fun <T> withManualOperation(
        waitForTurn: Boolean,
        unavailable: T,
        block: suspend (sessionEpoch: Long) -> T,
    ): T {
        if (admissionClosed) return unavailable
        if (waitForTurn) mutex.lock() else if (!mutex.tryLock()) return unavailable
        return try {
            if (admissionClosed) unavailable else block(currentSessionEpoch)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Closes admission before waiting, so no queued manual operation can slip between the current
     * operation and the session transition. The caller chooses whether the gate reopens after its
     * persisted-state check.
     */
    suspend fun <T> withSessionMutation(
        block: suspend (previousEpoch: Long, mutationEpoch: Long) -> MutationResult<T>,
    ): T {
        val wasClosed =
            synchronized(admissionLock) {
                val initial = admissionClosed
                pendingSessionMutations++
                admissionClosed = true
                initial
            }
        var acquired = false
        var reopenAdmission = false
        try {
            mutex.lock()
            acquired = true
            val previousEpoch = currentSessionEpoch
            val mutationEpoch = Math.addExact(previousEpoch, 1)
            currentSessionEpoch = mutationEpoch
            val result = block(previousEpoch, mutationEpoch)
            reopenAdmission = result.reopenAdmission
            return result.value
        } finally {
            if (acquired) mutex.unlock()
            synchronized(admissionLock) {
                pendingSessionMutations--
                val canReopen = if (acquired) reopenAdmission else !wasClosed
                if (canReopen && pendingSessionMutations == 0) admissionClosed = false
            }
        }
    }

    /** Closes the gate for a recovered, durable sign-out that still has a stored session. */
    fun closeAdmission() {
        synchronized(admissionLock) { admissionClosed = true }
    }

    fun reopenAdmission() {
        synchronized(admissionLock) { if (pendingSessionMutations == 0) admissionClosed = false }
    }

    data class MutationResult<T>(val value: T, val reopenAdmission: Boolean)
}
