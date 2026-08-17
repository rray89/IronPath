package com.example.ironpath.data.backup

fun interface BackupChangeTracker {
    suspend fun markIncludedDataChanged()
}
