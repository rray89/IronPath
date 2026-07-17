package com.example.ironpath.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class CriticalFlowBenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun historyScrolls250Logs() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 5,
            setupBlock = {
                seed(SEED_HISTORY)
                startAppAndDismissEntry()
                device.requireObject(By.res(BOTTOM_NAV_HISTORY)).click()
                device.requireObject(By.text("Benchmark Workout 000"))
            },
        ) {
            device.scrollUntilFound(By.res("log_benchmark-log-020"), maxSwipes = 8)
        }
    }

    @Test
    fun historyScrolls250Records() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 5,
            setupBlock = {
                seed(SEED_HISTORY)
                startAppAndDismissEntry()
                device.requireObject(By.res(BOTTOM_NAV_HISTORY)).click()
                device.requireObject(By.res(HISTORY_TAB_RECORDS)).click()
                device.requireObject(By.text("Benchmark Exercise 000"))
            },
        ) {
            device.scrollUntilFound(By.res("record_benchmark-record-020"), maxSwipes = 8)
        }
    }

    @Test
    fun activeSessionEntersTenSetsAndCompletes100SetTransaction() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics =
                listOf(
                    FrameTimingMetric(),
                    TraceSectionMetric(
                        sectionName = "IronPath#completeSession",
                        mode = TraceSectionMetric.Mode.Sum,
                    ),
                ),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 5,
            setupBlock = {
                seed(SEED_ACTIVE)
                pressHome()
            },
        ) {
            startAppAndDismissEntry()
            device.requireObject(By.res(BOTTOM_NAV_ACTIVE)).click()
            device.requireObject(By.text("BENCHMARK CAPACITY SESSION"))
            device.requireObject(By.text(ACTIVE_FIRST_EXERCISE))
            device.requireObject(By.res(ACTIVE_FIRST_WEIGHT_FIELD))

            repeat(10) { flatIndex ->
                val exerciseIndex = flatIndex / 5
                val setIndex = flatIndex % 5
                val weightField = activeSetField("set_weight", exerciseIndex, setIndex)
                val repsField = activeSetField("set_reps", exerciseIndex, setIndex)

                device.scrollUntilFound(By.res(weightField))
                device.replaceNumberField(weightField, (100 + flatIndex).toString())
                device.replaceNumberField(repsField, "8")
                device.dismissKeyboardWithoutNavigating(repsField, "8")
            }

            device.scrollUntilFound(By.res(ACTIVE_COMPLETE))
            device.clickAndAwaitGone(By.res(ACTIVE_COMPLETE))
            device.requireObject(By.text("Week complete!"))
        }
    }
}

private fun activeSetField(prefix: String, exerciseIndex: Int, setIndex: Int): String =
    String.format(
        Locale.ROOT,
        "%s_benchmark-set-%03d-%02d",
        prefix,
        exerciseIndex,
        setIndex,
    )
