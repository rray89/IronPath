package com.example.ironpath.testutil

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ironpath.data.local.IronPathDatabase
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class FileBackedRoomTestDatabaseRule : TestWatcher() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<IronPathDatabase>()
    private lateinit var databaseName: String

    fun open(): IronPathDatabase =
        Room.databaseBuilder(context, IronPathDatabase::class.java, databaseName)
            .addMigrations(IronPathDatabase.MIGRATION_1_2, IronPathDatabase.MIGRATION_2_3)
            .build()
            .also(opened::add)

    override fun starting(description: Description) {
        val safeMethod = description.methodName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        databaseName = "${description.testClass.simpleName}-$safeMethod.db"
        context.deleteDatabase(databaseName)
    }

    override fun finished(description: Description) {
        opened.forEach { database -> runCatching { database.close() } }
        opened.clear()
        if (::databaseName.isInitialized) context.deleteDatabase(databaseName)
    }
}
