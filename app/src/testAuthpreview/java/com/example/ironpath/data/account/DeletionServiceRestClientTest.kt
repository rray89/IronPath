package com.example.ironpath.data.account

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DeletionServiceRestClientTest {
    private val operation = "b0128305-f28b-4eba-b389-bb4c736f76c9"
    private var capability =
        """{"protocol":"ironpath-account-deletion-v1","projectId":"demo-deletion","authoritative":true,"resumable":true}"""
    private var code = 200
    private var receipt = """{"operationId":"$operation","state":"COMPLETE"}"""
    private val requests = mutableListOf<Triple<String, String?, String>>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                requests +=
                    Triple(
                        path,
                        exchange.requestHeaders.getFirst("Authorization"),
                        exchange.requestBody.bufferedReader().readText()
                    )
                val capabilityRequest = path == "/v1/capabilities"
                if (code in 300..399)
                    exchange.responseHeaders.add(
                        "Location",
                        "http://127.0.0.1:$address.port/stolen"
                    )
                val bytes = (if (capabilityRequest) capability else receipt).toByteArray()
                exchange.sendResponseHeaders(
                    if (capabilityRequest) 200 else code,
                    bytes.size.toLong()
                )
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun client(project: String = "demo-deletion", allow: Boolean = true) =
        DeletionServiceRestClient("http://127.0.0.1:${server.address.port}", project, allow)

    @After fun close() = server.stop(0)

    @Test
    fun `production client rejects cleartext endpoint before any connection`() = runBlocking {
        assertFalse(client(allow = false).available())
        assertEquals(
            DeletionServiceResult.Unavailable,
            client(allow = false).start(operation, "secret")
        )
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `endpoint configuration rejects secrets paths fragments and deceptive schemes`() {
        listOf(
                "",
                "http://service.example",
                "https://user:pass@service.example",
                "https://service.example/v1",
                "https://service.example?token=x",
                "https://service.example#secret",
                "file:///tmp/test",
                "https://service.example:99999"
            )
            .forEach { assertFalse(it, validDeletionEndpoint(it)) }
        assertTrue(validDeletionEndpoint("https://service.example"))
        assertTrue(validDeletionEndpoint("https://service.example:443"))
    }

    @Test
    fun `capability must be authoritative resumable and belong to configured Firebase project`() =
        runBlocking {
            assertTrue(client().available())
            assertFalse(client("wrong-project").available())
            capability = capability.replace("\"authoritative\":true", "\"authoritative\":false")
            assertFalse(client().available())
            assertEquals(DeletionServiceResult.Unavailable, client().start(operation, "secret"))
            assertTrue(requests.all { it.first == "/v1/capabilities" && it.second == null })
        }

    @Test
    fun `start submits only capability and bearer token never user supplied UID`() = runBlocking {
        assertEquals(DeletionServiceResult.Complete, client().start(operation, "synthetic-token"))
        assertEquals(
            Triple("/v1/deletions", "Bearer synthetic-token", """{"operationId":"$operation"}"""),
            requests.last()
        )
        assertEquals(null, requests.first().second)
    }

    @Test
    fun `resume requires no Auth identity and only accepts bound complete receipt`() = runBlocking {
        assertEquals(DeletionServiceResult.Complete, client().resume(operation))
        assertEquals(null, requests.last().second)
        assertEquals("/v1/deletions/$operation/resume", requests.last().first)
        receipt = receipt.replace(operation, "different")
        assertEquals(DeletionServiceResult.Unavailable, client().resume(operation))
    }

    @Test
    fun `pending missing malformed oversized and mismatched state never mean completed`() =
        runBlocking {
            code = 202
            receipt = """{"operationId":"$operation","state":"PENDING"}"""
            assertEquals(DeletionServiceResult.Pending, client().resume(operation))
            code = 404
            assertEquals(DeletionServiceResult.Missing, client().resume(operation))
            assertEquals(DeletionServiceResult.Unavailable, client().start(operation, "token"))
            code = 200
            assertEquals(DeletionServiceResult.Unavailable, client().resume(operation))
            receipt = "not json"
            assertEquals(DeletionServiceResult.Unavailable, client().resume(operation))
            receipt = "x".repeat(3000)
            assertEquals(DeletionServiceResult.Unavailable, client().resume(operation))
        }

    @Test
    fun `redirect auth failure and service failure never trigger implicit resubmit`() =
        runBlocking {
            for (status in listOf(302, 401, 409, 503)) {
                code = status
                requests.clear()
                assertEquals(
                    DeletionServiceResult.Unavailable,
                    client().start(operation, "synthetic")
                )
                assertEquals(2, requests.size)
                assertFalse(requests.any { it.first == "/stolen" })
            }
        }

    @Test
    fun `invalid operation token and configuration do not send credentials`() = runBlocking {
        assertEquals(DeletionServiceResult.Unavailable, client().start("../owner", "token"))
        assertEquals(
            DeletionServiceResult.Unavailable,
            client().start(operation, "secret\r\nHost:evil")
        )
        assertEquals(DeletionServiceResult.Unavailable, client().start(operation, ""))
        assertTrue(requests.isEmpty())
        assertNotEquals(client().binding, client("another-project").binding)
        assertFalse(DeletionReauthentication.Authenticated("secret").toString().contains("secret"))
    }
}
