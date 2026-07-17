package com.example.ironpath.testutil

import android.content.Context
import androidx.room.Room
import com.example.ironpath.data.local.IronPathDatabase
import org.junit.runner.Description

object TestDatabaseRegistry {
    private const val DATABASE_PREFIX = "ironpath-test-"
    private const val PRODUCTION_DATABASE = "ironpath.db"

    private var context: Context? = null
    private var databaseName: String? = null
    private var database: IronPathDatabase? = null

    @Synchronized
    fun start(context: Context, description: Description) {
        check(databaseName == null) { "A Hilt test database is already active" }
        val identifier =
            listOfNotNull(description.testClass?.simpleName, description.methodName)
                .joinToString("-")
                .ifBlank { "unknown-test" }
                .replace(Regex("[^A-Za-z0-9_.-]"), "-")
                .take(96)
        val name = "$DATABASE_PREFIX$identifier.db"
        check(name != PRODUCTION_DATABASE)

        val applicationContext = context.applicationContext
        applicationContext.deleteDatabase(name)
        this.context = applicationContext
        databaseName = name
    }

    @Synchronized
    fun open(context: Context): IronPathDatabase {
        val name =
            requireNotNull(databaseName) {
                "HiltTestDatabaseRule must be the outer rule before the Hilt component is created"
            }
        database?.let {
            return it
        }

        return Room.databaseBuilder(
                context.applicationContext,
                IronPathDatabase::class.java,
                name,
            )
            .addMigrations(IronPathDatabase.MIGRATION_1_2)
            .build()
            .also { database = it }
    }

    @Synchronized fun currentName(): String = requireNotNull(databaseName)

    @Synchronized
    fun closeCurrent() {
        database?.close()
        database = null
    }

    @Synchronized
    fun reopen(context: Context): IronPathDatabase {
        closeCurrent()
        return open(context)
    }

    @Synchronized
    fun finish() {
        closeCurrent()
        val applicationContext = context
        val name = databaseName
        if (applicationContext != null && name != null) {
            applicationContext.deleteDatabase(name)
        }
        context = null
        databaseName = null
    }
}
