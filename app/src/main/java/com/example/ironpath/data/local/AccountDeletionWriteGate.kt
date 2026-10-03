package com.example.ironpath.data.local

import androidx.room.withTransaction
import com.example.ironpath.domain.account.AccountDeletionStage

class AccountDeletionInProgressException : IllegalStateException("Account deletion is in progress")

class StaleProfileGenerationException : IllegalStateException("Profile generation has changed")

/** Call only inside a Room transaction so the deletion barrier and write are serialized. */
suspend fun IronPathDatabase.requireWritesAllowed(expectedProfileGeneration: Long? = null) {
    val journal = accountDeletionDao().getJournal()
    if (
        journal != null &&
            (journal.serviceBinding != null ||
                journal.stage !in
                    setOf(AccountDeletionStage.COMPLETE.name, AccountDeletionStage.CANCELLED.name))
    ) {
        // A service terminal still awaits exact acknowledgment after stabilization. Only
        // historical null-bound demo terminals retain their existing write policy.
        throw AccountDeletionInProgressException()
    }
    if (expectedProfileGeneration != null) {
        val actualGeneration = backupDao().getMetadata()?.profileGeneration
        if (actualGeneration != expectedProfileGeneration) {
            throw StaleProfileGenerationException()
        }
    }
}

/** Adds the persistent deletion barrier to a local write transaction. */
suspend fun <T> IronPathDatabase.withProfileWrite(
    expectedProfileGeneration: Long? = null,
    block: suspend () -> T,
): T = withTransaction {
    requireWritesAllowed(expectedProfileGeneration)
    block()
}
