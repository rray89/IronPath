# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew installDebug           # Build and install on connected device
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests on device/emulator
./gradlew clean                  # Clean build artifacts
./gradlew lint                   # Run lint checks
```

## Tech Stack

- **UI:** Jetpack Compose with Material 3
- **DI:** Koin 4.2.0 (not Hilt) with `koin-androidx-compose`
- **Database:** Room 2.8.4 with KSP 2.3.6 for annotation processing
- **Navigation:** Navigation Compose 2.9.0 with string routes
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
├── di/                      # Koin module definitions
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

## PR & Branch Naming

- PR titles: `Feature X.X: <description>` for features, `Bugfix: <description>` for bug fixes
- Branch names: `feature/X.X-short-name` or `bugfix/short-name`
