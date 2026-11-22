package com.example.ironpath.data.ai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

data class RemoteHttpResponse(val statusCode: Int, val body: String) {
    override fun toString() = "RemoteHttpResponse(statusCode=$statusCode, body=<redacted>)"
}

interface RemoteHttpClient {
    suspend fun post(url: String, headers: Map<String, String>, body: String): RemoteHttpResponse
}

@Singleton
class OkHttpRemoteHttpClient internal constructor(private val client: OkHttpClient) :
    RemoteHttpClient {
    @Inject constructor() : this(remoteOkHttpClient())

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String
    ): RemoteHttpResponse = suspendCancellableCoroutine { continuation ->
        val request =
            Request.Builder()
                .url(url)
                .post(OneShotJsonBody(body))
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(remoteHttpFailure())
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result =
                            response.use {
                                // Cancel before closing: never consume or log a provider's failure
                                // body.
                                if (!it.isSuccessful) {
                                    call.cancel()
                                    RemoteHttpResponse(it.code, "")
                                } else {
                                    RemoteHttpResponse(
                                        it.code,
                                        it.body.byteStream().use(::readBoundedUtf8)
                                    )
                                }
                            }
                        continuation.resume(result)
                    } catch (_: Exception) {
                        call.cancel()
                        continuation.resumeWithException(remoteHttpFailure())
                    }
                }
            }
        )
    }
}

internal fun remoteOkHttpClient(timeoutMillis: Long = 60_000): OkHttpClient =
    OkHttpClient.Builder()
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .fastFallback(false)
        .build()

/** Also prevents HTTP follow-ups such as Retry-After: 0 from replaying a paid request. */
private class OneShotJsonBody(body: String) : RequestBody() {
    private val bytes = body.toByteArray(Charsets.UTF_8)

    override fun contentType() = "application/json; charset=utf-8".toMediaType()

    override fun contentLength() = bytes.size.toLong()

    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }
}

private fun readBoundedUtf8(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (total <= MAX_RESPONSE_BYTES) {
        val read = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - total))
        if (read < 0) break
        output.write(buffer, 0, read)
        total += read
    }
    if (total > MAX_RESPONSE_BYTES) throw remoteHttpFailure()
    return output.toString(Charsets.UTF_8.name())
}

private fun remoteHttpFailure() = IOException("The remote request could not finish.")

private const val MAX_RESPONSE_BYTES = 256 * 1024
