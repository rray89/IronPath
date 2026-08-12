package com.example.ironpath.data.onboarding

interface OnboardingRepository {
    suspend fun isCompleted(): Boolean

    suspend fun complete(): Boolean

    suspend fun reset(): Boolean
}
