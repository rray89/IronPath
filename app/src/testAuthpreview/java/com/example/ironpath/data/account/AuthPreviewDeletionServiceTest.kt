package com.example.ironpath.data.account

import com.example.ironpath.domain.account.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AuthPreviewDeletionServiceTest {
    private val progress =
        AccountDeletionProgress(
            "b0128305-f28b-4eba-b389-bb4c736f76c9",
            AccountId("wrapper-owner"),
            1,
            2,
            AccountDeletionStage.PREPARED,
            serviceBinding = "verified-binding",
            receiptSecret = "s".repeat(43),
            subjectBinding = "a".repeat(64),
            receiptVersion = 1,
            remoteState = AccountDeletionRemoteState.RESERVED,
            installationId = "wrapper-installation",
        )
    private val draft =
        AccountDeletionDraft(
            progress.operationId,
            checkNotNull(progress.receiptSecret),
            AccountDeletionRequest(
                progress.accountId,
                1,
                2,
                serviceBinding = progress.serviceBinding
            ),
            "wrapper-installation"
        )

    @Test
    fun `reservation reaches application service binding with exact draft and fresh proof`() =
        runBlocking {
            val client = RecordingService()
            val service: AccountDeletionService = AuthPreviewDeletionService(client)
            assertEquals(client.response, service.reserve(draft, "fresh-reservation-proof"))
            assertEquals(draft to "fresh-reservation-proof", client.reserved)
        }

    @Test
    fun `status reaches application service binding without requesting authentication`() =
        runBlocking {
            val client = RecordingService()
            val service: AccountDeletionService = AuthPreviewDeletionService(client)
            assertEquals(client.response, service.status(progress))
            assertEquals(progress, client.observed)
        }

    @Test
    fun `explicit activation reaches application service binding with exact fresh proof`() =
        runBlocking {
            val client = RecordingService()
            val service: AccountDeletionService = AuthPreviewDeletionService(client)
            assertEquals(client.response, service.activate(progress, "fresh-activation-proof"))
            assertEquals(progress to "fresh-activation-proof", client.activated)
        }

    @Test
    fun `unactivated cancellation reaches application service binding without proof`() =
        runBlocking {
            val client = RecordingService()
            val service: AccountDeletionService = AuthPreviewDeletionService(client)
            assertEquals(client.response, service.cancel(progress))
            assertEquals(progress, client.cancelled)
        }

    @Test
    fun `verified binding remains dynamic after capabilities are checked`() = runBlocking {
        val client = RecordingService()
        val service: AccountDeletionService = AuthPreviewDeletionService(client)
        assertNull(service.binding)
        assertTrue(service.available())
        assertEquals("verified-binding", service.binding)
        client.binding = "changed-binding"
        assertEquals("changed-binding", service.binding)
    }

    private class RecordingService : AccountDeletionService {
        override var binding: String? = null
        val response =
            DeletionServiceResult.Receipt(
                DeletionServiceReceipt(
                    "b0128305-f28b-4eba-b389-bb4c736f76c9",
                    "a".repeat(64),
                    AccountDeletionRemoteState.RESERVED,
                    1
                )
            )
        var reserved: Pair<AccountDeletionDraft, String>? = null
        var observed: AccountDeletionProgress? = null
        var activated: Pair<AccountDeletionProgress, String>? = null
        var cancelled: AccountDeletionProgress? = null

        override suspend fun available(): Boolean {
            binding = "verified-binding"
            return true
        }

        override suspend fun reserve(
            draft: AccountDeletionDraft,
            token: String
        ): DeletionServiceResult {
            reserved = draft to token
            return response
        }

        override suspend fun status(progress: AccountDeletionProgress): DeletionServiceResult {
            observed = progress
            return response
        }

        override suspend fun activate(
            progress: AccountDeletionProgress,
            token: String
        ): DeletionServiceResult {
            activated = progress to token
            return response
        }

        override suspend fun cancel(progress: AccountDeletionProgress): DeletionServiceResult {
            cancelled = progress
            return response
        }

        override suspend fun start(operationId: String, token: String) =
            DeletionServiceResult.Unavailable

        override suspend fun resume(operationId: String) = DeletionServiceResult.Unavailable
    }
}
