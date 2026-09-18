package com.example.ironpath.domain.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStateResolverTest {
    @Test
    fun resolve_keepsConfirmedEmptyLineageSignedInButUnobservedOrDirtyEmptyRequiresChoice() {
        val remote = RemoteSnapshotPresence.Complete("empty-backup", 2, "installation")
        val lineage =
            PersistedConflictContext(
                "empty-backup",
                2,
                "digest",
                "installation",
                "installation",
                5,
                5
            )
        fun resolve(context: PersistedConflictContext?) =
            AccountStateResolver.resolve("owner-a", "owner-a", true, remote, context)
        assertEquals(AccountState.SignedIn(AccountId("owner-a")), resolve(lineage))
        assertTrue(resolve(null) is AccountState.AwaitingDataChoice)
        assertTrue(
            resolve(lineage.copy(lastObservedRemoteGeneration = 1))
                is AccountState.AwaitingDataChoice
        )
        assertTrue(
            resolve(lineage.copy(lastObservedRemoteBackupId = "old"))
                is AccountState.AwaitingDataChoice
        )
        assertTrue(
            resolve(lineage.copy(localChangeRevision = 6)) is AccountState.AwaitingDataChoice
        )
    }

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
