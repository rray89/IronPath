import java.util.Locale
import javax.xml.XMLConstants
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    jacoco
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.baselineprofile)
}

jacoco { toolVersion = "0.8.14" }

val androidTestCoverageRequested = providers.gradleProperty("enableAndroidTestCoverage").isPresent

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
            enableAndroidTestCoverage = androidTestCoverageRequested
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmarkRelease") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
        }
        create("nonMinifiedRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
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
            // AGP 9.1 resolves managed-device coverage through the last registered device.
            // Register only API 29 for the dedicated coverage invocation; normal runs retain
            // the complete API 29 + 36 production matrix below.
            localDevices {
                create("pixel2Api29") {
                    device = "Pixel 2"
                    apiLevel = 29
                    systemImageSource = "aosp"
                    testedAbi = "x86_64"
                }
                if (!androidTestCoverageRequested) {
                    create("pixel8Api36") {
                        device = "Pixel 8"
                        apiLevel = 36
                        systemImageSource = "aosp"
                        testedAbi = "x86_64"
                    }
                }
            }
            if (!androidTestCoverageRequested) {
                groups {
                    create("productionMatrix") {
                        targetDevices.add(allDevices.getByName("pixel2Api29"))
                        targetDevices.add(allDevices.getByName("pixel8Api36"))
                    }
                }
            }
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        // SDK upgrades are deliberate compatibility projects. Do not let the runner's installed
        // preview/new SDK make the currently tested target (API 36) fail nondeterministically.
        disable += "OldTargetApi"
    }
    buildFeatures { compose = true }
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

androidComponents {
    listOf("benchmarkRelease", "nonMinifiedRelease").forEach { buildType ->
        onVariants(selector().withBuildType(buildType)) { variant ->
            // Synthetic Baseline Profile build types don't consume build-type manifests through
            // the legacy source-set DSL. Wire the benchmark-only seed surface directly to each
            // release-like test variant while keeping it absent from production release builds.
            variant.sources.manifests.addStaticManifestFile(
                "src/benchmarkRelease/AndroidManifest.xml",
            )
            if (buildType == "nonMinifiedRelease") {
                checkNotNull(variant.sources.kotlin) {
                        "Kotlin sources are required for the nonMinifiedRelease profile target"
                    }
                    .addStaticSourceDirectory("src/benchmarkRelease/java")
            }
        }
    }
}

room { schemaDirectory("$projectDir/schemas") }

baselineProfile {
    mergeIntoMain = false
    saveInSrc = true
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
}

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
    implementation(libs.androidx.profileinstaller)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // Room 2.8.4 migration bundles use the kotlinx.serialization 1.8.1 GeneratedSerializer ABI.
    implementation(platform(libs.kotlinx.serialization.bom))
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.android.compiler)
    ksp(libs.mlkit.genai.schema.compiler)
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
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    baselineProfile(project(":benchmark"))
}

if (androidTestCoverageRequested) {
    val api29ExecutionDataDirectory =
        layout.buildDirectory.dir(
            "outputs/managed_device_code_coverage/debug/pixel2Api29",
        )
    val generatedCoverageClasses =
        listOf(
            "**/R.class",
            "**/R${'$'}*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*Factory*.*",
            "**/*_MembersInjector*.*",
            "**/*_Impl*.*",
            "**/Hilt_*.*",
            "**/*_HiltModules*.*",
            "**/*_HiltComponents*.*",
            "**/Dagger*.*",
            "**/hilt_aggregated_deps/**",
            "**/dagger/hilt/internal/aggregatedroot/codegen/**",
        )
    val api29ExecutionData =
        fileTree(api29ExecutionDataDirectory) { include("**/*.ec", "**/*.exec") }
    val api29ClassDirectories =
        fileTree(
            layout.buildDirectory.dir(
                "intermediates/classes/debug/transformDebugClassesWithAsm/dirs",
            ),
        ) {
            exclude(generatedCoverageClasses)
        }
    val verifyApi29ExecutionData =
        tasks.register("verifyPixel2Api29DebugAndroidTestCoverageData") {
            group = "verification"
            description = "Verifies that API 29 coverage data and matching app classes exist."
            dependsOn("pixel2Api29DebugAndroidTest")
            inputs.files(api29ExecutionData, api29ClassDirectories)

            doLast {
                val executionFiles = api29ExecutionData.files.filter { it.isFile }
                check(executionFiles.isNotEmpty()) {
                    "API 29 Android coverage execution data was not produced"
                }
                val api29Root = api29ExecutionDataDirectory.get().asFile.toPath()
                check(executionFiles.all { it.toPath().startsWith(api29Root) }) {
                    "Android coverage report contains execution data outside pixel2Api29"
                }
                check(api29ClassDirectories.files.any { it.isFile && it.extension == "class" }) {
                    "Android coverage classes were not found; verify the AGP class output path"
                }
            }
        }

    tasks.register<JacocoReport>("createPixel2Api29DebugAndroidTestCoverageReport") {
        group = "verification"
        description = "Creates API 29 managed-device JaCoCo XML and HTML reports."
        dependsOn(verifyApi29ExecutionData)

        executionData.setFrom(api29ExecutionData)
        // Match the post-Hilt/Dagger bytecode used by AGP before JaCoCo instrumentation.
        classDirectories.setFrom(api29ClassDirectories)
        sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))

        reports {
            xml.required.set(true)
            xml.outputLocation.set(
                layout.buildDirectory.file(
                    "reports/coverage/androidTest/debug/pixel2Api29/report.xml",
                ),
            )
            html.required.set(true)
            html.outputLocation.set(
                layout.buildDirectory.dir(
                    "reports/coverage/androidTest/debug/pixel2Api29/html",
                ),
            )
            csv.required.set(false)
        }
    }
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
