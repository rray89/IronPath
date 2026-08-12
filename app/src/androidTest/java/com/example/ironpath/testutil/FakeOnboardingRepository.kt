package com.example.ironpath.testutil

import com.example.ironpath.data.onboarding.OnboardingRepository

class FakeOnboardingRepository : OnboardingRepository {
    var completed = false
    var completionCount = 0
        private set

    var resetCount = 0
        private set

    override suspend fun isCompleted(): Boolean = completed

    override suspend fun complete(): Boolean {
        completionCount++
        completed = true
        return true
    }

    override suspend fun reset(): Boolean {
        resetCount++
        completed = false
        return true
    }
}
