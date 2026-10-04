package com.example.ironpath.data.ai

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteHttpClientTest {
    @Test
    fun `production client has full call deadline and disables redirects and retries`() {
        val client = remoteOkHttpClient()
        assertEquals(60_000, client.callTimeoutMillis)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.fastFallback)
    }

    @Test
    fun `success reads at most the allowed bytes and redacts response representation`() =
        runBlocking {
            val body = "x".repeat(256 * 1024)
            LocalHttpFixture { socket -> socket.respond(200, body) }
                .use { fixture ->
                    val response = OkHttpRemoteHttpClient().post(fixture.url, emptyMap(), "{}")
                    assertEquals(body, response.body)
                    assertEquals(1, fixture.requests.get())
                    assertFalse(response.toString().contains(body))
                }
        }

    @Test
    fun `oversized response fails without payload or cause disclosure`() = runBlocking {
        LocalHttpFixture { socket -> socket.respond(200, "x".repeat(256 * 1024 + 1)) }
            .use { fixture ->
                try {
                    OkHttpRemoteHttpClient().post(fixture.url, emptyMap(), "{}")
                    throw AssertionError("Oversized body must fail")
                } catch (failure: IOException) {
                    assertEquals("The remote request could not finish.", failure.message)
                    // Coroutine stack recovery may copy the sanitized exception as its cause.
                    generateSequence<Throwable>(failure) { it.cause }
                        .forEach {
                            assertEquals("The remote request could not finish.", it.message)
                        }
                }
                assertEquals(1, fixture.requests.get())
            }
    }

    @Test
    fun `failure bodies redirects and Retry-After never trigger another request`() = runBlocking {
        listOf(307, 401, 429, 500, 503).forEach { status ->
            LocalHttpFixture { socket ->
                    socket.respond(
                        status,
                        "secret failure response",
                        "Retry-After: 0\r\nLocation: /other\r\n"
                    )
                }
                .use { fixture ->
                    val response =
                        OkHttpRemoteHttpClient()
                            .post(fixture.url, mapOf("Authorization" to "Bearer secret-key"), "{}")
                    assertEquals(status, response.statusCode)
                    assertEquals("", response.body)
                    assertEquals("No paid retry or redirect for $status", 1, fixture.requests.get())
                }
        }
    }

    @Test
    fun `cancellation while reading response closes active socket promptly`() {
        val readingBody = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        LocalHttpFixture { socket ->
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\nx".toByteArray())
                    flush()
                }
                readingBody.countDown()
                if (socket.getInputStream().read() == -1) peerClosed.countDown()
            }
            .use { fixture ->
                runBlocking {
                    val request =
                        launch(Dispatchers.IO) {
                            try {
                                OkHttpRemoteHttpClient().post(fixture.url, emptyMap(), "{}")
                            } finally {
                                finished.countDown()
                            }
                        }
                    try {
                        assertTrue(readingBody.await(5, TimeUnit.SECONDS))
                        request.cancel()
                        assertTrue(
                            "caller stops after cancellation",
                            finished.await(2, TimeUnit.SECONDS)
                        )
                        assertTrue(
                            "body IO stops after cancellation",
                            peerClosed.await(2, TimeUnit.SECONDS)
                        )
                    } finally {
                        request.cancel()
                        fixture.close()
                    }
                }
            }
    }

    @Test
    fun `full call timeout closes delayed response and reports sanitized failure`() = runBlocking {
        val peerClosed = CountDownLatch(1)
        LocalHttpFixture { socket ->
                if (socket.getInputStream().read() == -1) peerClosed.countDown()
            }
            .use { fixture ->
                try {
                    OkHttpRemoteHttpClient(remoteOkHttpClient(timeoutMillis = 1000))
                        .post(fixture.url, emptyMap(), "{}")
                    throw AssertionError("Stalled response must time out")
                } catch (failure: IOException) {
                    assertEquals("The remote request could not finish.", failure.message)
                    // Coroutine stack recovery may copy the sanitized exception as its cause.
                    generateSequence<Throwable>(failure) { it.cause }
                        .forEach {
                            assertEquals("The remote request could not finish.", it.message)
                        }
                }
                assertTrue("timeout closes IO", peerClosed.await(2, TimeUnit.SECONDS))
                assertEquals(1, fixture.requests.get())
            }
    }
}

/** A loopback-only fixture that accepts follow-ups so tests detect accidental retries. */
private class LocalHttpFixture(private val reply: (Socket) -> Unit) : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newSingleThreadExecutor()
    private val active = AtomicReference<Socket?>()
    val requests = AtomicInteger()
    val url = "http://127.0.0.1:${server.localPort}/plan"

    init {
        executor.submit {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        active.set(socket)
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream()
                        val headers = StringBuilder()
                        while (!headers.endsWith("\r\n\r\n")) {
                            val byte = input.read()
                            check(byte >= 0)
                            headers.append(byte.toChar())
                        }
                        val length =
                            headers
                                .lines()
                                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                                ?.substringAfter(':')
                                ?.trim()
                                ?.toInt() ?: 0
                        repeat(length) { check(input.read() >= 0) }
                        requests.incrementAndGet()
                        reply(socket)
                        active.set(null)
                    }
                }
            } catch (_: IOException) {
                // Closing the fixture or client aborting an oversized reply ends its socket.
            }
        }
    }

    override fun close() {
        server.close()
        active.getAndSet(null)?.close()
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
}

private fun Socket.respond(status: Int, body: String, extraHeaders: String = "") {
    val bytes = body.toByteArray()
    getOutputStream().apply {
        write(
            "HTTP/1.1 $status Fixture\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extraHeaders\r\n"
                .toByteArray()
        )
        write(bytes)
        flush()
    }
}
