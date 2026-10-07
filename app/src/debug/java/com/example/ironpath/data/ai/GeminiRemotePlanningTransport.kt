package com.example.ironpath.data.ai

import com.example.ironpath.domain.planner.OnDeviceModelPrompt
import com.example.ironpath.domain.planner.RemotePlanningTransport
import com.example.ironpath.domain.planner.RemotePlanningTransportResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Singleton
class GeminiRemotePlanningTransport @Inject constructor(private val httpClient: RemoteHttpClient) :
    RemotePlanningTransport {
    override suspend fun generate(
        apiKey: String,
        prompt: OnDeviceModelPrompt,
        optionId: String,
    ): RemotePlanningTransportResult =
        try {
            val response =
                httpClient.post(
                    url = ENDPOINT,
                    headers =
                        mapOf(
                            "Content-Type" to "application/json",
                            "x-goog-api-key" to apiKey,
                        ),
                    body = GeminiInteractionsCodec.requestBody(prompt),
                )
            if (response.statusCode !in 200..299) {
                RemotePlanningTransportResult.ProviderFailure
            } else {
                GeminiInteractionsCodec.parseResponse(response.body)
                    ?: RemotePlanningTransportResult.ProviderFailure
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            RemotePlanningTransportResult.ProviderFailure
        }

    private companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1/interactions"
    }
}

internal object GeminiInteractionsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun requestBody(prompt: OnDeviceModelPrompt): String =
        buildJsonObject {
                put("model", MODEL)
                put("store", false)
                put("system_instruction", prompt.systemInstruction)
                put("input", prompt.userPrompt)
                put(
                    "generation_config",
                    buildJsonObject { put("max_output_tokens", MAX_OUTPUT_TOKENS) },
                )
                put(
                    "response_format",
                    buildJsonObject {
                        put("type", "text")
                        put("mime_type", "application/json")
                        put("schema", RemotePlanJsonCodec.responseSchema())
                    },
                )
            }
            .toString()

    fun parseResponse(responseBody: String): RemotePlanningTransportResult.Success? =
        runCatching {
                val response = json.parseToJsonElement(responseBody).jsonObject
                check(response.string("status") == "completed")
                val outputText =
                    response
                        .array("steps")
                        .map { it.jsonObject }
                        .last { it.string("type") == "model_output" }
                        .array("content")
                        .map { it.jsonObject }
                        .last { it.string("type") == "text" }
                        .string("text")
                RemotePlanningTransportResult.Success(
                    proposal = RemotePlanJsonCodec.parse(outputText),
                    usage =
                        parseTokenUsage(
                            response["usage"] as? JsonObject,
                            "total_input_tokens",
                            "total_output_tokens"
                        ),
                )
            }
            .getOrNull()

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

    private const val MODEL = "gemini-3.5-flash"
    private const val MAX_OUTPUT_TOKENS = 4_096
}
