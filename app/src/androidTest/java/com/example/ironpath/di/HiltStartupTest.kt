package com.example.ironpath.di

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.MainActivity
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HiltStartupTest {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var timeProvider: TimeProvider

    @Inject lateinit var idProvider: IdProvider

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun entryToHome_resolvesHiltGraphAndRendersMainNavigation() {
        composeRule.onNodeWithText("GET STARTED").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("HOME").assertIsDisplayed()
    }

    @Test
    fun productionGraph_resolvesDeterministicBoundaries() {
        assertTrue(::timeProvider.isInitialized)
        assertTrue(::idProvider.isInitialized)
    }
}
