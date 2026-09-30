package com.example.ironpath.testutil

import com.example.ironpath.data.account.RoomAccountContextReader
import com.example.ironpath.domain.account.AccountContextReader
import com.example.ironpath.domain.account.LocalAccountContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Singleton
class TestAccountContextReader
@Inject
constructor(private val delegate: RoomAccountContextReader) : AccountContextReader {
    @Volatile var failChanges = false

    override val changes: Flow<Unit>
        get() =
            if (failChanges) {
                flow { error("Account context change stream is unavailable") }
            } else {
                delegate.changes
            }

    override suspend fun read(): LocalAccountContext = delegate.read()
}
