package com.example.ironpath.testutil

import com.example.ironpath.domain.identity.IdProvider
import java.util.concurrent.atomic.AtomicInteger

class SequenceIdProvider(private val prefix: String) : IdProvider {
    private val next = AtomicInteger(1)

    override fun newId(): String = "$prefix-${next.getAndIncrement()}"

    fun reset() {
        next.set(1)
    }
}
