package com.example.ironpath.testutil

import com.example.ironpath.domain.identity.IdProvider

class FakeIdProvider(private var next: Int = 1) : IdProvider {
    override fun newId(): String = "test-id-${next++}"
}
