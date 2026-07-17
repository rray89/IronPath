plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

val baselineProfileUseConnectedDevices =
    providers
        .gradleProperty("baselineProfileUseConnectedDevices")
        .map(String::toBoolean)
        .getOrElse(false)

android {
    namespace = "com.example.ironpath.benchmark"
    compileSdk = 36
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    testOptions {
        managedDevices {
            localDevices {
                create("pixel8Api36") {
                    device = "Pixel 8"
                    apiLevel = 36
                    systemImageSource = "aosp"
                    testedAbi = "x86_64"
                }
            }
        }
    }
}

baselineProfile {
    if (!baselineProfileUseConnectedDevices) {
        managedDevices += "pixel8Api36"
    }
    useConnectedDevices = baselineProfileUseConnectedDevices
    skipBenchmarksOnEmulator = false
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
