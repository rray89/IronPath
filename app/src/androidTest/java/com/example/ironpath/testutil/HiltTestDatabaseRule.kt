package com.example.ironpath.testutil

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class HiltTestDatabaseRule : TestWatcher() {
    override fun starting(description: Description) {
        TestDatabaseRegistry.start(
            InstrumentationRegistry.getInstrumentation().targetContext,
            description,
        )
    }

    override fun finished(description: Description) {
        TestDatabaseRegistry.finish()
    }
}
