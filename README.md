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
- Review a rule draft with day moves/swaps, exercise edits/additions, removal/Undo and
  drag ordering; AI-assisted drafts use catalog-backed edits and final validation.
- Accept only after review; accepted plans and active-session exercises stay locked.
- Preview an accepted workout and run today's active session.
- Inspect completed workout logs, edit/delete manual records, and explicitly save a
  completed lift as a record with a read-only route back to its source.
- Continue an existing workout without replacing its logged sets, then plan the next
  week after completing the current week.

## AI architecture

`PlanningEngine` keeps provider code behind an application-owned domain contract.
The selection order depends on the build and runtime capability:

| Build | Provider order |
| --- | --- |
| Debug | On-device AI, opted-in remote experiment, deterministic fake AI, rule-based fallback |
| Release | On-device AI, rule-based fallback |

The on-device adapter uses ML Kit GenAI and Gemini Nano through AICore on supported
devices. Unsupported devices continue through the provider chain. The debug-only
remote experiment supports fixed Gemini, DeepSeek and OpenRouter routes with a
developer key in process memory. Its transports, selector UI and provider binding
are absent from release and authpreview. The comparison candidate awaits final
product and live-provider validation.

Every provider receives a bounded request and must return catalog IDs rather than
free-form exercise names. `PlanValidator` checks dates, selected days, equipment,
movement limits, volume, progression, and catalog membership. The on-device path allows one repair attempt before deterministic fallback; remote
experiments make only one paid attempt with no automatic repair. Final acceptance
revalidates against the current clock, and invalid drafts are never persisted.

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


## Combined reliability candidate

The integration branch combines the unmerged candidates [PR69](https://github.com/rray89/IronPath/pull/69),
[PR70](https://github.com/rray89/IronPath/pull/70) and [PR71](https://github.com/rray89/IronPath/pull/71),
plus restoration of V2 3.2/3.3 rule review. See the
[combined review guide](docs/combined-review-candidate.md) for its sources, verification
scope and concentrated product walkthrough. Technical tests do not establish BOSS
product acceptance; the original PRs remain separate, open candidates.

The Debug candidate retains main's account/backup demo behavior. It does not include
the separate PR65–68 real cloud backup/sync/restore/deletion candidate chain. Existing
authpreview identity behavior is preserved. Deletion-service implementation remains
paused, and this candidate performs no real-provider inference or live cloud setup.
