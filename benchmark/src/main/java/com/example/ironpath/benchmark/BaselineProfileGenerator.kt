package com.example.ironpath.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() =
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
            filterPredicate = ::isIronPathProfileRule,
        ) {
            clearTargetAppData()
            startActivityAndWait()
            device.requireObject(By.res(ENTRY_GET_STARTED))
        }

    @Test
    fun criticalJourneys() =
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = false,
            filterPredicate = ::isIronPathProfileRule,
        ) {
            seed(SEED_HISTORY)
            startAppAndDismissEntry()
            device.requireObject(By.res(BOTTOM_NAV_HISTORY)).click()
            device.requireObject(By.text("Benchmark Workout 000"))
            device.scrollUntilFound(By.res("log_benchmark-log-010"), maxSwipes = 4)
            device.requireObject(By.res(HISTORY_TAB_RECORDS)).click()
            device.requireObject(By.text("Benchmark Exercise 000"))
            device.scrollUntilFound(By.res("record_benchmark-record-010"), maxSwipes = 4)

            seed(SEED_ACTIVE)
            startAppAndDismissEntry()
            device.requireObject(By.res(BOTTOM_NAV_ACTIVE)).click()
            device.requireObject(By.text("BENCHMARK CAPACITY SESSION"))
            // The session header can render before the exercise and set flows. Wait at the top
            // until the first row is complete so an eager swipe cannot move it above the viewport.
            device.requireObject(By.text(ACTIVE_FIRST_EXERCISE))
            device.requireObject(By.res(ACTIVE_FIRST_WEIGHT_FIELD))
            device.replaceNumberField(ACTIVE_FIRST_WEIGHT_FIELD, "100")
            device.replaceNumberField(ACTIVE_FIRST_REPS_FIELD, "8")
            device.dismissKeyboardWithoutNavigating(ACTIVE_FIRST_REPS_FIELD, "8")
            device.scrollUntilFound(By.res(ACTIVE_COMPLETE))
            device.clickAndAwaitGone(By.res(ACTIVE_COMPLETE))
            device.requireObject(By.text("Week complete!"))
        }
}

private fun isIronPathProfileRule(rule: String): Boolean {
    val owner = rule.dropWhile { flag -> flag in "HSP" }
    return owner.startsWith("Lcom/example/ironpath/") &&
        !rule.contains("Lcom/example/ironpath/benchmark/")
}
