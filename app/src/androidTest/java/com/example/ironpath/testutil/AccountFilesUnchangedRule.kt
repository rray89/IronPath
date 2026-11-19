package com.example.ironpath.testutil

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ironpath.data.account.DeterministicAccountSessionAdapter
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Read-only proof that Hilt startup and journeys leave the installed app's files alone. */
class AccountFilesUnchangedRule : TestWatcher() {
    private lateinit var before: List<String>

    override fun starting(description: Description) {
        before = snapshot()
    }

    override fun finished(description: Description) {
        assertEquals("Instrumentation changed installed account files", before, snapshot())
    }

    private fun snapshot(): List<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return listOf(
                "ironpath-installation",
                DeterministicAccountSessionAdapter.SESSION_FILE_NAME,
                "ironpath-debug-remote"
            )
            .map { name ->
                val file = File(context.noBackupFilesDir, name)
                when {
                    !file.exists() -> "absent"
                    file.isDirectory ->
                        file
                            .walkTopDown()
                            .filter { it.isFile }
                            .sortedBy { it.relativeTo(file).path }
                            .joinToString("|") { child ->
                                child.relativeTo(file).path +
                                    ":" +
                                    MessageDigest.getInstance("SHA-256")
                                        .digest(child.readBytes())
                                        .joinToString("") { "%02x".format(it) }
                            }
                    else ->
                        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(
                            ""
                        ) {
                            "%02x".format(it)
                        }
                }
            }
    }
}
