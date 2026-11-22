package com.example.ironpath.data.ai

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteHttpCancellationTest {
    @Test
    fun `cancelling active HTTP closes socket and finishes without waiting for read timeout`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val requestReceived = CountDownLatch(1)
            val peerClosed = CountDownLatch(1)
            val clientFinished = CountDownLatch(1)
            val fixture = Executors.newSingleThreadExecutor()
            val accepted =
                fixture.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        var remaining = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                remaining = line.substringAfter(':').trim().toInt()
                            }
                            if (line.isEmpty()) break
                        }
                        repeat(remaining) { reader.read() }
                        requestReceived.countDown()
                        if (reader.read() == -1) peerClosed.countDown()
                    }
                }
            try {
                runBlocking {
                    val request =
                        launch(Dispatchers.IO) {
                            try {
                                OkHttpRemoteHttpClient()
                                    .post(
                                        "http://127.0.0.1:${server.localPort}/plan",
                                        mapOf("Content-Type" to "application/json"),
                                        "{}",
                                    )
                            } finally {
                                clientFinished.countDown()
                            }
                        }
                    try {
                        assertTrue(
                            "fixture received request",
                            requestReceived.await(5, TimeUnit.SECONDS)
                        )
                        request.cancel()
                        assertTrue(
                            "cancellation must finish promptly",
                            clientFinished.await(2, TimeUnit.SECONDS)
                        )
                        assertTrue(
                            "active socket must close",
                            peerClosed.await(2, TimeUnit.SECONDS)
                        )
                    } finally {
                        request.cancel()
                        server.close()
                        accepted.cancel(true)
                    }
                }
            } finally {
                fixture.shutdownNow()
            }
        }
    }
}
