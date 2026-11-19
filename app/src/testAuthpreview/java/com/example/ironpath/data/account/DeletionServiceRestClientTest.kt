package com.example.ironpath.data.account

import com.example.ironpath.domain.account.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DeletionServiceRestClientTest {
    private val operation = "b0128305-f28b-4eba-b389-bb4c736f76c9"
    private val secret = "s".repeat(43)
    private val project = "demo-deletion"
    private val instance = "ironpath-deletion-emulator-v2"
    private val uid = "synthetic-owner"
    private val subject
        get() = deletionSubjectBinding(project, instance, operation, uid)

    private var capability =
        """{"protocol":"ironpath-account-deletion-v2","projectId":"$project","serviceInstanceId":"$instance","authoritative":true,"resumable":true}"""
    private var code = 200
    private var reply = wire("RESERVED", 1)

    private data class Request(
        val path: String,
        val token: String?,
        val secret: String?,
        val body: String
    )

    private val requests = mutableListOf<Request>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                requests +=
                    Request(
                        path,
                        exchange.requestHeaders.getFirst("Authorization"),
                        exchange.requestHeaders.getFirst("Deletion-Receipt"),
                        exchange.requestBody.bufferedReader().readText()
                    )
                if (code in 300..399)
                    exchange.responseHeaders.add(
                        "Location",
                        "http://127.0.0.1:$address.port/stolen"
                    )
                val cap = path == "/v2/capabilities"
                val bytes = (if (cap) capability else reply).toByteArray()
                exchange.sendResponseHeaders(if (cap) 200 else code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun wire(state: String, version: Long) =
        """{"protocol":"ironpath-account-deletion-v2","projectId":"$project","serviceInstanceId":"$instance","operationId":"$operation","subjectBinding":"$subject","state":"$state","version":$version}"""

    private fun client(allow: Boolean = true) =
        DeletionServiceRestClient("http://127.0.0.1:${server.address.port}", project, allow)

    private suspend fun draft(c: DeletionServiceRestClient): AccountDeletionDraft {
        assertTrue(c.available())
        return AccountDeletionDraft(
            operation,
            secret,
            AccountDeletionRequest(AccountId(uid), 0, 2, serviceBinding = c.binding),
            "installation"
        )
    }

    private suspend fun progress(c: DeletionServiceRestClient): AccountDeletionProgress {
        val d = draft(c)
        return AccountDeletionProgress(
            operation,
            d.request.accountId,
            0,
            2,
            AccountDeletionStage.PREPARED,
            uid,
            c.binding,
            secret,
            subject,
            1,
            AccountDeletionRemoteState.RESERVED,
            "installation"
        )
    }

    @After fun close() = server.stop(0)

    @Test
    fun `production rejects cleartext and credential URLs before connection`() = runBlocking {
        assertFalse(client(false).available())
        assertTrue(requests.isEmpty())
        listOf(
                "",
                "http://service.example",
                "https://user:pass@service.example",
                "https://service.example/v2",
                "https://service.example?token=x",
                "https://service.example#secret",
                "file:///tmp/test",
                "https://service.example:99999"
            )
            .forEach { assertFalse(it, validDeletionEndpoint(it)) }
        assertTrue(validDeletionEndpoint("https://service.example"))
    }

    @Test
    fun `reserve sends only operation and separate secret and fresh token`() = runBlocking {
        val c = client()
        val d = draft(c)
        assertTrue(c.reserve(d, "fresh-synthetic") is DeletionServiceResult.Receipt)
        assertEquals(
            Request(
                "/v2/reservations",
                "Bearer fresh-synthetic",
                secret,
                """{"operationId":"$operation"}"""
            ),
            requests.last()
        )
        assertFalse(requests.last().body.contains(uid))
        assertFalse(requests.last().body.contains(secret))
        assertTrue(
            requests
                .filter { it.path.endsWith("capabilities") }
                .all { it.token == null && it.secret == null }
        )
    }

    @Test
    fun `status and non destructive cancel never submit authentication token`() = runBlocking {
        val c = client()
        val p = progress(c)
        assertTrue(c.status(p) is DeletionServiceResult.Receipt)
        assertEquals(
            Request("/v2/operations/$operation/status", null, secret, "{}"),
            requests.last()
        )
        reply = wire("CANCELLED_NO_DELETE", 2)
        val result = c.cancel(p) as DeletionServiceResult.Receipt
        assertEquals(AccountDeletionRemoteState.CANCELLED_NO_DELETE, result.value.state)
        assertEquals(
            Request("/v2/operations/$operation/cancel-unactivated", null, secret, "{}"),
            requests.last()
        )
    }

    @Test
    fun `only explicit activate sends new fresh token`() = runBlocking {
        val c = client()
        val p = progress(c)
        code = 202
        reply = wire("PENDING", 2)
        assertTrue(c.activate(p, "new-fresh-synthetic") is DeletionServiceResult.Receipt)
        assertEquals(
            Request(
                "/v2/operations/$operation/activate",
                "Bearer new-fresh-synthetic",
                secret,
                "{}"
            ),
            requests.last()
        )
        requests.clear()
        reply = wire("COMPLETE", 3)
        code = 200
        assertTrue(c.status(p) is DeletionServiceResult.Receipt)
        assertTrue(requests.all { it.token == null })
    }

    @Test
    fun `capability instance remains pinned and project protocol authority required`() =
        runBlocking {
            val c = client()
            val p = progress(c)
            requests.clear()
            capability = capability.replace(instance, "replacement-instance")
            assertFalse(c.available())
            assertEquals(DeletionServiceResult.Unavailable, c.status(p))
            assertTrue(requests.all { it.path == "/v2/capabilities" })
            capability =
                capability.replace("ironpath-account-deletion-v2", "ironpath-account-deletion-v1")
            assertFalse(client().available())
        }

    @Test
    fun `subject operation project service and protocol mismatch cannot mean complete`() =
        runBlocking {
            val c = client()
            val p = progress(c)
            for (pair in
                listOf(
                    operation to "other-operation",
                    subject to "0".repeat(64),
                    project to "other-project",
                    instance to "other-instance",
                    "ironpath-account-deletion-v2" to "other-protocol"
                )) {
                reply = wire("COMPLETE", 3).replace(pair.first, pair.second)
                assertEquals(DeletionServiceResult.Unavailable, c.status(p))
            }
        }

    @Test
    fun `state code version malformed oversized and missing remain distinct`() = runBlocking {
        val c = client()
        val p = progress(c)
        reply = wire("PENDING", 2)
        code = 200
        assertEquals(DeletionServiceResult.Unavailable, c.status(p))
        code = 202
        assertTrue(c.status(p) is DeletionServiceResult.Receipt)
        code = 200
        reply = wire("COMPLETE", 0)
        assertEquals(DeletionServiceResult.Unavailable, c.status(p))
        reply = wire("COMPLETE", 3)
        assertEquals(DeletionServiceResult.Unavailable, c.status(p.copy(receiptVersion = 4)))
        reply = "not json"
        assertEquals(DeletionServiceResult.Unavailable, c.status(p))
        reply = "x".repeat(3000)
        assertEquals(DeletionServiceResult.Unavailable, c.status(p))
        code = 404
        reply = "{}"
        assertEquals(DeletionServiceResult.Missing, c.status(p))
    }

    @Test
    fun `redirect failure never replays activation or leaks secret to destination`() = runBlocking {
        val c = client()
        val p = progress(c)
        for (status in listOf(302, 401, 409, 429, 503)) {
            code = status
            requests.clear()
            assertEquals(DeletionServiceResult.Unavailable, c.activate(p, "synthetic"))
            assertEquals(2, requests.size)
            assertFalse(requests.any { it.path == "/stolen" })
        }
    }

    @Test
    fun `invalid operation secret proof and legacy entry never submit credentials`() = runBlocking {
        val c = client()
        val d = draft(c)
        requests.clear()
        assertEquals(
            DeletionServiceResult.Unavailable,
            c.reserve(d.copy(operationId = "../uid"), "token")
        )
        assertEquals(
            DeletionServiceResult.Unavailable,
            c.reserve(d.copy(receiptSecret = "bad"), "token")
        )
        assertEquals(DeletionServiceResult.Unavailable, c.reserve(d, "secret\r\nHost:evil"))
        assertEquals(DeletionServiceResult.Unavailable, c.reserve(d, ""))
        assertEquals(DeletionServiceResult.Unavailable, c.start(operation, "token"))
        assertEquals(DeletionServiceResult.Unavailable, c.resume(operation))
        assertTrue(requests.isEmpty())
        assertFalse(DeletionReauthentication.Authenticated(secret).toString().contains(secret))
    }

    @Test
    fun `length prefixed subject binding separates unicode and component boundaries`() {
        assertNotEquals(
            deletionSubjectBinding("ab", "c", operation, uid),
            deletionSubjectBinding("a", "bc", operation, uid)
        )
        assertEquals(64, deletionSubjectBinding(project, instance, operation, "用户").length)
        assertNotEquals(subject, deletionSubjectBinding(project, instance, operation, "foreign"))
    }
}
