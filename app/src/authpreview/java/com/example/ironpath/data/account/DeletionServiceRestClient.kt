package com.example.ironpath.data.account

import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Bounded HTTPS transport; no credential URLs, redirects, retry, persistence or payload logging.
 */
class DeletionServiceRestClient(
    private val endpoint: String,
    private val projectId: String,
    private val allowLoopbackForTests: Boolean = false,
) : AccountDeletionService {
    private val validEndpoint = validDeletionEndpoint(endpoint, allowLoopbackForTests)
    override val binding: String? =
        if (!validEndpoint || projectId.isBlank()) null
        else
            MessageDigest.getInstance("SHA-256")
                .digest("$projectId\n$endpoint".toByteArray())
                .joinToString("") { "%02x".format(it) }

    override suspend fun available(): Boolean {
        if (binding == null) return false
        val response = request("GET", "/v1/capabilities") ?: return false
        return response.code == 200 &&
            response.body?.let { body ->
                (body["protocol"] as? JsonPrimitive)?.contentOrNull ==
                    "ironpath-account-deletion-v1" &&
                    (body["projectId"] as? JsonPrimitive)?.contentOrNull == projectId &&
                    (body["authoritative"] as? JsonPrimitive)?.booleanOrNull == true &&
                    (body["resumable"] as? JsonPrimitive)?.booleanOrNull == true
            } == true
    }

    override suspend fun start(operationId: String, token: String): DeletionServiceResult {
        if (
            !validOperation(operationId) ||
                token.isBlank() ||
                token.any { it == '\r' || it == '\n' } ||
                !available()
        )
            return DeletionServiceResult.Unavailable
        return receipt(
            operationId,
            request(
                "POST",
                "/v1/deletions",
                buildJsonObject { put("operationId", operationId) },
                token
            ),
            allowMissing = false
        )
    }

    override suspend fun resume(operationId: String): DeletionServiceResult {
        if (!validOperation(operationId) || !available()) return DeletionServiceResult.Unavailable
        return receipt(
            operationId,
            request("POST", "/v1/deletions/$operationId/resume", buildJsonObject {}),
            allowMissing = true
        )
    }

    private fun receipt(
        operationId: String,
        response: Response?,
        allowMissing: Boolean
    ): DeletionServiceResult {
        if (response == null) return DeletionServiceResult.Unavailable
        if (allowMissing && response.code == 404) return DeletionServiceResult.Missing
        val body = response.body ?: return DeletionServiceResult.Unavailable
        if ((body["operationId"] as? JsonPrimitive)?.contentOrNull != operationId)
            return DeletionServiceResult.Unavailable
        return when {
            response.code == 200 &&
                (body["state"] as? JsonPrimitive)?.contentOrNull == "COMPLETE" ->
                DeletionServiceResult.Complete
            response.code == 202 && (body["state"] as? JsonPrimitive)?.contentOrNull == "PENDING" ->
                DeletionServiceResult.Pending
            else -> DeletionServiceResult.Unavailable
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        token: String? = null
    ): Response? =
        withContext(Dispatchers.IO) {
            if (!validEndpoint) return@withContext null
            try {
                val connection = URI(endpoint + path).toURL().openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = method
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Accept", "application/json")
                    token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                    body?.let {
                        val bytes = it.toString().toByteArray(Charsets.UTF_8)
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { output -> output.write(bytes) }
                    }
                    val code = connection.responseCode
                    val stream =
                        if (code in 200..299) connection.inputStream else connection.errorStream
                    val bytes = stream?.use { it.readBytesBounded(2048) }
                    val parsed =
                        bytes?.let {
                            Json.parseToJsonElement(it.toString(Charsets.UTF_8)) as? JsonObject
                        }
                    Response(code, parsed)
                } finally {
                    connection.disconnect()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(512)
        while (true) {
            val read = read(buffer)
            if (read == -1) return result.toByteArray()
            require(result.size() + read <= limit)
            result.write(buffer, 0, read)
        }
    }

    private data class Response(val code: Int, val body: JsonObject?)

    private fun validOperation(value: String) =
        value.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}"))
}

internal fun validDeletionEndpoint(value: String, allowLoopbackForTests: Boolean = false): Boolean =
    try {
        val uri = URI(value)
        val schemeAllowed =
            uri.scheme == "https" ||
                (allowLoopbackForTests && uri.scheme == "http" && uri.host == "127.0.0.1")
        schemeAllowed &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.rawPath.isNullOrEmpty() &&
            uri.port in -1..65535
    } catch (_: Exception) {
        false
    }
