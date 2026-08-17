package com.example.ironpath.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoBackupInstallationSentinelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sentinelFile = File(context.noBackupFilesDir, "ironpath-installation")

    @Before
    @After
    fun removeSentinel() {
        sentinelFile.delete()
    }

    @Test
    fun writeAtomicallyReplacesTheNoBackupSentinel() = runBlocking {
        val sentinel = NoBackupInstallationSentinel(context)

        assertNull(sentinel.readInstallationId())
        assertEquals(true, sentinel.writeInstallationId("installation-a"))
        assertEquals("installation-a", sentinel.readInstallationId())
        assertEquals(true, sentinel.writeInstallationId("installation-b"))
        assertEquals("installation-b", sentinel.readInstallationId())
    }

    @Test
    fun readRejectsAnExistingEmptyOrWrongTypeSentinel() {
        val sentinel = NoBackupInstallationSentinel(context)
        sentinelFile.createNewFile()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { sentinel.readInstallationId() }
        }
        sentinelFile.delete()
        sentinelFile.mkdir()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { sentinel.readInstallationId() }
        }
        sentinelFile.delete()
    }
}
