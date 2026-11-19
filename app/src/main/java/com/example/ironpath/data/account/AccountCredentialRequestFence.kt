package com.example.ironpath.data.account

/** Invalidates late chooser callbacks when an Activity detaches or its request is cancelled. */
internal class AccountCredentialRequestFence {
    private var activeRequestId: Long? = null
    private var sequence = 0L

    fun begin(requestId: Long): Boolean {
        if (activeRequestId != null) return false
        activeRequestId = requestId
        sequence++
        return true
    }

    fun beginAttempt(requestId: Long): Attempt? {
        if (activeRequestId != requestId) return null
        return Attempt(requestId, ++sequence)
    }

    fun detach(requestId: Long): Boolean = invalidate(requestId)

    fun replaceHost(requestId: Long): Boolean = invalidate(requestId)

    fun cancel(requestId: Long): Boolean = invalidate(requestId)

    fun isCurrent(attempt: Attempt): Boolean =
        activeRequestId == attempt.requestId && sequence == attempt.sequence

    fun complete(attempt: Attempt): Boolean {
        if (!isCurrent(attempt)) return false
        activeRequestId = null
        sequence++
        return true
    }

    private fun invalidate(requestId: Long): Boolean {
        if (activeRequestId != requestId) return false
        activeRequestId = null
        sequence++
        return true
    }

    data class Attempt(val requestId: Long, val sequence: Long)
}
