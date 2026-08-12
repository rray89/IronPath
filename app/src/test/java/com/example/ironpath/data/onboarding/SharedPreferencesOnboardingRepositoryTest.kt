package com.example.ironpath.data.onboarding

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

class SharedPreferencesOnboardingRepositoryTest {

    @Test
    fun `read failure returns incomplete`() = runTest {
        val preferences = mockk<SharedPreferences>()
        every { preferences.getBoolean(any(), any()) } throws IllegalStateException("unreadable")

        val repository = repository(preferences)

        assertFalse(repository.isCompleted())
    }

    @Test
    fun `failed completion commit returns false`() = runTest {
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { preferences.edit() } returns editor
        every { editor.putBoolean(any(), true) } returns editor
        every { editor.commit() } returns false

        val repository = repository(preferences)

        assertFalse(repository.complete())
    }

    @Test
    fun `failed reset commit returns false`() = runTest {
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { preferences.edit() } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } returns false

        val repository = repository(preferences)

        assertFalse(repository.reset())
    }

    private fun repository(preferences: SharedPreferences): SharedPreferencesOnboardingRepository {
        val context = mockk<Context>()
        every { context.getSharedPreferences("ironpath_onboarding", Context.MODE_PRIVATE) } returns
            preferences
        return SharedPreferencesOnboardingRepository(context)
    }
}
