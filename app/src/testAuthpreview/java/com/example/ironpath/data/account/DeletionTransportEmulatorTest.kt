package com.example.ironpath.data.account

import com.example.ironpath.domain.account.*
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Only the explicit deletionTransportTest task may run these isolated synthetic flows. */
class DeletionTransportEmulatorTest {
    private val project = "demo-ironpath-deletion"
    private val instance = "ironpath-deletion-emulator-v2"
    private val authHost = System.getenv("FIREBASE_AUTH_EMULATOR_HOST").orEmpty()
    private val firestoreHost = System.getenv("FIRESTORE_EMULATOR_HOST").orEmpty()
    private val endpoint = System.getenv("IRONPATH_DELETION_ENDPOINT").orEmpty()

    init {
        require(authHost == "127.0.0.1:9197" && firestoreHost == "127.0.0.1:8187")
        require(
            validDeletionEndpoint(endpoint, allowLoopbackForTests = true) &&
                endpoint.startsWith("http://127.0.0.1:")
        )
    }

    @Test
    fun reservationSurvivesClientRecreationAndSecondDeviceRecoversAfterAuthDeletion() =
        runBlocking {
            val subject = "kotlin-deletion-${UUID.randomUUID()}"
            val initial = googleSignIn(subject)
            val uid = initial.uid
            val orphan = seedOrphan(uid)
            val firstClient = client()
            val firstDraft = draft(firstClient, uid)
            val secondDraft = draft(firstClient, uid)
            val first = reserve(firstClient, firstDraft, initial.token)
            val second = reserve(firstClient, secondDraft, initial.token)
            assertEquals(
                AccountDeletionRemoteState.RESERVED,
                receipt(firstClient.status(first)).state
            )
            assertNoDestructiveState(first)
            assertEquals(200, request(orphan, "GET", token = "owner").code)
            assertEquals(200, authLookup(initial.token).code)

            // Recreate the actual transport with only the acknowledged receipt. Reading cannot
            // activate.
            val restarted = client()
            assertEquals(
                AccountDeletionRemoteState.RESERVED,
                receipt(restarted.status(first)).state
            )
            assertNoDestructiveState(first)
            val freshProof = googleSignIn(subject)
            assertEquals(uid, freshProof.uid)
            assertEquals(
                AccountDeletionRemoteState.PENDING,
                receipt(restarted.activate(first, freshProof.token)).state
            )
            awaitComplete(client(), second)
            assertEquals(404, request(orphan, "GET", token = "owner").code)
            assertEquals(404, request(document("users/$uid"), "GET", token = "owner").code)
            assertEquals(400, authLookup(initial.token).code)
            assertEquals(
                200,
                request(document("accountDeletionTombstones/$uid"), "GET", token = "owner").code
            )

            val recovered = receipt(client().status(second))
            assertEquals(AccountDeletionRemoteState.COMPLETE, recovered.state)
            assertEquals(3L, recovered.version)
            assertEquals(second.operationId, recovered.operationId)
            assertNotEquals(uid, googleSignIn(subject).uid)
            assertEquals(404, request(orphan, "GET", token = "owner").code)
        }

    @Test
    fun externallyMissingAuthAllowsOnlyNonDestructiveReservationCancellation() = runBlocking {
        val identity = googleSignIn()
        val orphan = seedOrphan(identity.uid)
        val c = client()
        val progress = reserve(c, draft(c, identity.uid), identity.token)
        assertEquals(
            200,
            request(
                    "http://$authHost/identitytoolkit.googleapis.com/v1/accounts:delete?key=demo-key",
                    "POST",
                    buildJsonObject { put("idToken", identity.token) }
                )
                .code
        )
        val restarted = client()
        assertEquals(AccountDeletionRemoteState.RESERVED, receipt(restarted.status(progress)).state)
        assertEquals(
            DeletionServiceResult.Unavailable,
            restarted.activate(progress, "invalid-proof")
        )
        assertNoDestructiveState(progress)
        val cancelled = receipt(restarted.cancel(progress))
        assertEquals(AccountDeletionRemoteState.CANCELLED_NO_DELETE, cancelled.state)
        assertEquals(2L, cancelled.version)
        assertEquals(cancelled, receipt(client().status(progress)))
        assertEquals(200, request(orphan, "GET", token = "owner").code)
        assertNoDestructiveState(progress)
    }

    @Test
    fun delayedCancellationReplyCannotBecomeAnotherDevicesCompletion() = runBlocking {
        val identity = googleSignIn()
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val proxy =
            ForwardingService(endpoint) { path, response ->
                if (path.endsWith("/cancel-unactivated")) {
                    committed.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                response
            }
        try {
            val c = client(proxy.endpoint)
            val cancelled = reserve(c, draft(c, identity.uid), identity.token)
            val active = reserve(c, draft(c, identity.uid), identity.token)
            val delayed = async(Dispatchers.IO) { receipt(c.cancel(cancelled)) }
            assertTrue(
                "Cancellation never reached the service",
                committed.await(10, TimeUnit.SECONDS)
            )
            assertEquals(
                AccountDeletionRemoteState.PENDING,
                receipt(c.activate(active, identity.token)).state
            )
            awaitComplete(c, active)
            assertEquals(400, authLookup(identity.token).code)
            release.countDown()
            val terminal = delayed.await()
            assertEquals(AccountDeletionRemoteState.CANCELLED_NO_DELETE, terminal.state)
            assertEquals(2L, terminal.version)
            assertEquals(terminal, receipt(client(proxy.endpoint).status(cancelled)))
            assertEquals(terminal, receipt(c.cancel(cancelled)))
        } finally {
            release.countDown()
            proxy.close()
        }
    }

    @Test
    fun lostActivationResponseRecoversWithFreshClientStatusAndNeverReplaysMutation() = runBlocking {
        val identity = googleSignIn()
        val orphan = seedOrphan(identity.uid)
        val proxy =
            ForwardingService(endpoint) { path, response ->
                if (path.endsWith("/activate")) {
                    assertEquals(202, response.code)
                    // The real server committed; emulate an intermediary losing its acknowledgment.
                    WireResponse(503, buildJsonObject { put("error", "UNAVAILABLE") })
                } else response
            }
        try {
            val c = client(proxy.endpoint)
            val progress = reserve(c, draft(c, identity.uid), identity.token)
            assertEquals(DeletionServiceResult.Unavailable, c.activate(progress, identity.token))
            val restarted = client(proxy.endpoint)
            awaitComplete(restarted, progress)
            assertEquals(1, proxy.activationRequests.get())
            assertEquals(400, authLookup(identity.token).code)
            assertEquals(404, request(orphan, "GET", token = "owner").code)
            assertEquals(
                AccountDeletionRemoteState.COMPLETE,
                receipt(restarted.status(progress)).state
            )
            assertEquals(1, proxy.activationRequests.get())
        } finally {
            proxy.close()
        }
    }

    @Test
    fun concurrentActualClientsCancelOrActivateOneReservationWithOneTerminalOutcome() =
        runBlocking {
            val identity = googleSignIn()
            val orphan = seedOrphan(identity.uid)
            val c = client()
            val progress = reserve(c, draft(c, identity.uid), identity.token)
            val activation =
                async(Dispatchers.IO) { receipt(client().activate(progress, identity.token)) }
            val cancellation = async(Dispatchers.IO) { receipt(client().cancel(progress)) }
            val activated = activation.await()
            val cancelled = cancellation.await()
            if (activated.state == AccountDeletionRemoteState.CANCELLED_NO_DELETE) {
                assertEquals(activated, cancelled)
                assertEquals(activated, receipt(client().status(progress)))
                assertNoDestructiveState(progress)
                assertEquals(200, request(orphan, "GET", token = "owner").code)
                assertEquals(200, authLookup(identity.token).code)
            } else {
                assertEquals(AccountDeletionRemoteState.PENDING, activated.state)
                assertTrue(
                    cancelled.state in
                        setOf(
                            AccountDeletionRemoteState.PENDING,
                            AccountDeletionRemoteState.COMPLETE
                        )
                )
                awaitComplete(client(), progress)
                assertEquals(404, request(orphan, "GET", token = "owner").code)
                assertEquals(400, authLookup(identity.token).code)
            }
        }

    @Test
    fun unknownOperationWrongSecretWrongUidAndWrongBindingCannotReadOrActivate() = runBlocking {
        val identity = googleSignIn()
        val other = googleSignIn()
        val c = client()
        val d = draft(c, identity.uid)
        val progress = reserve(c, d, identity.token)
        assertEquals(
            DeletionServiceResult.Missing,
            c.status(progress.copy(operationId = UUID.randomUUID().toString()))
        )
        assertEquals(
            DeletionServiceResult.Unavailable,
            c.status(progress.copy(receiptSecret = secret()))
        )
        assertEquals(
            DeletionServiceResult.Unavailable,
            c.cancel(progress.copy(receiptSecret = secret()))
        )
        assertEquals(DeletionServiceResult.Unavailable, c.activate(progress, other.token))
        assertEquals(
            DeletionServiceResult.Unavailable,
            c.status(progress.copy(accountId = AccountId(other.uid)))
        )
        assertEquals(
            DeletionServiceResult.Unavailable,
            c.status(progress.copy(serviceBinding = "wrong-service"))
        )
        assertEquals(DeletionServiceResult.Unavailable, c.start(d.operationId, identity.token))
        assertEquals(DeletionServiceResult.Unavailable, c.resume(d.operationId))
        assertNoDestructiveState(progress)
        assertEquals(AccountDeletionRemoteState.RESERVED, receipt(c.status(progress)).state)
    }

    private suspend fun client(origin: String = endpoint): DeletionServiceRestClient =
        DeletionServiceRestClient(origin, project, allowLoopbackForTests = true).also {
            assertTrue(it.available())
        }

    private fun draft(client: DeletionServiceRestClient, uid: String) =
        AccountDeletionDraft(
            UUID.randomUUID().toString(),
            secret(),
            AccountDeletionRequest(AccountId(uid), 0, 2, serviceBinding = client.binding),
            UUID.randomUUID().toString()
        )

    private fun secret(): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private suspend fun reserve(
        client: DeletionServiceRestClient,
        draft: AccountDeletionDraft,
        proof: String
    ): AccountDeletionProgress {
        val acknowledged = receipt(client.reserve(draft, proof))
        assertEquals(AccountDeletionRemoteState.RESERVED, acknowledged.state)
        assertEquals(1L, acknowledged.version)
        assertEquals(draft.operationId, acknowledged.operationId)
        assertEquals(
            deletionSubjectBinding(
                project,
                instance,
                draft.operationId,
                draft.request.accountId.opaqueValue
            ),
            acknowledged.subjectBinding
        )
        return AccountDeletionProgress(
            operationId = draft.operationId,
            accountId = draft.request.accountId,
            sessionEpoch = draft.request.sessionEpoch,
            profileGeneration = draft.request.profileGeneration,
            stage = AccountDeletionStage.PREPARED,
            serviceBinding = draft.request.serviceBinding,
            receiptSecret = draft.receiptSecret,
            subjectBinding = acknowledged.subjectBinding,
            receiptVersion = acknowledged.version,
            remoteState = acknowledged.state,
            installationId = draft.installationId
        )
    }

    private fun receipt(result: DeletionServiceResult): DeletionServiceReceipt {
        assertTrue(
            "Expected a verified receipt, received $result",
            result is DeletionServiceResult.Receipt
        )
        return (result as DeletionServiceResult.Receipt).value
    }

    private suspend fun awaitComplete(
        client: DeletionServiceRestClient,
        progress: AccountDeletionProgress
    ) {
        withTimeout(20_000) {
            while (true) {
                val current = receipt(client.status(progress))
                when (current.state) {
                    AccountDeletionRemoteState.COMPLETE -> {
                        assertEquals(3L, current.version)
                        break
                    }
                    AccountDeletionRemoteState.PENDING -> yield()
                    else -> error("Activated receipt returned ${current.state}")
                }
            }
        }
    }

    private fun assertNoDestructiveState(progress: AccountDeletionProgress) {
        assertEquals(
            404,
            request(document("accountDeletionJobs/${progress.operationId}"), "GET", token = "owner")
                .code
        )
        assertEquals(
            404,
            request(
                    document("accountDeletionTombstones/${progress.accountId.opaqueValue}"),
                    "GET",
                    token = "owner"
                )
                .code
        )
    }

    private fun document(path: String) =
        "http://$firestoreHost/v1/projects/$project/databases/(default)/documents/$path"

    private fun seedOrphan(uid: String): String {
        val path = "users/$uid/backups/missing-manifest/chunks/unregistered"
        val commit = buildJsonObject {
            put(
                "writes",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "update",
                                buildJsonObject {
                                    put(
                                        "name",
                                        "projects/$project/databases/(default)/documents/$path"
                                    )
                                    put(
                                        "fields",
                                        buildJsonObject {
                                            put(
                                                "unexpected",
                                                buildJsonObject {
                                                    put("stringValue", "synthetic-orphan-payload")
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
        assertEquals(
            200,
            request(
                    "http://$firestoreHost/v1/projects/$project/databases/(default)/documents:commit",
                    "POST",
                    commit,
                    "owner"
                )
                .code
        )
        return document(path)
    }

    private data class Identity(val uid: String, val token: String)

    private fun googleSignIn(subject: String = "kotlin-deletion-${UUID.randomUUID()}"): Identity {
        val google = buildJsonObject {
            put("sub", subject)
            put("email", "$subject@example.test")
            put("email_verified", true)
        }
        val result =
            request(
                "http://$authHost/identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=demo-key",
                "POST",
                buildJsonObject {
                    put(
                        "postBody",
                        "providerId=google.com&id_token=" +
                            URLEncoder.encode(google.toString(), "UTF-8")
                    )
                    put("requestUri", "http://localhost")
                    put("returnSecureToken", true)
                }
            )
        assertEquals(200, result.code)
        return Identity(
            result.body!!.getValue("localId").jsonPrimitive.content,
            result.body.getValue("idToken").jsonPrimitive.content
        )
    }

    private fun authLookup(token: String) =
        request(
            "http://$authHost/identitytoolkit.googleapis.com/v1/accounts:lookup?key=demo-key",
            "POST",
            buildJsonObject { put("idToken", token) }
        )

    /**
     * Test-only intermediary forwards each request once and retains no proof or recovery secret.
     */
    private inner class ForwardingService(
        upstream: String,
        afterResponse: (String, WireResponse) -> WireResponse
    ) : AutoCloseable {
        val activationRequests = AtomicInteger()
        private val executor = Executors.newCachedThreadPool()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                this.executor = this@ForwardingService.executor
                createContext("/") { exchange ->
                    exchange.use {
                        val path = exchange.requestURI.path
                        if (path.endsWith("/activate")) activationRequests.incrementAndGet()
                        val body = exchange.requestBody.bufferedReader().use { it.readText() }
                        val response =
                            afterResponse(
                                path,
                                request(
                                    upstream + path,
                                    exchange.requestMethod,
                                    body
                                        .takeIf { it.isNotEmpty() }
                                        ?.let { Json.parseToJsonElement(it).jsonObject },
                                    exchange.requestHeaders
                                        .getFirst("Authorization")
                                        ?.removePrefix("Bearer "),
                                    exchange.requestHeaders.getFirst("Deletion-Receipt")
                                )
                            )
                        val bytes = response.body.toString().toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.responseHeaders.add("Cache-Control", "no-store")
                        exchange.sendResponseHeaders(response.code, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                }
                start()
            }
        val endpoint = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private data class WireResponse(val code: Int, val body: JsonObject?)

    private fun request(
        url: String,
        method: String,
        body: JsonObject? = null,
        token: String? = null,
        receiptSecret: String? = null
    ): WireResponse {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            receiptSecret?.let { connection.setRequestProperty("Deletion-Receipt", it) }
            body?.let {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = it.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { output -> output.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            return WireResponse(code, text?.let { Json.parseToJsonElement(it) as? JsonObject })
        } finally {
            connection.disconnect()
        }
    }
}
