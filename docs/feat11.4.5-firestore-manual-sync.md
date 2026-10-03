# Feat11.4.5 — Real manual sync

Status: implementation in progress; combined backup + sync product acceptance pending.

## Scope and decisions

BOSS authorized this bounded slice on October 2, 2026, moving the human checkpoint
to a usable combined real backup + sync candidate. It is stacked on the reviewed,
unmerged PR65 commit `445e8171ca008c83013c998a770649a496ad2142`. This does not
accept PR65 or authorize live configuration, device changes, or a merge.

The accepted V5 manual-sync flow and `ManualSyncMerger` remain authoritative.
Seven durable entity categories participate; active workouts, drafts, credentials,
and provider state do not. Shared Room snapshots establish the comparison baseline;
wall-clock time never picks a winner. One-sided changes are included in both
outcomes. Conflicts require an explicit local/cloud version choice; the cloud
choice affects conflicting versions, not the entire backup. Invalid candidate
graphs cannot be confirmed. Whole-backup restore, undo, and remote account deletion
remain unavailable in authpreview. Debug remains deterministic; release stays inert.

The preview explicitly reads and validates the latest complete cloud payload to
show differences, without changing Room ownership, rows, lineage, or cloud data.
Confirmation rechecks session, installation/profile, local content/revision, and
cloud identity/generation. It publishes the selected merged snapshot through the
existing guarded protocol, then conditionally applies it with the shared baseline
in one Room transaction. An active workout blocks sync. Cancel discards only the
ephemeral preview; process recreation always requires a new preview.

There is no distributed atomic commit between Firestore and Room. Cloud publication
may complete before a cancellation, lost receipt, or failed/stale local transaction.
Keep the old local baseline and all local rows in those cases. Explicit refresh and
a new three-way preview recover safely; never report the two sides as synchronized
until Room applies the validated result. Ordinary backup must not replace an unknown
newer generation merely because it originated from this installation. Exact-content
backup receipt recovery remains safe for an already owned profile. No automatic
sync or retry runs on sign-in, startup, resume, edits, or connectivity recovery.

## Verification plan

- Red/green JVM coordinator and protocol tests for merge/conflict/cancel, ownership,
  stale previews, duplicate actions, active workouts, unknown receipts and partial
  application; actual Kotlin REST emulator round trips and denied reads.
- Real Room conditional/atomic application and shared Compose state/action tests,
  including cloud review copy, conflict choices, cancellation and 200% font scale.
- JDK21, bounded Gradle workers/memory, formatting, lint, debug/release/authpreview
  builds, JVM/core coverage, checked-in Firebase rules, and applicable cloud CI.
- Local Android emulators and Seeker checks are deferred by BOSS; no device install,
  clear/uninstall, live backend write, or frozen PR65 APK replacement is permitted.
- Two sequential independent Astra Ultra reviews before final PR delivery.

## Combined acceptance (coordinator prepares the configured APK and live rules)

1. Sign in and verify that no training data uploads automatically.
2. Preview and confirm backup with synthetic portfolio data; refresh its summary.
3. Review manual sync against a second isolated installation's synthetic changes;
   inspect category counts, then cancel and verify both sides remain unchanged.
4. Reopen, confirm one-sided changes, and verify local records and cloud summary.
5. Make a same-record conflict, select each outcome in separate trials, and verify
   that only the explicit choice decides conflicting versions.
6. Change local/cloud data after preview or interrupt connectivity; require a fresh
   review, retain local work, and recover through an explicit retry.

Product acceptance is still required before merge. The coordinator owns live
Firebase setup, private configuration, device delivery, and integration decisions.
