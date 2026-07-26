package com.example.ironpath.data.ai

import com.example.ironpath.domain.planner.OnDeviceExerciseProposal
import com.example.ironpath.domain.planner.OnDeviceModelPrompt
import com.example.ironpath.domain.planner.OnDevicePlanProposal
import com.example.ironpath.domain.planner.OnDeviceWorkoutProposal
import com.example.ironpath.domain.planner.RemotePlanningTransport
import com.example.ironpath.domain.planner.RemotePlanningTransportResult
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RemoteHttpResponse(val statusCode: Int, val body: String)

interface RemoteHttpClient {
    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): RemoteHttpResponse
}

@Singleton
class UrlConnectionRemoteHttpClient @Inject constructor() : RemoteHttpClient {
    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): RemoteHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
                connection.readTimeout = NETWORK_TIMEOUT_MILLIS
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                headers.forEach(connection::setRequestProperty)
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                val responseBody =
                    if (status in 200..299) {
                        connection.inputStream.use(::readBoundedUtf8)
                    } else {
                        ""
                    }
                RemoteHttpResponse(statusCode = status, body = responseBody)
            } finally {
                connection.disconnect()
            }
        }

    private fun readBoundedUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (total <= MAX_RESPONSE_BYTES) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - total))
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        check(total <= MAX_RESPONSE_BYTES) { "Remote response exceeded the allowed size." }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val NETWORK_TIMEOUT_MILLIS = 60_000
        const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}

@Singleton
class GeminiRemotePlanningTransport @Inject constructor(private val httpClient: RemoteHttpClient) :
    RemotePlanningTransport {
    override suspend fun generate(
        apiKey: String,
        prompt: OnDeviceModelPrompt,
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
                GeminiInteractionsCodec.parseProposal(response.body)?.let {
                    RemotePlanningTransportResult.Success(it)
                } ?: RemotePlanningTransportResult.ProviderFailure
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
                put("system_instruction", prompt.systemInstruction)
                put("input", prompt.userPrompt)
                put(
                    "response_format",
                    buildJsonObject {
                        put("type", "text")
                        put("mime_type", "application/json")
                        put("schema", responseSchema())
                    },
                )
            }
            .toString()

    fun parseProposal(responseBody: String): OnDevicePlanProposal? =
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
                json.parseToJsonElement(outputText).jsonObject.toProposal()
            }
            .getOrNull()

    private fun responseSchema() = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("rationale", nullableStringSchema())
                put("warnings", arraySchema(stringSchema()))
                put("workouts", arraySchema(workoutSchema()))
            },
        )
        put("required", stringArray("rationale", "warnings", "workouts"))
        put("additionalProperties", false)
    }

    private fun workoutSchema() = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("dayOfWeek", integerSchema())
                put("title", stringSchema())
                put("exercises", arraySchema(exerciseSchema()))
            },
        )
        put("required", stringArray("dayOfWeek", "title", "exercises"))
        put("additionalProperties", false)
    }

    private fun exerciseSchema() = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("catalogId", stringSchema())
                put("sets", integerSchema())
                put("reps", integerSchema())
                put("targetWeightKg", numberSchema())
            },
        )
        put("required", stringArray("catalogId", "sets", "reps", "targetWeightKg"))
        put("additionalProperties", false)
    }

    private fun JsonObject.toProposal() =
        OnDevicePlanProposal(
            rationale = this["rationale"]?.jsonPrimitive?.contentOrNull,
            warnings = array("warnings").map { it.jsonPrimitive.content },
            workouts =
                array("workouts").map { workoutElement ->
                    val workout = workoutElement.jsonObject
                    OnDeviceWorkoutProposal(
                        dayOfWeek = workout.int("dayOfWeek"),
                        title = workout.string("title"),
                        exercises =
                            workout.array("exercises").map { exerciseElement ->
                                val exercise = exerciseElement.jsonObject
                                OnDeviceExerciseProposal(
                                    catalogId = exercise.string("catalogId"),
                                    sets = exercise.int("sets"),
                                    reps = exercise.int("reps"),
                                    targetWeightKg = exercise.double("targetWeightKg"),
                                )
                            },
                    )
                },
        )

    private fun stringSchema() = buildJsonObject { put("type", "string") }

    private fun nullableStringSchema() = buildJsonObject {
        put(
            "type",
            buildJsonArray {
                add(JsonPrimitive("string"))
                add(JsonPrimitive("null"))
            },
        )
    }

    private fun integerSchema() = buildJsonObject { put("type", "integer") }

    private fun numberSchema() = buildJsonObject { put("type", "number") }

    private fun arraySchema(items: JsonObject) = buildJsonObject {
        put("type", "array")
        put("items", items)
    }

    private fun stringArray(vararg values: String) = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.int(key: String): Int =
        checkNotNull(getValue(key).jsonPrimitive.intOrNull)

    private fun JsonObject.double(key: String): Double =
        checkNotNull(getValue(key).jsonPrimitive.doubleOrNull)

    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

    private const val MODEL = "gemini-3.5-flash"
}
