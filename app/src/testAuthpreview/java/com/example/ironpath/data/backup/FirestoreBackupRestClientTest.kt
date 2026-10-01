package com.example.ironpath.data.backup

import com.example.ironpath.domain.backup.BackupFailureReason
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FirestoreBackupRestClientTest {
    private val requests = mutableListOf<Pair<String, String>>()
    private var status = 200
    private var body = "{}"
    private var aborts = 0
    private var commits = 0
    private var authorized = true
    private var loseCommitResponse = false
    private var rollbackStatus = 200
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.toString()
                val incoming = exchange.requestBody.bufferedReader().readText()
                synchronized(requests) { requests += path to incoming }
                assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
                var code = status
                val response =
                    when {
                        path.endsWith(":beginTransaction") -> "{\"transaction\":\"tx+/=\"}"
                        path.endsWith(":batchGet") ->
                            "[{\"found\":{\"name\":\"projects/demo-ironpath/databases/(default)/documents/users/owner\",\"fields\":{\"generation\":{\"integerValue\":\"0\"}}}}]"
                        path.endsWith(":rollback") -> {
                            code = rollbackStatus
                            "{}"
                        }
                        path.endsWith(":commit") -> {
                            commits++
                            if (aborts > 0) {
                                aborts--
                                code = 409
                            }
                            if (loseCommitResponse) {
                                exchange.close()
                                return@createContext
                            }
                            if (code == 409) "{\"error\":{\"status\":\"ABORTED\"}}" else "{}"
                        }
                        else -> body
                    }
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun client() =
        FirestoreBackupRestClient(
            "demo-ironpath",
            { "test-token" },
            { authorized },
            "http://127.0.0.1:${server.address.port}/v1"
        )

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun readsWireFieldsWithoutPuttingTokenInUrls() = runBlocking {
        body =
            """{"fields":{"generation":{"integerValue":"2"},"backupIds":{"arrayValue":{"values":[{"stringValue":"backup"}]}},"latestCompletedAt":{"timestampValue":"2026-09-30T00:00:00Z"},"empty":{"nullValue":null},"counts":{"mapValue":{"fields":{"PersonalRecord":{"integerValue":"1"}}}}}}"""
        val fields = client().get("users/owner")!!
        assertEquals(2, fields.getValue("generation").jsonPrimitive.int)
        assertEquals(
            "backup",
            fields.getValue("backupIds").jsonArray.single().jsonPrimitive.content
        )
        assertEquals(JsonNull, fields["empty"])
        assertTrue(requests.all { "test-token" !in it.first })
    }

    @Test
    fun missingDocumentIsAbsentButPermissionAndQuotaAreTyped() = runBlocking {
        status = 404
        assertNull(client().get("users/owner"))
        for ((code, expected) in
            listOf(
                401 to BackupFailureReason.ReauthenticationRequired,
                403 to BackupFailureReason.PermissionDenied,
                429 to BackupFailureReason.QuotaOrRateLimited,
                503 to BackupFailureReason.ServiceUnavailable
            )) {
            status = code
            val error =
                runCatching { client().get("users/owner") }.exceptionOrNull() as CloudBackupFailure
            assertEquals(expected, error.reason)
        }
    }

    @Test
    fun transactionUsesRequestTimeAndRetriesOnlyProvenAbortedCommit() = runBlocking {
        body = "{\"fields\":{\"generation\":{\"integerValue\":\"0\"}}}"
        aborts = 2
        var callbacks = 0
        client().transaction {
            callbacks++
            assertEquals(0, get("users/owner")!!.getValue("generation").jsonPrimitive.int)
            put(
                "users/owner",
                buildJsonObject {
                    put("generation", 1)
                    put("latestCompletedAt", JsonNull)
                },
                setOf("latestCompletedAt")
            )
        }
        assertEquals(3, callbacks)
        assertEquals(3, commits)
        val commit = Json.parseToJsonElement(requests.last().second).jsonObject
        assertEquals("tx+/=", commit.getValue("transaction").jsonPrimitive.content)
        assertEquals(
            "REQUEST_TIME",
            commit
                .getValue("writes")
                .jsonArray
                .single()
                .jsonObject
                .getValue("updateTransforms")
                .jsonArray
                .single()
                .jsonObject
                .getValue("setToServerValue")
                .jsonPrimitive
                .content
        )
        val read = requests.first { it.first.endsWith(":batchGet") }
        assertEquals(
            "tx+/=",
            Json.parseToJsonElement(read.second)
                .jsonObject
                .getValue("transaction")
                .jsonPrimitive
                .content
        )
        assertTrue(requests.none { "?transaction=" in it.first })
    }

    @Test
    fun deniedTransactionDoesNotRetryAndSessionFenceBlocksRequests() = runBlocking {
        status = 403
        assertEquals(
            BackupFailureReason.PermissionDenied,
            (runCatching { client().transaction { put("users/owner", buildJsonObject {}) } }
                    .exceptionOrNull() as CloudBackupFailure)
                .reason
        )
        assertEquals(1, requests.size)
        authorized = false
        assertEquals(
            BackupFailureReason.ReauthenticationRequired,
            (runCatching { client().get("users/owner") }.exceptionOrNull() as CloudBackupFailure)
                .reason
        )
        assertEquals(1, requests.size)
    }

    @Test
    fun callbackFailureRollsBackBeforeCommitWithoutReplacingOriginalFailure() = runBlocking {
        rollbackStatus = 503
        val original = CloudBackupFailure(BackupFailureReason.ConcurrentRemoteChange)
        val error =
            runCatching {
                    client().transaction {
                        get("users/owner")
                        throw original
                    }
                }
                .exceptionOrNull()

        assertSame(original, error)
        assertEquals(0, commits)
        assertEquals(
            listOf(":beginTransaction", ":batchGet", ":rollback"),
            requests.map { ":" + it.first.substringAfterLast(":") },
        )
        val rollback = Json.parseToJsonElement(requests.last().second).jsonObject
        assertEquals("tx+/=", rollback.getValue("transaction").jsonPrimitive.content)
    }

    @Test
    fun cancellationBeforeCommitRollsBackAndStillPropagatesCancellation() = runBlocking {
        var cancelled = false
        val task = launch {
            val operation = currentCoroutineContext()[Job]!!
            val subject =
                FirestoreBackupRestClient(
                    "demo-ironpath",
                    { "test-token" },
                    { authorized && operation.isActive },
                    "http://127.0.0.1:${server.address.port}/v1",
                    rollbackAuthorized = { authorized },
                )
            try {
                subject.transaction {
                    get("users/owner")
                    currentCoroutineContext().cancel()
                    currentCoroutineContext().ensureActive()
                }
            } catch (error: CancellationException) {
                cancelled = true
            }
        }
        task.join()

        assertTrue(cancelled)
        assertEquals(0, commits)
        assertTrue(requests.last().first.endsWith(":rollback"))
    }

    @Test
    fun sessionChangeBeforeCommitDoesNotSendCleanupWithChangedIdentity() = runBlocking {
        val original = CloudBackupFailure(BackupFailureReason.ReauthenticationRequired)
        val error =
            runCatching {
                    client().transaction {
                        get("users/owner")
                        authorized = false
                        throw original
                    }
                }
                .exceptionOrNull()

        assertSame(original, error)
        assertEquals(2, requests.size)
        assertEquals(0, commits)
    }

    @Test
    fun lostCommitResponseIsNotReplayed() = runBlocking {
        loseCommitResponse = true
        val error =
            runCatching { client().transaction { put("users/owner", buildJsonObject {}) } }
                .exceptionOrNull() as CloudBackupFailure
        assertEquals(BackupFailureReason.Offline, error.reason)
        assertEquals(1, commits)
        assertTrue(requests.none { it.first.endsWith(":rollback") })
    }

    @Test
    fun abortedRetryIsBoundedAndUnknownWireTypesFailClosed() = runBlocking {
        aborts = 4
        val error =
            runCatching { client().transaction { delete("users/owner") } }.exceptionOrNull()
                as CloudBackupFailure
        assertEquals(BackupFailureReason.ConcurrentRemoteChange, error.reason)
        assertEquals(3, commits)
        body = "{\"fields\":{\"forbidden\":{\"bytesValue\":\"AA==\"}}}"
        assertEquals(
            BackupFailureReason.InvalidSnapshot,
            (runCatching { client().get("users/owner") }.exceptionOrNull() as CloudBackupFailure)
                .reason
        )
    }
}
