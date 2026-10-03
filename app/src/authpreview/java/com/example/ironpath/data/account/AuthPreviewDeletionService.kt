package com.example.ironpath.data.account

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthPreviewDeletionService @Inject constructor(runtime: AuthPreviewFirebaseRuntime) :
    AccountDeletionService {
    private val client =
        DeletionServiceRestClient(
            runtime.deletionServiceEndpoint,
            runtime.auth?.app?.options?.projectId.orEmpty()
        )
    override val binding
        get() = client.binding

    override suspend fun available() = client.available()

    override suspend fun start(operationId: String, token: String) =
        client.start(operationId, token)

    override suspend fun resume(operationId: String) = client.resume(operationId)
}
