package com.example.ironpath.data.ai

import com.example.ironpath.domain.planner.RemoteFailureKind
import com.example.ironpath.domain.planner.RemotePlanningTransportResult
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException

internal suspend fun requestRemotePlan(
    request: suspend () -> RemoteHttpResponse,
    parse: (String) -> RemotePlanningTransportResult.Success?
): RemotePlanningTransportResult {
    val response =
        try {
            request()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OversizedPlanResponse) {
            return RemotePlanningTransportResult.Failure(RemoteFailureKind.RESPONSE_FORMAT)
        } catch (_: InterruptedIOException) {
            return RemotePlanningTransportResult.Failure(RemoteFailureKind.TIMEOUT)
        } catch (_: IOException) {
            return RemotePlanningTransportResult.Failure(RemoteFailureKind.NETWORK)
        } catch (_: Exception) {
            return RemotePlanningTransportResult.Failure(RemoteFailureKind.REQUEST)
        }
    val failure =
        when (response.statusCode) {
            in 200..299 -> null
            401 -> RemoteFailureKind.AUTHENTICATION
            403 -> RemoteFailureKind.ACCESS
            408,
            504 -> RemoteFailureKind.TIMEOUT
            429 -> RemoteFailureKind.RATE_LIMIT
            in 500..599 -> RemoteFailureKind.SERVICE
            else -> RemoteFailureKind.REQUEST
        }
    if (failure != null) return RemotePlanningTransportResult.Failure(failure)
    val result =
        try {
            parse(response.body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    return result ?: RemotePlanningTransportResult.Failure(RemoteFailureKind.RESPONSE_FORMAT)
}
