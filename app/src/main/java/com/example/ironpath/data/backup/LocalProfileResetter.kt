package com.example.ironpath.data.backup

/** Reports whether the Room reset committed, independently of sentinel repair. */
sealed interface LocalProfileResetResult {
    data object NotCommitted : LocalProfileResetResult

    data class Committed(val installationMarkerUpdated: Boolean) : LocalProfileResetResult
}

interface LocalProfileResetter {
    suspend fun resetLocalProfile(pendingSignOutUid: String? = null): LocalProfileResetResult

    suspend fun clearPendingSignOut(uid: String): Boolean
}
