package com.example.ironpath.benchmark

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "com.example.ironpath"
internal const val SEED_ACTIVITY =
    "com.example.ironpath/com.example.ironpath.benchmark.BenchmarkSeedActivity"
internal const val SEED_SCENARIO_EXTRA = "benchmark_scenario"
internal const val SEED_HISTORY = "history"
internal const val SEED_ACTIVE = "active"
internal const val SEED_READY = "BENCHMARK_SEED_READY"
internal const val SEED_FAILED = "BENCHMARK_SEED_FAILED"
internal const val ENTRY_GET_STARTED = "entry_get_started"
internal const val ACTIVE_COMPLETE = "active_complete"
internal const val HISTORY_TAB_RECORDS = "history_tab_records"
internal const val ACTIVE_FIRST_EXERCISE = "1. Benchmark Movement 00"
internal const val ACTIVE_FIRST_WEIGHT_FIELD = "set_weight_benchmark-set-000-00"
internal const val ACTIVE_FIRST_REPS_FIELD = "set_reps_benchmark-set-000-00"
internal const val BOTTOM_NAV_HOME = "bottom_nav_home"
internal const val BOTTOM_NAV_PLAN = "bottom_nav_plan"
internal const val BOTTOM_NAV_ACTIVE = "bottom_nav_active"
internal const val BOTTOM_NAV_HISTORY = "bottom_nav_history"
internal const val UI_TIMEOUT_MS = 30_000L
internal const val SEED_TIMEOUT_MS = 45_000L

internal fun MacrobenchmarkScope.seed(scenario: String) {
    device.executeShellCommand(
        "am start -W -n $SEED_ACTIVITY --es $SEED_SCENARIO_EXTRA $scenario",
    )
    try {
        device.awaitSeedReady()
    } finally {
        killProcess()
    }
}

internal fun MacrobenchmarkScope.clearTargetAppData() {
    val result = device.executeShellCommand("pm clear $TARGET_PACKAGE").trim()
    check(result == "Success") { "Unable to clear target app data: $result" }
}

internal fun MacrobenchmarkScope.startAppAndDismissEntry() {
    startActivityAndWait { intent ->
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    device.requireObject(By.res(ENTRY_GET_STARTED)).click()
    device.requireObject(By.res(BOTTOM_NAV_HOME))
}

internal fun UiDevice.requireObject(
    selector: BySelector,
    timeoutMs: Long = UI_TIMEOUT_MS,
): UiObject2 =
    checkNotNull(wait(Until.findObject(selector), timeoutMs)) {
        "UI object did not appear within ${timeoutMs}ms: $selector"
    }

internal fun UiDevice.swipeUp() {
    // Keep the gesture left of the editable weight/repetition fields. Center swipes can be
    // consumed by Compose text fields instead of the parent vertical scroll container.
    val safeX = (displayWidth * 0.2).toInt()
    swipe(
        safeX,
        (displayHeight * 0.78).toInt(),
        safeX,
        (displayHeight * 0.28).toInt(),
        60,
    )
    waitForIdle()
}

internal fun UiDevice.dismissKeyboardWithoutNavigating(
    anchorResourceId: String,
    anchorText: String,
) {
    check(currentPackageName == TARGET_PACKAGE) {
        "Target app lost focus before dismissing the keyboard: $currentPackageName"
    }
    pressBack()
    waitForIdle()
    check(currentPackageName == TARGET_PACKAGE) {
        "Back navigated away instead of dismissing the keyboard: $currentPackageName"
    }
    requireObject(By.res(anchorResourceId).text(anchorText), FIELD_UPDATE_TIMEOUT_MS)
}

/** Replaces a focused numeric value using key events and verifies the Compose field updated. */
internal fun UiDevice.replaceNumberField(resourceId: String, value: String) {
    require(value.isNotEmpty() && value.all(Char::isDigit)) {
        "Numeric benchmark input must contain digits only: $value"
    }

    requireStableObject(By.res(resourceId)).click()
    waitForIdle()
    pressKeyCode(KeyEvent.KEYCODE_MOVE_END)
    repeat(MAX_NUMBER_FIELD_CHARACTERS) { pressKeyCode(KeyEvent.KEYCODE_DEL) }
    value.forEach { digit -> pressKeyCode(KeyEvent.KEYCODE_0 + digit.digitToInt()) }
    waitForIdle()
    requireObject(By.res(resourceId).text(value), FIELD_UPDATE_TIMEOUT_MS)
}

internal fun UiDevice.scrollUntilFound(
    selector: BySelector,
    maxSwipes: Int = 60,
): UiObject2 {
    repeat(maxSwipes + 1) { attempt ->
        findObject(selector)?.let {
            // Include the final fling/tail frames in measured scroll blocks and never hand a
            // moving target to the next interaction.
            return requireStableObject(selector)
        }
        if (attempt < maxSwipes) swipeUp()
    }
    error("UI object did not appear after $maxSwipes safe-edge swipes: $selector")
}

internal fun UiDevice.clickAndAwaitGone(
    selector: BySelector,
) {
    requireStableObject(selector).click()
    check(wait(Until.gone(selector), UI_TIMEOUT_MS)) {
        "UI object did not disappear within ${UI_TIMEOUT_MS}ms: $selector"
    }
}

private fun UiDevice.requireStableObject(
    selector: BySelector,
    timeoutMs: Long = STABLE_OBJECT_TIMEOUT_MS,
): UiObject2 {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    var previousBounds: Rect? = null
    var stableSamples = 0
    while (SystemClock.uptimeMillis() < deadline) {
        val target = findObject(selector)
        val bounds = target?.visibleBounds
        if (target != null && bounds != null && !bounds.isEmpty) {
            if (bounds == previousBounds) {
                stableSamples += 1
            } else {
                previousBounds = Rect(bounds)
                stableSamples = 1
            }
            if (stableSamples >= REQUIRED_STABLE_SAMPLES) return target
        } else {
            previousBounds = null
            stableSamples = 0
        }
        SystemClock.sleep(STABLE_SAMPLE_INTERVAL_MS)
    }
    error("UI object did not settle within ${timeoutMs}ms: $selector")
}

private fun UiDevice.awaitSeedReady() {
    if (wait(Until.findObject(By.text(SEED_READY)), SEED_TIMEOUT_MS) != null) return
    findObject(By.textStartsWith(SEED_FAILED))?.let { failure ->
        error(failure.text ?: SEED_FAILED)
    }
    error("Benchmark seed did not complete within ${SEED_TIMEOUT_MS}ms")
}

private const val MAX_NUMBER_FIELD_CHARACTERS = 12
private const val FIELD_UPDATE_TIMEOUT_MS = 5_000L
private const val STABLE_OBJECT_TIMEOUT_MS = 5_000L
private const val STABLE_SAMPLE_INTERVAL_MS = 100L
private const val REQUIRED_STABLE_SAMPLES = 3
