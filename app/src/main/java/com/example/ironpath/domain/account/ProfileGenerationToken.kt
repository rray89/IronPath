package com.example.ironpath.domain.account

import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Captures the local profile generation when a screen starts issuing profile mutations. A token
 * belongs to one ViewModel instance, so delayed work from an old screen cannot write into a profile
 * created after account deletion or another destructive profile reset.
 */
class ProfileGenerationToken @Inject constructor(private val contextReader: AccountContextReader) {
    private val mutex = Mutex()
    @Volatile private var capturedGeneration: Long? = null

    suspend fun initialize(): Long =
        mutex.withLock {
            capturedGeneration
                ?: contextReader.read().profileGeneration.also { capturedGeneration = it }
        }

    fun current(): Long? = capturedGeneration
}
