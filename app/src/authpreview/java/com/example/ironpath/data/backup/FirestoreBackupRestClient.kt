package com.example.ironpath.data.backup

import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.backup.BackupFailureReason
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

fun interface FirestoreBackupClientFactory {
    suspend fun forAccount(account: AccountId): FirestoreBackupClient
}

interface FirestoreBackupClient {
    suspend fun get(path: String): JsonObject?

    suspend fun transaction(block: suspend FirestoreBackupTransaction.() -> Unit)
}

interface FirestoreBackupTransaction {
    suspend fun get(path: String): JsonObject?

    fun put(path: String, fields: JsonObject, serverTimes: Set<String> = emptySet())

    fun delete(path: String)
}

/** No persistence, write queue, listener, background retry, or credential-bearing URL. */
class FirestoreBackupRestClient(
    projectId: String,
    private val token: suspend () -> String,
    private val authorized: () -> Boolean,
    private val endpoint: String = "https://firestore.googleapis.com/v1",
    private val rollbackAuthorized: () -> Boolean = authorized,
) : FirestoreBackupClient {
    private val database = "projects/$projectId/databases/(default)"
    private val documents = "$database/documents"

    init {
        require(projectId.matches(Regex("[a-zA-Z0-9-]+")))
    }

    override suspend fun get(path: String): JsonObject? = read(path, null)

    private suspend fun read(path: String, transaction: String?): JsonObject? {
        validatePath(path)
        val document =
            if (transaction == null) {
                request("GET", "$documents/$path", allowMissing = true)?.jsonObject ?: return null
            } else {
                // batchGet keeps the transaction bytes in the JSON body. It uses the same official
                // transaction snapshot and avoids the emulator's broken bytes-valued query decoder.
                val result =
                    request(
                            "POST",
                            "$documents:batchGet",
                            buildJsonObject {
                                put(
                                    "documents",
                                    JsonArray(listOf(JsonPrimitive("$documents/$path")))
                                )
                                put("transaction", transaction)
                            }
                        )!!
                        .jsonArray
                        .single()
                        .jsonObject
                if ("missing" in result) {
                    require(result.getValue("missing").jsonPrimitive.content == "$documents/$path")
                    return null
                }
                result.getValue("found").jsonObject.also {
                    require(it.getValue("name").jsonPrimitive.content == "$documents/$path")
                }
            }
        return decodeFields(document["fields"]?.jsonObject ?: buildJsonObject {})
    }

    override suspend fun transaction(block: suspend FirestoreBackupTransaction.() -> Unit) {
        // Retry only ABORTED, which proves this transaction did not commit. A timeout/unknown
        // receipt is never retried here; a later explicit operation reads the durable registry.
        repeat(3) { attempt ->
            val id =
                request("POST", "$documents:beginTransaction", buildJsonObject {})!!
                    .jsonObject
                    .getValue("transaction")
                    .jsonPrimitive
                    .content
            val writes = mutableListOf<JsonObject>()
            val transaction =
                object : FirestoreBackupTransaction {
                    override suspend fun get(path: String) = read(path, id)

                    override fun put(path: String, fields: JsonObject, serverTimes: Set<String>) {
                        validatePath(path)
                        writes += buildJsonObject {
                            put(
                                "update",
                                buildJsonObject {
                                    put("name", "$documents/$path")
                                    put("fields", encodeFields(fields))
                                }
                            )
                            if (serverTimes.isNotEmpty())
                                put(
                                    "updateTransforms",
                                    JsonArray(
                                        serverTimes.sorted().map { field ->
                                            buildJsonObject {
                                                put("fieldPath", field)
                                                put("setToServerValue", "REQUEST_TIME")
                                            }
                                        }
                                    )
                                )
                        }
                    }

                    override fun delete(path: String) {
                        validatePath(path)
                        writes += buildJsonObject { put("delete", "$documents/$path") }
                    }
                }
            var commitAttempted = false
            try {
                transaction.block()
                currentCoroutineContext().ensureActive()
                commitAttempted = true
                request(
                    "POST",
                    "$documents:commit",
                    buildJsonObject {
                        put("transaction", id)
                        put("writes", JsonArray(writes))
                    }
                )
                return
            } catch (error: FirestoreRestAborted) {
                if (attempt == 2)
                    throw CloudBackupFailure(BackupFailureReason.ConcurrentRemoteChange)
            } catch (error: Exception) {
                if (!commitAttempted) rollbackBeforeCommit(id)
                throw error
            }
            // No automatic retry is used for any transport, permission, quota, or auth failure.
        }
    }

    private suspend fun rollbackBeforeCommit(transaction: String) =
        withContext(NonCancellable) {
            // Release Standard transaction read locks on callback failure or cancellation. This
            // cleanup never follows an attempted commit and cannot establish its remote outcome.
            // The original UID/epoch remains mandatory even when the operation Job is cancelled.
            withTimeoutOrNull(2_000) {
                try {
                    request(
                        "POST",
                        "$documents:rollback",
                        buildJsonObject { put("transaction", transaction) },
                        authorization = rollbackAuthorized,
                        timeoutMillis = 2_000,
                    )
                } catch (_: Exception) {
                    // Best effort only: never replace the original typed failure/cancellation.
                }
            }
        }

    private suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        allowMissing: Boolean = false,
        authorization: () -> Boolean = authorized,
        timeoutMillis: Int = 20_000,
    ): JsonElement? {
        currentCoroutineContext().ensureActive()
        if (!authorization()) throw CloudBackupFailure(BackupFailureReason.ReauthenticationRequired)
        val bearer = token()
        if (!authorization()) throw CloudBackupFailure(BackupFailureReason.ReauthenticationRequired)
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val connection = URI("$endpoint/$path").toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = minOf(10_000, timeoutMillis)
                connection.readTimeout = timeoutMillis
                connection.instanceFollowRedirects = false
                if (bearer.isNotBlank())
                    connection.setRequestProperty("Authorization", "Bearer $bearer")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (body != null) {
                    connection.doOutput = true
                    val payload = body.toString().toByteArray(Charsets.UTF_8)
                    // Streaming mode prevents HttpURLConnection from replaying a buffered POST
                    // after a lost response. The application retries only proven ABORTED commits.
                    connection.setFixedLengthStreamingMode(payload.size)
                    connection.outputStream.use { it.write(payload) }
                }
                val code = connection.responseCode
                if (code == 404 && allowMissing) return@withContext null
                if (code == 409) {
                    // HTTP 409 also represents ALREADY_EXISTS. Only structured ABORTED proves
                    // the transaction did not commit and permits a bounded in-request retry.
                    val error =
                        connection.errorStream
                            ?.use { stream ->
                                val bytes = ByteArray(8192)
                                val count = stream.read(bytes)
                                if (count < 0) "" else String(bytes, 0, count, Charsets.UTF_8)
                            }
                            .orEmpty()
                    val status =
                        runCatching {
                                Json.parseToJsonElement(error)
                                    .jsonObject["error"]
                                    ?.jsonObject
                                    ?.get("status")
                                    ?.jsonPrimitive
                                    ?.content
                            }
                            .getOrNull()
                    if (status == "ABORTED") throw FirestoreRestAborted()
                    throw CloudBackupFailure(BackupFailureReason.ConcurrentRemoteChange)
                }
                if (code !in 200..299)
                    throw CloudBackupFailure(
                        when (code) {
                            401 -> BackupFailureReason.ReauthenticationRequired
                            403 -> BackupFailureReason.PermissionDenied
                            429 -> BackupFailureReason.QuotaOrRateLimited
                            else -> BackupFailureReason.ServiceUnavailable
                        }
                    )
                val text =
                    connection.inputStream.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAX_RESPONSE_BYTES)
                                throw CloudBackupFailure(BackupFailureReason.InvalidSnapshot)
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                currentCoroutineContext().ensureActive()
                if (!authorization())
                    throw CloudBackupFailure(BackupFailureReason.ReauthenticationRequired)
                if (text.isBlank()) buildJsonObject {} else Json.parseToJsonElement(text)
            } catch (error: IOException) {
                // A commit may have reached the server. This reason never asserts no remote write.
                throw CloudBackupFailure(BackupFailureReason.Offline)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun validatePath(path: String) {
        require(path.split('/').all { it.matches(Regex("[a-zA-Z0-9_-]{1,128}")) })
        require(path.startsWith("users/"))
    }

    private fun encodeFields(fields: JsonObject): JsonObject =
        JsonObject(
            fields.mapValues { (key, value) ->
                if (key in TIMESTAMPS && value != JsonNull)
                    buildJsonObject { put("timestampValue", value) }
                else encode(value)
            }
        )

    private fun encode(value: JsonElement): JsonObject =
        when (value) {
            JsonNull -> buildJsonObject { put("nullValue", JsonNull) }
            is JsonArray ->
                buildJsonObject {
                    put(
                        "arrayValue",
                        buildJsonObject { put("values", JsonArray(value.map(::encode))) }
                    )
                }
            is JsonObject ->
                buildJsonObject {
                    put("mapValue", buildJsonObject { put("fields", encodeFields(value)) })
                }
            is JsonPrimitive ->
                when {
                    value.isString -> buildJsonObject { put("stringValue", value) }
                    value.longOrNull != null ->
                        buildJsonObject { put("integerValue", value.content) }
                    else -> throw CloudBackupFailure(BackupFailureReason.InvalidSnapshot)
                }
        }

    private fun decodeFields(fields: JsonObject): JsonObject =
        JsonObject(fields.mapValues { decode(it.value.jsonObject) })

    private fun decode(value: JsonObject): JsonElement =
        when {
            "nullValue" in value -> JsonNull
            "stringValue" in value -> value.getValue("stringValue")
            "integerValue" in value ->
                JsonPrimitive(value.getValue("integerValue").jsonPrimitive.content.toLong())
            "timestampValue" in value -> value.getValue("timestampValue")
            "arrayValue" in value ->
                JsonArray(
                    value
                        .getValue("arrayValue")
                        .jsonObject["values"]
                        ?.jsonArray
                        ?.map { decode(it.jsonObject) }
                        .orEmpty()
                )
            "mapValue" in value ->
                decodeFields(
                    value.getValue("mapValue").jsonObject["fields"]?.jsonObject
                        ?: buildJsonObject {}
                )
            else -> throw CloudBackupFailure(BackupFailureReason.InvalidSnapshot)
        }

    private class FirestoreRestAborted : Exception()

    private companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        val TIMESTAMPS = setOf("createdAt", "completedAt", "latestCompletedAt")
    }
}
