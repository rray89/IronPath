package com.example.ironpath.domain.account

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountStateResolverTest {
    @Test
    fun resolve_returnsLocalOnlyWithoutAnAuthenticatedSession() {
        assertEquals(
            AccountState.LocalOnly,
            AccountStateResolver.resolve(authenticatedUid = null, localOwnerUid = "owner-a"),
        )
    }

    @Test
    fun resolve_returnsSignedInOnlyWhenTheSessionOwnsTheLocalProfile() {
        assertEquals(
            AccountState.SignedIn(AccountId("owner-a")),
            AccountStateResolver.resolve(authenticatedUid = "owner-a", localOwnerUid = "owner-a"),
        )
    }

    @Test
    fun resolve_requiresADataChoiceForUnclaimedOrDifferentlyOwnedData() {
        assertEquals(
            AccountState.AwaitingDataChoice(
                AccountId("owner-a"),
                DataChoiceContext(
                    LocalOwnership.Unclaimed,
                    localDataIsEmpty = false,
                    remoteSnapshot = RemoteSnapshotPresence.Absent,
                    conflict = null,
                ),
            ),
            AccountStateResolver.resolve(authenticatedUid = "owner-a", localOwnerUid = null),
        )
        assertEquals(
            AccountState.AwaitingDataChoice(
                AccountId("owner-a"),
                DataChoiceContext(
                    LocalOwnership.Account(AccountId("owner-b")),
                    localDataIsEmpty = false,
                    remoteSnapshot = RemoteSnapshotPresence.Absent,
                    conflict = null,
                ),
            ),
            AccountStateResolver.resolve(authenticatedUid = "owner-a", localOwnerUid = "owner-b"),
        )
    }

    @Test
    fun resolve_requiresADataChoiceWhenSameOwnerHasAnUnresolvedRemoteSnapshot() {
        val remote = RemoteSnapshotPresence.Complete("backup-b", 2, "other-installation")
        val conflict =
            PersistedConflictContext(
                lastObservedRemoteBackupId = "backup-a",
                lastObservedRemoteGeneration = 1,
                lastObservedRemoteDigest = "digest-a",
                lastObservedSourceInstallationId = "other-installation",
                currentInstallationId = "current-installation",
                localChangeRevision = 4,
                lastCompleteLocalRevision = 3,
            )

        assertEquals(
            AccountState.AwaitingDataChoice(
                AccountId("owner-a"),
                DataChoiceContext(
                    LocalOwnership.Account(AccountId("owner-a")),
                    localDataIsEmpty = false,
                    remoteSnapshot = remote,
                    conflict = conflict,
                ),
            ),
            AccountStateResolver.resolve(
                authenticatedUid = "owner-a",
                localOwnerUid = "owner-a",
                remoteSnapshot = remote,
                conflict = conflict,
            ),
        )
    }
}
