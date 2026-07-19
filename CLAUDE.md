# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew installDebug           # Build and install on connected device
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests on device/emulator
./gradlew pixel2Api29DebugAndroidTest # Run instrumented tests on the managed API 29 fallback
./gradlew :app:createPixel2Api29DebugAndroidTestCoverageReport -PenableAndroidTestCoverage # API 29 instrumented XML/HTML coverage
test -n "${SONAR_TOKEN:-}" && test -n "${SONAR_HOST_URL:-}" && test -n "${SONAR_PROJECT_KEY:-}" && test -n "${SONAR_ORGANIZATION:-}"
./gradlew :app:assembleDebug sonar -Dsonar.projectKey="$SONAR_PROJECT_KEY" -Dsonar.organization="$SONAR_ORGANIZATION" -Dsonar.coverage.jacoco.xmlReportPaths=coverage/unit/report.xml,coverage/android/report.xml
./gradlew :app:productionMatrixGroupDebugAndroidTest # Run the managed API 29 + 36 matrix
./gradlew :app:generateReleaseBaselineProfile -PbaselineProfileUseConnectedDevices=true -Pandroid.testInstrumentationRunnerArguments.class=com.example.ironpath.benchmark.BaselineProfileGenerator -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile # Generate the release profile on Seeker
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.ironpath.benchmark.StartupBenchmark,com.example.ironpath.benchmark.CriticalFlowBenchmark -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark # Run benchmark classes on Seeker
./gradlew clean                  # Clean build artifacts
./gradlew lint                   # Run lint checks
```

## Tech Stack

- **UI:** Jetpack Compose with Material 3
- **DI:** Dagger Hilt 2.60.1 with KSP and AndroidX Hilt Compose integration
- **Database:** Room 2.8.4 with KSP 2.3.6 for annotation processing
- **Navigation:** Navigation Compose 2.9.8 with string routes
- **Performance:** Macrobenchmark and Baseline Profiles on Seeker locally and managed API 36 in CI
- **Static analysis:** SonarQube Cloud OSS with SonarScanner for Gradle 7.3.1.8318 on Java 21
- **Language:** Kotlin 2.3.20 with JVM target 11
- **Min SDK:** 29 (Android 10), Compile/Target SDK: 36
- **Versions:** Managed via `gradle/libs.versions.toml` — add new dependencies there, not inline in `build.gradle.kts`

## Product Context

IronPath is a local-first workout planning app — a portfolio project for job interviews. The MVP goal is a clean, demoable foundation, not a production fitness app.

Sources of truth:
- **PRD**: `docs/ironpath-mvp-prd.md` — locked MVP scope, state rules, data model
- **Design system**: `docs/IronPath Kinetic v2.md` — "Kinetic Precision" aesthetic
- **Figma**: 12 screens in team library (Starter plan, limited MCP calls)
- **Color reference**: `docs/Screenshot 2026-03-30 at 1.10.13 AM.png`

Key decisions:
- Dark mode only, no theme switching
- No auth backend — entry screen Google sign-in is a non-functional placeholder
- Plan generation uses a simple local algorithm (hardcoded exercise templates), not AI
- All data persisted locally with Room, no backend sync
- Bottom nav order: Home, Plan, Active, History
- No "This Week" screen — after accepting a plan, navigate to Home; Plan tab shows summary/rest state
- No automatic record creation on session completion in MVP

## Architecture

Single-activity Compose app with bottom navigation (4 tabs).

```
com.example.ironpath/
├── MainActivity.kt          # Scaffold + NavHost + bottom nav
├── data/
│   ├── local/               # Room entities, DAOs, database
│   └── repository/          # PlanRepository, SessionRepository, HistoryRepository, RecordRepository
├── di/                      # Hilt modules for third-party/interface bindings
├── domain/
│   └── planner/             # Local plan generation algorithm
├── ui/
│   ├── components/          # ExerciseCard, GreenGradientButton, DayPicker, etc.
│   ├── navigation/          # NavGraph, bottom nav bar, routes
│   ├── screens/
│   │   ├── entry/           # Entry/splash screen
│   │   ├── home/            # Home tab (empty + active plan states)
│   │   ├── plan/            # Planner Setup, Plan Review
│   │   ├── active/          # Active Session, empty/rest day states
│   │   └── history/         # Logs, Records, Add Record
│   └── theme/               # Color, Type, Theme (Kinetic Precision)
```

Data model (Room entities): WeeklyPlan → PlannedWorkout → PlannedExercise, ActiveSession → SessionExercise → SessionSet, WorkoutLog, PersonalRecord. All IDs are UUIDs. See PRD for full persistence rules.

## Design System — Kinetic Precision

- **Palette**: surface `#0e0e0e`, surface-container-low `#131313`, primary `#39FF14`, on-surface `#FFFFFF`, on-surface-variant `#ADAAAA`
- **Font**: Space Grotesk (Google Fonts). Labels: uppercase + wide tracking. Display: tight negative tracking.
- **No 1px borders**: use tonal surface shifts to separate sections
- **Primary CTA**: gradient `#8EFF71` → `#2FF801` at 135 degrees
- **Elevation**: no shadows — use tonal layering and ambient glow (primary at 8% opacity, 40px blur)
- **Corners**: sharp (0.125rem–0.25rem), avoid rounded-full

## Key Conventions

- Dark-only theme — no dynamic color, no Material You
- Edge-to-edge display enabled in `MainActivity`
- The app uses `Theme.IronPath` (no action bar) from `res/values/themes.xml`
- Figma text has Stitch export artifacts (garbled chars) — always use PRD text, not Figma literals
- Figma nav shows "Dashboard" in some screens — use "Home" per PRD
- Owned production classes use constructor injection. Reserve Hilt modules for third-party objects and interface bindings.
- JVM tests construct subjects directly. Use Hilt only for graph/startup/instrumented integration tests.
- Every new Hilt binding must compile in debug and release and resolve in the API 29 startup or journey suite.
- Coverage reports must identify their producing layer. JVM rankings are limited to the hard core scope; API 29 instrumented coverage and future unified/new-code views are informational and never replace behavior-suite gates.
- **Production-level tests are required for every feature and bug fix.** Start with a failing test and follow `docs/testing-strategy.md`. Select every applicable layer: JVM domain/ViewModel tests, real Room DAO/transaction/migration tests, isolated Compose tests, navigation tests, and a critical real-app journey test. Cover happy, empty, validation, error, boundary-time, duplicate/concurrent action, and recreation behavior where relevant. Every new interactive component requires semantic label/state coverage, and layout-changing UI requires a 200% font-scale test. No feature is complete until Spotless, lint, debug/release assembly, applicable device tests, and core coverage gates pass.

## Test Device Policy

- The default physical target for instrumented tests and smoke tests is Seeker. Confirm it appears as `device` in `adb devices -l` before running. If more than one target is attached, set `ANDROID_SERIAL` to Seeker's current serial; never hardcode the serial in the repository.
- If Seeker is absent, offline, or unauthorized, use the Gradle-managed API 29 fallback with `./gradlew pixel2Api29DebugAndroidTest`.
- API 36 accessibility, adaptive-layout, and performance coverage runs through the managed `pixel8Api36` target; the full API 29 + 36 matrix is a nightly/release gate rather than the default feature loop.
- Android Studio is optional for this workflow. The Gradle wrapper plus Android SDK command-line tools, platform-tools/adb, and the emulator are sufficient.

## PR & Branch Naming

- Feature PR titles: `feat<id>: short description`
- Bug PR titles: `bug<id>: short description`
- Feature branches: `feat/feat<id>-short-title`
- Bug branches: `bug/bug<id>-short-title`
- Follow-up/sub-feature PR titles: `feat<id>.<subid>: short description`
- Follow-up branches: `feat/feat<id>.<subid>-short-title`
