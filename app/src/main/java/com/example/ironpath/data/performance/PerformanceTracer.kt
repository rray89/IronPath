package com.example.ironpath.data.performance

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Small injectable boundary around platform tracing so JVM tests never call Android stubs. */
@Singleton
class PerformanceTracer @Inject constructor() {
    private val nextCookie = AtomicInteger()

    fun beginAsyncSection(name: String): Int {
        val cookie = nextCookie.incrementAndGet()
        Trace.beginAsyncSection(name, cookie)
        return cookie
    }

    fun endAsyncSection(name: String, cookie: Int) = Trace.endAsyncSection(name, cookie)
}
