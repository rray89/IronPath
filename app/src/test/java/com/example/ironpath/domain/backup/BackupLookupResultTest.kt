package com.example.ironpath.domain.backup

import org.junit.Assert.assertNotEquals
import org.junit.Test

class BackupLookupResultTest {
    @Test
    fun lookupFailuresAndDisabledFoundationRemainDistinctFromKnownAbsence() {
        BackupFailureReason.entries.forEach { reason ->
            assertNotEquals(BackupLookupResult.Absent, BackupLookupResult.Failed(reason))
        }
        assertNotEquals(BackupLookupResult.Absent, BackupLookupResult.Unavailable)
    }
}
