package com.example.ironpath.data.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesOnboardingRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearPreferences() {
        check(
            context
                .getSharedPreferences("ironpath_onboarding", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        )
    }

    @Test
    fun freshStoreIsIncomplete() = runBlocking {
        assertFalse(SharedPreferencesOnboardingRepository(context).isCompleted())
    }

    @Test
    fun completionSurvivesARepositoryRecreation() = runBlocking {
        assertTrue(SharedPreferencesOnboardingRepository(context).complete())

        assertTrue(SharedPreferencesOnboardingRepository(context).isCompleted())
    }

    @Test
    fun resetSurvivesARepositoryRecreation() = runBlocking {
        val repository = SharedPreferencesOnboardingRepository(context)
        assertTrue(repository.complete())

        assertTrue(repository.reset())
        assertFalse(SharedPreferencesOnboardingRepository(context).isCompleted())
    }
}
