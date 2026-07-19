package com.example.ironpath.domain.identity

import java.util.UUID
import javax.inject.Inject

fun interface IdProvider {
    fun newId(): String
}

class UuidIdProvider @Inject constructor() : IdProvider {
    override fun newId(): String = UUID.randomUUID().toString()
}
