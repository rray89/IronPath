# Combined reliability review candidate

This candidate keeps one code baseline for the local plan → training → history →
records → next-week loop. It remains unmerged pending concentrated BOSS product
acceptance. This guide preserves the original MVP, V2 3.2/3.3, V3 feat8 and V4
feat9.4/feat9.6.1 identities rather than inventing a new feature number.

## Sources and integration

- Base main: `ff847487d8310685b5308ef84c8796e9edbff1ea`.
- PR69: `68be5938866fd6664e57c85520d411a30df78bcd` — fresh AI acceptance validation,
  configuration invalidation, Debug remote comparison.
- PR70: `e748407d766edfe3db505252614099626eb24527` — manual Records edit/delete,
  explicit derived records and read-only provenance.
- PR71: `393ed5632af92ce8c3b6c00125599f42861d1621` — atomic workout startup and
  continuation, explicit next-week intent/target, session-blocked/idempotent acceptance.

All three source commits are preserved in the integration ancestry. The original PRs
are not rewritten, closed or automatically merged. PR71's textual conflicts were in
PlanViewModel, PlanScreen and AI review tests; the integration retains both contracts.
Intake freezes both the next-week target and remote configuration. Plan uses persisted
accepted state plus SavedStateHandle setup intent, while AI retains typed pending and
mapped drafts, current-clock validation, stable retry IDs and cancellation. Navigation
keeps next-week entry and Records source/back-stack behavior. The AI Room fixture is
adapted to SessionRepository's TimeProvider/IdProvider dependencies without restoring
destructive session replacement.

V2 restoration uses immutable draft transformations and the existing plan acceptance
transaction. Day moves/swaps preserve identity and date/order; free-text form edits
validate V2 ranges, removals retain one Undo, and handles support both drag and
accessible reorder actions. An empty rule draft cannot be accepted. No schema,
post-accept editor, Active exercise editor or AI free-text policy is added.

## Combined acceptance walkthrough

1. Generate a rule plan for two or three days. Move a workout to an empty day, then
   select an occupied day to swap. Titles and exercises follow their original workout.
2. Edit name/sets/reps/kg; test 1/20 sets, 1/100 reps and 0/fractional kg, an invalid
   value, and Cancel. Add the same exercise name again and confirm duplicates are allowed.
3. Drag a handle and use its Move up/Move down menu. Remove an exercise and Undo;
   remove the final exercise of a day and Undo. Accept, relaunch, and confirm dates,
   names, order and read-only accepted rows persist. An all-empty draft stays unaccepted.
4. Start a seeded today's workout through Preview/Active, enter a set and repeat start
   from another entry. Continue keeps the session and entered values. Complete the week,
   open next-week Setup from Home and Plan, Cancel once, then accept the next week.
   Old logs/records survive; acceptance is blocked while a workout remains active.
5. In Records, edit a manual row, Cancel, then Save. Delete with both Cancel and Confirm.
   From a completed log, save an eligible set; open its Logged record and follow the
   read-only source path back to Records. Reopening does not duplicate it; an incomplete
   set cannot be saved.
6. Generate a deterministic AI draft and edit a catalog exercise. Check source, warnings,
   final validation and single acceptance. Changing Remote AI configuration invalidates
   the old draft/late generation; cancel or leave generation and confirm no late draft.
   Cross-day invalidation and remote changes have deterministic automated proof.
7. At 200% font size, reach the day picker, edit validation, Save/Cancel, Undo, reorder
   menu, next-week and record/source decisions. Verify the text and sequence are usable.

Internal DevTools and seeded data are optional manual fixtures; this worker never
installs, clears, resets or runs tests on the physical device. BOSS/root chooses the
final manual environment. The combined APK uses the current Debug account/backup demo,
not the separate PR65–68 real cloud candidate chain. Real identity from main remains
available only in its existing variant. The deletion service remains paused.

## Automatic evidence contract

Local evidence includes JDK21 JVM/core coverage, Spotless/lint, debug/release and
instrumentation APK compilation. Focused editor tests first failed for the expected
unimplemented transformations. New tests cover immutable boundaries, busy/no-op/Undo
commands, UI move/swap/cancel/validation/drag/accessibility actions, 200% font scale,
real NavHost+Room modified-plan acceptance/recreation and integrated AI/session/config
acceptance guards. Existing source tests remain; obsolete static-rule-review assertions
are updated to V2 while preserving their name/prescription/removal checks. Taller rule
rows use explicit scrolling in the original plan persistence journey.

The integration PR owns two sequential independent GPT-6.1 Sol reviews and fresh cloud
CI/required Nightly proof for the combined head. Source-PR green checks are historical
input evidence and do not count as the combined head passing. Instrumentation
compilation does not count as device execution. Cloud emulator performance is
informational; no controlled physical-device performance claim is made.

## Separate live-provider step

No live inference is part of automatic verification or this delivery. A later explicit
live test needs the selected supplier/route, an app-entered limited test key, authorized
request count and spending cap, plus the device/install/data scope. Use synthetic
intake. Keys stay in process memory and are cleared by disable/route switch/process
exit. Provider availability, output quality and billing cannot be inferred from the
HTTP fixtures or validator tests. No credits, backend deployment or real user data are
authorized here.
