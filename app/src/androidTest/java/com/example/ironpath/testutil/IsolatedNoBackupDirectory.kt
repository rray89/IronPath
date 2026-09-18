package com.example.ironpath.testutil

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.rules.ExternalResource

/** Real file persistence without ever reading or writing the installed app's account files. */
class IsolatedNoBackupDirectory : ExternalResource() {
    lateinit var context: Context
        private set

    private lateinit var directory: File

    override fun before() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        directory =
            Files.createTempDirectory(base.cacheDir.toPath(), "ironpath-account-test-").toFile()
        context =
            object : ContextWrapper(base) {
                override fun getNoBackupFilesDir() = directory
            }
    }

    override fun after() {
        check(directory.deleteRecursively())
    }
}
