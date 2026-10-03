package com.example.ironpath.data.account

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthPreviewDeletionService internal constructor(client: AccountDeletionService) :
    AccountDeletionService by client {
    @Inject
    constructor(
        runtime: AuthPreviewFirebaseRuntime
    ) : this(
        DeletionServiceRestClient(
            runtime.deletionServiceEndpoint,
            runtime.auth?.app?.options?.projectId.orEmpty(),
        )
    )
}
