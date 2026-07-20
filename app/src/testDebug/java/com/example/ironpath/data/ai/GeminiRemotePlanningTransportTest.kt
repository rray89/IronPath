package com.example.ironpath.data.ai

import com.example.ironpath.domain.planner.OnDeviceModelPrompt
import com.example.ironpath.domain.planner.RemotePlanningTransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiRemotePlanningTransportTest {
    @Test
    fun `request uses structured output and keeps key out of url and body`() = runTest {
        val httpClient =
            RecordingRemoteHttpClient(
                response = RemoteHttpResponse(statusCode = 401, body = "ignored server detail")
            )
        val transport = GeminiRemotePlanningTransport(httpClient)

        val result =
            transport.generate(
                apiKey = "secret-test-key",
                prompt = OnDeviceModelPrompt("system rules", "bounded planning summary"),
            )

        assertEquals(RemotePlanningTransportResult.ProviderFailure, result)
        assertEquals("secret-test-key", httpClient.headers["x-goog-api-key"])
        assertFalse(httpClient.url.orEmpty().contains("secret-test-key"))
        assertFalse(httpClient.body.orEmpty().contains("secret-test-key"))
        assertTrue(httpClient.body.orEmpty().contains("gemini-3.5-flash"))
        assertTrue(httpClient.body.orEmpty().contains("response_format"))
        assertTrue(httpClient.body.orEmpty().contains("application/json"))
    }

    @Test
    fun `completed structured response maps into an owned proposal`() = runTest {
        val responseBody =
            """
            {
              "status": "completed",
              "steps": [{
                "type": "model_output",
                "content": [{
                  "type": "text",
                  "text": "{\"rationale\":\"Steady week\",\"warnings\":[],\"workouts\":[{\"dayOfWeek\":1,\"title\":\"Full Body\",\"exercises\":[{\"catalogId\":\"push-ups\",\"sets\":3,\"reps\":8,\"targetWeightKg\":0.0}]}]}"
                }]
              }]
            }
            """
                .trimIndent()
        val transport =
            GeminiRemotePlanningTransport(
                RecordingRemoteHttpClient(RemoteHttpResponse(200, responseBody))
            )

        val result =
            transport.generate(
                apiKey = "test-key",
                prompt = OnDeviceModelPrompt("system", "summary"),
            )

        assertTrue(result is RemotePlanningTransportResult.Success)
        result as RemotePlanningTransportResult.Success
        assertEquals("Steady week", result.proposal.rationale)
        assertEquals("push-ups", result.proposal.workouts.single().exercises.single().catalogId)
    }

    @Test
    fun `malformed or incomplete response fails without server body leakage`() = runTest {
        listOf(
                RemoteHttpResponse(200, "not-json"),
                RemoteHttpResponse(200, "{\"status\":\"failed\",\"steps\":[]}"),
                RemoteHttpResponse(500, "secret upstream response"),
            )
            .forEach { response ->
                val result =
                    GeminiRemotePlanningTransport(RecordingRemoteHttpClient(response))
                        .generate("test-key", OnDeviceModelPrompt("system", "summary"))

                assertEquals(RemotePlanningTransportResult.ProviderFailure, result)
                assertFalse(result.toString().contains(response.body))
            }
    }
}

private class RecordingRemoteHttpClient(private val response: RemoteHttpResponse) :
    RemoteHttpClient {
    var url: String? = null
    var headers: Map<String, String> = emptyMap()
    var body: String? = null

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): RemoteHttpResponse {
        this.url = url
        this.headers = headers
        this.body = body
        return response
    }
}
