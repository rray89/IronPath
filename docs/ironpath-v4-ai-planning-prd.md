# IronPath v4 AI Planning PRD

Date: 2026-07-09
Status: Draft

## Purpose

This document becomes the main planning reference for IronPath's next major direction after v3. v3 made plans, logs, and records reviewable. v4 explores AI-assisted planning while keeping the app local-first, portfolio-friendly, and safe enough to demo without pretending to be a production health platform.

Older PRDs remain useful baseline context:

- `docs/ironpath-mvp-prd.md` defines the local-first MVP and core state rules.
- `docs/ironpath-v2-prd.md` defines plan review and record CRUD improvements.
- `docs/ironpath-v3-prd.md` defines workout preview, workout log detail, and derived records.

## Product thesis

IronPath should not become an AI coach that autonomously tells users how to train. For v4, AI is a planning assistant that drafts a one-week plan from structured user inputs, recent workout context, and the existing exercise catalog. The app remains responsible for validation, persistence, fallback, and user review.

The portfolio story should be:

- modern Android architecture with a pluggable planning engine
- on-device-first AI where available
- deterministic safety validation before any plan is persisted
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
- broad analytics dashboards

---

## Feature 1: Planning engine abstraction

### Context

The existing local planner is useful and should not be discarded. v4 should place it behind an explicit planning boundary so AI can be added without spreading model-specific behavior through ViewModels, repositories, or UI code.

### Scope

Introduce a domain-level planning contract that can support multiple engines:

- `RuleBasedPlanningEngine`
- `FakeAiPlanningEngine`
- `OnDeviceAiPlanningEngine`
- `DebugRemotePlanningEngine`

The initial engine abstraction should be small and testable. It should accept structured planning input and return either a structured plan draft or a typed failure.

### Product rules

- UI and persistence layers do not know which engine produced a draft.
- AI engines never write Room entities directly.
- The current rule-based generator remains available even when AI is disabled.
- The app can select an engine by feature flag, debug setting, capability check, or future user-facing control.

### Data model impact

No Room schema change is required for the abstraction itself. The contract should produce in-memory draft models that can later be mapped to existing `WeeklyPlan`, `PlannedWorkout`, and `PlannedExercise` entities after validation.

---

## Feature 2: Structured planning intake

### Context

AI planning is only useful if the app gives the model enough structured context. Random plan generation would weaken the product and create injury risk. v4 should collect a small but meaningful intake before AI generation.

### Scope

Planner Setup should collect or derive:

- goal: strength, hypertrophy, general fitness, return-to-routine, or maintenance
- training days per week
- preferred training days where possible
- training experience: beginner, intermediate, advanced
- available equipment
- injury notes and forbidden movements
- exercise preferences or dislikes
- recent completed workouts summary
- recent records summary

The initial version may keep intake local and lightweight. It does not need a full user profile system.

### Product rules

- injury and forbidden movement inputs must be treated as hard constraints.
- planning intake should be explicit enough to explain why a plan was drafted.
- users can still generate with the rule-based planner if they skip AI.
- historical workout context should be summarized before it is sent to any AI provider.

### Data model impact

The first implementation may store intake in memory during Planner Setup. Persisting a local planning profile is allowed if it materially improves the UX, but it should stay small and local-only.

---

## Feature 3: Plan draft contract

### Context

AI output must be constrained before the app can reason about it. Raw prose is useful as rationale, but not as the source of truth for workouts.

### Scope

All planning engines should emit a `PlanDraft` shape, not Room entities and not arbitrary markdown.

Recommended draft fields:

- generated week start date
- workout days
- workout title
- exercise catalog IDs
- sets
- reps
- target weight where available
- optional rationale
- optional warnings
- provider metadata: engine type, generation duration, fallback reason if any

### Product rules

- drafts reference exercises by known catalog ID whenever possible.
- unknown free-text exercises are invalid in the default v4 path.
- rationale may be shown to users, but it never bypasses validation.
- a draft is not accepted until it passes validation and the user accepts it.

### Data model impact

No schema change is required unless later implementation chooses to persist AI provenance. For v4, provider metadata may remain in memory or be logged in debug-only surfaces.

---

## Feature 4: Deterministic plan validation

### Context

This is the safety center of v4. AI can propose a plan, but deterministic app code decides whether the plan is allowed to become real app data.

### Scope

Add a `PlanValidator` between every planning engine and persistence.

Validator responsibilities:

- reject malformed drafts
- reject unknown exercise IDs
- reject schedules outside the target Monday-Sunday week
- reject plans with workouts scheduled in the past
- enforce valid workout count and at least one rest day
- enforce per-day exercise limits
- enforce per-exercise set, rep, and weight bounds
- enforce weekly set volume caps
- enforce basic same-muscle rest spacing where catalog metadata supports it
- block injury-conflicting or user-forbidden movements
- block beginner-inappropriate advanced lifts where catalog metadata supports it
- block unsafe or impossible load progression from recent history

### Product rules

- nothing unvalidated ever persists.
- validator failures should produce user-readable warnings where useful.
- invalid AI drafts may get one repair attempt if the provider supports it.
- after repair failure, the app falls back to the rule-based planner.
- validation rules must be covered by JVM unit tests.

### Data model impact

Validation may require adding or formalizing exercise catalog metadata, such as muscle group, equipment, beginner suitability, movement pattern, or injury tags. Avoid Room schema changes unless the existing catalog lives in persisted data and truly needs expansion.

---

## Feature 5: AI-assisted planner review flow

### Context

The minimum impressive portfolio feature is not a chatbot. It is a complete planning loop: user enters goals, taps "Generate with AI", sees a validated draft, understands why it was generated, and can accept or fall back.

### Scope

Users may:

- choose AI-assisted generation from Planner Setup
- see generation progress
- see whether the draft came from on-device AI, fake AI, debug API, or rule-based fallback
- review the generated week in the existing Plan Review pattern
- inspect validation warnings when present
- accept a validated draft
- regenerate or use the rule-based fallback

Users may not:

- accept an invalid draft
- bypass validation
- persist raw AI text as a workout
- receive medical or injury treatment advice

### UX rules

- the feature should feel like an extension of Planner Setup and Plan Review, not a separate AI chat product.
- generated rationale should be short and practical.
- validation status should be visible but not scary.
- fallback should be framed as normal behavior, not a crash or failure.

### Data model impact

Accepted drafts map into the existing weekly plan persistence flow. No new accepted-plan state is required.

---

## Feature 6: On-device provider spike

### Context

On-device AI is the preferred long-term portfolio story, but device support and API maturity are uncertain. v4 should prove the integration path without making the entire feature depend on it.

### Scope

Explore an on-device implementation behind `OnDeviceAiPlanningEngine`.

Preferred spike path:

- ML Kit GenAI Prompt API / AICore / Gemini Nano
- runtime capability check before exposing or selecting the provider
- local generation timeout
- graceful fallback to rule-based generation

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

### Context

Remote models may produce better plans than small on-device models, but production remote AI implies auth, backend routing, secrets, monitoring, abuse handling, and cost controls. That is not v4 scope.

### Scope

Optionally add a debug-only `DebugRemotePlanningEngine` for comparison experiments.

Allowed behavior:

- available only in debug builds
- hidden behind a developer setting or debug surface
- accepts a user-provided API key during local testing
- stores the key locally using an Android secure storage mechanism if storage is needed
- uses the same `PlanDraft` and `PlanValidator` path as every other engine

Not allowed:

- committed API keys
- embedded production secrets
- production backend claims
- bypassing local validation because a remote model seems stronger

### Data model impact

No Room schema change is expected.

---

## Suggested implementation order

1. `feat9: add V4 AI Planning PRD`
2. `feat9.1: add planning engine and plan draft contracts`
3. `feat9.2: add deterministic plan validator`
4. `feat9.3: add fake AI planning engine and planner review UI flow`
5. `feat9.4: add on-device provider capability spike`
6. `feat9.5: add debug-only remote provider experiment`
7. `feat9.6: polish V4 demo documentation`

This order intentionally builds the demo loop before depending on real model availability. The fake engine makes the UI, fallback, and validation story testable. The on-device provider can then plug into an already working flow.

## Testing strategy

### Unit tests

Expected coverage:

- `PlanningEngine` contract behavior with fake engines
- `PlanDraft` parsing and mapping
- validator success cases
- malformed draft rejection
- unknown exercise rejection
- invalid week/date rejection
- past scheduled workout rejection
- volume cap rejection
- progression cap rejection
- injury and forbidden movement rejection
- fallback after invalid AI draft

### ViewModel tests

Expected coverage:

- planner setup intake state
- AI generation loading state
- validated draft state
- validation warning state
- provider unavailable fallback
- accept validated draft
- block invalid draft acceptance

### Integration and device testing

Expected coverage:

- existing unit test gate remains required
- debug assemble remains required
- on-device provider manually tested on a capable device when available
- Seeker physical-device smoke test remains useful for the normal rule-based and fake-AI flows

CI should not require real model weights, network calls, or AICore availability.

## Portfolio demo story

A strong 60-second demo should show:

1. Open Planner Setup.
2. Enter a goal, available days, equipment, and one injury or forbidden movement.
3. Tap "Generate with AI".
4. Show a validated one-week draft in Plan Review.
5. Point out the provider badge and validation status.
6. Accept the plan.
7. Briefly show fallback behavior when AI is unavailable or produces an invalid draft.

The message to interviewers is that IronPath uses AI as a bounded planning component inside a normal Android architecture, not as an uncontrolled text generator.

## Open questions

- Should v4 persist a local planning profile, or keep intake ephemeral until the AI flow proves useful?
- Should the first on-device spike use ML Kit Prompt API / AICore first, or compare it against MediaPipe LLM Inference?
- How much exercise catalog metadata is already available, and how much needs to be added for validation?
- Should remote API experimentation live behind a debug screen, Gradle build flag, or both?
- Should AI generation be a separate choice from rule-based generation, or should the app auto-select the best available engine?

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
