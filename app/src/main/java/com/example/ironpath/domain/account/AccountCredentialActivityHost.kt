package com.example.ironpath.domain.account

import androidx.activity.ComponentActivity
import javax.inject.Inject
import javax.inject.Singleton

/** Narrow Activity lifecycle seam for identity providers that require a live UI host. */
interface AccountCredentialActivityHost {
    fun attach(activity: ComponentActivity)

    fun detach(activity: ComponentActivity)
}

@Singleton
class NoOpAccountCredentialActivityHost @Inject constructor() : AccountCredentialActivityHost {
    override fun attach(activity: ComponentActivity) = Unit

    override fun detach(activity: ComponentActivity) = Unit
}
