package com.example.ironpath.data.account

import android.content.Context
import android.content.MutableContextWrapper
import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.ironpath.domain.account.AccountCredentialActivityHost
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal sealed interface GoogleCredentialRequestResult {
    data class Selected(val credential: Credential) : GoogleCredentialRequestResult

    data object Cancelled : GoogleCredentialRequestResult

    data object Failed : GoogleCredentialRequestResult
}

/** One-request broker that keeps Activity references weak and survives Activity recreation. */
@Singleton
class GoogleCredentialActivityBroker
@Inject
constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val runtime: AuthPreviewFirebaseRuntime,
) : AccountCredentialActivityHost {
    private val lock = Any()
    private val activityContext = MutableContextWrapper(applicationContext)
    private val credentialManager = CredentialManager.create(activityContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requestFence = AccountCredentialRequestFence()
    private var activity = WeakReference<ComponentActivity>(null)
    private var pending: PendingRequest? = null

    override fun attach(activity: ComponentActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val replacedRequest =
            synchronized(lock) {
                val currentActivity = this.activity.get()
                val replacingHost = currentActivity != null && currentActivity !== activity
                val request =
                    if (replacingHost) {
                        pending?.also {
                            pending = null
                            requestFence.replaceHost(it.requestId)
                            it.job?.cancel()
                            it.job = null
                        }
                    } else {
                        null
                    }
                this.activity = WeakReference(activity)
                activityContext.baseContext = activity
                if (!replacingHost) launchPendingLocked()
                request
            }
        replacedRequest?.let {
            resumeSafely(it.continuation, GoogleCredentialRequestResult.Cancelled)
        }
    }

    override fun detach(activity: ComponentActivity) {
        val detachedRequest =
            synchronized(lock) {
                if (this.activity.get() !== activity) return
                val request = pending
                pending = null
                request?.let {
                    requestFence.detach(it.requestId)
                    it.job?.cancel()
                    it.job = null
                }
                this.activity.clear()
                activityContext.baseContext = applicationContext
                request
            }
        detachedRequest?.let {
            resumeSafely(it.continuation, GoogleCredentialRequestResult.Cancelled)
        }
    }

    internal suspend fun request(requestId: Long): GoogleCredentialRequestResult {
        val webClientId = runtime.googleWebClientId
        if (!runtime.configured || webClientId.isBlank())
            return GoogleCredentialRequestResult.Failed
        return suspendCancellableCoroutine { continuation ->
            synchronized(lock) {
                if (!continuation.isActive) return@synchronized
                if (pending != null || !requestFence.begin(requestId)) {
                    resumeSafely(continuation, GoogleCredentialRequestResult.Failed)
                    return@synchronized
                }
                pending = PendingRequest(requestId, webClientId, continuation)
                launchPendingLocked()
            }
            continuation.invokeOnCancellation { cancel(requestId) }
        }
    }

    fun cancel(requestId: Long) {
        val request =
            synchronized(lock) {
                pending
                    ?.takeIf { it.requestId == requestId }
                    ?.also {
                        pending = null
                        requestFence.cancel(requestId)
                    }
            } ?: return
        request.job?.cancel()
        resumeSafely(request.continuation, GoogleCredentialRequestResult.Cancelled)
    }

    /** Provider cleanup is best effort; Firebase sign-out is authoritative for the app session. */
    suspend fun clearProviderCredentialState() {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (_: CancellationException) {
            // Firebase has already signed out; provider cleanup cannot undo or fail that commit.
        } catch (_: Exception) {
            // A provider cleanup failure must not restore a cleared Firebase session.
        }
    }

    private fun launchPendingLocked() {
        val request = pending ?: return
        val activeActivity =
            activity.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        if (request.job != null) return

        val attempt = requestFence.beginAttempt(request.requestId) ?: return
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val result =
                    try {
                        val option = GetSignInWithGoogleOption.Builder(request.webClientId).build()
                        val credentialRequest =
                            GetCredentialRequest.Builder().addCredentialOption(option).build()
                        GoogleCredentialRequestResult.Selected(
                            credentialManager
                                .getCredential(activityContext, credentialRequest)
                                .credential,
                        )
                    } catch (_: GetCredentialCancellationException) {
                        GoogleCredentialRequestResult.Cancelled
                    } catch (_: NoCredentialException) {
                        GoogleCredentialRequestResult.Failed
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: GetCredentialException) {
                        GoogleCredentialRequestResult.Failed
                    } catch (_: Exception) {
                        GoogleCredentialRequestResult.Failed
                    }
                complete(attempt, result)
            }
        request.job = job
        // Keep a strong reference only for this short launch boundary; the manager receives the
        // mutable wrapper, which is rebound on Activity recreation and reset on detach.
        check(activity.get() === activeActivity)
        job.start()
    }

    private fun complete(
        attempt: AccountCredentialRequestFence.Attempt,
        result: GoogleCredentialRequestResult,
    ) {
        val request =
            synchronized(lock) {
                pending
                    ?.takeIf { it.requestId == attempt.requestId && requestFence.complete(attempt) }
                    ?.also { pending = null }
            } ?: return
        resumeSafely(request.continuation, result)
    }

    private fun resumeSafely(
        continuation: CancellableContinuation<GoogleCredentialRequestResult>,
        result: GoogleCredentialRequestResult,
    ) {
        try {
            continuation.resume(result)
        } catch (_: IllegalStateException) {
            // The caller may have cancelled while the provider result was being delivered.
        }
    }

    private class PendingRequest(
        val requestId: Long,
        val webClientId: String,
        val continuation: CancellableContinuation<GoogleCredentialRequestResult>,
        var job: Job? = null,
    )
}
