# Production Testing Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

This compatibility header comes from the planning template; IronPath's `AGENTS.md` still requires the user to explicitly approve either execution workflow before it is invoked.

**Goal:** Turn IronPath's strong mocked JVM-test suite into a production-grade test system that verifies real Room persistence, deterministic time behavior, Compose UI and navigation, full user journeys, accessibility, release builds, and enforceable CI gates for every future feature.

**Architecture:** Begin only after the Hilt prerequisite PR in `2026-07-16-hilt-migration.md` has merged. Keep the fast JVM suite as the base of the pyramid, add real in-memory/file-backed Room tests and migration tests under `androidTest`, test pure screen composables independently, and reserve a small number of real-app tests for Hilt/navigation/persistence boundaries. Make time and validation injectable/pure so boundary cases are deterministic. Split delivery into four reviewable PRs so database, UI, and CI risks can be approved independently.

**Tech Stack:** Kotlin 2.3.20/JVM 11 with AGP running on JDK 17, Dagger Hilt 2.60.1, AndroidX Hilt 1.3.0, JUnit 4, MockK, kotlinx-coroutines-test, Turbine, Room 2.8.4 `room-testing`, AndroidX Test/Espresso, Compose UI Test, Navigation Test 2.9.0, JaCoCo, Gradle Managed Devices on API 29 and API 36.

## Global Constraints

- Do not begin implementation until the user explicitly approves this reviewed plan.
- Do not begin until `feat10: migrate dependency injection to Hilt` is merged and its API 29 Hilt startup check is green.
- Follow red-green-refactor: every production behavior change starts with a failing test, the failure is observed, and only then is minimal implementation added.
- `docs/ironpath-mvp-prd.md` remains the default product source of truth unless the user explicitly approves the current implementation as the replacement contract.
- Never use wall-clock time directly in ViewModels, repositories, domain logic, or test assertions; use the shared `TimeProvider` boundary.
- Never rely on entity-constructor UUID/time defaults at a production creation site; use `IdProvider` plus `TimeProvider`, and pass fixed values in tests.
- Tests must be deterministic: no `Thread.sleep`, no arbitrary coroutine delays, no dependence on test execution order, locale, timezone, or the current calendar date.
- Room behavior is verified against real Room-generated DAOs; mocked repository tests remain only where they verify orchestration that a database test cannot express cheaply.
- Every new feature must include the lowest-cost tests that prove its domain logic, persistence behavior, UI states/actions, navigation contract, and failure behavior.
- CI must gate pull requests on formatting, lint, debug and release assembly, JVM tests, core coverage thresholds, migration tests, and the API 29 smoke device suite.
- API 36 instrumentation and performance suites may run nightly if their runtime makes them unsuitable for every pull request, but failures remain release-blocking.
- Coverage is a guardrail, not the goal: enforce at least 85% line and 70% branch coverage across domain, repositories, and non-dev ViewModels; do not include generated Room/KSP code or Compose rendering code in this threshold.
- Use test tags only for stable interaction targets; assert user-visible text/content descriptions whenever those are part of the product contract.
- Each task below ends in an independently testable commit. Each PR must pass the full gate defined for that phase before merge.

---

## Baseline and Scope Decision

Current verified baseline on 2026-07-16:

- 107 JVM tests pass.
- The only instrumentation test is the generated package-name assertion.
- JaCoCo overall coverage is 16.3% line / 14.3% branch because UI and generated code are included.
- Handwritten repositories, domain classes, and non-dev ViewModels are approximately 92.0% line / 71.8% branch covered.
- `lintDebug` completes with 0 errors and 24 warnings.
- `assembleRelease` succeeds.
- CI reports coverage but does not enforce a threshold and does not run lint, release assembly, Room/migration tests, Compose tests, or device tests.

Product-contract conflicts identified during review:

1. The locked PRD prohibits workout-day reassignment and exercise add/edit/remove in Planner Review; the current UI and tests support those behaviors.
2. The locked PRD says records are manual and add-only; the current UI and tests support edit/delete and creating records from workout-log sets.

**Recorded decision (2026-07-16): PRD-authoritative.** Remove workout-day reassignment and exercise add/edit/remove/reorder controls from Planner Review while retaining whole-workout deletion, regeneration, and acceptance. Remove record edit/delete and workout-log-to-record actions while retaining manual Add Record. Replace the disputed unit tests with UI absence/read-only assertions. Keep `RecordSource` and migration support for historical `Logged` rows as future-ready schema, not as an active creation path.

Tasks 1-6 intentionally deferred tests for those conflicting branches. Tasks 7-9 apply the recorded decision and must not reintroduce them.

## Delivery Sequence

| PR | Tasks | Merge gate |
|---|---|---|
| `feat10.1: establish deterministic testing foundation` | 1-3 | JVM tests, coverage gate, lint, debug/release assembly |
| `feat10.2: verify Room persistence and migrations` | 4-6 | PR 1 gate plus Room DAO, transaction, and migration tests added to the existing API 29 CI job |
| `feat10.3: verify Compose navigation and critical journeys` | 7-9 | PR 2 gate plus screen, navigation, Hilt, and critical-flow tests in the API 29 PR job |
| `feat10.4: enforce production quality gates` | 10-12 | PR 3 gate plus accessibility, API 36, performance smoke, and the final CI matrix |

Branches follow repository convention: `feat/feat10.1-testing-foundation`, `feat/feat10.2-room-integrity`, `feat/feat10.3-ui-journeys`, and `feat/feat10.4-production-gates`.

## File Responsibility Map

**Create:**

- `docs/testing-strategy.md` — durable test pyramid, risk rules, future-feature Definition of Done, CI matrix, and flake policy.
- `app/src/main/java/com/example/ironpath/domain/time/TimeProvider.kt` — the only production source for current instant/date/timezone.
- `app/src/main/java/com/example/ironpath/domain/identity/IdProvider.kt` — the only production source for newly generated entity IDs.
- `app/src/main/java/com/example/ironpath/domain/validation/RecordDraftValidator.kt` — pure record validation and normalization.
- `app/src/main/java/com/example/ironpath/domain/session/SessionSetInput.kt` — pure set-input parsing/completion rules.
- `app/src/test/java/com/example/ironpath/testutil/FakeTimeProvider.kt` — mutable deterministic time for JVM tests.
- `app/src/test/java/com/example/ironpath/testutil/FakeIdProvider.kt` — deterministic sequential IDs for JVM tests.
- `app/src/test/java/com/example/ironpath/domain/planner/WorkoutDateRulesTest.kt` — direct calendar boundary tests.
- `app/src/test/java/com/example/ironpath/domain/validation/RecordDraftValidatorTest.kt` — record validation boundary matrix.
- `app/src/test/java/com/example/ironpath/domain/session/SessionSetInputTest.kt` — set parsing/completion matrix.
- `app/src/main/java/com/example/ironpath/di/CoreBindingsModule.kt` — Hilt bindings for deterministic time and ID interfaces.
- `app/src/androidTest/java/com/example/ironpath/testutil/TestDatabaseModule.kt` — Hilt test replacement for the production Room database in real-app tests.
- `app/src/androidTest/java/com/example/ironpath/testutil/TestCoreBindingsModule.kt` — Hilt test replacement for deterministic time and IDs.
- `app/src/androidTest/java/com/example/ironpath/testutil/MutableTimeProvider.kt` — per-test fixed/adjustable clock used by Hilt journeys.
- `app/src/androidTest/java/com/example/ironpath/testutil/SequenceIdProvider.kt` — per-test deterministic ID sequence used by Hilt journeys.
- `app/src/androidTest/java/com/example/ironpath/testutil/RoomTestDatabaseRule.kt` — real in-memory database lifecycle.
- `app/src/androidTest/java/com/example/ironpath/testutil/FileBackedRoomTestDatabaseRule.kt` — reopenable isolated database lifecycle.
- `app/src/androidTest/java/com/example/ironpath/testutil/TestData.kt` — deterministic Room/entity fixtures.
- `app/src/androidTest/java/com/example/ironpath/data/local/dao/*DaoTest.kt` — real DAO query, ordering, constraint, and cascade tests.
- `app/src/androidTest/java/com/example/ironpath/data/repository/SessionRepositoryIntegrationTest.kt` — cross-DAO transaction tests.
- `app/src/androidTest/java/com/example/ironpath/data/local/IronPathDatabaseMigrationTest.kt` — schema and data-preservation tests.
- `app/src/main/java/com/example/ironpath/ui/testing/TestTags.kt` — stable semantics tags shared by screens and tests.
- `app/src/androidTest/java/com/example/ironpath/ui/screens/**/*ScreenTest.kt` — isolated screen-state and interaction tests.
- `app/src/androidTest/java/com/example/ironpath/ui/navigation/IronPathNavigationTest.kt` — real NavHost/back-stack tests.
- `app/src/androidTest/java/com/example/ironpath/e2e/*JourneyTest.kt` — small, critical real-app journeys.
- `benchmark/build.gradle.kts`, `benchmark/src/main/AndroidManifest.xml`, `benchmark/src/main/java/com/example/ironpath/StartupBenchmark.kt` — release startup, baseline-profile generation, and critical interaction performance coverage.

**Modify:**

- `AGENTS.md` — broaden TDD from selected logic classes to production-level feature coverage and required gates.
- `gradle/libs.versions.toml` — add Room testing, Navigation testing, AndroidX Test Core, benchmark, and baseline-profile artifacts/plugins; Hilt dependencies already come from the prerequisite PR.
- `app/build.gradle.kts` — add test dependencies, exported schemas as Android-test assets, managed devices, test runner arguments, and core coverage verification.
- `settings.gradle.kts` — include the `benchmark` module.
- `.github/workflows/android-ci.yml` — run deterministic PR and nightly test matrices and publish all reports.
- `app/src/androidTest/java/com/example/ironpath/di/HiltStartupTest.kt` — extend the prerequisite startup test as new production bindings are added.
- Time-dependent domain/ViewModel/screen files — consume `TimeProvider` or explicit `today`/`nowMillis` inputs.
- Screen composables — add stable semantics and move validation/set parsing into pure tested helpers.

---

### Task 1: Establish the Durable Testing Contract

**Files:**
- Create: `docs/testing-strategy.md`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: the locked PRD, current architecture, and current CI/test baseline.
- Produces: one durable Definition of Done that all later features and agents must follow.

- [ ] **Step 1: Write `docs/testing-strategy.md` with the exact policy**

The document must contain these enforceable sections:

```markdown
# IronPath Testing Strategy

## Test pyramid
- Pure JVM tests: domain rules, reducers/validators, ViewModel state, error and concurrency branches.
- Room integration tests: every DAO query, constraint, cascade, migration, and cross-DAO transaction.
- Compose tests: every user-visible state, validation message, enabled/disabled action, and callback contract.
- Real-app journey tests: only critical navigation/persistence paths; keep them few and deterministic.
- Performance/accessibility: release startup, critical scrolling/input, semantics, font scale, and touch targets.

## Feature risk matrix
| Change | Required proof |
|---|---|
| Domain or ViewModel | failing JVM test, success/error/boundary cases |
| Entity, DAO, query, transaction | real Room test; migration test for schema changes |
| Screen or component | isolated Compose state/action test and accessibility semantics |
| Route/back stack | NavHost test |
| Critical user journey | real-app persistence test |
| Time/date behavior | fixed timezone and boundary-date tests through TimeProvider |
| Performance-sensitive flow | benchmark or explicit performance evidence |

## Definition of Done
1. Product contract and acceptance criteria are written before code.
2. Tests fail for the intended reason before production code changes.
3. Happy path, empty state, validation, failure, duplicate/concurrent action, and recreation behavior are covered where applicable.
4. Relevant JVM, Room, Compose, navigation, and journey tests pass locally.
5. Spotless, lint, debug build, release build, and coverage gates pass.
6. Schema changes include exported schema JSON plus every supported migration path test.
7. No disabled, ignored, order-dependent, sleeping, or retry-masked test is merged.

## Coverage policy
- Scope: domain, repositories, and non-dev ViewModels.
- Minimum: 85% line and 70% branch.
- Changed production logic must not reduce either metric.
- Compose/generated code is assessed through behavior tests, not JaCoCo percentage.

## Flake policy
- Quarantine is not a pass: a flaky test blocks release until fixed or removed with an approved replacement.
- Use test schedulers/idling/observable state, never sleeps.
- Fix locale, timezone, clock, animation clock, and database state in test setup.
```

- [ ] **Step 2: Replace the narrow TDD paragraph in `AGENTS.md`**

Use this exact project instruction:

```markdown
- **Production-level tests are required for every feature and bug fix.** Start with a failing test and follow `docs/testing-strategy.md`. Select every applicable layer: JVM domain/ViewModel tests, real Room DAO/transaction/migration tests, isolated Compose tests, navigation tests, and a critical real-app journey test. Cover happy, empty, validation, error, boundary-time, duplicate/concurrent action, and recreation behavior where relevant. No feature is complete until Spotless, lint, debug/release assembly, applicable device tests, and core coverage gates pass.
```

- [ ] **Step 3: Verify policy completeness and terminology**

Run:

```bash
rg -n "Production-level tests|Definition of Done|85% line|70% branch|Flake policy" AGENTS.md docs/testing-strategy.md
```

Expected: every term appears and the old ViewModel/repository/domain-only TDD sentence is absent.

- [ ] **Step 4: Commit the durable contract**

```bash
git add AGENTS.md docs/testing-strategy.md
git commit -m "docs: define production testing standard"
```

---

### Task 2: Add Deterministic Time and Pure Validation Boundaries

**Files:**
- Create: `app/src/main/java/com/example/ironpath/domain/time/TimeProvider.kt`
- Create: `app/src/main/java/com/example/ironpath/domain/identity/IdProvider.kt`
- Create: `app/src/main/java/com/example/ironpath/domain/validation/RecordDraftValidator.kt`
- Create: `app/src/main/java/com/example/ironpath/domain/session/SessionSetInput.kt`
- Create: `app/src/test/java/com/example/ironpath/testutil/FakeTimeProvider.kt`
- Create: `app/src/test/java/com/example/ironpath/testutil/FakeIdProvider.kt`
- Create: `app/src/test/java/com/example/ironpath/domain/validation/RecordDraftValidatorTest.kt`
- Create: `app/src/test/java/com/example/ironpath/domain/session/SessionSetInputTest.kt`
- Create: `app/src/main/java/com/example/ironpath/di/CoreBindingsModule.kt`
- Modify: `PlanGenerator.kt`, `HomeViewModel.kt`, `PlanViewModel.kt`, `ActiveViewModel.kt`, `WorkoutPreviewViewModel.kt`, `WorkoutLogDetailViewModel.kt`, `AddRecordScreen.kt`, `ActiveScreen.kt`
- Modify: their existing JVM tests.

**Interfaces:**
- Produces: `TimeProvider.now(): Instant`, `TimeProvider.today(): LocalDate`, `TimeProvider.zoneId: ZoneId`, `IdProvider.newId(): String`, `RecordDraftValidator.validate(...)`, and `SessionSetInput.withWeight/withReps(...)`.
- Consumes: the Hilt constructor-injected graph from the prerequisite PR and existing entity types.

- [ ] **Step 1: Write failing time and validation tests**

Required record cases:

```kotlin
@Test fun `all invalid fields are returned with field-specific messages`()
@Test fun `zero negative NaN and infinite record weights attach errors to weight field`()
@Test fun `malformed and future dates attach errors to date field`()
@Test fun `valid record draft is trimmed normalized and mapped`()
```

Required session-set cases:

```kotlin
@Test fun `set is complete only when reps and weight are both present`()
@Test fun `zero weight is valid for a bodyweight exercise`()
@Test fun `negative or non-finite weight is cleared and cannot complete a set`()
@Test fun `zero or negative reps are cleared and cannot complete a set`()
@Test fun `completedAt is assigned from TimeProvider and cleared when an input is removed`()
```

Run:

```bash
./gradlew testDebugUnitTest --tests '*RecordDraftValidatorTest' --tests '*SessionSetInputTest'
```

Expected: compilation fails because the new types do not exist.

- [ ] **Step 2: Implement the time boundary**

```kotlin
interface TimeProvider {
    val zoneId: ZoneId
    fun now(): Instant
    fun today(): LocalDate = now().atZone(zoneId).toLocalDate()
    fun epochMillis(): Long = now().toEpochMilli()
}

class SystemTimeProvider @Inject constructor() : TimeProvider {
    private val clock: Clock = Clock.systemDefaultZone()
    override val zoneId: ZoneId get() = clock.zone
    override fun now(): Instant = clock.instant()
}

fun interface IdProvider {
    fun newId(): String
}

class UuidIdProvider @Inject constructor() : IdProvider {
    override fun newId(): String = UUID.randomUUID().toString()
}
```

Test utility contract:

```kotlin
class FakeTimeProvider(
    private var instant: Instant = Instant.parse("2026-07-16T19:00:00Z"),
    override val zoneId: ZoneId = ZoneId.of("America/Vancouver"),
) : TimeProvider {
    override fun now(): Instant = instant
    fun advanceBy(duration: Duration) { instant = instant.plus(duration) }
}

class FakeIdProvider(private var next: Int = 1) : IdProvider {
    override fun newId(): String = "test-id-${next++}"
}
```

- [ ] **Step 3: Implement pure record and session-set validation**

Use this contract so validation is independent from Compose:

```kotlin
enum class RecordField { ExerciseName, Weight, Date }

data class ValidatedRecordDraft(
    val exerciseName: String,
    val normalizedExerciseName: String,
    val weightKg: Double,
    val achievedOn: String,
    val note: String?,
)

sealed interface RecordDraftResult {
    data class Valid(val draft: ValidatedRecordDraft) : RecordDraftResult
    data class Invalid(val errors: Map<RecordField, String>) : RecordDraftResult
}

class RecordDraftValidator {
    fun validate(
        exerciseName: String,
        weightText: String,
        dateText: String,
        note: String,
        today: LocalDate,
    ): RecordDraftResult {
        val name = exerciseName.trim()
        val weight = weightText.toDoubleOrNull()
        val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
        val errors = buildMap {
            if (name.isEmpty()) put(RecordField.ExerciseName, "Exercise name is required")
            if (weight == null || !weight.isFinite() || weight <= 0.0) {
                put(RecordField.Weight, "Weight must be a positive number")
            }
            when {
                date == null -> put(RecordField.Date, "Invalid date format (use YYYY-MM-DD)")
                date.isAfter(today) -> put(RecordField.Date, "Date cannot be in the future")
            }
        }
        if (errors.isNotEmpty()) return RecordDraftResult.Invalid(errors)
        return RecordDraftResult.Valid(
            ValidatedRecordDraft(
            exerciseName = name,
            normalizedExerciseName = name.lowercase().trim(),
            weightKg = requireNotNull(weight),
            achievedOn = requireNotNull(date).toString(),
            note = note.ifBlank { null },
            ),
        )
    }
}
```

`SessionSetInput` must preserve entity identity and `isExtra`, accept `0.0` kg, require reps `> 0`, reject non-finite/negative weight, and set `completedAt` only when both parsed values are valid:

```kotlin
object SessionSetInput {
    fun withWeight(set: SessionSet, text: String, nowMillis: Long): SessionSet {
        val weight = text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        return applyCompletion(set.copy(weightKg = weight), nowMillis)
    }

    fun withReps(set: SessionSet, text: String, nowMillis: Long): SessionSet {
        val reps = text.toIntOrNull()?.takeIf { it > 0 }
        return applyCompletion(set.copy(reps = reps), nowMillis)
    }

    private fun applyCompletion(set: SessionSet, nowMillis: Long): SessionSet =
        set.copy(
            completedAt = if (set.reps != null && set.weightKg != null) {
                set.completedAt ?: nowMillis
            } else {
                null
            },
        )
}
```

- [ ] **Step 4: Inject `TimeProvider` into production logic**

Replace every production `LocalDate.now()`, `System.currentTimeMillis()`, and `ZoneId.systemDefault()` used for business behavior. Audit every entity default using `UUID.randomUUID()` or current time and make every production creation site pass explicit IDs/timestamps. The system implementations from Step 2 are constructor-injectable; bind them as Hilt singletons:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class CoreBindingsModule {
    @Binds
    @Singleton
    abstract fun bindTimeProvider(implementation: SystemTimeProvider): TimeProvider

    @Binds
    @Singleton
    abstract fun bindIdProvider(implementation: UuidIdProvider): IdProvider
}
```

Add the new providers to the constructor-injected production classes that need them; Hilt compile errors are the binding-change guard. Remove default `id` values from all ten Room entities and remove default time values from `WeeklyPlan.createdAt`, `ActiveSession.startedAt/lastUpdatedAt`, and `PersonalRecord.createdAt` after every creation site supplies explicit values. Update `PlanGenerator`, `StartPlannedWorkoutUseCase`, `PlanViewModel.addExerciseToReview`, `ActiveViewModel.addExtraSet/finishWorkout`, `HistoryViewModel`, `DevToolsSeeder`, previews, fixtures, and tests to obtain IDs/times explicitly. Snapshot conversions continue reusing the already-explicit session exercise/set IDs. `AddRecordScreen` receives `today: LocalDate`, delegates validation to `RecordDraftValidator`, renders each returned error beside/semantically on its field, and emits only `ValidatedRecordDraft`. `HistoryViewModel` maps a valid draft to `PersonalRecord` using `IdProvider.newId()` and `TimeProvider.epochMillis()`; edits preserve the existing ID/createdAt. Active set fields delegate to `SessionSetInput` rather than calculating completion timestamps in the composable.

- [ ] **Step 5: Update existing tests to use `FakeTimeProvider`**

Replace all test calls to `LocalDate.now()`, `System.currentTimeMillis()`, and implicit entity IDs/times with `FakeTimeProvider`, `FakeIdProvider`, and explicit fixture values. Timer tests advance both the fake provider and coroutine scheduler; no real delay is allowed.

- [ ] **Step 6: Run focused then full verification**

```bash
./gradlew testDebugUnitTest
./gradlew spotlessCheck lintDebug assembleDebug assembleRelease
```

Expected: all 107 existing tests plus the new boundary tests pass; lint has no new warnings; both APK variants assemble.

- [ ] **Step 7: Commit deterministic boundaries**

```bash
git add app/src/main app/src/test
git commit -m "feat10.1: make time and input rules deterministic"
```

---

### Task 3: Close Core Unit and Hilt Binding Coverage Gaps

**Files:**
- Create: `app/src/test/java/com/example/ironpath/domain/planner/WorkoutDateRulesTest.kt`
- Create: `app/lint-baseline.xml`
- Modify: existing repository and ViewModel tests.
- Modify: `app/src/main/java/com/example/ironpath/di/CoreBindingsModule.kt`
- Modify: `app/src/androidTest/java/com/example/ironpath/di/HiltStartupTest.kt`
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`

**Interfaces:**
- Produces: compile-time-valid Hilt bindings and a `verifyCoreCoverage` Gradle gate.
- Consumes: the deterministic time and validation boundaries from Task 2.

- [ ] **Step 1: Write direct workout-date rule tests**

Cover exactly:

```kotlin
@Test fun `today ignores invalid completed skipped and past workouts`()
@Test fun `next upcoming sorts by scheduled date then day of week`()
@Test fun `same weekday next week is not today`()
@Test fun `year boundary returns January workout after December`()
@Test fun `DST transition dates remain calendar-date based in America Vancouver`()
@Test fun `empty list returns null for today and next`()
```

- [ ] **Step 2: Add missing state/action/failure tests**

Add focused tests for currently uncovered production behavior:

- `PlanViewModel`: `setGoal`, `toggleDay`, `clearUndo`, `backToSetup`, suggestion merge/sort, duplicate accept tap, repository failure resets accept guard. Defer new tests for `updateExerciseInReview` and other disputed review controls until the product-contract decision.
- `HistoryViewModel`: show/hide add flow, suggestion merge/sort, save success, duplicate rapid save, insert failure leaves form open and exposes error.
- `ActiveViewModel`: timer uses fake time, repository failure resets finish guard, callback is not invoked on failure.
- Repositories: replace five `kotlin.assert(flow === expected)` calls with `assertSame`; use non-relaxed mocks for orchestration tests.
- Delete `ExampleUnitTest.kt` because it provides no product signal.

- [ ] **Step 3: Extend the Hilt graph and runtime smoke test**

The Hilt prerequisite already provides Hilt/KSP dependencies, `DatabaseModule`, the custom runner, and `HiltStartupTest`. Add only `CoreBindingsModule` from Task 2; do not add another DI test library or runtime container.

Extend `HiltStartupTest` with injected roots:

```kotlin
@Inject lateinit var timeProvider: TimeProvider
@Inject lateinit var idProvider: IdProvider

@Test
fun productionGraph_resolvesDeterministicBoundaries() {
    assertTrue(::timeProvider.isInitialized)
    assertTrue(::idProvider.isInitialized)
}
```

Run Hilt generation for debug, release, and androidTest:

```bash
./gradlew assembleDebug assembleRelease assembleDebugAndroidTest
./gradlew pixel2Api29DebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.ironpath.di.HiltStartupTest
```

Expected: a missing constructor or binding fails KSP/assembly, and both Hilt startup tests pass on API 29. JVM tests continue constructing subjects directly.

- [ ] **Step 4: Add an enforceable core coverage task**

The verification task must parse `app/build/reports/coverage/test/debug/report.xml`, include only source files under:

```text
com/example/ironpath/domain/**
com/example/ironpath/data/repository/**
com/example/ironpath/ui/screens/**/*ViewModel.kt
```

Exclude `DevToolsViewModel.kt`, generated classes, entities, DAOs, and Compose screen files. Fail below 85% line or 70% branch. Wire it after `createDebugUnitTestCoverageReport`, and print both measured percentages in the failure/success message.

Use an explicit XML-based task so the gate does not depend on the unfiltered project total:

```kotlin
tasks.register("verifyCoreCoverage") {
    dependsOn("createDebugUnitTestCoverageReport")
    doLast {
        val report = layout.buildDirectory.file(
            "reports/coverage/test/debug/report.xml"
        ).get().asFile
        check(report.isFile) { "Coverage report not found: $report" }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder().parse(report)
        val sourceFiles = document.getElementsByTagName("sourcefile")
        val totals = mutableMapOf("LINE" to intArrayOf(0, 0), "BRANCH" to intArrayOf(0, 0))
        for (index in 0 until sourceFiles.length) {
            val source = sourceFiles.item(index) as org.w3c.dom.Element
            val packageName = (source.parentNode as org.w3c.dom.Element)
                .getAttribute("name")
            val path = "$packageName/${source.getAttribute("name")}"
            val included = path.startsWith("com/example/ironpath/domain/") ||
                path.startsWith("com/example/ironpath/data/repository/") ||
                (path.contains("com/example/ironpath/ui/screens/") &&
                    path.endsWith("ViewModel.kt") &&
                    !path.endsWith("DevToolsViewModel.kt"))
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
            "Core coverage failed: line %.1f%% (min 85%%), branch %.1f%% (min 70%%)"
                .format(line, branch)
        }
        println("Core coverage passed: line %.1f%%, branch %.1f%%".format(line, branch))
    }
}
```

Register this task only inside `if (project.hasProperty("enableCoverage")) { ... }`, because AGP creates `createDebugUnitTestCoverageReport` only for coverage-enabled configuration. Every command that invokes `verifyCoreCoverage` must include `-PenableCoverage`.

- [ ] **Step 5: Baseline existing lint warnings and reject new warnings**

Add:

```kotlin
lint {
    baseline = file("lint-baseline.xml")
    warningsAsErrors = true
}
```

Run `./gradlew lintDebug` once to generate `app/lint-baseline.xml` from the 24 verified pre-existing warnings, inspect the baseline to ensure it contains no error-severity issue, then run `./gradlew lintDebug` again. Expected: the second run passes and any newly introduced warning fails the task.

- [ ] **Step 6: Observe the gate pass**

```bash
./gradlew createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage
```

Expected: core coverage remains at or above 85% line / 70% branch.

- [ ] **Step 7: Run the PR 1 merge gate**

```bash
./gradlew spotlessCheck lintDebug assembleDebug assembleRelease testDebugUnitTest createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage
```

Expected: every task succeeds with no new lint warning.

- [ ] **Step 8: Commit core coverage, lint baseline, and Hilt binding verification**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/lint-baseline.xml app/src/main app/src/test app/src/androidTest/java/com/example/ironpath/di/HiltStartupTest.kt
git commit -m "feat10.1: enforce core behavior and Hilt coverage"
```

---

### Task 4: Add Real Room DAO and Constraint Tests

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/androidTest/java/com/example/ironpath/testutil/RoomTestDatabaseRule.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/testutil/TestData.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/data/local/dao/PlanDaoTest.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/data/local/dao/SessionDaoTest.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/data/local/dao/HistoryDaoTest.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/data/local/dao/RecordDaoTest.kt`

**Interfaces:**
- Produces: an isolated real Room database per test and deterministic entity factories.
- Consumes: Room schemas, DAOs, entities, AndroidJUnit4.

- [ ] **Step 1: Add test dependencies and schema assets**

Add catalog aliases using existing production versions plus explicit AndroidX Test Core 1.7.0:

```toml
androidxTestCore = "1.7.0"
androidxTestRunner = "1.7.0"
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
androidx-navigation-testing = { group = "androidx.navigation", name = "navigation-testing", version.ref = "navigationCompose" }
androidx-test-core-ktx = { group = "androidx.test", name = "core-ktx", version.ref = "androidxTestCore" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
```

At implementation time, confirm both `androidx.test:core-ktx:1.7.0` and `androidx.test:runner:1.7.0` are still stable compatible releases; keep separate catalog versions because those artifacts can advance independently. Update a version only if official AndroidX release notes show a newer stable. Add `androidTestImplementation(libs.androidx.room.testing)`, `androidTestImplementation(libs.androidx.test.core.ktx)`, `androidTestImplementation(libs.androidx.test.runner)`, and expose exported schemas:

```kotlin
sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
```

- [ ] **Step 2: Create the real database rule**

```kotlin
class RoomTestDatabaseRule : TestWatcher() {
    lateinit var database: IronPathDatabase
        private set

    override fun starting(description: Description) {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            IronPathDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    override fun finished(description: Description) {
        database.close()
    }
}
```

`TestData.kt` must use fixed IDs, ISO dates, and timestamps; no entity factory may use a default UUID or current-time constructor value.

- [ ] **Step 3: Write Plan DAO tests before changing production code**

Required tests:

```kotlin
@Test fun createPlanWithWorkouts_archivesPreviousActivePlan_andPersistsCompleteGraph()
@Test fun workoutsAndExercises_areReturnedInProductOrder()
@Test fun deletingWorkout_cascadesItsExercisesOnly()
@Test fun activePlanFlow_updatesAfterReplacement()
@Test fun duplicatePrimaryKey_rollsBackWholePlanTransaction()
```

- [ ] **Step 4: Write Session DAO tests**

Required tests:

```kotlin
@Test fun startNewSession_replacesExistingSession_andCascadesOldChildren()
@Test fun sessionExercisesAndSets_areReturnedInOrder()
@Test fun completedSetCount_requiresBothStoredValues()
@Test fun deletingSession_cascadesExercisesAndSets()
```

If the real query counts negative or non-finite values that pure validation prevents, document this as a domain-boundary invariant; do not duplicate UI validation in SQL.

- [ ] **Step 5: Write History and Record DAO tests**

Required tests:

```kotlin
@Test fun logs_areSortedMostRecentFirst()
@Test fun loggedSnapshot_cascadesFromLogToExercisesToSets()
@Test fun records_sortByAchievedDateThenCreatedAtDescending()
@Test fun exactNormalizedDateWeightDuplicate_isRejectedByUniqueIndex()
@Test fun duplicateAcrossDifferentDateOrWeight_isAllowed()
```

Keep `sameRecordId_isExcludedDuringDuplicateCheck()` (record editing) and `loggedRecords_areFilteredByWorkoutLogAndSorted()` (log-to-record derivation) on the decision-contingent list. Add them here only if the Task 7 product decision explicitly retains those disputed controls; otherwise delete or replace the corresponding production paths without first locking them in through persistence tests.

- [ ] **Step 6: Run on a connected API 29-or-newer device**

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.ironpath.data.local.dao.PlanDaoTest,com.example.ironpath.data.local.dao.SessionDaoTest,com.example.ironpath.data.local.dao.HistoryDaoTest,com.example.ironpath.data.local.dao.RecordDaoTest
```

Expected: all DAO tests pass against Room-generated implementations.

- [ ] **Step 7: Commit real DAO coverage**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/androidTest
git commit -m "feat10.2: verify Room DAO behavior"
```

---

### Task 5: Verify Cross-DAO Session Completion Transactions

**Files:**
- Create: `app/src/androidTest/java/com/example/ironpath/data/repository/SessionRepositoryIntegrationTest.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/testutil/FileBackedRoomTestDatabaseRule.kt`
- Modify only if a failing test proves a defect: `SessionRepository.kt` or DAO/entity declarations.

**Interfaces:**
- Consumes: real `IronPathDatabase`, `SessionRepository`, deterministic fixtures.
- Produces: proof that workout completion is atomic and preserves the immutable history snapshot.

- [ ] **Step 1: Add an isolated reopenable database rule**

```kotlin
class FileBackedRoomTestDatabaseRule : TestWatcher() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var databaseName: String
    private val opened = mutableListOf<IronPathDatabase>()

    fun open(): IronPathDatabase = Room.databaseBuilder(
            context,
            IronPathDatabase::class.java,
            databaseName,
        ).addMigrations(IronPathDatabase.MIGRATION_1_2)
        .allowMainThreadQueries()
        .build()
        .also(opened::add)

    override fun starting(description: Description) {
        databaseName = "${description.testClass.simpleName}-${description.methodName}.db"
        context.deleteDatabase(databaseName)
    }

    override fun finished(description: Description) {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
        context.deleteDatabase(databaseName)
    }
}
```

The reopen test must close its first database instance before calling `open()` again. Other integration tests continue using the faster in-memory rule.

- [ ] **Step 2: Write the integration tests**

Required tests:

```kotlin
@Test fun completeSession_snapshotsExercisesAndSets_insertsOneLog_andDeletesActiveGraph()
@Test fun completeSession_withNoExercises_writesLogAndDeletesSession()
@Test fun completeSession_preservesUnfinishedAndExtraSetsExactly()
@Test fun completeSession_duplicateLogId_rollsBackSnapshotAndKeepsActiveSession()
@Test fun reopeningFileBackedDatabase_preservesCompletedHistoryAndNoActiveSession()
```

The rollback test must preinsert the conflicting log ID, call `completeSession`, assert the expected SQLite constraint exception, then prove the active session/exercises/sets still exist and no partial logged snapshot was inserted.

- [ ] **Step 3: Run the focused repository integration suite**

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.ironpath.data.repository.SessionRepositoryIntegrationTest
```

Expected: tests pass without mocking `RoomDatabase.withTransaction`.

- [ ] **Step 4: Remove redundant mock-only transaction tests**

Keep the fast mapping/orchestration assertions if they add unique signal. Remove tests whose only assertion is that mocked `withTransaction` was invoked; the real rollback test supersedes them.

- [ ] **Step 5: Commit transaction coverage**

```bash
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat10.2: verify session completion transactions"
```

---

### Task 6: Test Every Supported Room Migration Path

**Files:**
- Create: `app/src/androidTest/java/com/example/ironpath/data/local/IronPathDatabaseMigrationTest.kt`
- Verify: `app/schemas/com.example.ironpath.data.local.IronPathDatabase/1.json`
- Verify: `app/schemas/com.example.ironpath.data.local.IronPathDatabase/2.json`
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/android-ci.yml`

**Interfaces:**
- Consumes: `IronPathDatabase.MIGRATION_1_2` and exported schemas.
- Produces: schema validation, old-data preservation proof, and a reusable all-migrations test pattern.

- [ ] **Step 1: Write the failing migration tests with `MigrationTestHelper`**

Required tests:

```kotlin
@Test fun migrate1To2_preservesExistingPlansSessionsLogsAndRecords_andCreatesSnapshotTables()
@Test fun migrate1To2_enforcesNewForeignKeysAndIndexes()
@Test fun allMigrations_openLatestSchemaAndAllDaosRemainUsable()
```

Seed version-1 tables using SQL with fixed IDs. After `runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)`, query every preserved row and inspect `PRAGMA foreign_key_list`, `PRAGMA index_list`, and the two new tables.

- [ ] **Step 2: Run only the migration class**

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.ironpath.data.local.IronPathDatabaseMigrationTest
```

Expected before production changes: either pass, or expose a specific schema/data defect. Never use destructive migration as a test fix.

- [ ] **Step 3: Add the migration regression rule to `docs/testing-strategy.md`**

Exact rule:

```markdown
Every database version increment must export the new schema and add both a direct previous-to-current migration test and an oldest-supported-to-current all-migrations test. The test must assert representative data, constraints, and indexes; schema validation alone is insufficient.
```

- [ ] **Step 4: Expand the existing API 29 Hilt job before PR 2 merges**

The Hilt prerequisite already creates `pixel2Api29`, enables KVM, uploads managed-device artifacts, and gates `HiltStartupTest`. Keep the required job name `api29-hilt-smoke` so repository branch protection remains attached; expand only its displayed step name and test filter to include the real Room/transaction/migration package:

```yaml
- name: Run API 29 Hilt and persistence tests
  run: >-
    ./gradlew pixel2Api29DebugAndroidTest
    -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
    -Pandroid.testInstrumentationRunnerArguments.package=com.example.ironpath
```

At this phase the package contains the prerequisite Hilt startup test plus the persistence suites, so the package filter is complete and future-proof. Keep the existing KVM/artifact steps and required-check status. PR 2 and PR 3 are not allowed to rely on a developer's local physical device.

- [ ] **Step 5: Run the PR 2 merge gate**

```bash
./gradlew spotlessCheck lintDebug assembleRelease testDebugUnitTest createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage pixel2Api29DebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

Expected: JVM, Hilt startup, real Room, transaction, and migration tests pass.

- [ ] **Step 6: Commit migration coverage and API 29 CI enforcement**

```bash
git add app/build.gradle.kts app/src/androidTest docs/testing-strategy.md app/schemas .github/workflows/android-ci.yml
git commit -m "feat10.2: validate Room migrations in CI"
```

---

### Task 7: Add Stable Semantics and Isolated Compose Screen Tests

**Files:**
- Create: `app/src/main/java/com/example/ironpath/ui/testing/TestTags.kt`
- Modify: screen and shared-component composables to add stable semantics.
- Create: `app/src/androidTest/java/com/example/ironpath/ui/screens/home/HomeScreenTest.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/ui/screens/plan/PlanScreenTest.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/ui/screens/active/ActiveScreenTest.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/ui/screens/history/HistoryScreenTest.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/ui/screens/history/AddRecordScreenTest.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/ui/screens/history/WorkoutLogDetailScreenTest.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/ui/screens/workoutpreview/WorkoutPreviewScreenTest.kt`

**Interfaces:**
- Consumes: pure content composables, `RecordDraftValidator`, `SessionSetInput`, and the approved product-contract decision.
- Produces: deterministic state rendering and callback proof without a real database or navigation graph.

The user selected **PRD-authoritative**. This PR records that decision in its description and removes the disputed behavior before adding screen assertions.

- [ ] **Step 1: Add stable test tags only where text is ambiguous**

Define constants for inputs and repeated rows:

```kotlin
object TestTags {
    const val RECORD_NAME = "record_name"
    const val RECORD_WEIGHT = "record_weight"
    const val RECORD_DATE = "record_date"
    const val RECORD_NOTE = "record_note"
    fun setWeight(id: String) = "set_weight_$id"
    fun setReps(id: String) = "set_reps_$id"
    fun workout(id: String) = "workout_$id"
    fun record(id: String) = "record_$id"
}
```

Buttons and state headings remain asserted by visible text; fields and repeated rows use tags.

- [ ] **Step 2: Write Home, Plan, and Active state matrices**

Home tests:

```kotlin
@Test fun noPlan_showsOpenPlan_andInvokesCallback()
@Test fun activePlan_showsCountsTodayAndNextWorkout()
@Test fun activeSession_showsReturnToActiveInsteadOfStart()
@Test fun completedWeek_showsPlanNextWeek()
```

Plan tests must cover Setup, Review, Accepted, selected-day behavior, generate/accept/regenerate callbacks, whole-workout deletion, and the absence of workout-day/exercise editing controls.

Active tests:

```kotlin
@Test fun noPlanAndRestDay_renderCorrectActions()
@Test fun readyState_startsTheProvidedWorkout()
@Test fun inSession_ordersExercisesAndSets_andShowsExtraSetStyling()
@Test fun enteringBothValidValues_marksSetComplete_withInjectedTimestamp()
@Test fun clearingEitherValue_clearsCompletion()
@Test fun addSetAndCompleteWorkout_invokeExactlyOnce()
```

- [ ] **Step 3: Write History and record-flow matrices**

History tests cover empty logs, populated logs, Records tab selection, source badges, sorting presentation, add callback, and non-clickable display-only record rows.

Add Record tests cover every validator result plus a valid mapped record, optional note, cancel, external duplicate error, suggestion selection, and rotation with `StateRestorationTester`.

Workout log detail tests cover loading/not-found/ready, ordered snapshot sets, empty snapshot copy, and unconditional suppression of record-creation actions.

- [ ] **Step 4: Write Workout Preview tests**

Cover loading/not-found/ready, ordered exercises, disabled start for future/completed/active-session cases, and exactly-once start/back callbacks.

- [ ] **Step 5: Run the isolated screen suite**

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.example.ironpath.ui.screens
```

Expected: all screen tests pass without launching `MainActivity` or opening Room.

- [ ] **Step 6: Commit isolated UI coverage**

```bash
git add app/src/main/java/com/example/ironpath/ui app/src/androidTest/java/com/example/ironpath/ui/screens
git commit -m "feat10.3: verify Compose screen contracts"
```

---

### Task 8: Verify Navigation, Back Stack, and Real Hilt Startup

**Files:**
- Create: `app/src/androidTest/java/com/example/ironpath/ui/navigation/IronPathNavigationTest.kt`
- Modify: `app/src/androidTest/java/com/example/ironpath/di/HiltStartupTest.kt`
- Modify: `app/src/main/java/com/example/ironpath/ui/navigation/IronPathNavHost.kt` only if a test exposes a route defect.

**Interfaces:**
- Consumes: `TestNavHostController`, `ComposeNavigator`, production routes, `HiltTestRunner`, and the production Hilt graph.
- Produces: proof of start destination, argument encoding, route/back behavior, bar visibility, and startup resolution.

- [ ] **Step 1: Write NavHost route tests**

Required tests:

```kotlin
@Test fun entryIsStartDestination_andGetStartedRemovesEntryFromBackStack()
@Test fun bottomNavigation_preservesHomePlanActiveHistoryState_withoutDuplicateDestinations()
@Test fun workoutPreview_encodesId_andBackReturnsToOrigin()
@Test fun workoutLogDetail_decodesId_andBackReturnsToHistory()
@Test fun completingWorkout_clearsActiveAndReturnsHome()
@Test fun backingOutOfActive_doesNotDeletePersistedSession()
@Test fun entryAndDevTools_hideTopAndBottomBars()
```

Use UI clicks for navigation assertions wherever a visible action exists; inspect `currentBackStackEntry` only for route arguments and stack invariants.

- [ ] **Step 2: Extend the prerequisite Hilt startup test**

Retain the prerequisite Entry-to-Home test. Add navigation to Plan, Active, and History through the bottom bar, calling `waitForIdle()` after each click. The test passes only if all four destination-scoped Hilt ViewModels resolve without a Hilt or database-open exception.

- [ ] **Step 3: Put Hilt rules before Compose rules**

Annotate real-activity navigation/startup classes with `@HiltAndroidTest`. Use `HiltAndroidRule` at order 0 and the Compose rule at order 1; call `hiltRule.inject()` in `@Before`. The generated package-name test was already deleted by the Hilt prerequisite.

- [ ] **Step 4: Run focused navigation and startup tests**

```bash
./gradlew pixel2Api29DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.example.ironpath.ui.navigation
./gradlew pixel2Api29DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.ironpath.di.HiltStartupTest
```

Expected: all routes resolve and app startup reaches Home.

- [ ] **Step 5: Commit navigation/startup coverage**

```bash
git add app/src/main app/src/androidTest
git commit -m "feat10.3: verify navigation and app startup"
```

---

### Task 9: Add Three Critical Real-App Persistence Journeys

**Files:**
- Create: `app/src/androidTest/java/com/example/ironpath/e2e/PlanPersistenceJourneyTest.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/e2e/WorkoutCompletionJourneyTest.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/e2e/RecordPersistenceJourneyTest.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/testutil/TestDatabaseModule.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/testutil/TestCoreBindingsModule.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/testutil/MutableTimeProvider.kt`
- Create: `app/src/androidTest/java/com/example/ironpath/testutil/SequenceIdProvider.kt`

**Interfaces:**
- Consumes: the prerequisite `HiltTestRunner`, production UI/navigation/repositories, replaceable `DatabaseModule`/`CoreBindingsModule`, an isolated file-backed test database, and fixed time/IDs.
- Produces: end-to-end proof across process/activity recreation and persisted state.

- [ ] **Step 1: Build Hilt test binding replacements**

Keep the prerequisite `HiltTestRunner` and `HiltTestApplication`. Replace only the production modules:

```kotlin
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DatabaseModule::class],
)
object TestDatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): IronPathDatabase {
        context.deleteDatabase("ironpath-e2e.db")
        return Room.databaseBuilder(
                context,
                IronPathDatabase::class.java,
                "ironpath-e2e.db",
            )
            .addMigrations(IronPathDatabase.MIGRATION_1_2)
            .build()
    }

    @Provides @Singleton
    fun providePlanDao(database: IronPathDatabase): PlanDao = database.planDao()

    @Provides @Singleton
    fun provideSessionDao(database: IronPathDatabase): SessionDao = database.sessionDao()

    @Provides @Singleton
    fun provideHistoryDao(database: IronPathDatabase): HistoryDao = database.historyDao()

    @Provides @Singleton
    fun provideRecordDao(database: IronPathDatabase): RecordDao = database.recordDao()
}
```

```kotlin
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [CoreBindingsModule::class],
)
object TestCoreBindingsModule {
    @Provides @Singleton
    fun provideMutableTimeProvider(): MutableTimeProvider =
        MutableTimeProvider(
            initialInstant = Instant.parse("2026-07-13T17:00:00Z"),
            zoneId = ZoneId.of("America/Vancouver"),
        )

    @Provides @Singleton
    fun provideTimeProvider(provider: MutableTimeProvider): TimeProvider = provider

    @Provides @Singleton
    fun provideSequenceIdProvider(): SequenceIdProvider = SequenceIdProvider("e2e")

    @Provides @Singleton
    fun provideIdProvider(provider: SequenceIdProvider): IdProvider = provider
}
```

Use these resettable fake implementations:

```kotlin
class MutableTimeProvider(
    private val initialInstant: Instant,
    override val zoneId: ZoneId,
) : TimeProvider {
    private var currentInstant: Instant = initialInstant

    override fun now(): Instant = currentInstant

    fun setInstant(value: Instant) {
        currentInstant = value
    }

    fun reset() {
        currentInstant = initialInstant
    }
}

class SequenceIdProvider(private val prefix: String) : IdProvider {
    private var next: Int = 1

    override fun newId(): String = "$prefix-${next++}"

    fun reset() {
        next = 1
    }
}
```

Every Hilt journey class uses `HiltAndroidRule` at order 0 and `createAndroidComposeRule<MainActivity>()` at order 1. The provider deletes the named test database before Hilt constructs the per-test singleton, so isolation happens before the activity rule launches. Inject `IronPathDatabase`, `MutableTimeProvider`, and `SequenceIdProvider` after `hiltRule.inject()`; configure the fakes in `@Before` and close/delete the database in `@After`. Do not use `@UninstallModules` per test because it generates additional components and increases build time.

Once these global `@TestInstallIn` replacements land, Hilt instrumented tests no longer execute the production `DatabaseModule` provider. Its graph remains compile-validated in debug/release, its builder/migration behavior is covered by the real Room suites, and the prerequisite PR's API 29 startup test is the retained evidence that the production provider itself opened successfully.

- [ ] **Step 2: Write plan persistence journey**

The Compose activity rule launches before the test's @Before method. That is intentional: the per-test Hilt providers have already created a fresh database and fixed clock, the UI initially observes an empty Room Flow, and DAO seeding after injection is observed reactively. After seeding, use Compose waitUntil with a user-visible state predicate before interacting; never use a sleep.

Exact journey:

1. Launch Entry and tap Get Started.
2. Open Plan, select Strength plus Monday/Wednesday/Friday, and generate.
3. Assert three review workouts, accept, and assert Home summary.
4. Recreate the activity.
5. Assert the accepted plan and counts remain present.

- [ ] **Step 3: Write workout completion journey**

Seed an accepted plan with a workout scheduled for the fixed `today`, then:

1. Open workout preview and start.
2. Enter one completed bodyweight or weighted set and add one unfinished extra set.
3. Recreate the activity and assert the active session/set values remain.
4. Finish the workout.
5. Assert Home shows the completed count and no active session.
6. Open History and assert one log with the persisted exercise/set snapshot.
7. Reopen the database and assert the same state directly.

- [ ] **Step 4: Write record persistence/duplicate journey**

Exact journey:

1. Open History → Records → Add Record.
2. Enter `  Deadlift  `, `180.5`, fixed today, and a note; save.
3. Assert normalized display and persistence after recreation.
4. Attempt the same normalized name/date/weight again.
5. Assert the duplicate message, one database row, and the form remains open.

- [ ] **Step 5: Run the journey package twice**

```bash
./gradlew pixel2Api29DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.example.ironpath.e2e
./gradlew pixel2Api29DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.example.ironpath.e2e
```

Expected: both consecutive runs pass, proving isolation and lack of order dependence.

- [ ] **Step 6: Expand the API 29 PR job to gate screen and journey tests**

Replace the persistence-only instrumentation package filter with the full non-performance app suite so `ui.screens`, `ui.navigation`, `di`, `data`, and `e2e` all run on every pull request:

```yaml
- name: Run API 29 production test suite
  run: >-
    ./gradlew pixel2Api29DebugAndroidTest
    -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

Keep accessibility checks requiring API 35+ and benchmarks out of this API 29 job; Task 12 assigns them to API 36 nightly coverage.

- [ ] **Step 7: Run the PR 3 merge gate**

```bash
./gradlew spotlessCheck lintDebug assembleRelease testDebugUnitTest createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage pixel2Api29DebugAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

Expected: all unit, Room, migration, screen, navigation, startup, and journey tests pass.

- [ ] **Step 8: Commit real-app journeys and the expanded PR gate**

```bash
git add app/build.gradle.kts app/src/androidTest .github/workflows/android-ci.yml
git commit -m "feat10.3: verify critical persisted journeys"
```

---

### Task 10: Add Accessibility and Adaptive-UI Gates

**Files:**
- Create: `app/src/androidTest/java/com/example/ironpath/accessibility/AccessibilityTest.kt`
- Modify: screen semantics/content descriptions only where failing evidence requires it.
- Modify: `docs/testing-strategy.md`

**Interfaces:**
- Consumes: production Compose semantics tree and representative app states.
- Produces: automated semantics, large-font, small-screen, and touch-target coverage.

- [ ] **Step 1: Write automated accessibility checks for every route**

Enable Compose accessibility validation before content/launch, visit Entry, Home, Plan, Active, History, Workout Preview, and Workout Log Detail, and assert no validation error for interactive elements.

- [ ] **Step 2: Add semantic contract tests**

Assert:

- Every actionable icon has a content description or an adjacent merged semantic label.
- Selected bottom-nav and History tabs expose selected state.
- Validation errors are discoverable and associated with the relevant field.
- Completed sets expose state text, not color alone.
- Read-only actions are absent/disabled semantically, not merely invisible.

- [ ] **Step 3: Add adaptive configuration coverage**

Run representative screens at font scales `1.0` and `2.0`, portrait widths 320dp and 411dp, and landscape. Assert primary actions remain displayed/scrollable and no text is clipped from the semantics tree.

- [ ] **Step 4: Update future-feature policy**

Add: any new interactive component requires a semantic label/state and at least one test at 200% font scale when it changes layout.

- [ ] **Step 5: Run accessibility package**

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.example.ironpath.accessibility
```

Expected: no accessibility validation failures on API 35+ and all manual semantic assertions pass.

- [ ] **Step 6: Commit accessibility coverage**

```bash
git add app/src/main app/src/androidTest docs/testing-strategy.md
git commit -m "feat10.4: enforce accessibility contracts"
```

---

### Task 11: Add Release Startup and Critical-Flow Performance Tests

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Create: `benchmark/build.gradle.kts`
- Create: `benchmark/src/main/AndroidManifest.xml`
- Create: `benchmark/src/main/java/com/example/ironpath/BaselineProfileGenerator.kt`
- Create: `benchmark/src/main/java/com/example/ironpath/StartupBenchmark.kt`
- Create: `benchmark/src/main/java/com/example/ironpath/CriticalFlowBenchmark.kt`

**Interfaces:**
- Consumes: release-like app build with seeded deterministic data.
- Produces: cold/warm startup, history-scroll, and active-set-input measurements plus baseline-profile evidence.

- [ ] **Step 1: Add executable benchmark and baseline-profile wiring**

Add stable Benchmark/Baseline Profile `1.4.1` catalog entries, the `com.android.test` and `androidx.baselineprofile` plugins, and Macrobenchmark dependency. Confirm official AndroidX release notes at implementation time; update the single version only if a newer stable exists.

Apply `androidx.baselineprofile` to `:app`, add the profile producer dependency, and create a non-debuggable `benchmark` build type initialized from `release`:

```kotlin
android {
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }
}

dependencies {
    baselineProfile(project(":benchmark"))
}
```

Configure `benchmark/build.gradle.kts` with:

```kotlin
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.example.ironpath.benchmark"
    compileSdk = 36
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments[
            "androidx.benchmark.suppressErrors"
        ] = "EMULATOR"
    }
    buildTypes { create("benchmark") }
    testOptions {
        managedDevices {
            localDevices {
                create("pixel8Api36") {
                    device = "Pixel 8"
                    apiLevel = 36
                    systemImageSource = "google"
                }
            }
        }
    }
}

baselineProfile {
    managedDevices += "pixel8Api36"
    useConnectedDevices = false
}
```

This device is declared inside the producer module; the `:app` managed-device declaration does not create benchmark-module tasks.

- [ ] **Step 2: Write startup benchmarks**

```kotlin
@Test fun coldStartup()
@Test fun warmStartup()
```

Use `StartupMode.COLD` and `StartupMode.WARM`, at least 10 measured iterations, `CompilationMode.Partial(BaselineProfileMode.Require)`, and wait for the Entry screen content description/text before stopping each iteration.

- [ ] **Step 3: Write critical interaction benchmarks**

Measure:

- Scrolling History with at least 250 logs and 250 records.
- Opening a workout containing 20 exercises/100 sets.
- Entering weight/reps across 10 sets and completing the workout transaction.

Report frame timing and trace sections; do not assert brittle absolute milliseconds in emulator runs. Store emulator results as informational trend artifacts. A regression percentage becomes release-blocking only after the same benchmark is run on a controlled physical device; emulator CI blocks only crashes, ANRs, missing profile packaging, or benchmark execution failures.

- [ ] **Step 4: Generate and install the baseline profile**

```bash
./gradlew :app:generateBaselineProfile
./gradlew :app:assembleRelease
```

Expected: baseline profile is packaged in the release APK and benchmark build succeeds.

- [ ] **Step 5: Run benchmarks on API 36**

```bash
./gradlew :benchmark:pixel8Api36BenchmarkAndroidTest
```

Expected: the explicitly configured `benchmark` build type produces this task, benchmark reports and traces are generated, and no crash/ANR occurs.

- [ ] **Step 6: Commit performance coverage**

```bash
git add settings.gradle.kts gradle/libs.versions.toml app benchmark
git commit -m "feat10.4: add startup and critical-flow benchmarks"
```

---

### Task 12: Enforce the Full CI and Release Matrix

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/android-ci.yml`
- Create: `.github/workflows/android-nightly.yml`
- Modify: `docs/testing-strategy.md`

**Interfaces:**
- Consumes: every test suite and gate from Tasks 1-11.
- Produces: fast PR feedback, nightly broad compatibility/performance coverage, retained reports, and branch protection-ready job names.

- [ ] **Step 1: Configure Gradle Managed Devices**

Before committing the image choice, run the SDK manager from `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --list` and confirm the API 36 `google` image is listed. The current official Managed Devices documentation uses `localDevices` plus `targetDevices.add(devices[...])`; keep that AGP 9.1-compatible DSL and prove it by running `./gradlew tasks --group verification` after this edit.

```kotlin
testOptions {
    managedDevices {
        localDevices {
            create("pixel2Api29") {
                device = "Pixel 2"
                apiLevel = 29
                systemImageSource = "aosp"
            }
            create("pixel8Api36") {
                device = "Pixel 8"
                apiLevel = 36
                systemImageSource = "google"
            }
        }
        groups {
            create("productionMatrix") {
                targetDevices.add(devices["pixel2Api29"])
                targetDevices.add(devices["pixel8Api36"])
            }
        }
    }
}
```

Use the same verified API 36 source inside `benchmark/build.gradle.kts`. If `sdkmanager --list` disproves availability on the CI image, stop implementation and update both declarations to the listed stable source before running tests; do not let Gradle fail later with an opaque missing-package error.

- [ ] **Step 2: Replace the PR workflow with explicit gates**

PR-required jobs:

1. `static-and-build`: `spotlessCheck lintDebug assembleDebug assembleRelease`.
2. `unit-and-coverage`: `testDebugUnitTest createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage`.
3. `api29-hilt-smoke`: the complete non-performance instrumentation suite, including Room, migrations, screen contracts, navigation, startup, accessibility-compatible checks, and the three journeys on `pixel2Api29`. Retain this historical job name to preserve branch-protection continuity.

Upload unit XML/HTML, JaCoCo XML/HTML, lint HTML/XML, instrumentation HTML/XML, screenshots, and logcat on failure. Keep coverage comment behavior, but prefix the heading with `Codex:` only if it is ever posted manually rather than by the workflow bot.

Both PR and nightly workflows must enable KVM using the udev step introduced in Task 6. Every GMD command on GitHub-hosted runners must include:

```text
-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

- [ ] **Step 3: Add nightly compatibility/performance workflow**

Nightly and manual-dispatch jobs:

1. Full `productionMatrixGroupDebugAndroidTest` on API 29 and 36.
2. Accessibility package on API 36.
3. `:app:generateBaselineProfile` plus `:benchmark:pixel8Api36BenchmarkAndroidTest` on the API 36 device declared inside `:benchmark`; emulator measurements are informational artifacts, while crashes/ANRs/profile-generation failures block release.
4. Repeat the journey package three times to detect state leakage.

- [ ] **Step 4: Fix branch trigger patterns**

Replace `feature/**` and `bugfix/**` with repository conventions:

```yaml
push:
  branches: [main, "feat/**", "bug/**"]
pull_request:
  branches: [main]
```

- [ ] **Step 5: Run the entire local release gate**

```bash
./gradlew spotlessCheck lintDebug assembleDebug assembleRelease testDebugUnitTest createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage pixel2Api29DebugAndroidTest pixel8Api36DebugAndroidTest
```

Expected: all production gates pass using the host GPU locally. Document any expected existing lint warning baseline explicitly; no new warning is accepted silently. Keep the `swiftshader_indirect` flag on GitHub-hosted CI commands only.

- [ ] **Step 6: Confirm CI workflow syntax and tasks**

```bash
./gradlew tasks --group verification
git diff --check
```

Expected: managed-device and coverage tasks are listed; no whitespace errors.

- [ ] **Step 7: Commit and raise the final testing PR**

```bash
git add app/build.gradle.kts .github docs/testing-strategy.md
git commit -m "feat10.4: enforce production test gates"
```

Use `gh-raise-pr` with title `feat10.4: enforce production test gates` on branch `feat/feat10.4-production-gates`. The PR description must list exact local commands, device/API coverage, report locations, current coverage percentages, and any intentionally deferred product-contract decision.

---

## Final Verification Checklist

- [ ] Search the plan/implementation for placeholders or skipped tests:

```bash
rg -n "TBD|TODO|FIXME|@Ignore|@Disabled|Thread\.sleep|allowMainThreadQueries" app docs/testing-strategy.md
```

Expected: `allowMainThreadQueries` appears only in the in-memory Room test rule; every other search term has no result unless it belongs to unrelated pre-existing code with a documented disposition.

- [ ] Verify test inventory and zero failures:

```bash
./gradlew spotlessCheck lintDebug assembleRelease testDebugUnitTest createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage productionMatrixGroupDebugAndroidTest
```

- [ ] Verify the release package contains the baseline profile and launches on API 29 and API 36.
- [ ] Verify a version-1 database upgrades to version 2 without data loss.
- [ ] Verify each critical journey passes twice from a clean database.
- [ ] Verify coverage remains at or above 85% line / 70% branch in the selected core scope.
- [ ] Verify the approved PRD/implementation contract is reflected consistently in PRD, UI, and tests.
- [ ] Verify `AGENTS.md` and `docs/testing-strategy.md` make production-level tests mandatory for every future feature and bug fix.

## Rollback and Failure Rules

- A failed migration test blocks release; do not add destructive fallback.
- A flaky device test is treated as a defect, not retried into green.
- If the API 36 nightly suite finds a platform-only failure, add the smallest reproducing test to the PR suite before fixing it.
- Emulator performance changes are informational trends. If a controlled physical-device benchmark regresses by more than 20%, attach before/after traces and require explicit approval; CI emulator runs block only crashes, ANRs, missing profile packaging, or execution failure.
- If core coverage falls below threshold, add behavior tests or narrow out generated/non-behavioral code with a documented rule; never lower the threshold to merge a feature.
