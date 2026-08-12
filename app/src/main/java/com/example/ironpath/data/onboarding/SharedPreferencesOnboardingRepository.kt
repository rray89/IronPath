package com.example.ironpath.data.onboarding

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SharedPreferencesOnboardingRepository
@Inject
constructor(@param:ApplicationContext private val context: Context) : OnboardingRepository {
    override suspend fun isCompleted(): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { preferences().getBoolean(KEY_ONBOARDING_COMPLETED, false) }
                .getOrDefault(false)
        }

    @SuppressLint("UseKtx") // SharedPreferences.edit(commit = true) does not expose commit failure.
    override suspend fun complete(): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { preferences().edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).commit() }
                .getOrDefault(false)
        }

    @SuppressLint("UseKtx") // Reset must also report a failed disk commit to DevTools.
    override suspend fun reset(): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { preferences().edit().remove(KEY_ONBOARDING_COMPLETED).commit() }
                .getOrDefault(false)
        }

    private fun preferences() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFERENCES_NAME = "ironpath_onboarding"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }
}
