package com.example.ironpath.data.ai

import com.example.ironpath.domain.planner.OnDeviceModelPrompt
import com.example.ironpath.domain.planner.RemotePlanningTransportResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
    fun `request schema leaves bounds local while preserving the full structural contract`() =
        runTest {
            val httpClient =
                RecordingRemoteHttpClient(
                    response = RemoteHttpResponse(statusCode = 401, body = "ignored")
                )
            GeminiRemotePlanningTransport(httpClient)
                .generate(
                    apiKey = "test-key",
                    prompt = OnDeviceModelPrompt("system rules", "bounded planning summary"),
                )

            val requestBody = checkNotNull(httpClient.body)
            val request = Json.parseToJsonElement(requestBody).jsonObject
            assertEquals(
                4_096,
                request
                    .getValue("generation_config")
                    .jsonObject
                    .getValue("max_output_tokens")
                    .jsonPrimitive
                    .int,
            )
            val responseSchema =
                request.getValue("response_format").jsonObject.getValue("schema").jsonObject

            listOf("minimum", "maximum", "minItems", "maxItems").forEach { providerBound ->
                assertFalse(
                    "Provider schema must not include $providerBound",
                    responseSchema.toString().contains("\"$providerBound\""),
                )
            }
            assertClosedObject(responseSchema, "rationale", "warnings", "workouts")
            assertEquals(
                setOf("string", "null"),
                responseSchema
                    .property("rationale")
                    .getValue("type")
                    .jsonArray
                    .map { type -> type.jsonPrimitive.content }
                    .toSet(),
            )
            assertEquals("array", responseSchema.property("warnings").type())
            assertEquals("string", responseSchema.property("warnings").items().type())
            assertEquals("array", responseSchema.property("workouts").type())

            val workoutSchema = responseSchema.property("workouts").items()
            assertClosedObject(workoutSchema, "dayOfWeek", "title", "exercises")
            assertEquals("integer", workoutSchema.property("dayOfWeek").type())
            assertEquals("string", workoutSchema.property("title").type())
            assertEquals("array", workoutSchema.property("exercises").type())

            val exerciseSchema = workoutSchema.property("exercises").items()
            assertClosedObject(
                exerciseSchema,
                "catalogId",
                "sets",
                "reps",
                "targetWeightKg",
            )
            assertEquals("string", exerciseSchema.property("catalogId").type())
            assertEquals("integer", exerciseSchema.property("sets").type())
            assertEquals("integer", exerciseSchema.property("reps").type())
            assertEquals("number", exerciseSchema.property("targetWeightKg").type())
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

    @Test
    fun `provider collections outside local limits fail before proposal mapping`() = runTest {
        val exercise = """{"catalogId":"push-ups","sets":3,"reps":8,"targetWeightKg":0.0}"""
        val workout = """{"dayOfWeek":1,"title":"Full Body","exercises":[$exercise]}"""
        val sevenWorkouts =
            (1..7).joinToString(separator = ",") { day ->
                """{"dayOfWeek":$day,"title":"Day $day","exercises":[$exercise]}"""
            }
        val nineExercises = List(9) { exercise }.joinToString(separator = ",")
        val sixWarnings = List(6) { index -> "\"warning-$index\"" }.joinToString(separator = ",")
        val overLimitOutputs =
            listOf(
                """{"rationale":null,"warnings":[],"workouts":[]}""",
                """{"rationale":null,"warnings":[],"workouts":[$sevenWorkouts]}""",
                """{"rationale":null,"warnings":[],"workouts":[{"dayOfWeek":1,"title":"Empty","exercises":[]}]}""",
                """{"rationale":null,"warnings":[],"workouts":[{"dayOfWeek":1,"title":"Full Body","exercises":[$nineExercises]}]}""",
                """{"rationale":null,"warnings":[$sixWarnings],"workouts":[$workout]}""",
            )

        overLimitOutputs.forEachIndexed { index, output ->
            val transport =
                GeminiRemotePlanningTransport(
                    RecordingRemoteHttpClient(
                        RemoteHttpResponse(statusCode = 200, body = completedResponse(output))
                    )
                )

            val result =
                transport.generate(
                    apiKey = "test-key",
                    prompt = OnDeviceModelPrompt("system", "summary"),
                )

            assertEquals(
                "Over-limit fixture $index must be rejected",
                RemotePlanningTransportResult.ProviderFailure,
                result,
            )
        }
    }
}

private fun assertClosedObject(
    schema: kotlinx.serialization.json.JsonObject,
    vararg requiredProperties: String,
) {
    assertEquals("object", schema.type())
    assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean)
    assertEquals(
        requiredProperties.toSet(),
        schema
            .getValue("required")
            .jsonArray
            .map { requiredProperty -> requiredProperty.jsonPrimitive.content }
            .toSet(),
    )
}

private fun kotlinx.serialization.json.JsonObject.property(name: String) =
    getValue("properties").jsonObject.getValue(name).jsonObject

private fun kotlinx.serialization.json.JsonObject.items() = getValue("items").jsonObject

private fun kotlinx.serialization.json.JsonObject.type() = getValue("type").jsonPrimitive.content

private fun completedResponse(outputText: String) =
    buildJsonObject {
            put("status", "completed")
            put(
                "steps",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "model_output")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", outputText)
                                        }
                                    )
                                },
                            )
                        }
                    )
                },
            )
        }
        .toString()

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
