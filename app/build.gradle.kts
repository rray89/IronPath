import java.util.Locale
import javax.xml.XMLConstants

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.ironpath"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.ironpath"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.example.ironpath.HiltTestRunner"
    }

    buildTypes {
        debug {
            // JaCoCo instrumentation for unit-test coverage.
            // Enabled only when -PenableCoverage is passed (e.g. in CI).
            // Local: ./gradlew testDebugUnitTest
            // CI / coverage report: ./gradlew createDebugUnitTestCoverageReport -PenableCoverage
            enableUnitTestCoverage = project.hasProperty("enableCoverage")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.all { test ->
            test.maxHeapSize = "2g"
            test.jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=512m")
        }
        managedDevices {
            localDevices {
                create("pixel2Api29") {
                    device = "Pixel 2"
                    apiLevel = 29
                    systemImageSource = "aosp"
                }
            }
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
    }
    buildFeatures { compose = true }
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // Room 2.8.4 migration bundles use the kotlinx.serialization 1.8.1 GeneratedSerializer ABI.
    implementation(platform(libs.kotlinx.serialization.bom))
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.android.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.navigation.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

if (project.hasProperty("enableCoverage")) {
    tasks.register("verifyCoreCoverage") {
        dependsOn("createDebugUnitTestCoverageReport")
        doLast {
            val report =
                layout.buildDirectory.file("reports/coverage/test/debug/report.xml").get().asFile
            check(report.isFile) { "Coverage report not found: $report" }

            val factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    setFeature(
                        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                        false,
                    )
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                    isXIncludeAware = false
                    isExpandEntityReferences = false
                }
            val document = factory.newDocumentBuilder().parse(report)
            val sourceFiles = document.getElementsByTagName("sourcefile")
            val totals = mutableMapOf("LINE" to intArrayOf(0, 0), "BRANCH" to intArrayOf(0, 0))

            for (index in 0 until sourceFiles.length) {
                val source = sourceFiles.item(index) as org.w3c.dom.Element
                val packageName = (source.parentNode as org.w3c.dom.Element).getAttribute("name")
                val path = "$packageName/${source.getAttribute("name")}"
                val included =
                    path.endsWith(".kt") &&
                        (path.startsWith("com/example/ironpath/domain/") ||
                            path.startsWith("com/example/ironpath/data/repository/") ||
                            (path.startsWith("com/example/ironpath/ui/screens/") &&
                                path.endsWith("ViewModel.kt") &&
                                !path.endsWith("DevToolsViewModel.kt")))
                if (!included) continue

                val counters = source.getElementsByTagName("counter")
                for (counterIndex in 0 until counters.length) {
                    val counter = counters.item(counterIndex) as org.w3c.dom.Element
                    val total = totals[counter.getAttribute("type")] ?: continue
                    total[0] += counter.getAttribute("covered").toInt()
                    total[1] += counter.getAttribute("missed").toInt()
                }
            }

            fun percentage(type: String): Double {
                val (covered, missed) = totals.getValue(type)
                check(covered + missed > 0) { "No $type counters found in core scope" }
                return covered * 100.0 / (covered + missed)
            }

            val line = percentage("LINE")
            val branch = percentage("BRANCH")
            check(line >= 85.0 && branch >= 70.0) {
                "Core coverage failed: line %.2f%% (min 85%%), branch %.2f%% (min 70%%)"
                    .format(Locale.US, line, branch)
            }
            println(
                "Core coverage passed: line %.2f%%, branch %.2f%%".format(Locale.US, line, branch),
            )
        }
    }
}
