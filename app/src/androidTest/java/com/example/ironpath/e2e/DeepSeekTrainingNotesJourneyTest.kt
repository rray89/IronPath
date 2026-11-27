package com.example.ironpath.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.MainActivity
import com.example.ironpath.data.ai.RemoteHttpClient
import com.example.ironpath.data.ai.RemoteHttpResponse
import com.example.ironpath.di.DebugRemoteHttpModule
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.testing.TestTags
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@UninstallModules(DebugRemoteHttpModule::class)
@RunWith(AndroidJUnit4::class)
class DeepSeekTrainingNotesJourneyTest {
    @get:Rule(order = 0) val databaseRule = HiltTestDatabaseRule()
    @get:Rule(order = 1) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<MainActivity>()

    private val requests = CopyOnWriteArrayList<CapturedRequest>()

    @BindValue
    @JvmField
    val fakeHttp: RemoteHttpClient =
        object : RemoteHttpClient {
            override suspend fun post(
                url: String,
                headers: Map<String, String>,
                body: String,
            ): RemoteHttpResponse {
                val userPrompt =
                    Json.parseToJsonElement(body)
                        .jsonObject
                        .getValue("messages")
                        .jsonArray
                        .last()
                        .jsonObject
                        .getValue("content")
                        .jsonPrimitive
                        .content
                requests += CapturedRequest(url, userPrompt)
                return when (requests.size) {
                    1 -> RemoteHttpResponse(503, PRIVATE_MARKER)
                    3 -> completion(PRIVATE_MARKER)
                    else -> completion("A local test draft.")
                }
            }
        }

    @Test
    fun shareIsFreshAcrossRetryRegenerateEditAndProviderChange() {
        waitForTag(TestTags.ENTRY_GET_STARTED)
        clickTag(TestTags.ENTRY_GET_STARTED)
        waitForTag(TestTags.bottomNav(Route.PLAN))
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.PLAN)).performClick()
        waitForTag(TestTags.planDay(1))
        clickTag(TestTags.planDay(1))
        replaceText(TestTags.PLAN_INJURY_NOTES, "High impact $PRIVATE_MARKER")
        replaceText(TestTags.PLAN_PREFERENCES, "Push-ups $PRIVATE_MARKER")
        replaceText(TestTags.PLAN_DISLIKES, "Walking Lunges $PRIVATE_MARKER")
        selectProvider("DEEPSEEK")

        clickTag(TestTags.PLAN_GENERATE_AI)
        composeRule.onNodeWithText("Generate with DeepSeek").assertIsDisplayed()
        composeRule.onNodeWithTag(SHARE_NOTES).assertIsOff().performClick().assertIsOn()
        composeRule.onNodeWithText("Cancel").performClick()
        assertTrue(requests.isEmpty())

        clickTag(TestTags.PLAN_GENERATE_AI)
        composeRule.onNodeWithTag(SHARE_NOTES).assertIsOff().performClick()
        composeRule.onNodeWithText("Generate").performClick()
        composeRule
            .onNodeWithText("DeepSeek · Flash could not generate a plan")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(PRIVATE_MARKER).assertDoesNotExist()
        assertEquals(1, requests.size)
        assertSharedRequest(requests.single())

        clickTag(TestTags.PLAN_GENERATE_AI)
        composeRule.onNodeWithText("Retry with DeepSeek").assertIsDisplayed()
        composeRule.onNodeWithTag(SHARE_NOTES).assertIsOff()
        composeRule.onNodeWithText("Retry").performClick()
        waitForTag(TestTags.PLAN_AI_REVIEW)
        assertEquals(2, requests.size)
        assertFalse(requests[1].prompt.contains(PRIVATE_MARKER))
        composeRule.onNodeWithText("A local test draft.").performScrollTo().assertIsDisplayed()

        clickTag(TestTags.PLAN_AI_REGENERATE)
        composeRule.onNodeWithText("Regenerate with DeepSeek").assertIsDisplayed()
        composeRule.onNodeWithTag(SHARE_NOTES).assertIsOff().performClick()
        composeRule.onNodeWithText("Regenerate").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText("Exercise Preferences — Includes Push-ups (3 sets).")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("WHY THIS WEEK").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("Exercise Preferences — Includes Push-ups (3 sets).")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Exercise Dislikes — Omits Walking Lunges from the eligible options.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(PRIVATE_MARKER).assertDoesNotExist()
        assertEquals(3, requests.size)
        assertSharedRequest(requests[2])

        clickTag(TestTags.planAiExercise(1, "push-ups"))
        val editor = composeRule.onNodeWithTag(TestTags.PLAN_AI_EDITOR_LIST)
        editor.performScrollToNode(hasTestTag(TestTags.PLAN_AI_EDITOR_SETS))
        composeRule.onNodeWithTag(TestTags.PLAN_AI_EDITOR_SETS).performTextReplacement("4")
        editor.performScrollToNode(hasTestTag(TestTags.PLAN_AI_EDITOR_CONFIRM))
        composeRule.onNodeWithTag(TestTags.PLAN_AI_EDITOR_CONFIRM).performClick()
        composeRule
            .onNodeWithText("Exercise Preferences — Includes Push-ups (3 sets).")
            .assertDoesNotExist()
        composeRule
            .onNodeWithText(
                "You edited this draft. DeepSeek received the notes for the original generation."
            )
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("EDIT INPUTS").performScrollTo().performClick()
        waitForTag(TestTags.PLAN_GENERATE_AI)
        clickTag(TestTags.PLAN_GENERATE_AI)
        composeRule.onNodeWithTag(SHARE_NOTES).assertIsOff()
        composeRule.onNodeWithText("Cancel").performClick()
        selectProvider("OPENROUTER_OPENAI")
        clickTag(TestTags.PLAN_GENERATE_AI)
        waitForTag(TestTags.PLAN_AI_REVIEW)
        composeRule.onNodeWithTag(SHARE_NOTES).assertDoesNotExist()
        assertEquals(4, requests.size)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", requests.last().url)
        assertFalse(requests.last().prompt.contains(PRIVATE_MARKER))
    }

    private fun selectProvider(optionId: String) {
        clickTag("plan_remote_option_$optionId")
        clickTag(TestTags.PLAN_REMOTE_AI_TOGGLE)
        replaceText(TestTags.PLAN_REMOTE_AI_KEY, "synthetic-test-key")
    }

    private fun clickTag(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun replaceText(tag: String, text: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performTextReplacement(text)
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertSharedRequest(request: CapturedRequest) {
        assertEquals("https://api.deepseek.com/chat/completions", request.url)
        assertTrue(request.prompt.contains("Injury notes: High impact $PRIVATE_MARKER"))
        assertTrue(request.prompt.contains("Exercise preferences: Push-ups $PRIVATE_MARKER"))
        assertTrue(request.prompt.contains("Exercise dislikes: Walking Lunges $PRIVATE_MARKER"))
        assertFalse(request.prompt.contains("Recent workouts:"))
        assertFalse(request.prompt.contains("Recent records:"))
        assertFalse(request.prompt.contains("Recent exercise loads:"))
    }

    private fun completion(prose: String): RemoteHttpResponse {
        val plan =
            """{"rationale":"$prose","warnings":[],"workouts":[{"dayOfWeek":1,"title":"$prose","exercises":[{"catalogId":"push-ups","sets":3,"reps":8,"targetWeightKg":0}]}]}"""
        val message = buildJsonObject {
            put("role", "assistant")
            put("content", plan)
        }
        return RemoteHttpResponse(
            200,
            """{"choices":[{"finish_reason":"stop","message":$message}]}"""
        )
    }

    private data class CapturedRequest(val url: String, val prompt: String)

    private companion object {
        const val SHARE_NOTES = "plan_share_training_notes"
        const val PRIVATE_MARKER = "synthetic-private-marker"
    }
}
