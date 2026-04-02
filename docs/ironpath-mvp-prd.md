# IronPath MVP PRD

Date: 2026-03-24
Last updated: 2026-03-29
Status: Locked MVP scope for current portfolio-project build

## Product intent
IronPath is a local-first workout planning app skeleton for a private portfolio project. The MVP goal is a clean, credible, demoable foundation rather than a production-ready fitness app.

This MVP focuses on:
- generating one calendar-week workout plan
- reviewing a generated weekly plan before save
- referencing the current accepted week from Home / Plan state
- executing today’s workout in an active session
- storing simple workout history and personal records

Out of scope for MVP:
- auth and account systems
- multi-device sync
- backend persistence beyond optional planner generation
- on-device AI or foundation-model integration
- AI-generated coaching or intelligence features
- full custom program building
- advanced analytics or adaptive features
- theme switching

## Theme scope
- dark mode only
- no theme switching in MVP

## Navigation
Top-level tabs:
- Home
- Plan
- Active
- History

History contains:
- Logs
- Records

## Planning scope
- MVP generates one week only
- a week is a fixed Monday-Sunday calendar week
- canonical product rule: if the user starts mid-week without an active plan, generate the next full Monday-Sunday week
- if local dev/test tooling temporarily allows current-week seeding for faster manual testing, treat that as internal-only dev behavior rather than product truth
- planner setup should collect specific workout days before generation

Planner Review supports light adjustment only.

Users may:
- review a generated week
- delete an entire workout day/session
- regenerate the full week
- accept the plan

Users may not:
- reassign workout days in review
- edit exercise details in review
- add or remove individual exercises in review
- reorder workouts freely
- use advanced program-builder interactions

## Plan / Review / Active state split
These are distinct product states:
- Planner Review = review a newly generated week before saving it
- Accepted current week = current-week state shown through Home + Plan
- Active Session = execute and log the current workout

They may share components, but should not be treated as the same screen or interaction mode.

## Route/state rules
### Home
Supported Home states:
- no active saved plan -> CTA to Plan
- active saved plan exists, next workout not today -> show next workout summary / rest state
- workout is today -> CTA to Start Session / Open Active
- current week fully completed -> CTA to Plan for next week

### Plan
Plan owns the plan lifecycle:
- no saved week -> Planner Setup
- generated but not accepted -> Planner Review
- accepted current week -> current-week state (return to Home; Plan shows summary/rest state, not review UI)
- completed week -> Planner Setup for next week

### Active
Active owns workout execution:
- no active saved plan -> no-plan state with CTA to Plan
- saved plan exists but no workout today -> rest-day state
- workout is today -> active-session entry / in-progress session
- session completed -> return to Home

### History
History contains:
- workout logs
- personal records

## Back/navigation behavior
- use normal navigation behavior by default
- `Planner Review` back -> `Planner Setup`
- `Add Record` back/save -> `History / Records`
- backing out of `Active Session` should not discard the session
- completing a workout should route back to `Home`

## Logs model
History / Logs stays simple in MVP.

Rules:
- one completed workout creates one `WorkoutLog`
- logs are sorted most recent first
- each log item shows workout title, completion date, duration, and exercise count
- `PlannedWorkout` and `WorkoutLog` are separate objects
- plan-to-log linkage is allowed via optional `sourcePlannedWorkoutId`
- no rich planned-vs-actual comparison UI in MVP

## Records model
Records are intentionally narrow for MVP.

Rules:
- a record is a simple max-weight personal record stored in kg
- records are weight-only
- `PersonalRecord` is a separate entity
- MVP supports manual records only
- `sourceType` remains future-ready for logged records later
- `sourceWorkoutLogId` is optional
- duplicates over time are allowed
- exact duplicates are blocked by normalized `exerciseName + achievedOn + weightKg`
- store `normalizedExerciseName` as a real field
- default sort is `achievedOn DESC, createdAt DESC`
- no search/filter in MVP
- no record detail screen in MVP

Records list row shows:
- exercise name
- weight in kg
- date
- source badge (`Manual` or `Logged`)

## Add Record flow
Rules:
- simple full-screen flow
- exercise name is free text
- show name suggestions from planned exercise names and existing record names

Required fields:
- exercise name
- weight (kg)
- achieved date

Optional field:
- note

Validation:
- exercise name required
- weight required and positive
- date required
- future date not allowed
- exact duplicate entries blocked

Save behavior:
1. create record
2. store locally
3. return to History / Records
4. show the newly added record in the list

MVP is add-only:
- no edit flow
- no delete flow

## Active session rules
Core rules:
- user logs the planned exercises for that workout
- user may add extra sets
- user may not add exercises during the session
- user may not remove exercises during the session
- exercise names are not editable during the session
- planned sets are not deleted; unfinished sets remain blank
- extra sets may be added with a simple `Add Set` action

Set completion rules:
- a set counts as done only when both reps and weight are filled
- this applies to planned sets and extra sets

Exercise completion flexibility:
- user may skip an exercise entirely
- skipped exercises may remain with no completed sets
- workout may still end even if some exercises or sets are incomplete

In-progress session rules:
- only one in-progress session may exist at a time
- do not allow editing/replacing the weekly plan while an active session exists
- if an old unfinished session blocks the next session, it may be auto-completed first
- auto-complete should count the planned workout as completed only if at least one set was logged

Completion behavior:
1. finalize the active session
2. write a workout log entry
3. mark the planned workout as completed when appropriate
4. update derived home/plan state
5. no automatic record creation in MVP
6. route back to Home

## Persistence rules
- all primary IDs are UUID strings
- do not use date/time-derived IDs as identity
- timestamps remain normal fields such as `startedAt`, `completedAt`, and `achievedOn`
- planned workouts store both day-of-week and full scheduled date
- `WeeklyPlan` is a separate parent entity with status `Active | Archived`
- `PlannedWorkout.status = Upcoming | Completed | Skipped`
- `InProgress` belongs to active-session state, not `PlannedWorkout`
- `ActiveSession` is a separate persisted entity
- `ActiveSession` stores source planned workout plus its own copied snapshot
- `ActiveSession` stores `startedAt` and `lastUpdatedAt`
- `WorkoutLog` stores raw timestamps plus derived `durationMinutes`
- `sourcePlannedWorkoutId` is optional provenance, not the core truth of an active session or completed log

## In scope now
- route clarity
- state clarity
- clean local-first model
- simple logs/history
- narrow records model
- lightweight Add Record flow
- implementation-friendly navigation behavior

## Out of scope for now
- theme switching
- richer settings/profile customization
- public auth implementation
- backend persistence
- multi-device sync
- guest-to-account migration
- multi-week planning
- review-time day reassignment
- exercise-level plan editing during review
- custom program builder flows
- charts/trends/progression analytics
- advanced adaptive/intelligence features

## Internal Dev Tools (not product MVP UI)
These are allowed as local-only internal testing helpers.

Rules:
- Dev Tools are internal developer support, not part of the user-facing MVP story
- hidden entry is acceptable; current preferred entry is multi-tap on the IRONPATH title
- no need to optimize discoverability for normal users
- keep the scope small and practical
- avoid turning Dev Tools into a full settings/debug system

Initial desired capabilities:
- seed a test weekly plan for today
- seed a test weekly plan for tomorrow
- seed workout history logs
- seed manual records
- clear all local data

Design direction:
- keep all dev/test actions in one dedicated Dev Tools surface rather than scattering them across normal screens
- if the app is ever prepared for public release later, visibility/hardening can be revisited then

## Build principle
Build the smallest version that looks intentional, works end-to-end, and tells a strong portfolio story.
