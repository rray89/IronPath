package com.example.ironpath.data.backup

import com.example.ironpath.domain.backup.BackupFailureReason
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
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
    private var commitStatus = 200
    private var commitErrorStatus = "ABORTED"
    private var authorized = true
    private var loseCommitResponse = false
    private var beginStatus = 200
    private var batchReadTime = "2026-09-30T00:00:01Z"
    private var batchUpdateTime = "2026-09-30T00:00:00.123456Z"
    private val missingPaths = mutableSetOf<String>()
    private var batchOverride: ((JsonObject) -> String)? = null
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
                        path.endsWith(":beginTransaction") -> {
                            code = beginStatus
                            "{\"transaction\":\"tx+/=\"}"
                        }
                        path.endsWith(":batchGet") -> {
                            val request = Json.parseToJsonElement(incoming).jsonObject
                            batchOverride?.invoke(request)
                                ?: run {
                                    val name =
                                        request
                                            .getValue("documents")
                                            .jsonArray
                                            .single()
                                            .jsonPrimitive
                                            .content
                                    JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("readTime", batchReadTime)
                                                    if (
                                                        name.substringAfter("/documents/") in
                                                            missingPaths
                                                    )
                                                        put("missing", name)
                                                    else
                                                        put(
                                                            "found",
                                                            buildJsonObject {
                                                                put("name", name)
                                                                put("updateTime", batchUpdateTime)
                                                                put(
                                                                    "fields",
                                                                    buildJsonObject {
                                                                        put(
                                                                            "generation",
                                                                            buildJsonObject {
                                                                                put(
                                                                                    "integerValue",
                                                                                    "0"
                                                                                )
                                                                            }
                                                                        )
                                                                    }
                                                                )
                                                            }
                                                        )
                                                }
                                            )
                                        )
                                        .toString()
                                }
                        }
                        path.endsWith(":commit") -> {
                            commits++
                            if (code == 200) code = commitStatus
                            if (aborts > 0) {
                                aborts--
                                code = 409
                            }
                            if (loseCommitResponse) {
                                exchange.close()
                                return@createContext
                            }
                            if (code >= 400) "{\"error\":{\"status\":\"$commitErrorStatus\"}}"
                            else "{}"
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
    fun sameSessionCanInspectWhenOrdinaryReadSucceedsButExplicitBeginIsDenied() = runBlocking {
        body = "{\"fields\":{\"generation\":{\"integerValue\":\"0\"}}}"
        beginStatus = 403
        val subject = client()
        assertEquals(0, subject.get("users/owner")!!.getValue("generation").jsonPrimitive.int)

        subject.transaction {
            assertEquals(0, get("users/owner")!!.getValue("generation").jsonPrimitive.int)
        }

        assertEquals(0, commits)
        assertTrue(requests.none { it.first.endsWith(":beginTransaction") })
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
        assertFalse("transaction" in commit)
        assertEquals(
            buildJsonObject { put("updateTime", batchUpdateTime) },
            commit.getValue("writes").jsonArray.single().jsonObject["currentDocument"]
        )
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
        assertTrue(
            requests
                .filter { it.first.endsWith(":batchGet") }
                .all {
                    val read = Json.parseToJsonElement(it.second).jsonObject
                    "readTime" !in read && "transaction" !in read && "newTransaction" !in read
                }
        )
        assertTrue(requests.none { it.first.endsWith(":beginTransaction") })
    }

    @Test
    fun deniedTransactionDoesNotRetryAndSessionFenceBlocksRequests() = runBlocking {
        status = 403
        assertEquals(
            BackupFailureReason.PermissionDenied,
            (runCatching {
                        client().transaction {
                            get("users/owner")
                            put("users/owner", buildJsonObject {})
                        }
                    }
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
    fun callbackFailureNeedsNoRollbackAndPreservesOriginalFailure() = runBlocking {
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
            listOf(":batchGet"),
            requests.map { ":" + it.first.substringAfterLast(":") },
        )
    }

    @Test
    fun cancellationBeforeCommitSendsNoWriteOrCleanupAndStillPropagatesCancellation() =
        runBlocking {
            var cancelled = false
            val task = launch {
                val operation = currentCoroutineContext()[Job]!!
                val subject =
                    FirestoreBackupRestClient(
                        "demo-ironpath",
                        { "test-token" },
                        { authorized && operation.isActive },
                        "http://127.0.0.1:${server.address.port}/v1",
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
            assertEquals(1, requests.size)
            assertTrue(requests.single().first.endsWith(":batchGet"))
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
        assertEquals(1, requests.size)
        assertEquals(0, commits)
    }

    @Test
    fun lostCommitResponseIsNotReplayed() = runBlocking {
        loseCommitResponse = true
        val error =
            runCatching {
                    client().transaction {
                        get("users/owner")
                        put("users/owner", buildJsonObject {})
                    }
                }
                .exceptionOrNull() as CloudBackupFailure
        assertEquals(BackupFailureReason.Offline, error.reason)
        assertEquals(1, commits)
        assertTrue(requests.none { it.first.endsWith(":rollback") })
    }

    @Test
    fun abortedRetryIsBoundedAndUnknownWireTypesFailClosed() = runBlocking {
        aborts = 4
        val error =
            runCatching {
                    client().transaction {
                        get("users/owner")
                        delete("users/owner")
                    }
                }
                .exceptionOrNull() as CloudBackupFailure
        assertEquals(BackupFailureReason.ConcurrentRemoteChange, error.reason)
        assertEquals(3, commits)
        body = "{\"fields\":{\"forbidden\":{\"bytesValue\":\"AA==\"}}}"
        assertEquals(
            BackupFailureReason.InvalidSnapshot,
            (runCatching { client().get("users/owner") }.exceptionOrNull() as CloudBackupFailure)
                .reason
        )
    }

    @Test
    fun dependentReadsShareServerSnapshotAndReadOnlyInspectionNeverCommits() = runBlocking {
        client().transaction {
            get("users/owner")
            get("users/owner/backups/complete")
            get("users/owner")
        }
        assertEquals(2, requests.size)
        val first = Json.parseToJsonElement(requests[0].second).jsonObject
        val second = Json.parseToJsonElement(requests[1].second).jsonObject
        assertFalse("readTime" in first)
        assertEquals(batchReadTime, second.getValue("readTime").jsonPrimitive.content)
        assertEquals(0, commits)
    }

    @Test
    fun commitGuardsCreationDeletionAndEveryUnwrittenReadIncludingAbsence() = runBlocking {
        missingPaths += "users/owner/backups/new"
        missingPaths += "users/owner/backups/absent"
        client().transaction {
            get("users/owner")
            get("users/owner/backups/new")
            get("users/owner/backups/old")
            get("users/owner/backups/absent")
            put("users/owner/backups/new", buildJsonObject { put("generation", 1) })
            delete("users/owner/backups/old")
        }
        val writes =
            Json.parseToJsonElement(requests.last().second)
                .jsonObject
                .getValue("writes")
                .jsonArray
                .map { it.jsonObject }
        assertEquals(4, writes.size)
        assertEquals(buildJsonObject { put("exists", false) }, writes[0]["currentDocument"])
        assertTrue(writes[1].getValue("delete").jsonPrimitive.content.endsWith("/backups/old"))
        assertEquals(
            buildJsonObject { put("updateTime", batchUpdateTime) },
            writes[1]["currentDocument"]
        )
        assertTrue(writes[2].getValue("verify").jsonPrimitive.content.endsWith("/users/owner"))
        assertEquals(writes[1]["currentDocument"], writes[2]["currentDocument"])
        assertTrue(writes[3].getValue("verify").jsonPrimitive.content.endsWith("/backups/absent"))
        assertEquals(writes[0]["currentDocument"], writes[3]["currentDocument"])
    }

    @Test
    fun writePermissionIsIndependentFromSuccessfulInspectionAndNeverRetried() = runBlocking {
        client().transaction { get("users/owner") }
        commitStatus = 403
        val failure =
            runCatching {
                    client().transaction {
                        get("users/owner")
                        put("users/owner", buildJsonObject {})
                    }
                }
                .exceptionOrNull() as CloudBackupFailure
        assertEquals(BackupFailureReason.PermissionDenied, failure.reason)
        assertEquals(1, commits)
    }

    @Test
    fun blindWritesDuplicateWritesAndReadsAfterWritesFailBeforeCommit() = runBlocking {
        assertTrue(
            runCatching { client().transaction { put("users/owner", buildJsonObject {}) } }
                .isFailure
        )
        assertTrue(
            runCatching {
                    client().transaction {
                        get("users/owner")
                        put("users/owner", buildJsonObject {})
                        put("users/owner", buildJsonObject {})
                    }
                }
                .isFailure
        )
        assertTrue(
            runCatching {
                    client().transaction {
                        get("users/owner")
                        delete("users/owner")
                        get("users/owner/backups/other")
                    }
                }
                .isFailure
        )
        assertEquals(0, commits)
    }

    @Test
    fun mismatchedSnapshotTimeCannotBecomeACompletedInspection() = runBlocking {
        val failure =
            runCatching {
                    client().transaction {
                        get("users/owner")
                        batchReadTime = "2026-09-30T00:00:02Z"
                        get("users/owner/backups/complete")
                    }
                }
                .exceptionOrNull()
        assertEquals(BackupFailureReason.ServiceUnavailable, (failure as CloudBackupFailure).reason)
        assertEquals(0, commits)
    }

    @Test
    fun failedVersionPreconditionAndAlreadyExistsRequireReviewWithoutRetry() = runBlocking {
        for ((http, status) in listOf(400 to "FAILED_PRECONDITION", 409 to "ALREADY_EXISTS")) {
            commitStatus = http
            commitErrorStatus = status
            val before = commits
            val failure =
                runCatching {
                        client().transaction {
                            get("users/owner")
                            put("users/owner", buildJsonObject {})
                        }
                    }
                    .exceptionOrNull() as CloudBackupFailure
            assertEquals(BackupFailureReason.ConcurrentRemoteChange, failure.reason)
            assertEquals(before + 1, commits)
        }
    }

    @Test
    fun fragmentedStructuredErrorIsReadCompletelyButOversizeOrPartialJsonIsRejected() {
        val error = """{"error":{"status":"FAILED_PRECONDITION"}}""".toByteArray()
        val fragmented =
            object : ByteArrayInputStream(error) {
                override fun read(bytes: ByteArray, offset: Int, length: Int) =
                    super.read(bytes, offset, minOf(3, length))
            }
        assertEquals("FAILED_PRECONDITION", firestoreRestErrorStatus(fragmented))
        assertNull(firestoreRestErrorStatus(ByteArrayInputStream(error.copyOf(error.size - 1))))
        assertNull(firestoreRestErrorStatus(ByteArrayInputStream(error + ByteArray(8193))))
    }

    @Test
    fun malformedReadEnvelopeIsAServiceFailureWithoutClaimingCorruptBackup() = runBlocking {
        batchOverride = { """[{"missing":"wrong/path","readTime":"$batchReadTime"}]""" }
        val failure =
            runCatching { client().transaction { get("users/owner") } }.exceptionOrNull()
                as CloudBackupFailure
        assertEquals(BackupFailureReason.ServiceUnavailable, failure.reason)
        assertEquals(0, commits)
    }
}
