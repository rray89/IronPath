package com.example.ironpath.data.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Keeps best-effort provider cleanup from reversing a Firebase sign-out that already committed. */
internal suspend fun clearSessionWithProviderCleanup(
    signOutAndVerify: () -> Boolean,
    clearProviderState: suspend () -> Unit,
): Boolean {
    if (!signOutAndVerify()) return false

    withContext(NonCancellable) {
        try {
            clearProviderState()
        } catch (_: CancellationException) {
            // The local Firebase session is already cleared; provider cleanup is best effort.
        } catch (_: Exception) {
            // Provider cleanup cannot undo the committed local session change.
        }
    }
    return true
}
