# IronPath v3 PRD

Date: 2026-04-15
Status: Draft

## Purpose

This document becomes the main forward-looking product planning reference after MVP and v2. Older PRDs remain useful historical context, but future implementation planning should start here.

## Overview

v3 focuses on making workouts and history more reviewable, trustworthy, and connected. The app already supports one-week planning, accepted-plan state, active session execution, workout logs, and manual records. v3 turns those foundations into a clearer user story:

- preview what a scheduled workout contains before starting it
- inspect what happened in a completed workout
- derive meaningful records from completed workouts without creating dead-end history rows

v3 should stay local-first, portfolio-friendly, and small enough to ship in reviewable PRs.

## Current product baseline

The following product behavior is treated as already established and carried forward into v3:

- dark mode only
- single-activity Compose app with 4 tabs: Home, Plan, Active, History
- one-week planning baseline with Monday-Sunday scheduling
- planner setup, plan review, accepted plan, and active session remain distinct states
- local persistence with Room and UUID-based IDs
- v2 review improvements are already implemented:
  - exercise-level editing during plan review
  - review-time workout day reassignment
  - record edit and delete
- current local plan generation baseline is considered good enough to build on for now

## v3 goals

- make accepted workouts previewable before execution
- make workout logs explorable instead of list-only
- let completed workouts produce meaningful record flows
- improve confidence in the app's history and provenance story

## v3 non-goals

v3 does not aim to solve:

- auth or account systems
- cloud sync / backup
- multi-device data reconciliation
- AI-first plan generation
- multi-week planning
- a full custom program builder
- broad analytics dashboards

---

## Feature 1: Accepted workout preview

Status: Implemented in PR #23 (`feat/feat6-workout-preview`).

### Context

Right now, accepted plans are visible at a summary level, but users cannot properly inspect a scheduled workout before they begin. That makes the plan feel shallower than it really is and makes Home / Plan less informative than they should be.

### Scope

Users may open a preview for an accepted upcoming workout from:

- Home when a next workout or today's workout is shown
- Plan when the app is in accepted-plan state

The preview is read-only.

Users may:

- inspect workout title, scheduled day, and scheduled date
- inspect the planned exercise list
- inspect planned sets, reps, and target weight for each exercise
- start the workout from the preview when the scheduled workout is today

Users may not:

- edit exercises from preview
- reorder exercises from preview
- modify workout day assignment from preview
- start a future-day workout early unless that behavior is explicitly added later

### Navigation

Rules:

- `Home next workout/today workout card tap` -> `Workout Preview`
- `Plan accepted-week workout tap` -> `Workout Preview`
- `Workout Preview back` -> return to previous screen
- `Workout Preview start` -> `Active`

### Preview content

The screen should include:

- workout title
- scheduled date / day label
- list of exercises in plan order
- set and rep prescription
- target weight when present
- clear CTA when the workout is available to start today

### Data model impact

No schema changes are required if preview reads from the accepted `PlannedWorkout` and `PlannedExercise` data already stored locally.

### Implementation notes

- prefer reusing existing workout card styling and exercise row patterns where possible
- keep the preview intentionally read-only so accepted-plan behavior remains stable

---

## Feature 2: Workout log detail and history review

Status: Implemented in PR #24 (`feat/feat7-workout-log-detail`).

### Context

History currently proves that workouts were completed, but it does not let users inspect those completed sessions in a meaningful way. That weakens both portfolio storytelling and later record derivation.

### Scope

Users may open a completed workout log detail screen from History / Logs.

Users may:

- open a log row to inspect a completed workout
- review high-level workout metadata
- review the exercises performed in that session
- review planned vs logged set data when that information exists in the session snapshot

Users may not:

- edit completed workout logs in v3
- create records automatically just by opening a workout
- mutate accepted plan data from history

### Navigation

Rules:

- `History / Logs row tap` -> `Workout Log Detail`
- `Workout Log Detail back` -> `History / Logs`
- if the detail screen exposes record actions, returning from those actions should land back on the same log detail or back on History in a predictable way

### Detail content

The screen should include:

- workout title
- completion date
- duration
- exercise count
- list of performed exercises
- per-set weight and reps where logged
- clear empty or skipped states for unfinished planned sets

### Data model impact

v3 likely needs persisted read access to the completed session snapshot rather than only the summary `WorkoutLog` row. If that snapshot is not currently queryable after completion, v3 should add the minimum persistence needed to reconstruct a workout detail view reliably.

### Implementation notes

- this feature is the foundation for derived records
- if history cannot tell a coherent workout story, derived records will feel disconnected

---

## Feature 3: Derived records from completed workouts

### Context

Manual records already work, but records derived from completed workouts are the more credible long-term flow. The key constraint is that derived records must not become unclickable dead ends. Users need provenance.

### Product direction

v3 should prefer a guided derivation flow over blind automatic record creation.

Recommended model:

- a completed workout log exposes eligible record candidates
- the user can promote a logged performance into a personal record
- the resulting record remains linked to the source workout log

This keeps records intentional and reviewable while still reducing duplicate manual entry.

### Scope

Users may:

- create a record from a completed workout detail view
- create a record from a specific completed set or exercise summary
- see whether a record came from manual entry or a logged workout
- navigate from a logged-derived record back to its source workout when provenance exists

Users may not:

- have records silently auto-created for every workout in v3
- edit workout history through the record flow
- derive records from incomplete in-progress sessions

### Eligibility rules

Base rules:

- only completed workouts may produce derived records
- only sets with both reps and weight filled count as candidates
- v3 remains weight-only for record values
- a derived record uses the completed workout date as `achievedOn`
- exact duplicate entries are still blocked

### UX rules

Rules:

- derived record creation should begin from `Workout Log Detail`, not from the flat records list
- record creation should feel like "Save this lift as a record" rather than hidden automation
- logged-derived records display a `Logged` source badge
- tapping a logged-derived record should offer a path to the source workout when `sourceWorkoutLogId` exists

### Data model impact

The existing records model already anticipates this direction:

- `sourceType` becomes actively used
- `sourceWorkoutLogId` should be populated for logged-derived records

No major schema expansion is required unless the current log/session persistence is too shallow to identify candidate lifts cleanly.

### Explicitly out of scope for v3

- fully automatic record generation from workouts
- trend or leaderboard logic
- one-rep-max estimation formulas
- auto-detection of "best set" across multiple algorithms

---

## Navigation updates in v3

- `Home` can open `Workout Preview`
- `Plan` accepted state can open `Workout Preview`
- `History / Logs` can open `Workout Log Detail`
- `History / Records` can open record edit for manual records and provenance-aware detail behavior for logged-derived records

## Suggested implementation order

1. Accepted workout preview
2. Workout log detail and history review
3. Derived records from completed workouts

This order keeps the data and navigation story coherent.

## Future roadmap candidates

These items remain important, but they are not the recommended core of v3.

### Polish track

- UI polish and interaction cleanup
- active-session UX improvements

These are worth doing as opportunistic follow-up PRs whenever they can be scoped tightly.

### Platform track

- login flow
- background sync / backup strategy
- multi-device sync
- guest-to-account migration

These are valuable but significantly increase architecture and product surface area. They should be treated as a separate platform phase rather than folded into workout-review work.

### AI planning track

- AI-assisted planning intake
- AI-assisted planning rules and constraints
- external model support and API key strategy
- on-device model exploration, including whether a small model such as Gemma is realistic on target devices
- output validation and safety guardrails to avoid unsafe or random programming
- structured planning schema for generated workouts

This area is high-interest but still research-heavy. It should begin with a dedicated product/technical spike before implementation.

### Other possible future features

- charts, trends, and progression insights
- multi-week planning
- custom program builder flows
- richer settings or profile customization
- theme switching

### Features already considered complete enough for now

- better local plan generation baseline

## AI planning research questions

Before AI-assisted planning becomes an implementation feature, the product needs answers for:

- what user inputs are required up front
- which constraints are hard rules vs soft preferences
- how generated plans are validated before showing them to users
- what minimum safe exercise programming rules are enforced outside the model
- whether on-device inference is viable for the target portfolio/demo devices
- whether remote-model usage fits the product story and secret-management constraints

## Build principle

v3 should strengthen trust in the current app before widening scope. The next release should help users understand their workouts and history more clearly, not simply add more surface area.
