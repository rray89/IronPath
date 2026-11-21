package com.example.ironpath.data.ai

import com.example.ironpath.domain.planner.OnDeviceModelPrompt
import com.example.ironpath.domain.planner.PlanningTokenUsage
import com.example.ironpath.domain.planner.RemotePlanningRoute
import com.example.ironpath.domain.planner.RemotePlanningTransport
import com.example.ironpath.domain.planner.RemotePlanningTransportResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Singleton
class DeepSeekRemotePlanningTransport
@Inject
constructor(private val httpClient: RemoteHttpClient) : RemotePlanningTransport {
    override suspend fun generate(
        apiKey: String,
        prompt: OnDeviceModelPrompt,
        optionId: String
    ): RemotePlanningTransportResult =
        chatCompletion(
            httpClient,
            "https://api.deepseek.com/chat/completions",
            apiKey,
            buildJsonObject {
                put("model", RemotePlanningRoute.DEEPSEEK.model)
                put("stream", false)
                put("max_tokens", MAX_OUTPUT_TOKENS)
                put("thinking", buildJsonObject { put("type", "disabled") })
                put("response_format", buildJsonObject { put("type", "json_object") })
                // DeepSeek JSON mode requires an explicit JSON instruction and output structure.
                put(
                    "messages",
                    messages(
                        prompt,
                        "\nReturn only a JSON object matching this schema: ${RemotePlanJsonCodec.responseSchema()}"
                    )
                )
            }
        )
}

@Singleton
class OpenRouterRemotePlanningTransport
@Inject
constructor(private val httpClient: RemoteHttpClient) : RemotePlanningTransport {
    override suspend fun generate(
        apiKey: String,
        prompt: OnDeviceModelPrompt,
        optionId: String
    ): RemotePlanningTransportResult {
        val route = RemotePlanningRoute.fromId(optionId)
        if (
            route != RemotePlanningRoute.OPENROUTER_OPENAI &&
                route != RemotePlanningRoute.OPENROUTER_QWEN
        ) {
            return RemotePlanningTransportResult.ProviderFailure
        }
        return chatCompletion(
            httpClient,
            "https://openrouter.ai/api/v1/chat/completions",
            apiKey,
            buildJsonObject {
                put("model", route.model)
                put("stream", false)
                put("max_tokens", MAX_OUTPUT_TOKENS)
                put("messages", messages(prompt))
                // Explicitly override account defaults; an empty array does not disable them.
                put(
                    "plugins",
                    buildJsonArray {
                        listOf(
                                "web",
                                "response-healing",
                                "context-compression",
                                "file-parser",
                                "pareto-router"
                            )
                            .forEach { plugin ->
                                add(
                                    buildJsonObject {
                                        put("id", plugin)
                                        put("enabled", false)
                                    }
                                )
                            }
                    }
                )
                put(
                    "provider",
                    buildJsonObject {
                        put("only", buildJsonArray { add(JsonPrimitive(route.provider)) })
                        put("order", buildJsonArray { add(JsonPrimitive(route.provider)) })
                        put("allow_fallbacks", false)
                        put("require_parameters", true)
                    }
                )
                if (route == RemotePlanningRoute.OPENROUTER_QWEN) {
                    put("reasoning", buildJsonObject { put("enabled", false) })
                }
                put(
                    "response_format",
                    buildJsonObject {
                        put("type", "json_schema")
                        put(
                            "json_schema",
                            buildJsonObject {
                                put("name", "ironpath_plan")
                                put("strict", true)
                                put("schema", RemotePlanJsonCodec.responseSchema())
                            }
                        )
                    }
                )
            }
        )
    }
}

@Singleton
class RoutingRemotePlanningTransport
@Inject
constructor(
    private val gemini: GeminiRemotePlanningTransport,
    private val deepSeek: DeepSeekRemotePlanningTransport,
    private val openRouter: OpenRouterRemotePlanningTransport,
) : RemotePlanningTransport {
    override suspend fun generate(
        apiKey: String,
        prompt: OnDeviceModelPrompt,
        optionId: String
    ): RemotePlanningTransportResult =
        when (RemotePlanningRoute.fromId(optionId)) {
            RemotePlanningRoute.GEMINI -> gemini.generate(apiKey, prompt, optionId)
            RemotePlanningRoute.DEEPSEEK -> deepSeek.generate(apiKey, prompt, optionId)
            RemotePlanningRoute.OPENROUTER_OPENAI,
            RemotePlanningRoute.OPENROUTER_QWEN -> openRouter.generate(apiKey, prompt, optionId)
            null -> RemotePlanningTransportResult.ProviderFailure
        }
}

private suspend fun chatCompletion(
    httpClient: RemoteHttpClient,
    endpoint: String,
    apiKey: String,
    request: JsonObject
): RemotePlanningTransportResult =
    try {
        val response =
            httpClient.post(
                url = endpoint,
                headers =
                    mapOf(
                        "Content-Type" to "application/json",
                        "Authorization" to "Bearer $apiKey"
                    ),
                body = request.toString(),
            )
        if (response.statusCode !in 200..299) RemotePlanningTransportResult.ProviderFailure
        else parseChatCompletion(response.body)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        RemotePlanningTransportResult.ProviderFailure
    }

private fun parseChatCompletion(body: String): RemotePlanningTransportResult.Success {
    val response = Json.parseToJsonElement(body).jsonObject
    check(response["error"] == null || response["error"] == JsonNull)
    val choice = response.getValue("choices").jsonArray.single().jsonObject
    check(choice.getValue("finish_reason").jsonPrimitive.content == "stop")
    val message = choice.getValue("message").jsonObject
    check(message.getValue("role").jsonPrimitive.content == "assistant")
    check(message["refusal"] == null || message["refusal"] == JsonNull)
    check(
        message["tool_calls"] == null ||
            message["tool_calls"] == JsonNull ||
            message.getValue("tool_calls").jsonArray.isEmpty()
    )
    val content = message.getValue("content").jsonPrimitive
    check(content.isString && content.content.isNotBlank())
    return RemotePlanningTransportResult.Success(
        proposal = RemotePlanJsonCodec.parse(content.content),
        usage = parseTokenUsage(response["usage"] as? JsonObject),
    )
}

private fun messages(prompt: OnDeviceModelPrompt, systemSuffix: String = "") = buildJsonArray {
    add(
        buildJsonObject {
            put("role", "system")
            put("content", prompt.systemInstruction + systemSuffix)
        }
    )
    add(
        buildJsonObject {
            put("role", "user")
            put("content", prompt.userPrompt)
        }
    )
}

internal fun parseTokenUsage(
    usage: JsonObject?,
    inputKey: String = "prompt_tokens",
    outputKey: String = "completion_tokens"
): PlanningTokenUsage? {
    fun tokenCount(key: String): Long? {
        val number = usage?.get(key) as? JsonPrimitive ?: return null
        return number
            .takeUnless { it.isString }
            ?.longOrNull
            ?.takeIf { it in 0..MAX_REPORTED_TOKENS }
    }
    val input = tokenCount(inputKey)
    val output = tokenCount(outputKey)
    val total = tokenCount("total_tokens")
    return if (input == null && output == null && total == null) null
    else PlanningTokenUsage(input, output, total)
}

private const val MAX_OUTPUT_TOKENS = 4_096
private const val MAX_REPORTED_TOKENS = 10_000_000L
