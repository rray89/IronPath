package com.example.ironpath.data.backup

import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.backup.BackupFailureReason
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

fun interface FirestoreBackupClientFactory {
    suspend fun forAccount(account: AccountId): FirestoreBackupClient
}

interface FirestoreBackupClient {
    suspend fun get(path: String): JsonObject?

    /** Consistent reads; mutations atomically compare every read version at commit. */
    suspend fun transaction(block: suspend FirestoreBackupTransaction.() -> Unit)
}

interface FirestoreBackupTransaction {
    /** Read all dependencies before mutations, including each mutation target. */
    suspend fun get(path: String): JsonObject?

    /** Stage at most one mutation per path; all read versions are checked at commit. */
    fun put(path: String, fields: JsonObject, serverTimes: Set<String> = emptySet())

    fun delete(path: String)
}

/** No persistence, write queue, listener, background retry, or credential-bearing URL. */
class FirestoreBackupRestClient(
    projectId: String,
    private val token: suspend () -> String,
    private val authorized: () -> Boolean,
    private val endpoint: String = "https://firestore.googleapis.com/v1",
) : FirestoreBackupClient {
    private val database = "projects/$projectId/databases/(default)"
    private val documents = "$database/documents"

    init {
        require(projectId.matches(Regex("[a-zA-Z0-9-]+")))
    }

    override suspend fun get(path: String): JsonObject? {
        validatePath(path)
        val document =
            request("GET", "$documents/$path", allowMissing = true)?.jsonObject ?: return null
        return decodeFields(document["fields"]?.jsonObject ?: buildJsonObject {})
    }

    override suspend fun transaction(block: suspend FirestoreBackupTransaction.() -> Unit) {
        // Mobile clients emulate optimistic transactions rather than calling beginTransaction.
        // Pin reads to the first server readTime; compare every read version in one atomic commit.
        // A timeout/unknown receipt is never retried; later explicit inspection recovers it.
        repeat(3) { attempt ->
            var readTime: String? = null
            val reads = linkedMapOf<String, ReadDocument>()
            val writes = linkedMapOf<String, JsonObject>()
            val transaction =
                object : FirestoreBackupTransaction {
                    override suspend fun get(path: String): JsonObject? {
                        validatePath(path)
                        check(writes.isEmpty()) { "Read before writing" }
                        reads[path]?.let {
                            return it.fields
                        }
                        val result =
                            request(
                                    "POST",
                                    "$documents:batchGet",
                                    buildJsonObject {
                                        put(
                                            "documents",
                                            JsonArray(listOf(JsonPrimitive("$documents/$path")))
                                        )
                                        readTime?.let { put("readTime", it) }
                                    }
                                )!!
                                .jsonArray
                                .single()
                                .jsonObject
                        val receivedTime = result.getValue("readTime").jsonPrimitive.content
                        val instant = Instant.parse(receivedTime)
                        require(instant.nano % 1_000 == 0)
                        readTime?.let { require(Instant.parse(it) == instant) }
                        readTime = receivedTime
                        val document =
                            if ("missing" in result) {
                                require(
                                    result.getValue("missing").jsonPrimitive.content ==
                                        "$documents/$path"
                                )
                                ReadDocument(null, null)
                            } else {
                                val found = result.getValue("found").jsonObject
                                require(
                                    found.getValue("name").jsonPrimitive.content ==
                                        "$documents/$path"
                                )
                                val version = found.getValue("updateTime").jsonPrimitive.content
                                require(Instant.parse(version) <= instant)
                                ReadDocument(
                                    decodeFields(found["fields"]?.jsonObject ?: buildJsonObject {}),
                                    version
                                )
                            }
                        reads[path] = document
                        return document.fields
                    }

                    private fun write(path: String, operation: JsonObject) {
                        validatePath(path)
                        // No blind writes or repeated writes can escape the version guard.
                        check(path in reads && path !in writes) { "Read each mutation target once" }
                        writes[path] =
                            JsonObject(
                                operation +
                                    ("currentDocument" to reads.getValue(path).precondition())
                            )
                    }

                    override fun put(path: String, fields: JsonObject, serverTimes: Set<String>) {
                        write(
                            path,
                            buildJsonObject {
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
                        )
                    }

                    override fun delete(path: String) {
                        write(path, buildJsonObject { put("delete", "$documents/$path") })
                    }
                }
            try {
                transaction.block()
                currentCoroutineContext().ensureActive()
                if (!authorized())
                    throw CloudBackupFailure(BackupFailureReason.ReauthenticationRequired)
                // A read-only snapshot never calls any write/transaction-management endpoint.
                if (writes.isEmpty()) return
                // The same read-only verify operation used by the official Firebase mobile/web
                // wire protocol guards dependencies that are not themselves being mutated.
                val verifies =
                    reads
                        .filterKeys { it !in writes }
                        .map { (path, document) ->
                            buildJsonObject {
                                put("verify", "$documents/$path")
                                put("currentDocument", document.precondition())
                            }
                        }
                request(
                    "POST",
                    "$documents:commit",
                    buildJsonObject { put("writes", JsonArray(writes.values + verifies)) }
                )
                return
            } catch (error: FirestoreRestAborted) {
                if (attempt == 2)
                    throw CloudBackupFailure(BackupFailureReason.ConcurrentRemoteChange)
            }
            // No automatic retry is used for any transport, permission, quota, or auth failure.
        }
    }

    private data class ReadDocument(val fields: JsonObject?, val updateTime: String?) {
        fun precondition() = buildJsonObject {
            if (updateTime == null) put("exists", false) else put("updateTime", updateTime)
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        allowMissing: Boolean = false,
    ): JsonElement? {
        currentCoroutineContext().ensureActive()
        if (!authorized()) throw CloudBackupFailure(BackupFailureReason.ReauthenticationRequired)
        val bearer = token()
        if (!authorized()) throw CloudBackupFailure(BackupFailureReason.ReauthenticationRequired)
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val connection = URI("$endpoint/$path").toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
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
                if (code == 409 || (code == 400 && path.endsWith(":commit"))) {
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
                    if (code == 409 && status == "ABORTED") throw FirestoreRestAborted()
                    if (code == 409 || status == "FAILED_PRECONDITION")
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
                if (!authorized())
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
