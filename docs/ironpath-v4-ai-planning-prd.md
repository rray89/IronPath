# IronPath v4 AI Planning PRD

Date: 2026-07-09
Last updated: 2026-07-26
Status: Implemented

## Purpose

This document becomes the main planning reference for IronPath's next major direction after v3. v3 made plans, logs, and records reviewable. v4 explores AI-assisted planning while keeping the app local-first, portfolio-friendly, and safe enough to demo without pretending to be a production health platform.

Older PRDs remain useful baseline context:

- `docs/ironpath-mvp-prd.md` defines the local-first MVP and core state rules.
- `docs/ironpath-v2-prd.md` defines plan review and record CRUD improvements.
- `docs/ironpath-v3-prd.md` defines workout preview, workout log detail, and derived records.

## Product thesis

IronPath should not become an AI coach that autonomously tells users how to train. For v4, AI is a planning assistant that drafts a one-week plan from structured user inputs, recent workout context, and a formal exercise catalog derived from the current local template pool. The app remains responsible for validation, persistence, fallback, and user review.

The portfolio story should be:

- modern Android architecture with a pluggable planning engine
- on-device-first AI where available
- deterministic constraint validation before any plan is persisted
- graceful fallback when AI is unavailable, slow, invalid, or unsafe
- optional debug-only API experimentation without creating a production backend

## Current product baseline

The following behavior is treated as already established and carried forward into v4:

- dark mode only
- single-activity Compose app with 4 tabs: Home, Plan, Active, History
- local persistence with Room and UUID-based IDs
- one-week Monday-Sunday planning baseline
- generated plans are reviewed before acceptance
- accepted workouts can be previewed
- completed workout logs can be inspected
- records can be manually created, edited, deleted, or derived from completed workouts
- the current local rule-based planner is good enough to remain the fallback path
- the current planner relies on hardcoded exercise templates, not a formal catalog with stable IDs or safety metadata
- production dependencies use Hilt with constructor injection for owned classes and explicit bindings for interfaces
- `docs/testing-strategy.md` defines the established JVM, Room, Compose, navigation, journey, accessibility, coverage, and device gates that v4 inherits

## External context

Android's on-device AI surface is actively evolving. Current official Android and Google docs position Gemini Nano through AICore and ML Kit GenAI APIs as the primary Android-native on-device path. ML Kit's Prompt API supports custom text and multimodal prompts, but is beta and device capability must be checked at runtime. Google's Gemma 4 / Gemini Nano 4 direction is promising, but still belongs in a spike rather than a hard dependency for the whole feature.

Google Health Coach and Fitbit Air also make the market direction clear: production AI coaching depends on deep health data, wearables, recovery signals, subscriptions, privacy controls, and clinical/sports-science review. IronPath should not try to compete with that scope. Its value is a credible local-first Android implementation that demonstrates AI architecture and safety boundaries.

References:

- Android Gemini Nano: https://developer.android.com/ai/gemini-nano
- ML Kit GenAI overview: https://developers.google.com/ml-kit/genai
- ML Kit Prompt API: https://developers.google.com/ml-kit/genai/prompt/android
- Gemma 4 AICore Developer Preview: https://developer.android.com/blog/posts/announcing-gemma-4-in-the-ai-core-developer-preview
- Google Health Coach: https://blog.google/products-and-platforms/products/google-health/google-health-coach/
- Fitbit Air: https://blog.google/products-and-platforms/devices/fitbit/fitbit-air/

## v4 goals

- introduce AI-assisted one-week plan generation behind a clean domain contract
- make AI output reviewable and explainable before acceptance
- keep the existing rule-based planner as the always-available fallback
- validate all generated plans deterministically before persistence
- formalize the minimum exercise catalog metadata needed for safe AI validation
- collect enough planning intake context to make AI output meaningful
- support an on-device provider spike without blocking the product flow
- optionally support a debug-only remote provider for comparison experiments

## v4 non-goals

v4 does not aim to solve:

- production auth or accounts
- cloud sync, backup, or multi-device data reconciliation
- subscriptions, billing, or production backend model routing
- full health coaching chat
- medical advice, diagnosis, injury treatment, or recovery prescriptions
- wearable, Health Connect, nutrition, sleep, or medical-record ingestion
- multi-week periodization
- fully autonomous plan acceptance
- automatic in-workout plan adaptation
- model fine-tuning
- bundling large model weights in the production APK
- shipping embedded provider API keys
- sending user training history, injury notes, or planning intake to remote AI providers in release builds
- broad analytics dashboards

---

## Feature 1: Planning engine abstraction

**Status: Implemented** - PR #37 (feat/feat9.1-planning-engine-contracts)

### Context

The existing local planner is useful and should not be discarded. v4 should place it behind an explicit planning boundary so AI can be added without spreading model-specific behavior through ViewModels, repositories, or UI code.

### Scope

Introduce a domain-level planning contract that can support multiple engines:

- `RuleBasedPlanningEngine`
- `FakeAiPlanningEngine`
- `OnDeviceAiPlanningEngine`
- `DebugRemotePlanningEngine`

The initial engine abstraction should be small and testable. It should accept structured planning input and return either a structured plan draft or a typed failure.

Feature 1 must also formalize the minimum local exercise catalog needed by later AI validation. This catalog can be backed by the existing hardcoded template pool, but it should expose stable IDs and enough metadata for validation:

- display name
- primary muscle group or movement pattern
- required equipment
- beginner suitability
- injury or caution tags where obvious
- whether the exercise is allowed in AI-generated drafts

### Product rules

- UI and persistence layers do not know which engine produced a draft.
- AI engines never write Room entities directly.
- The current rule-based generator remains available even when AI is disabled.
- Release builds should expose an explicit "Generate with AI" choice, then auto-select the best available real provider behind that choice: on-device first, rule-based fallback when AI is unavailable.
- `FakeAiPlanningEngine` is debug/test-only and should never be presented as a real AI provider in release builds.
- Debug builds may expose provider selection for development and demos.
- Owned planning implementations use constructor injection. Hilt modules are limited to interface bindings and third-party provider construction.
- `feat9.1` must establish a real provider variant boundary before debug-only engines are added. Debug provider implementations, bindings, and selector UI belong in `src/debug`; a narrowly scoped `BuildConfig.DEBUG` gate is acceptable only when source-set isolation is impractical.
- The existing release-shipping hidden DevTools gesture is not an acceptable security boundary for a remote provider or API key.
- Shared and release planning bindings must resolve in debug and release builds. Debug-only bindings must resolve in debug, remain absent from release, and participate in the API 29 Hilt startup or journey suite where applicable.

### Data model impact

No Room schema change is required for the abstraction itself. The contract should produce in-memory draft models that can later be mapped to existing `WeeklyPlan`, `PlannedWorkout`, and `PlannedExercise` entities after validation.

---

## Feature 2: Structured planning intake

**Status: Implemented** - PR #39 (feat/feat9.3-structured-planning-intake)

### Context

AI planning is only useful if the app gives the model enough structured context. Random plan generation would weaken the product and create injury risk. v4 should collect a small but meaningful intake before AI generation.

### Scope

Planner Setup should collect or derive:

- goal: strength, hypertrophy, general fitness, return-to-routine, or maintenance
- training days per week, capped at 6 in v4 so at least one rest day remains
- selected training days, with an optional future flexible-mode interpretation
- training experience: beginner, intermediate, advanced
- available equipment
- injury notes and forbidden movements
- exercise preferences or dislikes
- recent completed workouts summary
- recent records summary

The initial version may keep intake local and lightweight. It does not need a full user profile system.

V4 should introduce a canonical `PlanningGoal` with exactly those five intake values. `RuleBasedPlanningEngine` must define an explicit template strategy for every value rather than silently falling through to a generic plan; it may adapt the current Strength, Hypertrophy, Endurance, and Rehab template pools, but user-facing copy must not present return-to-routine as medical rehabilitation. The goal mapping and fallback output for all five values are part of `feat9.3` acceptance criteria.

### Constraint classification

The intake should classify constraints so prompting, validation, and UX do not treat every preference the same way.

| Input | Classification | Enforcement |
| --- | --- | --- |
| injury notes and forbidden movements | hard | validator blocks conflicting exercises |
| selected training days and training-day count | hard in v4 | validator blocks wrong day count or wrong scheduled days |
| available equipment | hard | validator blocks equipment-infeasible exercises |
| training experience | hard for unsafe advanced exercises, soft for plan style | validator blocks beginner-inappropriate lifts where catalog metadata supports it |
| exercise dislikes | soft | prompt preference unless explicitly marked forbidden |
| goal | soft | guides plan shape and rationale |
| recent history and records | hard for unsafe progression, soft for personalization | validator blocks unsafe jumps; prompt uses summary for context |

### Product rules

- injury and forbidden movement inputs must be treated as hard constraints.
- planning intake should be explicit enough to explain why a plan was drafted.
- users can still generate with the rule-based planner if they skip AI.
- historical workout context should be summarized before it is sent to any AI provider.
- in release builds, planning intake, injury notes, and history summaries stay on device.

### Locked v4 intake defaults

- the canonical goal set is strength, hypertrophy, general fitness, return-to-routine, and maintenance.
- the V4 intake accepts one to six selected days; the legacy rule generator retains its existing seven-day domain behavior outside this intake path.
- recent workout and record context uses a bounded 28-day local history window and canonical exercise-catalog lookup.
- forbidden movement caution tags are hard constraints; injury notes are safety context; preferences and dislikes remain soft planning guidance.
- user-entered intake survives recreation through `SavedStateHandle`, while generated drafts and generation progress remain in memory and are never restored as accepted state.
- `FakeAiPlanningEngine` and its binding remain debug/test-only and provide deterministic portfolio and test behavior.
- rule-based fallback eligibility depends on valid selected days, not AI equipment selection; AI generation additionally requires at least one equipment option.
- changing inputs after generation starts or completes cancels the old request, ignores late results, and exposes a stale state that requires regeneration.

### Data model impact

The first implementation may store intake in memory during Planner Setup. Persisting a local planning profile is allowed if it materially improves the UX, but it should stay small and local-only.

---

## Feature 3: Plan draft contract

### Context

AI output must be constrained before the app can reason about it. Raw prose is useful as rationale, but not as the source of truth for workouts.

### Scope

All planning engines should emit a `PlanDraft` shape, not Room entities and not arbitrary markdown.

Recommended draft fields:

- target week start date supplied by the app
- workout days
- workout title
- exercise catalog IDs
- sets
- reps
- target weight in kg where available
- optional rationale
- optional warnings
- provider metadata: engine type, generation duration, fallback reason if any

### Product rules

- drafts must reference exercises by known catalog ID.
- unknown free-text exercises are invalid in v4.
- rationale may be shown to users, but it never bypasses validation.
- a draft is not accepted until it passes validation and the user accepts it.
- the model never chooses the target week; the app computes the target Monday-Sunday week and rejects drafts that do not match it.

### Data model impact

No schema change is required unless later implementation chooses to persist AI provenance. For v4, provider metadata may remain in memory or be logged in debug-only surfaces.

---

## Feature 4: Deterministic plan validation

**Status: Implemented** - PR #38 (feat/feat9.2-plan-validation)

### Context

This is the safety center of v4. AI can propose a plan, but deterministic app code decides whether the plan is allowed to become real app data.

### Scope

Add a `PlanValidator` between every planning engine and persistence.

Validator responsibilities:

- reject malformed drafts
- reject unknown exercise IDs
- reject schedules outside the app-computed target Monday-Sunday week
- reject plans with workouts scheduled in the past
- enforce valid workout count and at least one rest day
- enforce no more than one workout per day
- enforce the requested training-day count and selected training days
- enforce per-day exercise limits
- enforce per-exercise set, rep, and weight bounds
- enforce weight units as kg
- enforce available-equipment constraints
- enforce weekly set volume caps
- enforce basic same-muscle rest spacing where catalog metadata supports it
- block injury-conflicting or user-forbidden movements
- block beginner-inappropriate advanced lifts where catalog metadata supports it
- block unsafe or impossible load progression from recent history

### Product rules

- nothing unvalidated ever persists.
- validation is an app-level constraint and consistency check, not medical clearance; a "validated" label must not imply that a workout is clinically safe for a specific injury.
- validation runs once when a draft is created and again at final accept.
- any user edit in Plan Review marks the prior validation result stale and removes the "validated" badge until the edited plan passes validation again.
- if final validation fails, the app blocks acceptance and shows the validator warnings; it does not persist the plan.
- validator failures should produce user-readable warnings where useful.
- invalid AI drafts may get one repair attempt if the provider supports it.
- after repair failure, the app falls back to the rule-based planner.
- validation rules must be covered by JVM unit tests.

### Locked v4 validator defaults

These defaults are implemented by the v4 validator in `feat9.2`. They do not change the legacy v2 planner UI or acceptance behavior until Feature 5 wires the validated path end to end.

- plans contain 1-6 workouts, leaving at least one rest day in every Monday-Sunday week.
- workouts contain 1-8 unique catalog exercises.
- each exercise contains 1-6 sets and 1-30 reps per set.
- `targetWeightKg` is the only accepted load field and must be a finite value from 0-300 kg.
- weekly volume is capped at 120 hard sets total and 25 hard sets for any catalog primary-muscle group. The primary-muscle cap is intentionally an approximation; `CORE` and `FULL_BODY` remain their own catalog groups rather than being distributed fractionally across other groups.
- workouts sharing a catalog primary-muscle group require at least one full rest day between them. `CORE` and `FULL_BODY` are exempt until the catalog can represent secondary-muscle contribution accurately.
- load progression compares only the same stable exercise catalog ID. A target may increase by the larger of 10 percent or 2.5 kg over the recent maximum. With no exact-exercise history, v4 does not invent a cross-exercise limit; the normal absolute bounds still apply.
- free-text injury notes inform prompting but are not parsed as deterministic medical rules. Only explicit structured forbidden-movement caution tags can block matching catalog entries.
- catalog entries marked as unsuitable for AI drafts are rejected for every AI provider. The local rule-based engine may still use them when its structured template semantics are unambiguous.

`feat9.2` establishes the pure validator, immutable validation token, and token-gated entity mapper. Feature 5 (`feat9.4`) must route AI-assisted review and final acceptance through this boundary before the PRD's persistence invariant is considered wired end to end. The legacy v2 rule-based acceptance path remains behaviorally unchanged during this intermediate slice and can still persist empty or seven-day plans that the v4 validator rejects.

### Data model impact

Validation depends on the minimum exercise catalog defined in Feature 1. Avoid Room schema changes unless the catalog truly needs persisted app data rather than local domain definitions.

---

## Feature 5: AI-assisted planner review flow

**Status: Implemented** - PR #40 (feat/feat9.4-ai-plan-review)

### Context

The minimum impressive portfolio feature is not a chatbot. It is a complete planning loop: user enters goals, taps "Generate with AI", sees a validated draft, understands why it was generated, and can accept or fall back.

### Scope

Users may:

- choose AI-assisted generation from Planner Setup
- see generation progress
- see whether the draft came from on-device AI, debug API, or rule-based fallback
- review the generated week in the existing Plan Review pattern
- add or replace exercises in an AI-assisted draft through a catalog-backed picker
- inspect validation warnings when present
- accept a validated draft
- regenerate or use the rule-based fallback

Users may not:

- accept an invalid draft
- bypass validation
- add unknown free-text exercises to an AI-assisted draft
- persist raw AI text as a workout
- receive medical or injury treatment advice
- keep a stale "validated" badge after editing an AI draft

### UX rules

- the feature should feel like an extension of Planner Setup and Plan Review, not a separate AI chat product.
- AI-assisted Plan Review may reuse v2 editing patterns, but exercise add/replace must use known catalog entries so final validation can keep the safety invariant.
- existing free-text exercise editing remains outside the AI-assisted draft path unless a future PR migrates it to catalog-backed editing too.
- generated rationale should be short and practical.
- generated rationale should be length-capped, rendered as plain text, and treated as untrusted model output.
- AI-generated rationale must not include medical claims.
- AI-generated plans should include a static "not medical advice" line near validation or rationale copy.
- validation status should be visible but not scary.
- fallback should be framed as normal behavior, not a crash or failure.
- only one generation request may control planner state at a time. A replacement request invalidates or cancels the previous request, and late results are ignored.
- cancellation, timeout, navigation away, or provider failure must not persist a partial draft.
- the accept action is disabled while persistence is in progress. Repeated acceptance of the same draft is idempotent and must not create another `WeeklyPlan` row, while accepting a genuinely different validated plan keeps the existing archive-then-insert replacement behavior.

### Data model impact

Accepted drafts map into the existing weekly plan persistence flow. No new accepted-plan state is required.

---

## Feature 6: On-device provider spike

**Status: Implemented** - PR #41 (feat/feat9.5-on-device-provider)

### Context

On-device AI is the preferred long-term portfolio story, but device support and API maturity are uncertain. v4 should prove the integration path without making the entire feature depend on it.

### Scope

Explore an on-device implementation behind `OnDeviceAiPlanningEngine`.

Preferred spike path:

- ML Kit GenAI Prompt API / AICore / Gemini Nano
- runtime capability check before exposing or selecting the provider
- local generation timeout
- graceful fallback to rule-based generation

Implementation decision: `feat9.5` selected the ML Kit Prompt API with typed
structured output. The app does not trigger model downloads in this slice;
downloadable, downloading, unsupported, timeout, and provider-error states continue
through the variant-specific fallback chain. See `docs/on-device-ai-spike.md` for the
capability matrix, architecture boundary, and device evidence.

Alternate spike path:

- MediaPipe LLM Inference or another local inference path with a small quantized model
- model downloaded or sideloaded for development
- no large weights committed to the repo
- no large weights bundled in the production APK

### Product rules

- on-device provider availability is optional.
- CI never depends on a real model.
- app behavior must remain useful when the model is unavailable.
- model setup and failure states should be documented for portfolio/demo review.

### Data model impact

No Room schema change is expected.

---

## Feature 7: Debug-only remote provider experiment

**Status: Implemented** - PR #42 (feat/feat9.6-debug-remote-provider)

### Context

Remote models may produce better plans than small on-device models, but production remote AI implies auth, backend routing, secrets, monitoring, abuse handling, and cost controls. That is not v4 scope.

### Scope

Optionally add a debug-only `DebugRemotePlanningEngine` for comparison experiments.

Allowed behavior:

- available only in debug builds
- hidden behind both a debug build flag and a developer setting or debug surface
- accepts a user-provided API key during local testing
- stores the key locally using an Android secure storage mechanism if storage is needed
- uses the same `PlanDraft` and `PlanValidator` path as every other engine
- discloses in the debug UI that planning intake, injury notes, and history summaries may leave the device
- sends summarized context only, not full raw workout history, unless a future PR explicitly expands the debug experiment

Not allowed:

- committed API keys
- embedded production secrets
- production backend claims
- bypassing local validation because a remote model seems stronger
- remote AI calls in release builds

Implementation decision: `feat9.6` uses the Gemini Interactions API with
`gemini-3.5-flash` and structured JSON output. The developer-supplied key stays only
in process memory, so secure persistence is not needed; disabling the experiment or
ending the process clears it. Release and release-like variants contain no remote
transport, provider engine, or candidate binding. See
`docs/debug-remote-ai-experiment.md` for setup, privacy boundaries, provider order,
and verification evidence.

Every remote request sets `store: false` to opt out of provider-side Interaction
resource retention. Planning context still leaves the device for Google processing;
the flag does not make the remote experiment equivalent to on-device AI.

**Compatibility follow-up status: Implemented** - PR #44
(`feat/feat9.8-fix-gemini-schema`)

Compatibility follow-up: a July 26, 2026 live Seeker smoke found that the current
Gemini endpoint rejects IronPath's combined nested numeric and collection bounds as
an invalid request. The remote response schema therefore owns structural typing,
required fields, and closed objects only. The existing app-owned mapper and
`PlanValidator` remain responsible for every value, count, catalog, safety, and
training constraint before a proposal can be reviewed or persisted. This preserves
the original trust boundary instead of weakening validation to accommodate a
provider-specific schema limit.

Post-fix acceptance evidence: on July 26, 2026, a debug build on a physical Seeker
submitted a fully synthetic one-day intake with empty recent history to
`gemini-3.5-flash` through stable `/v1/interactions`. Plan Review displayed
`REMOTE AI EXPERIMENT · GENERATED PLAN`; the generated draft was not accepted or
persisted by IronPath, and the verified request opted out of provider-side Interaction
resource retention. After force-stopping and relaunching the app, Remote AI Lab was
disabled and the API-key field was absent, confirming the process-only secret
boundary. The key and raw provider payload are not part of the recorded evidence.

### Data model impact

No Room schema change is expected.

---

## Suggested implementation order

1. `feat9: add V4 AI Planning PRD`
2. `feat9.1: add planning engine contracts, provider variant boundary, and minimum exercise catalog`
3. `feat9.2: add plan draft contract and deterministic validator`
4. `feat9.3: add structured planning intake and fake AI planning engine`
5. `feat9.4: add AI-assisted planner review flow with catalog-backed exercise edits and final validation`
6. `feat9.5: add on-device provider capability spike`
7. `feat9.6: add debug-only remote provider experiment`
8. `feat9.7: polish V4 demo documentation`
9. `feat9.8: harden Gemini structured-output compatibility`

This order intentionally builds the demo loop before depending on real model availability. The fake engine makes the UI, fallback, and validation story testable. The on-device provider can then plug into an already working flow.

## Testing strategy

V4 inherits `docs/testing-strategy.md` as the authoritative project-wide test and quality policy. The requirements below add AI-specific proof; they do not replace the repository's TDD, coverage, build, accessibility, device, or journey gates. Every implementation PR starts with a test that fails for the intended reason before production code changes.

### Feature proof matrix

| Implementation | Required proof |
| --- | --- |
| `feat9.1` planning contracts, variant boundary, and catalog | JVM contract and catalog tests, stable-ID and metadata boundary cases, source-set and release provider-boundary verification, release graph compilation, and API 29 Hilt graph resolution for bindings present in the tested variant |
| `feat9.2` draft and validator | Exhaustive JVM validator and mapping tests using fixed time, including valid, malformed, safety, date, equipment, progression, and boundary inputs |
| `feat9.3` intake and fake AI | JVM ViewModel tests plus isolated Compose tests for intake, loading, validation, failure, cancellation, and restored state; semantics and 200% font-scale coverage for every new control |
| `feat9.4` review and acceptance | JVM, Compose, real NavHost, real Room, and real-app journey coverage for generation, editing, stale validation, revalidation, acceptance, duplicate actions, persistence, recreation, and back-stack behavior |
| `feat9.5` on-device spike | Provider contract tests with deterministic fakes; timeout, cancellation, malformed output, unsupported-device, and provider-exception cases; release assembly; manual generation on a capable physical device and fallback verification on Seeker |
| `feat9.6` debug remote experiment | Fake-transport tests for success and failure, secret-redaction checks, debug-only UI and binding coverage, and release-variant proof that remote provider selection and calls are unavailable |
| `feat9.7` demo documentation | Documentation review against the implemented provider support, setup, fallback behavior, privacy boundary, and reproducible demo steps |
| `feat9.8` Gemini compatibility | Outbound codec regression proving provider-side numeric and collection bounds are absent while local validator boundary coverage remains green; debug/release assembly; an authorized live Gemini smoke on Seeker that reaches `REMOTE AI EXPERIMENT` |

### Domain and ViewModel tests

Expected coverage:

- `PlanningEngine` contract behavior with fake engines
- `PlanDraft` parsing and mapping
- validator success cases
- malformed draft rejection
- unknown exercise rejection
- invalid week/date rejection
- past scheduled workout rejection using a hand-built invalid `PlanDraft`, because the rule-based generator itself targets the upcoming week
- duplicate workout day rejection
- requested training-day mismatch rejection
- selected training-day mismatch rejection
- equipment mismatch rejection
- volume cap rejection
- progression cap rejection
- injury and forbidden movement rejection
- stale validation after review edits
- unknown free-text exercise rejection in AI-assisted review
- provider timeout, cancellation, unsupported-device, and exception handling
- one repair attempt followed by deterministic fallback
- fallback after invalid AI draft
- planner setup intake state
- AI generation loading state
- validated draft state
- stale validation state after user edits
- validation warning state
- provider unavailable fallback
- explicit rule-based fallback behavior for all five `PlanningGoal` values
- accept validated draft
- block invalid draft acceptance
- late result ignored after cancellation or replacement generation
- repeated generation and acceptance actions do not create competing state or duplicate plans

All date behavior uses the injected `TimeProvider` with fixed timezone and boundary dates. Provider doubles must cover valid, invalid, slow, cancelled, and failed responses without depending on live model output.

### UI, accessibility, and navigation tests

Expected coverage:

- isolated Compose coverage for every Planner Setup and Plan Review state, action, warning, provider label, and validation status
- accessible labels, roles, enabled or disabled state, loading state, validation state, and error associations for every new interactive control
- 200% font-scale evidence plus compact portrait and landscape coverage for layout-changing screens
- real NavHost coverage for setup to review, fallback, acceptance, back navigation, and recreation with a draft in progress
- deterministic generation progress using fake engines; Compose and navigation tests never wait for a real model

### Persistence and journey tests

Expected coverage:

- real Room tests for draft-to-accepted-plan mapping and atomic persistence behavior
- migration tests with representative data if any v4 implementation changes the Room schema
- repeated acceptance of the same draft produces no competing Active plan and no orphaned Archived duplicate; accepting a different validated plan still archives the previous Active plan and inserts the replacement atomically
- cancelled, invalid, stale, or partially mapped drafts leave no persisted plan data
- a critical real-app journey covers intake, fake generation, review editing, stale validation, final validation, acceptance, process-level recreation, and the accepted plan on Home
- journey fixtures and provider doubles remain deterministic across the nightly state-leak repetitions

### Build, coverage, and device gates

Expected coverage:

- the repository's core JVM line and branch coverage gate applies to planning domain, repositories, and non-dev ViewModels
- Sonar new-code coverage and condition coverage are reviewed together with the required behavior suites; aggregate coverage never substitutes for layer-specific proof
- pull requests pass `Static & Build`, `Unit Tests & Coverage`, and `API 29 Hilt Smoke`
- applicable implementation PRs pass focused Room, Compose, navigation, and real-app journey tests before merge
- new UI participates in the managed API 29 compatibility suite and API 36 accessibility and adaptive-layout suite
- debug and release assembly prove provider variant boundaries, including the absence of debug remote behavior from release builds
- Seeker is the default physical target for rule-based, fake-AI, fallback, and end-to-end smoke testing
- the on-device provider is manually tested on a capable physical device when available; unsupported-device fallback remains testable everywhere
- if v4 changes startup or a benchmarked critical flow, Baseline Profile and macrobenchmark evidence is updated using deterministic fake-provider behavior rather than live model generation

CI must not require real model weights, live network calls, provider API keys, AICore availability, or nondeterministic model output. Live on-device generation is physical-device evidence, not a replacement for automated provider-contract and fallback tests.

## Portfolio demo story

**Status: Implemented** - PR #43 (feat/feat9.7-v4-demo-documentation)

A strong 60-second demo assumes either a debug build using `FakeAiPlanningEngine` or a physical device with a supported on-device provider. On a release build without on-device capability, the honest demo outcome is the rule-based fallback rather than a simulated AI draft.

The demo should show:

1. Open Planner Setup.
2. Enter a goal, available days, equipment, and one injury or forbidden movement.
3. Tap "Generate with AI".
4. Show a validated one-week draft in Plan Review.
5. Point out the provider label, fallback explanation when present, and the fact that
   acceptance is available only for a validated draft.
6. Edit one catalog-backed exercise prescription and show that the draft is
   revalidated immediately.
7. Accept the plan.
8. Briefly show fallback behavior when AI is unavailable or produces an invalid draft.

The message to interviewers is that IronPath uses AI as a bounded planning component inside a normal Android architecture, not as an uncontrolled text generator. The reproducible debug, release fallback, optional remote, and optional live on-device paths are documented in `docs/v4-ai-planning-demo.md`.

## Open questions

- Should v4 persist a local planning profile, or keep intake ephemeral until the AI flow proves useful?
- Which catalog metadata beyond the v4 minimum is worth adding for later analytics or coaching features?
- How strict should the first progression cap be for users with sparse history?

## Explicitly deferred future features

The following remain future candidates after v4:

- login flow
- background sync and backup strategy
- Google Sheets import/export or source-of-truth experiments
- Health Connect or wearable integration
- AI coaching chat
- adaptive changes from readiness, sleep, nutrition, or recovery data
- multi-week planning and periodization
- workout image/video instruction generation
- analytics, trends, and progression dashboards
- production remote AI service with auth, billing, monitoring, and abuse controls
