package com.example.ironpath.data.ai

import com.example.ironpath.domain.planner.OnDeviceModelPrompt
import com.example.ironpath.domain.planner.PlanningTokenUsage
import com.example.ironpath.domain.planner.RemotePlanningRoute
import com.example.ironpath.domain.planner.RemotePlanningTransportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionRemotePlanningTransportTest {
    private val prompt = OnDeviceModelPrompt("system rules", "bounded planning summary")

    @Test
    fun `DeepSeek uses exact endpoint model disabled thinking and JSON object contract`() =
        runTest {
            val http = FixtureRemoteHttpClient()
            val result = DeepSeekRemotePlanningTransport(http).generate("secret-test-key", prompt)
            assertEquals(RemotePlanningTransportResult.ProviderFailure, result)
            assertEquals("https://api.deepseek.com/chat/completions", http.url)
            assertEquals("Bearer secret-test-key", http.headers["Authorization"])
            val body = http.request()
            assertEquals("deepseek-flash", body.string("model"))
            assertEquals("disabled", body.getValue("thinking").jsonObject.string("type"))
            assertEquals("json_object", body.getValue("response_format").jsonObject.string("type"))
            assertEquals(4096, body.getValue("max_tokens").jsonPrimitive.int)
            assertFalse(body.getValue("stream").jsonPrimitive.boolean)
            val system = body.getValue("messages").jsonArray.first().jsonObject.string("content")
            assertTrue(system.startsWith(prompt.systemInstruction))
            assertTrue(system.contains("JSON"))
            assertTrue(system.contains("targetWeightKg"))
            assertFalse(http.body.orEmpty().contains("secret-test-key"))
            assertFalse(http.url.orEmpty().contains("secret-test-key"))
        }

    @Test
    fun `OpenRouter pins exact model provider and required strict schema without fallback`() =
        runTest {
            listOf(RemotePlanningRoute.OPENROUTER_OPENAI, RemotePlanningRoute.OPENROUTER_QWEN)
                .forEach { route ->
                    val http = FixtureRemoteHttpClient()
                    OpenRouterRemotePlanningTransport(http)
                        .generate("secret-test-key", prompt, route.name)
                    assertEquals("https://openrouter.ai/api/v1/chat/completions", http.url)
                    assertEquals("Bearer secret-test-key", http.headers["Authorization"])
                    val body = http.request()
                    assertEquals(route.model, body.string("model"))
                    assertEquals(4096, body.getValue("max_tokens").jsonPrimitive.int)
                    assertFalse(body.getValue("stream").jsonPrimitive.boolean)
                    val provider = body.getValue("provider").jsonObject
                    assertEquals(
                        listOf(route.provider),
                        provider.getValue("only").jsonArray.map { it.jsonPrimitive.content }
                    )
                    assertEquals(
                        listOf(route.provider),
                        provider.getValue("order").jsonArray.map { it.jsonPrimitive.content }
                    )
                    assertFalse(provider.getValue("allow_fallbacks").jsonPrimitive.boolean)
                    assertTrue(provider.getValue("require_parameters").jsonPrimitive.boolean)
                    val format = body.getValue("response_format").jsonObject
                    assertEquals("json_schema", format.string("type"))
                    val schema = format.getValue("json_schema").jsonObject
                    assertTrue(schema.getValue("strict").jsonPrimitive.boolean)
                    assertEquals("ironpath_plan", schema.string("name"))
                    assertFalse(
                        schema
                            .getValue("schema")
                            .jsonObject
                            .getValue("additionalProperties")
                            .jsonPrimitive
                            .boolean
                    )
                    if (route == RemotePlanningRoute.OPENROUTER_QWEN) {
                        assertFalse(
                            body
                                .getValue("reasoning")
                                .jsonObject
                                .getValue("enabled")
                                .jsonPrimitive
                                .boolean
                        )
                    } else {
                        assertFalse(body.containsKey("reasoning"))
                    }
                    listOf("models", "tools").forEach { assertFalse(body.containsKey(it)) }
                    val plugins = body.getValue("plugins").jsonArray.map { it.jsonObject }
                    assertEquals(
                        setOf(
                            "web",
                            "response-healing",
                            "context-compression",
                            "file-parser",
                            "pareto-router"
                        ),
                        plugins.map { it.string("id") }.toSet()
                    )
                    assertTrue(plugins.all { !it.getValue("enabled").jsonPrimitive.boolean })
                    assertFalse(http.body.orEmpty().contains("secret-test-key"))
                }
        }

    @Test
    fun `complete assistant output maps the same proposal for both providers`() = runTest {
        val response = chatResponse(validOutput)
        val deepSeek =
            DeepSeekRemotePlanningTransport(
                FixtureRemoteHttpClient(RemoteHttpResponse(200, response))
            )
        val router =
            OpenRouterRemotePlanningTransport(
                FixtureRemoteHttpClient(RemoteHttpResponse(200, response))
            )
        listOf(
                deepSeek.generate("key", prompt),
                router.generate("key", prompt, RemotePlanningRoute.OPENROUTER_QWEN.name)
            )
            .forEach { result ->
                assertTrue(result is RemotePlanningTransportResult.Success)
                result as RemotePlanningTransportResult.Success
                assertEquals("Steady week", result.proposal.rationale)
                assertEquals(
                    "push-ups",
                    result.proposal.workouts.single().exercises.single().catalogId
                )
            }
    }

    @Test
    fun `truncated refused malformed empty and structurally wrong output is rejected once`() =
        runTest {
            val responses =
                listOf(
                    RemoteHttpResponse(401, "secret upstream body"),
                    RemoteHttpResponse(429, "secret upstream body"),
                    RemoteHttpResponse(503, "secret upstream body"),
                    RemoteHttpResponse(200, "not-json"),
                    RemoteHttpResponse(200, "{}"),
                    RemoteHttpResponse(200, chatResponse(validOutput, finish = "length")),
                    RemoteHttpResponse(200, chatResponse(validOutput, finish = "content_filter")),
                    RemoteHttpResponse(200, chatResponse(validOutput, refusal = "refused")),
                    RemoteHttpResponse(200, chatResponse("")),
                    RemoteHttpResponse(200, chatResponse("{}")),
                    RemoteHttpResponse(200, chatResponse("```json\n$validOutput\n```")),
                    RemoteHttpResponse(
                        200,
                        chatResponse(validOutput.replace("\"warnings\":[]", "\"warnings\":[1]"))
                    ),
                    RemoteHttpResponse(
                        200,
                        chatResponse(validOutput.replace("\"sets\":3", "\"sets\":\"3\""))
                    ),
                    RemoteHttpResponse(
                        200,
                        "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":null}}]}"
                    ),
                )
            responses.forEach { response ->
                listOf(false, true).forEach { openRouter ->
                    val http = FixtureRemoteHttpClient(response)
                    val result =
                        if (openRouter)
                            OpenRouterRemotePlanningTransport(http)
                                .generate("key", prompt, RemotePlanningRoute.OPENROUTER_OPENAI.name)
                        else DeepSeekRemotePlanningTransport(http).generate("key", prompt)
                    assertEquals(RemotePlanningTransportResult.ProviderFailure, result)
                    assertEquals(1, http.calls)
                    assertFalse(result.toString().contains("secret"))
                }
            }
        }

    @Test
    fun `HTTP errors sanitize exception details while cancellation propagates`() = runTest {
        val failure =
            object : RemoteHttpClient {
                override suspend fun post(
                    url: String,
                    headers: Map<String, String>,
                    body: String
                ): RemoteHttpResponse = error("secret detail")
            }
        assertEquals(
            RemotePlanningTransportResult.ProviderFailure,
            DeepSeekRemotePlanningTransport(failure).generate("key", prompt)
        )
        val cancelled =
            object : RemoteHttpClient {
                override suspend fun post(
                    url: String,
                    headers: Map<String, String>,
                    body: String
                ): RemoteHttpResponse = throw CancellationException("cancel")
            }
        try {
            DeepSeekRemotePlanningTransport(cancelled).generate("key", prompt)
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    @Test
    fun `usage accepts only bounded numeric counters without inventing missing counts`() = runTest {
        val fixtures =
            listOf(
                "{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}" to
                    PlanningTokenUsage(10, 20, 30),
                "{\"prompt_tokens\":10}" to PlanningTokenUsage(inputTokens = 10),
                "{\"prompt_tokens\":-1,\"completion_tokens\":\"20\",\"total_tokens\":999999999999999999}" to
                    null,
                "{\"prompt_tokens\":null,\"completion_tokens\":{}}" to null,
            )
        fixtures.forEach { (usage, expected) ->
            val envelope = Json.parseToJsonElement(chatResponse(validOutput)).jsonObject
            val response =
                JsonObject(envelope + ("usage" to Json.parseToJsonElement(usage))).toString()
            val result =
                DeepSeekRemotePlanningTransport(
                        FixtureRemoteHttpClient(RemoteHttpResponse(200, response))
                    )
                    .generate("key", prompt)
            assertTrue(result is RemotePlanningTransportResult.Success)
            assertEquals(expected, (result as RemotePlanningTransportResult.Success).usage)
        }
    }

    @Test
    fun `routing dispatches exactly one selected route and unknown route never sends a request`() =
        runTest {
            val http = FixtureRemoteHttpClient()
            val routing =
                RoutingRemotePlanningTransport(
                    GeminiRemotePlanningTransport(http),
                    DeepSeekRemotePlanningTransport(http),
                    OpenRouterRemotePlanningTransport(http)
                )
            RemotePlanningRoute.entries.forEach { route ->
                routing.generate("key", prompt, route.name)
                val body = http.request()
                assertEquals(route.model, body.string("model"))
            }
            assertEquals(4, http.calls)
            assertEquals(
                RemotePlanningTransportResult.ProviderFailure,
                routing.generate("key", prompt, "UNKNOWN")
            )
            assertEquals(4, http.calls)
        }

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
}

private class FixtureRemoteHttpClient(
    private val response: RemoteHttpResponse = RemoteHttpResponse(401, "ignored")
) : RemoteHttpClient {
    var calls = 0
    var url: String? = null
    var headers: Map<String, String> = emptyMap()
    var body: String? = null

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String
    ): RemoteHttpResponse {
        calls++
        this.url = url
        this.headers = headers
        this.body = body
        return response
    }

    fun request() = Json.parseToJsonElement(checkNotNull(body)).jsonObject
}

private const val validOutput =
    """{"rationale":"Steady week","warnings":[],"workouts":[{"dayOfWeek":1,"title":"Full Body","exercises":[{"catalogId":"push-ups","sets":3,"reps":8,"targetWeightKg":0.0}]}]}"""

private fun chatResponse(content: String, finish: String = "stop", refusal: String? = null) =
    buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("finish_reason", finish)
                            put(
                                "message",
                                buildJsonObject {
                                    put("role", "assistant")
                                    put("content", content)
                                    refusal?.let { put("refusal", it) }
                                }
                            )
                        }
                    )
                }
            )
        }
        .toString()
