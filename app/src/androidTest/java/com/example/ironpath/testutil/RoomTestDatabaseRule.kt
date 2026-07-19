package com.example.ironpath.testutil

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ironpath.data.local.IronPathDatabase
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class RoomTestDatabaseRule : TestWatcher() {
    lateinit var database: IronPathDatabase
        private set

    override fun starting(description: Description) {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    IronPathDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
    }

    override fun finished(description: Description) {
        if (::database.isInitialized) database.close()
    }
}
