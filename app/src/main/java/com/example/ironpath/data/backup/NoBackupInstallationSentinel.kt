package com.example.ironpath.data.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class NoBackupInstallationSentinel @Inject constructor(@ApplicationContext context: Context) :
    InstallationSentinel {
    private val directory = context.noBackupFilesDir
    private val sentinel = File(directory, SENTINEL_FILE_NAME)

    override suspend fun readInstallationId(): String? =
        withContext(Dispatchers.IO) {
            if (!sentinel.exists()) return@withContext null
            check(sentinel.isFile) { "Installation sentinel is not a regular file" }
            sentinel.readText().trim().also { value ->
                check(value.isNotEmpty()) { "Installation sentinel is empty" }
            }
        }

    override suspend fun writeInstallationId(installationId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (installationId.isBlank()) return@withContext false
            var temporary: File? = null
            try {
                check(directory.isDirectory || directory.mkdirs())
                temporary = File.createTempFile("$SENTINEL_FILE_NAME-", ".tmp", directory)
                FileOutputStream(temporary).use { output ->
                    output.write(installationId.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                Files.move(
                    temporary.toPath(),
                    sentinel.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                sentinel.readText() == installationId
            } catch (_: Exception) {
                false
            } finally {
                temporary?.delete()
            }
        }

    private companion object {
        const val SENTINEL_FILE_NAME = "ironpath-installation"
    }
}
