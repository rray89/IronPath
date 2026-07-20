# IronPath

IronPath is a local-first Android workout planner built as a portfolio project. It
covers the full weekly loop: create and review a plan, run a workout, inspect the
completed log, and save records derived from completed sets.

The current version also explores AI-assisted planning without treating model output
as trusted application state. AI proposes a one-week draft; IronPath owns the
exercise catalog, validates every constraint deterministically, lets the user review
and edit the draft, and persists it only after a valid acceptance.

## Product flow

- Create a plan for the upcoming Monday-Sunday week.
- Choose a goal, workout days, experience, equipment, and movement limits.
- Generate with AI or use the deterministic rule-based planner.
- Review and edit a catalog-backed draft before accepting it.
- Preview an accepted workout and run today's active session.
- Inspect completed workout logs and manage personal records.

## AI architecture

`PlanningEngine` keeps provider code behind an application-owned domain contract.
The selection order depends on the build and runtime capability:

| Build | Provider order |
| --- | --- |
| Debug | On-device AI, opted-in remote experiment, deterministic fake AI, rule-based fallback |
| Release | On-device AI, rule-based fallback |

The on-device adapter uses ML Kit GenAI and Gemini Nano through AICore on supported
devices. Unsupported devices continue through the provider chain. The debug-only
Gemini experiment accepts a developer key in process memory so hosted output can be
compared locally; its transport, configuration, UI, and provider binding are absent
from release builds.

Every provider receives a bounded request and must return catalog IDs rather than
free-form exercise names. `PlanValidator` checks dates, selected days, equipment,
movement limits, volume, progression, and catalog membership. One repair attempt is
allowed before deterministic fallback, and invalid drafts are never persisted.

See the [V4 AI Planning PRD](docs/ironpath-v4-ai-planning-prd.md),
[on-device provider notes](docs/on-device-ai-spike.md), and
[debug remote experiment notes](docs/debug-remote-ai-experiment.md) for the detailed
boundaries.

## Architecture

- Kotlin and coroutines
- Jetpack Compose with Material 3
- Navigation Compose
- Room local persistence
- Dagger Hilt with constructor injection for owned production classes
- Build-variant isolation for debug-only AI providers
- ML Kit GenAI Prompt API for the optional on-device provider
- JVM, Room, Compose, navigation, accessibility, real-app journey, and benchmark tests

The app is a single-activity Compose application with four tabs: Home, Plan, Active,
and History. Room remains the source of truth for accepted plans, sessions, logs, and
records. Planning intake and unaccepted AI drafts are intentionally ephemeral.

## Run locally

Requirements:

- Android SDK with API 36 installed
- JDK 21
- an API 29+ device or emulator

Build and install a debug APK:

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Run the main quality gates:

```bash
./gradlew spotlessCheck test verifyCoreCoverage lintDebug assembleRelease -PenableCoverage
./gradlew pixel2Api29DebugAndroidTest
```

Seeker is the default physical test target for this repository. The
[V4 demo guide](docs/v4-ai-planning-demo.md) contains reproducible setup, demo,
fallback, and verification paths.

## Scope boundary

IronPath demonstrates Android architecture and bounded AI integration. It is not a
medical product or an autonomous coach. V4 deliberately excludes production auth,
cloud sync, subscriptions, wearable ingestion, multi-week periodization, and remote
AI in release builds.

The product history and future scope remain documented in the
[MVP](docs/ironpath-mvp-prd.md), [V2](docs/ironpath-v2-prd.md),
[V3](docs/ironpath-v3-prd.md), and [V4](docs/ironpath-v4-ai-planning-prd.md) PRDs.
