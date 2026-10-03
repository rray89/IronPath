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
    @Volatile private var instanceId: String? = null
    override val binding: String?
        get() =
            instanceId?.let { instance ->
                if (!validEndpoint || projectId.isBlank()) null
                else
                    sha256(
                        "ironpath-account-deletion-v2\n$projectId\n$instance\n$endpoint"
                            .toByteArray()
                    )
            }

    override suspend fun available(): Boolean {
        if (!validEndpoint || projectId.isBlank()) return false
        val response = request("GET", "/v2/capabilities") ?: return false
        val body = response.body ?: return false
        val instance = (body["serviceInstanceId"] as? JsonPrimitive)?.contentOrNull ?: return false
        if (
            response.code != 200 ||
                (body["protocol"] as? JsonPrimitive)?.contentOrNull != PROTOCOL ||
                (body["projectId"] as? JsonPrimitive)?.contentOrNull != projectId ||
                (body["authoritative"] as? JsonPrimitive)?.booleanOrNull != true ||
                (body["resumable"] as? JsonPrimitive)?.booleanOrNull != true ||
                !instance.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
                instanceId != null && instanceId != instance
        )
            return false
        instanceId = instance
        return true
    }

    // Legacy APIs cannot create or recover an unknown operation through this v2 client.
    override suspend fun start(operationId: String, token: String) =
        DeletionServiceResult.Unavailable

    override suspend fun resume(operationId: String) = DeletionServiceResult.Unavailable

    override suspend fun reserve(
        draft: AccountDeletionDraft,
        token: String
    ): DeletionServiceResult =
        rpc(
            draft.operationId,
            draft.receiptSecret,
            draft.request.accountId.opaqueValue,
            draft.request.serviceBinding,
            "/v2/reservations",
            token,
            buildJsonObject { put("operationId", draft.operationId) }
        )

    override suspend fun status(
        progress: com.example.ironpath.domain.account.AccountDeletionProgress
    ) = progressRpc(progress, "status")

    override suspend fun activate(
        progress: com.example.ironpath.domain.account.AccountDeletionProgress,
        token: String
    ) = progressRpc(progress, "activate", token)

    override suspend fun cancel(
        progress: com.example.ironpath.domain.account.AccountDeletionProgress
    ) = progressRpc(progress, "cancel-unactivated")

    private suspend fun progressRpc(
        progress: com.example.ironpath.domain.account.AccountDeletionProgress,
        action: String,
        token: String? = null
    ): DeletionServiceResult {
        val result =
            rpc(
                progress.operationId,
                progress.receiptSecret ?: return DeletionServiceResult.Unavailable,
                progress.accountId.opaqueValue,
                progress.serviceBinding,
                "/v2/operations/${progress.operationId}/$action",
                token
            )
        val receipt = (result as? DeletionServiceResult.Receipt)?.value ?: return result
        if (
            receipt.subjectBinding != progress.subjectBinding ||
                receipt.version < progress.receiptVersion
        )
            return DeletionServiceResult.Unavailable
        return result
    }

    private suspend fun rpc(
        operationId: String,
        secret: String,
        expectedUid: String,
        expectedBinding: String?,
        path: String,
        token: String? = null,
        body: JsonObject = buildJsonObject {}
    ): DeletionServiceResult {
        if (
            !validOperation(operationId) ||
                !secret.matches(Regex("[A-Za-z0-9_-]{43}")) ||
                token != null &&
                    (token.isBlank() ||
                        token.length > 16384 ||
                        token.any { it == '\r' || it == '\n' }) ||
                !available() ||
                expectedBinding == null ||
                expectedBinding != binding
        )
            return DeletionServiceResult.Unavailable
        val response =
            request("POST", path, body, token, secret) ?: return DeletionServiceResult.Unavailable
        if (response.code == 404) return DeletionServiceResult.Missing
        val value = response.body ?: return DeletionServiceResult.Unavailable
        val instance = instanceId ?: return DeletionServiceResult.Unavailable
        if (
            (value["protocol"] as? JsonPrimitive)?.contentOrNull != PROTOCOL ||
                (value["projectId"] as? JsonPrimitive)?.contentOrNull != projectId ||
                (value["serviceInstanceId"] as? JsonPrimitive)?.contentOrNull != instance ||
                (value["operationId"] as? JsonPrimitive)?.contentOrNull != operationId ||
                (value["subjectBinding"] as? JsonPrimitive)?.contentOrNull !=
                    deletionSubjectBinding(projectId, instance, operationId, expectedUid)
        )
            return DeletionServiceResult.Unavailable
        val state =
            com.example.ironpath.domain.account.AccountDeletionRemoteState.entries.firstOrNull {
                it.name == (value["state"] as? JsonPrimitive)?.contentOrNull
            } ?: return DeletionServiceResult.Unavailable
        val version =
            (value["version"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                ?: return DeletionServiceResult.Unavailable
        val expectedCode =
            if (state == com.example.ironpath.domain.account.AccountDeletionRemoteState.PENDING) 202
            else 200
        if (response.code != expectedCode) return DeletionServiceResult.Unavailable
        return DeletionServiceResult.Receipt(
            DeletionServiceReceipt(
                operationId,
                value.getValue("subjectBinding").jsonPrimitive.content,
                state,
                version
            )
        )
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        token: String? = null,
        receiptSecret: String? = null
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
                    receiptSecret?.let { connection.setRequestProperty("Deletion-Receipt", it) }
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

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PROTOCOL = "ironpath-account-deletion-v2"
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

internal fun deletionSubjectBinding(
    project: String,
    instance: String,
    operation: String,
    uid: String
): String {
    val bytes = java.io.ByteArrayOutputStream()
    for (part in listOf("ironpath-delete-v2", project, instance, operation, uid)) {
        val encoded = part.toByteArray(Charsets.UTF_8)
        bytes.write(java.nio.ByteBuffer.allocate(4).putInt(encoded.size).array())
        bytes.write(encoded)
    }
    return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") {
        "%02x".format(it)
    }
}
