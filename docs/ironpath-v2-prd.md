# IronPath v2 PRD

Date: 2026-04-09
Status: Draft

## Overview

v2 builds on the locked MVP foundation with three focused enhancements that deepen the planner review experience and complete the records CRUD cycle. All changes are local-only and extend existing Room entities and UI patterns.

v2 does not change:
- navigation structure (4-tab bottom nav)
- theme (dark mode only)
- active session rules
- logs model
- persistence rules (UUIDs, timestamps, etc.)

## Carries forward from v1

All v1 rules remain in effect unless explicitly modified below. The v1 PRD (`docs/ironpath-mvp-prd.md`) is the canonical reference for anything not addressed here.

## Still out of scope

- auth, accounts, guest-to-account migration
- backend persistence, multi-device sync
- on-device AI / foundation-model integration
- theme switching
- multi-week planning
- custom program builder flows
- charts/trends/progression analytics
- advanced adaptive/intelligence features

---

## Feature 1: Exercise-level plan editing during review

### Context

v1 Planner Review allows deleting entire workout days and regenerating the full week, but users cannot touch individual exercises. v2 opens exercise-level editing so the generated plan feels customizable before accepting.

### Scope

Exercise editing is available only in Planner Review state (pre-accept, in-memory plan). Once a plan is accepted, exercises are locked.

Users may:
- edit an exercise's sets, reps, and weight values
- remove an individual exercise from a workout
- add a new exercise to a workout
- reorder exercises within a workout

Users may not:
- edit exercises after a plan is accepted
- edit exercises during an active session (unchanged from v1)

### Edit exercise details

Rules:
- tap an exercise row to open an inline edit mode or bottom sheet
- editable fields: exercise name, sets, reps, weight (kg)
- exercise name is free text with suggestions from the template pool and existing record names
- sets must be a positive integer (1-20)
- reps must be a positive integer (1-100)
- weight must be non-negative (0 is allowed for bodyweight)
- save updates the in-memory plan immediately
- cancel discards changes and closes the editor

### Remove exercise

Rules:
- each exercise row shows a delete/remove action (icon or swipe)
- removing the last exercise from a workout removes the entire workout day (same as v1 delete-workout behavior)
- no modal confirmation for individual exercise removal
- removal updates the in-memory plan immediately
- show an undo snackbar/toast after removal so the action is easily reversible

### Add exercise

Rules:
- each workout card shows an "Add Exercise" action at the bottom of the exercise list
- opens the same editor used for editing (inline or bottom sheet)
- new exercise is appended to the end of the list (highest `orderIndex + 1`)
- all fields required: name, sets, reps, weight
- same validation rules as edit
- duplicate exercise names within the same workout are allowed — users may intentionally repeat a movement with different reps or weights

### Reorder exercises

Rules:
- drag handle on each exercise row within a workout
- reorder updates `orderIndex` values in the in-memory plan
- reorder is constrained within a single workout (no cross-workout drag)

### Data model impact

No entity schema changes required. `PlannedExercise` already has all needed fields (`name`, `sets`, `reps`, `weightKg`, `orderIndex`). Changes are in-memory during review and persisted on accept via the existing `createPlanWithWorkouts` transaction.

### DAO additions needed

After accept, exercises are persisted. For any future post-accept editing (not in v2 scope), the following would be needed:
- `@Update` for `PlannedExercise`
- `@Delete` for `PlannedExercise`
- `@Query` for max `orderIndex` per workout

For v2, since editing happens in-memory pre-accept, no DAO changes are strictly required. The existing batch `insertExercises` handles persistence on accept.

---

## Feature 2: Review-time day reassignment

### Context

v1 locks workout-to-day assignment after generation. If the user wants Tuesday's workout on Thursday instead, they must regenerate the entire week. v2 lets users move workouts between days during review.

### Scope

Day reassignment is available only in Planner Review state. Once a plan is accepted, day assignments are locked.

Users may:
- move a workout to a different day within the same Monday-Sunday week
- swap two workouts between their assigned days

Users may not:
- move a workout outside the generated week's date range
- assign multiple workouts to the same day
- reassign days after accepting the plan

### Move workout interaction

Rules:
- tap the day header/label on a workout card to open a day picker
- day picker shows Monday-Sunday; occupied days (assigned to another workout) are visually distinct from empty days
- selecting an empty day moves the workout to that day
- selecting an occupied day always performs a swap — both workouts exchange their days with no additional confirmation
- moving a workout updates both `dayOfWeek` and `scheduledDate` in the in-memory plan
- workout title remains unchanged (e.g., "Chest/Tris" stays "Chest/Tris" regardless of day)

### Visual feedback

- after reassignment, the review list re-sorts by day of week (Monday first)
- brief visual confirmation (reorder animation or highlight) is encouraged but not required

### Data model impact

No entity schema changes. `PlannedWorkout` already stores `dayOfWeek` (1-7 ISO) and `scheduledDate`. Both are updated in-memory during review and persisted on accept.

### Constraints

- if a user deletes a workout (v1 feature) and then reassigns another workout to that now-empty day, the empty day simply becomes available in the picker
- if all workouts are deleted except one, that workout can still be reassigned freely
- if all workouts are deleted, the Accept Plan action is disabled; the user must regenerate or be left on the review screen with no way to accept an empty plan

---

## Feature 3: Records edit and delete

### Context

v1 records are add-only. Users cannot fix a typo in an exercise name or remove an incorrect entry. v2 completes the CRUD cycle with edit and delete flows.

### Edit record

Rules:
- tap a record row in the Records list to open the edit flow
- edit flow reuses the same form layout as Add Record
- all fields are pre-populated with current values
- same validation rules as Add Record:
  - exercise name required
  - weight required and positive
  - date required, future date not allowed
  - exact duplicate entries blocked (excluding the record being edited)
- save updates the existing record in place (same UUID)
- cancel returns to Records list with no changes
- `normalizedExerciseName` is recomputed on save if name changes

### Delete record

Rules:
- delete action is available from the edit screen (not from the list directly)
- delete requires confirmation dialog: "Delete this record? This cannot be undone."
- on confirm: delete the record, return to Records list
- on cancel: dismiss dialog, stay on edit screen
- no cascade effects (records are standalone entities)

### Navigation updates

- `Record row tap` -> Edit Record screen
- `Edit Record back/cancel` -> Records list
- `Edit Record save` -> Records list (with updated record visible)
- `Edit Record delete confirm` -> Records list (record removed)

### Data model impact

No entity schema changes. `PersonalRecord` entity is unchanged.

### DAO additions needed

- `@Update` for `PersonalRecord`
- `@Delete` or `@Query("DELETE FROM personal_records WHERE id = :id")` for `PersonalRecord`
- `@Query` to get single record by ID for pre-populating the edit form

---

## Updated "Out of scope" list

The following items were promoted from out-of-scope to in-scope for v2:
- ~~exercise-level plan editing during review~~ -> Feature 1
- ~~review-time day reassignment~~ -> Feature 2
- records edit and delete (implied by "no edit flow / no delete flow" in v1) -> Feature 3

## Dev Tools updates

No new dev tools capabilities required for v2. Existing seed data tools remain sufficient for testing the new features.

## Database migration

v2 requires no schema changes to Room entities. The database version remains at 1 and no migration is needed. All new functionality operates on existing tables and columns.

## Build principle

Same as v1: build the smallest version that looks intentional, works end-to-end, and tells a strong portfolio story. v2 features should feel like natural extensions, not bolted-on additions.
