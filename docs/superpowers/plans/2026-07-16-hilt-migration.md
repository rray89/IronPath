# Hilt Dependency Injection Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

This compatibility header comes from the planning template; IronPath's AGENTS.md still requires the user to explicitly approve either execution workflow before it is invoked.

**Goal:** Replace Koin with Hilt without changing product behavior, persistence, navigation, or unit-test semantics, and establish a production Hilt startup test on API 29 before the broader production-testing plan begins.

**Architecture:** Keep constructor injection as the application default, use one Hilt DatabaseModule only for Room and DAO objects that IronPath cannot construct directly, and preserve every existing Koin singleton lifetime with @Singleton. Route arguments move from Koin parameters to SavedStateHandle in the two destination-scoped ViewModels. The cutover is incremental inside one PR: Hilt is introduced alongside Koin, the graph is converted, Compose switches to Hilt, Koin is removed, and an API 29 managed-device smoke test proves the final runtime graph.

**Tech Stack:** Kotlin 2.3.20, AGP 9.1.0 built-in Kotlin, KSP 2.3.6, Java/Kotlin JVM target 11, Dagger Hilt 2.60.1, AndroidX Hilt 1.3.0, Jetpack Compose, Navigation Compose 2.9.0, Room 2.8.4, JUnit 4, Compose UI Test, Gradle Managed Devices.

**Version refresh (2026-07-16):** The implementation gate found that Dagger 2.59 is the first Hilt Gradle Plugin line with AGP 9 support, and 2.60.1 is the current bug-fix release. AndroidX Hilt 1.4.0's Compose artifact requires AGP 9.2/compile SDK 37, so IronPath stays on AndroidX Hilt 1.3.0 to preserve the approved AGP 9.1/compile SDK 36 toolchain. This is a compatibility correction, not an application toolchain upgrade.

## Global Constraints

- Do not begin implementation until the user explicitly approves this reviewed plan.
- Deliver this prerequisite as PR feat10: migrate dependency injection to Hilt on branch feat/feat10-hilt-migration.
- This PR must merge before 2026-07-16-production-testing-foundation.md begins.
- Preserve all product behavior, route strings and argument keys, Room schema/version/migrations, database filename, and UI copy.
- Preserve existing object lifetimes: the database, DAOs, repositories, domain services, and development seeder remain application singletons; ViewModels remain destination/ViewModel scoped.
- Continue constructing repositories, domain classes, and ViewModels directly in JVM tests. Do not turn unit tests into Hilt tests.
- Use SavedStateHandle for workoutId and workoutLogId; do not introduce assisted injection solely to reproduce Koin parameter passing.
- Use Hilt modules only for third-party objects or interface bindings. Owned concrete types use constructor injection.
- Do not add a service locator, production field injection, EntryPointAccessors, or global mutable test state.
- During intermediate commits Koin and Hilt may coexist, but the final source tree and resolved runtime dependencies must contain no Koin code or artifact.
- Hilt test components use HiltTestApplication and HiltAndroidRule. The test runner must be in androidTest, not production.
- API 29 Hilt startup is a required PR check. A debug/release compile alone is not sufficient evidence for the migration.
- This is an architecture-only change. Product-contract conflicts identified in the production-testing plan remain undecided and out of scope.
- Preserve sourceCompatibility, targetCompatibility, and Kotlin bytecode target 11. CI/AGP continue running on JDK 17; changing application bytecode is not part of the DI migration.
- At implementation time, verify the pinned Hilt versions against the official Android Hilt and AndroidX Hilt release pages before editing Gradle. If either version is no longer available or compatible, stop and update this plan rather than silently choosing another version.
- Before Task 1, check the Dagger/Hilt release notes for AGP 9 built-in-Kotlin compatibility. The first clean Hilt build is the decisive compatibility gate; do not reapply org.jetbrains.kotlin.android or disable built-in Kotlin as an unreviewed fallback.

---

## Delivery and Review Boundary

This is one PR with six independently testable commits:

| Commit | Result |
|---|---|
| feat10: add Hilt toolchain and entry points | Hilt compiles with AGP 9.1 built-in Kotlin while Koin still owns runtime resolution |
| feat10: constructor-inject the application graph | Room, DAOs, repositories, domain services, and seeder are Hilt-resolvable |
| feat10: migrate ViewModels to Hilt | All seven ViewModels are compile-time validated; route IDs come from SavedStateHandle |
| feat10: switch Compose from Koin to Hilt | Hilt owns runtime ViewModel creation and Koin is completely removed |
| test: verify Hilt startup on Android | Custom Hilt runner and real startup/navigation smoke test pass |
| ci: gate Hilt startup on API 29 | Managed-device CI is required and documentation names Hilt as the project DI standard |

The PR is not reviewable as complete until all six results are present. Intermediate commits are migration checkpoints, not merge candidates.

## File Responsibility Map

**Create:**

- app/src/main/java/com/example/ironpath/di/DatabaseModule.kt — Hilt providers for Room and the four DAOs.
- app/src/androidTest/java/com/example/ironpath/HiltTestRunner.kt — boots HiltTestApplication for instrumented tests.
- app/src/androidTest/java/com/example/ironpath/di/HiltStartupTest.kt — proves entry-to-Home startup and runtime ViewModel resolution.

**Delete:**

- app/src/main/java/com/example/ironpath/di/AppModule.kt — Koin module graph.
- app/src/androidTest/java/com/example/ironpath/ExampleInstrumentedTest.kt — generated package-name assertion superseded by the Hilt startup test.

**Modify:**

- gradle/libs.versions.toml — add Hilt/AndroidX Hilt artifacts and plugin, then remove Koin.
- build.gradle.kts — expose the Hilt Gradle plugin.
- app/build.gradle.kts — apply Hilt without changing JVM target 11, add compiler/runtime/test dependencies, configure the Hilt runner and API 29 managed device.
- gradle.properties — provide enough daemon Metaspace for Hilt and Room KSP across debug, release, unit-test, and instrumented-test variants.
- IronPathApplication.kt and MainActivity.kt — add Hilt entry points and remove Koin startup.
- Four data/repository files — constructor injection plus preserved singleton scope.
- PlanGenerator.kt, StartPlannedWorkoutUseCase.kt, and DevToolsSeeder.kt — constructor injection plus preserved singleton scope.
- Seven ViewModel files — @HiltViewModel and @Inject; two consume SavedStateHandle.
- Seven screen files currently importing Koin — use androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel.
- ui/navigation/IronPathNavHost.kt — stop manually forwarding route IDs into screen constructors.
- WorkoutPreviewViewModelTest.kt and WorkoutLogDetailViewModelTest.kt — provide route arguments through SavedStateHandle.
- .github/workflows/android-ci.yml — add required API 29 Hilt startup job.
- AGENTS.md, CLAUDE.md, and README.md — replace Koin guidance with Hilt while retaining JVM target 11.

---

### Task 1: Establish the Hilt Toolchain Without Cutting Over Runtime DI

**Files:**
- Modify: gradle/libs.versions.toml
- Modify: build.gradle.kts
- Modify: app/build.gradle.kts
- Modify: app/src/main/java/com/example/ironpath/IronPathApplication.kt
- Modify: app/src/main/java/com/example/ironpath/MainActivity.kt

**Interfaces:**
- Produces: Hilt code generation, @HiltAndroidApp IronPathApplication, and an @AndroidEntryPoint MainActivity.
- Consumes: existing KSP 2.3.6 and the Koin graph during this intermediate checkpoint.

- [ ] **Step 1: Record the pre-migration behavior baseline**

Run:

~~~bash
./gradlew spotlessCheck lintDebug assembleDebug assembleRelease testDebugUnitTest
~~~

Expected: 107 JVM tests pass, lint has no errors, and both debug and release APKs assemble. Save the output in the PR notes; a pre-existing failure blocks the migration.

- [ ] **Step 2: Add pinned Hilt versions and aliases**

Add these entries while retaining Koin temporarily:

~~~toml
[versions]
hilt = "2.60.1"
androidxHilt = "1.3.0"

[libraries]
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-android-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-android-testing = { module = "com.google.dagger:hilt-android-testing", version.ref = "hilt" }
androidx-hilt-lifecycle-viewmodel-compose = { module = "androidx.hilt:hilt-lifecycle-viewmodel-compose", version.ref = "androidxHilt" }

[plugins]
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
~~~

Do not add the legacy hilt-navigation-compose artifact; hiltViewModel is in androidx.hilt.lifecycle.viewmodel.compose for AndroidX Hilt 1.3.0.

- [ ] **Step 3: Apply Hilt while preserving JVM target 11**

Expose the plugin at the root:

~~~kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.spotless)
}
~~~

Apply it in app/build.gradle.kts:

~~~kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.hilt)
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.android.compiler)
}
~~~

The compileOptions block is shown to make the non-change explicit. AGP 9.1 built-in Kotlin derives its JVM target from android.compileOptions.targetCompatibility, so do not add a redundant kotlin.compilerOptions block. Gradle/AGP run on JDK 17 in CI independently of the application's target-11 bytecode. Keep implementation(libs.koin.androidx.compose) only until Task 4.

- [ ] **Step 4: Add Hilt application/activity entry points while Koin remains active**

Add @HiltAndroidApp but retain the existing startKoin body:

~~~kotlin
@HiltAndroidApp
class IronPathApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@IronPathApplication)
            modules(databaseModule, repositoryModule, domainModule, devModule, viewModelModule)
        }
    }
}
~~~

Annotate the activity without changing its body:

~~~kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { IronPathTheme { IronPathApp() } }
    }
}
~~~

- [ ] **Step 5: Verify Hilt code generation and unchanged behavior**

~~~bash
./gradlew clean kspDebugKotlin assembleDebug assembleRelease testDebugUnitTest
~~~

Expected: com.google.dagger.hilt.android applies and generates the application component under AGP 9.1 built-in Kotlin with no org.jetbrains.kotlin.android plugin present; Koin still resolves the running app; all 107 JVM tests remain green. If this compatibility gate fails, stop and revise the plan rather than adding the Kotlin Android plugin or disabling built-in Kotlin.

- [ ] **Step 6: Commit the toolchain checkpoint**

~~~bash
git add gradle/libs.versions.toml build.gradle.kts app/build.gradle.kts app/src/main/java/com/example/ironpath/IronPathApplication.kt app/src/main/java/com/example/ironpath/MainActivity.kt
git commit -m "Codex-feat10: add Hilt toolchain and entry points"
~~~

---

### Task 2: Constructor-Inject the Data and Domain Graph

**Files:**
- Create: app/src/main/java/com/example/ironpath/di/DatabaseModule.kt
- Modify: four app/src/main/java/com/example/ironpath/data/repository files
- Modify: app/src/main/java/com/example/ironpath/domain/planner/PlanGenerator.kt
- Modify: app/src/main/java/com/example/ironpath/domain/session/StartPlannedWorkoutUseCase.kt
- Modify: app/src/main/java/com/example/ironpath/dev/DevToolsSeeder.kt

**Interfaces:**
- Produces: one SingletonComponent graph for IronPathDatabase, four DAOs, four repositories, PlanGenerator, StartPlannedWorkoutUseCase, and DevToolsSeeder.
- Consumes: @ApplicationContext Context and IronPathDatabase.MIGRATION_1_2.

- [ ] **Step 1: Add the Room Hilt module**

~~~kotlin
package com.example.ironpath.di

import android.content.Context
import androidx.room.Room
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.PlanDao
import com.example.ironpath.data.local.dao.RecordDao
import com.example.ironpath.data.local.dao.SessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): IronPathDatabase =
        Room.databaseBuilder(
                context,
                IronPathDatabase::class.java,
                "ironpath.db",
            )
            .addMigrations(IronPathDatabase.MIGRATION_1_2)
            .build()

    @Provides @Singleton
    fun providePlanDao(database: IronPathDatabase): PlanDao = database.planDao()

    @Provides @Singleton
    fun provideSessionDao(database: IronPathDatabase): SessionDao = database.sessionDao()

    @Provides @Singleton
    fun provideHistoryDao(database: IronPathDatabase): HistoryDao = database.historyDao()

    @Provides @Singleton
    fun provideRecordDao(database: IronPathDatabase): RecordDao = database.recordDao()
}
~~~

- [ ] **Step 2: Preserve repository singleton lifetimes**

Change only declarations; keep every method body unchanged:

~~~kotlin
@Singleton
class PlanRepository @Inject constructor(private val planDao: PlanDao)

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val historyDao: HistoryDao,
    private val database: IronPathDatabase,
)

@Singleton
class HistoryRepository @Inject constructor(private val historyDao: HistoryDao)

@Singleton
class RecordRepository @Inject constructor(private val recordDao: RecordDao)
~~~

Use javax.inject.Inject and javax.inject.Singleton consistently.

- [ ] **Step 3: Preserve domain and seeder singleton lifetimes**

~~~kotlin
@Singleton
class PlanGenerator @Inject constructor()

@Singleton
class StartPlannedWorkoutUseCase @Inject constructor(
    private val planRepository: PlanRepository,
    private val sessionRepository: SessionRepository,
)

@Singleton
class DevToolsSeeder @Inject constructor(
    private val database: IronPathDatabase,
    private val planRepository: PlanRepository,
    private val recordRepository: RecordRepository,
)
~~~

These declarations preserve the existing constructor parameter order; add only injection/scope annotations and leave every method body unchanged.

- [ ] **Step 4: Prove direct-construction tests remain unchanged**

~~~bash
./gradlew testDebugUnitTest --tests "com.example.ironpath.data.repository.*" --tests "com.example.ironpath.domain.*" --tests "com.example.ironpath.dev.*"
./gradlew assembleDebug assembleRelease
~~~

Expected: unit tests pass without Hilt rules and Hilt compiles the singleton graph.

- [ ] **Step 5: Commit the application graph**

~~~bash
git add app/src/main/java/com/example/ironpath/di/DatabaseModule.kt app/src/main/java/com/example/ironpath/data/repository app/src/main/java/com/example/ironpath/domain app/src/main/java/com/example/ironpath/dev/DevToolsSeeder.kt
git commit -m "Codex-feat10: constructor-inject the application graph"
~~~

---

### Task 3: Migrate All ViewModels and Route Arguments

**Files:**
- Modify: seven ViewModel files under app/src/main/java/com/example/ironpath/ui/screens
- Modify: app/src/main/java/com/example/ironpath/di/AppModule.kt temporarily
- Modify: WorkoutPreviewViewModelTest.kt
- Modify: WorkoutLogDetailViewModelTest.kt

**Interfaces:**
- Produces: seven @HiltViewModel types; WorkoutPreviewViewModel consumes SavedStateHandle[Route.WORKOUT_ID_ARG] and WorkoutLogDetailViewModel consumes SavedStateHandle[Route.WORKOUT_LOG_ID_ARG].
- Consumes: constructor-injected repositories/domain services from Task 2.

- [ ] **Step 1: Convert the five non-argument ViewModels**

For HomeViewModel, PlanViewModel, ActiveViewModel, HistoryViewModel, and DevToolsViewModel, apply this pattern to the existing parameter lists:

~~~kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val planRepository: PlanRepository,
    private val sessionRepository: SessionRepository,
) : ViewModel()
~~~

Do not add scopes to ViewModels.

- [ ] **Step 2: Move workout-preview arguments to SavedStateHandle**

~~~kotlin
@HiltViewModel
class WorkoutPreviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val planRepository: PlanRepository,
    private val sessionRepository: SessionRepository,
    private val startPlannedWorkout: StartPlannedWorkoutUseCase,
) : ViewModel() {
    private val workoutId: String =
        savedStateHandle.get<String>(Route.WORKOUT_ID_ARG).orEmpty()

    // Existing body remains unchanged.
}
~~~

Update direct test construction:

~~~kotlin
WorkoutPreviewViewModel(
    savedStateHandle = SavedStateHandle(mapOf(Route.WORKOUT_ID_ARG to workoutId)),
    planRepository = planRepository,
    sessionRepository = sessionRepository,
    startPlannedWorkout = startPlannedWorkout,
)
~~~

Add the exact boundary test:

~~~kotlin
@Test
fun missingWorkoutRouteArgument_loadsNotFound() = runTest {
    coEvery { planRepository.getWorkoutById("") } returns null
    val viewModel =
        WorkoutPreviewViewModel(
            savedStateHandle = SavedStateHandle(),
            planRepository = planRepository,
            sessionRepository = sessionRepository,
            startPlannedWorkout = startPlannedWorkout,
        )

    advanceUntilIdle()

    assertEquals(WorkoutPreviewUiState.NotFound, viewModel.uiState.value)
}
~~~

- [ ] **Step 3: Move workout-log arguments to SavedStateHandle**

~~~kotlin
@HiltViewModel
class WorkoutLogDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val historyRepository: HistoryRepository,
    private val recordRepository: RecordRepository,
) : ViewModel() {
    private val logId: String =
        savedStateHandle.get<String>(Route.WORKOUT_LOG_ID_ARG).orEmpty()

    // Existing body remains unchanged.
}
~~~

Update direct tests with SavedStateHandle(mapOf(Route.WORKOUT_LOG_ID_ARG to logId)) and add:

~~~kotlin
@Test
fun missingWorkoutLogRouteArgument_loadsNotFound() = runTest {
    val historyRepository = mockk<HistoryRepository>()
    val recordRepository = mockk<RecordRepository>(relaxed = true)
    coEvery { historyRepository.getLogDetail("") } returns null
    val viewModel =
        WorkoutLogDetailViewModel(
            savedStateHandle = SavedStateHandle(),
            historyRepository = historyRepository,
            recordRepository = recordRepository,
        )

    advanceUntilIdle()

    assertEquals(WorkoutLogDetailUiState.NotFound, viewModel.uiState.value)
}
~~~

- [ ] **Step 4: Keep the intermediate Koin checkpoint runnable**

Adapt only the two Koin parameter factories:

~~~kotlin
viewModel { params ->
    WorkoutPreviewViewModel(
        SavedStateHandle(mapOf(Route.WORKOUT_ID_ARG to params.get<String>())),
        get(),
        get(),
        get(),
    )
}
viewModel { params ->
    WorkoutLogDetailViewModel(
        SavedStateHandle(mapOf(Route.WORKOUT_LOG_ID_ARG to params.get<String>())),
        get(),
        get(),
    )
}
~~~

This compatibility code is deleted in Task 4 and must not merge without that task.

- [ ] **Step 5: Run focused and full verification**

~~~bash
./gradlew testDebugUnitTest --tests "*WorkoutPreviewViewModelTest" --tests "*WorkoutLogDetailViewModelTest"
./gradlew testDebugUnitTest assembleDebug assembleRelease
~~~

Expected: the two new route-boundary cases and all existing tests pass; Hilt validates every ViewModel constructor.

- [ ] **Step 6: Commit the ViewModel conversion**

~~~bash
git add app/src/main/java/com/example/ironpath/ui app/src/main/java/com/example/ironpath/di/AppModule.kt app/src/test/java/com/example/ironpath/ui
git commit -m "Codex-feat10: migrate ViewModels to Hilt"
~~~

---

### Task 4: Cut Compose Over to Hilt and Remove Koin

**Files:**
- Modify: seven Koin-using screen files
- Modify: app/src/main/java/com/example/ironpath/ui/navigation/IronPathNavHost.kt
- Modify: app/src/main/java/com/example/ironpath/IronPathApplication.kt
- Delete: app/src/main/java/com/example/ironpath/di/AppModule.kt
- Modify: app/build.gradle.kts
- Modify: gradle.properties
- Modify: gradle/libs.versions.toml

**Interfaces:**
- Produces: Hilt as the only runtime DI container and hiltViewModel as the only screen-level ViewModel resolver.
- Consumes: @AndroidEntryPoint MainActivity and the Hilt ViewModels from Task 3.

- [ ] **Step 1: Replace all seven Compose resolver imports and defaults**

In ActiveScreen, DevToolsScreen, HistoryScreen, HomeScreen, and PlanScreen replace:

~~~kotlin
import org.koin.androidx.compose.koinViewModel
~~~

with:

~~~kotlin
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
~~~

Replace each default with the matching ViewModel type:

~~~kotlin
viewModel: HomeViewModel = hiltViewModel()
~~~

Keep the explicit ViewModel parameter so isolated screen tests can pass a directly constructed ViewModel.

- [ ] **Step 2: Remove route IDs from the two screen APIs**

WorkoutPreviewScreen becomes:

~~~kotlin
@Composable
fun WorkoutPreviewScreen(
    onBack: () -> Unit,
    onStarted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkoutPreviewViewModel = hiltViewModel(),
)
~~~

WorkoutLogDetailScreen becomes:

~~~kotlin
@Composable
fun WorkoutLogDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    recordActionsEnabled: Boolean = true,
    viewModel: WorkoutLogDetailViewModel = hiltViewModel(),
)
~~~

Use the AndroidX Hilt import in both files and delete both Koin parametersOf imports.

- [ ] **Step 3: Let Navigation populate SavedStateHandle**

For workout preview, stop reading and forwarding workoutId:

~~~kotlin
composable(
    route = Route.WORKOUT_PREVIEW,
    arguments = listOf(navArgument(Route.WORKOUT_ID_ARG) { type = NavType.StringType }),
) {
    WorkoutPreviewScreen(
        onBack = { navController.popBackStack() },
        onStarted = {
            navController.navigate(Route.ACTIVE) {
                popUpTo(Route.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
        modifier = Modifier.padding(innerPadding),
    )
}
~~~

For log detail, retain backStackEntry only to derive readOnly for the screen flag. Delete the local logId and the logId argument. Navigation automatically places both declared route arguments in the Hilt ViewModel SavedStateHandle.

- [ ] **Step 4: Remove Koin startup and module graph**

Replace the application with:

~~~kotlin
package com.example.ironpath

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class IronPathApplication : Application()
~~~

Delete app/src/main/java/com/example/ironpath/di/AppModule.kt.

- [ ] **Step 5: Remove Koin dependencies and version**

Delete these catalog entries:

~~~toml
koin = "4.2.0"
koin-androidx-compose = { group = "io.insert-koin", name = "koin-androidx-compose", version.ref = "koin" }
~~~

Delete implementation(libs.koin.androidx.compose) from app/build.gradle.kts.

- [ ] **Step 6: Prove Koin is absent and Hilt owns both variants**

Increase the project Gradle daemon Metaspace from 512 MiB to 1024 MiB. Hilt and Room both run KSP across debug, release, unit-test, and instrumented-test variants; the prior cap reproducibly exhausts Metaspace in the combined gate even though individual variants compile.

~~~bash
rg -n "org\.koin|koinViewModel|parametersOf|startKoin|io\.insert-koin" app build.gradle.kts gradle/libs.versions.toml
./gradlew spotlessApply
./gradlew testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest
~~~

Expected: rg returns no matches; all JVM tests pass; debug, release, and test APKs compile through Hilt.

- [ ] **Step 7: Commit the runtime cutover**

~~~bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main
git commit -m "Codex-feat10: switch Compose from Koin to Hilt"
~~~

---

### Task 5: Add the Hilt Test Runner and Runtime Startup Test

**Files:**
- Create: app/src/androidTest/java/com/example/ironpath/HiltTestRunner.kt
- Create: app/src/androidTest/java/com/example/ironpath/di/HiltStartupTest.kt
- Delete: app/src/androidTest/java/com/example/ironpath/ExampleInstrumentedTest.kt
- Modify: app/build.gradle.kts

**Interfaces:**
- Produces: com.example.ironpath.HiltTestRunner and an entry-to-Home runtime DI smoke test.
- Consumes: HiltTestApplication, HiltAndroidRule, @AndroidEntryPoint MainActivity, and the production Hilt graph.

- [ ] **Step 1: Add Hilt instrumented-test dependencies**

~~~kotlin
dependencies {
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
}
~~~

Do not add kspTest: JVM tests directly construct their subjects and do not use Hilt.

- [ ] **Step 2: Create the custom runner**

~~~kotlin
package com.example.ironpath

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application =
        super.newApplication(
            classLoader,
            HiltTestApplication::class.java.name,
            context,
        )
}
~~~

Configure:

~~~kotlin
defaultConfig {
    testInstrumentationRunner = "com.example.ironpath.HiltTestRunner"
}
~~~

- [ ] **Step 3: Write the Hilt startup test**

~~~kotlin
package com.example.ironpath.di

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HiltStartupTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun entryToHome_resolvesHiltGraphAndRendersMainNavigation() {
        composeRule.onNodeWithText("GET STARTED").assertIsDisplayed().performClick()
        // MainActivity renders BottomNavItem.Home as its uppercase label.
        composeRule.onNodeWithText("HOME").assertIsDisplayed()
    }
}
~~~

This intentionally enters Home so HomeViewModel, repositories, DAOs, and Room resolve at runtime. Hilt compilation validates the remaining ViewModel constructors.

- [ ] **Step 4: Delete the generated assertion and compile the test APK**

Delete ExampleInstrumentedTest.kt, then run:

~~~bash
./gradlew assembleDebugAndroidTest
~~~

Expected: the custom runner, Hilt test component, Compose rule, and startup test compile.

- [ ] **Step 5: Run on Seeker, or use the managed-device fallback**

~~~bash
ANDROID_SERIAL=<seeker-serial> ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.ironpath.di.HiltStartupTest
~~~

Before running, use `adb devices -l` and select the physical device whose model is `Seeker`; do not commit or hardcode its serial. Expected: Entry renders, Get Started opens Home, and no Hilt/Room exception occurs. If Seeker is absent, offline, or unauthorized, continue to Task 6 and use `pixel2Api29DebugAndroidTest`. Task 5 is not complete until one real execution passes on Seeker or the documented fallback.

- [ ] **Step 6: Commit the test harness**

~~~bash
git add app/build.gradle.kts app/src/androidTest
git commit -m "Codex-test: verify Hilt startup on Android"
~~~

---

### Task 6: Gate the Migration on API 29 and Update Project Contracts

**Files:**
- Modify: app/build.gradle.kts
- Modify: .github/workflows/android-ci.yml
- Modify: AGENTS.md
- Modify: CLAUDE.md
- Modify: README.md

**Interfaces:**
- Produces: pixel2Api29DebugAndroidTest, required api29-hilt-smoke CI job, and durable Hilt project guidance.
- Consumes: HiltStartupTest from Task 5.

- [ ] **Step 1: Add the API 29 Gradle Managed Device**

~~~kotlin
android {
    testOptions {
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
}
~~~

Run ./gradlew tasks --group verification and confirm pixel2Api29DebugAndroidTest exists before editing CI.

- [ ] **Step 2: Add the required API 29 CI job**

Add a job named api29-hilt-smoke with checkout, JDK 17, Gradle permission, local.properties, and this KVM step:

~~~yaml
- name: Enable KVM
  run: |
    echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' \
      | sudo tee /etc/udev/rules.d/99-kvm4all.rules
    sudo udevadm control --reload-rules
    sudo udevadm trigger --name-match=kvm
~~~

Run only the Hilt startup class:

~~~yaml
- name: Run API 29 Hilt startup
  run: >-
    ./gradlew pixel2Api29DebugAndroidTest
    -Pandroid.testInstrumentationRunnerArguments.class=com.example.ironpath.di.HiltStartupTest
    -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
~~~

Upload app/build/reports/androidTests/managedDevice and app/build/outputs/androidTest-results/managedDevice with if: always(). Make api29-hilt-smoke a required PR check before merge.

- [ ] **Step 3: Update the durable project guidance**

In AGENTS.md make these replacements:

~~~markdown
- **DI:** Dagger Hilt 2.60.1 with KSP and AndroidX Hilt Compose integration
- **Language:** Kotlin 2.3.20 with JVM target 11
│   ├── di/                  # Hilt modules for third-party/interface bindings
~~~

Add:

~~~markdown
- Owned production classes use constructor injection; Hilt modules are reserved for third-party objects and interface bindings.
- JVM tests construct subjects directly. Hilt is used only for graph/startup/instrumented integration tests.
- Every new Hilt binding must compile in debug and release and resolve in the API 29 startup or journey suite.
~~~

Add the same test-device policy to AGENTS.md and CLAUDE.md:

~~~markdown
## Test Device Policy

- The default physical target for instrumented tests and smoke tests is Seeker. Confirm it appears as `device` in `adb devices -l` before running, and target it explicitly when more than one device is attached. Do not hardcode its serial in the repository.
- If Seeker is absent, offline, or unauthorized, use the Gradle-managed `pixel2Api29` emulator with `./gradlew pixel2Api29DebugAndroidTest`.
- Android Studio is optional for this workflow. The Gradle wrapper plus Android SDK command-line tools, platform-tools/adb, and the emulator are sufficient.
~~~

Apply the same DI and architecture-tree replacements to CLAUDE.md; it must not continue to say “Koin 4.2.0 (not Hilt)” or “Koin module definitions.” Retain its existing JVM target 11 line. Replace Koin with Hilt in README.md without changing unrelated product text.

- [ ] **Step 4: Run the complete migration gate**

~~~bash
./gradlew spotlessCheck lintDebug assembleDebug assembleRelease testDebugUnitTest assembleDebugAndroidTest pixel2Api29DebugAndroidTest
rg -n "org\.koin|koinViewModel|parametersOf|startKoin|io\.insert-koin|Koin module|Koin 4" app build.gradle.kts gradle/libs.versions.toml AGENTS.md CLAUDE.md README.md
~~~

Expected: all builds/tests pass and rg returns no matches. Inspect the managed-device report and confirm HiltStartupTest executed.

- [ ] **Step 5: Commit CI and documentation**

~~~bash
git add app/build.gradle.kts .github/workflows/android-ci.yml AGENTS.md CLAUDE.md README.md
git commit -m "Codex-ci: gate Hilt startup on API 29"
~~~

- [ ] **Step 6: Raise the prerequisite PR**

At execution time, use the gh-raise-pr skill:

~~~text
Title: feat10: migrate dependency injection to Hilt
Base: main
Head: feat/feat10-hilt-migration
~~~

The PR description must report the 107-test baseline, debug/release results, API 29 result, absence-of-Koin scan, and explicitly state that no product behavior or Room schema changed.

---

## Rollback and Failure Rules

- If Hilt 2.60.1 or AndroidX Hilt 1.3.0 is incompatible with AGP 9.1/Kotlin 2.3.20, stop at Task 1 and update this reviewed plan; do not downgrade the project toolchain opportunistically.
- If Hilt requires a source/bytecode target change in this exact toolchain, stop and revise the plan; do not fold a JVM-target migration into feat10.
- If SavedStateHandle changes route behavior, restore the existing orEmpty-to-NotFound behavior and add the failing route test before proceeding.
- If a ViewModel cannot be Hilt-constructed, fix the missing constructor/module binding. Do not inject the ViewModel directly, add an entry point, or retain a hidden Koin fallback.
- If the API 29 smoke test fails after compile succeeds, treat it as a migration blocker; do not defer it to the later testing plan.
- Reverting the PR must restore the prior Koin files and dependencies together. Never leave both containers active on main.

## Final Self-Review Checklist

- [ ] Every former Koin single has an equivalent @Singleton Hilt lifetime.
- [ ] All seven ViewModels use @HiltViewModel and constructor injection.
- [ ] Both route-scoped ViewModels use the existing Route keys through SavedStateHandle.
- [ ] All seven screens retain explicit ViewModel parameters for isolated tests.
- [ ] Hilt is the only DI dependency and runtime initializer.
- [ ] JVM tests remain ordinary JUnit tests.
- [ ] Hilt test rule executes before the Compose rule.
- [ ] API 29 runs the entry-to-Home smoke test in CI.
- [ ] Debug, release, unit, lint, formatting, test-APK, and managed-device gates pass.
- [ ] AGENTS.md, CLAUDE.md, and README.md describe Hilt and retain JVM target 11.
- [ ] The revised production-testing plan assumes this PR has merged.
