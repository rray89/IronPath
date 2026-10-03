# Feat11.4.6 — Real cloud restore and one undo

Status: combined backup + sync + restore candidate; human acceptance pending.

BOSS authorized this bounded slice on October 2, 2026 and moved the human checkpoint
past the separately reviewed backup and sync candidates to the combined experience.
The feature is stacked on `codex/feat11.4.5-cloud-sync`. Its earlier backup and sync
dependencies stay frozen. This is not product acceptance, merge permission, or
permission to change live Firebase, private configuration, or installed device data.

## Behavior and authority

Authpreview reuses the accepted V5 whole-backup review. Opening it explicitly downloads
only the latest COMPLETE, supported and validated snapshot. The preview identifies the
backup date and source device, with Added, Updated and Replaced counts for each durable
entity category. It never merges. Cancel and ordinary Back invalidate the ephemeral
preview without changing training rows, ownership, shared baseline, remote data or the
existing undo slot. Restart requires a fresh preview.

Confirmation requires a long press. An active workout needs a separate unchecked
acknowledgement naming its loss; that acknowledgement is bound to the previewed session.
Confirmation rechecks account/session, installation, local profile generation,
revision/content, baseline, active session and complete remote identity/generation.
Complete Firestore payloads are immutable; the validated preview payload is retained
only in memory, and a fresh metadata inspection guards confirmation. Room repeats its
local comparison inside the transaction. Changes arriving during inspection require
review again. There is no cross-service transaction: the selected complete snapshot is
current at the final remote check, and a later remote generation requires another
explicit lookup/review.

The existing Room transaction replaces all seven durable training categories, removes
the explicitly acknowledged active workout, updates ownership and lineage, and replaces
exactly one bounded undo slot containing the previous local graph and shared baseline.
The same transaction rolls everything back on failure. The Room boundary also rejects
an artifact whose account owner differs from the requested account and rejects pending
sign-out. No schema migration or new cloud format is introduced.

One undo is local, account/installation scoped and survives a cold database reopen. It
has its own impact preview and long press, and an active workout blocks it. It restores
the prior durable rows and matching baseline, advances the current local revision,
preserves installation/profile generation and consumes the slot atomically. It never
restores a discarded active workout. A subsequent successful restore replaces the slot;
failed/cancelled restores and undos preserve it. Profile reset, installation transfer,
Remove-data sign-out and the existing demo deletion transaction clear it. Keeping data
on sign-out preserves the same account's slot without granting another account access.

Undo remains `Local changes` or `Review required`, including an unclaimed pre-restore
profile with no previous baseline. An already observed newer same-account generation
stays separate from the restored older baseline so a later sync can compare safely.
A fresh explicit lookup can recover the latest observation after process restart.

Restore and undo never publish, run retention, delete or otherwise change the cloud
backup. No remote work is triggered by sign-in, startup, resume, ordinary local edits
or connectivity recovery. Debug remains deterministic and release keeps the inert
binding. Real remote account deletion, background sync, historical snapshot selection
and per-record restore remain excluded.

The sync dependency's recovery limits are unchanged: unknown completion receipts need
an explicit fresh preview against the retained shared baseline, and an interrupted
young upload whose candidate changed may wait for the existing 24-hour lease before
reclamation. This feature does not imply those limits are solved.

## Verification and build

The regression suite covers explicit download/preview, cancellation, duplicate actions,
corrupt data, stale local/remote/profile/session guards, active-workout acknowledgement,
transaction failure, persistent one-undo semantics, baseline restoration and dirty
status after recreation. Real Room tests combine the cloud coordinator with a complete
durable graph, cold reopen, one undo and reset. Existing trigger-failure Room tests
continue proving atomic rollback and slot preservation. Shared cloud Compose tests
cover accurate copy, callbacks, checkbox/hold semantics and 200% font-scale reachability.
The actual Kotlin REST emulator test compares the published metadata, manifest and
chunks before and after restore plus undo; the existing owner/cross-owner/unauthenticated
and interrupted-upload rules tests remain required.

Use JDK 21 and the Android SDK, without private configuration:

```bash
./gradlew --no-daemon --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2' \
  assembleAuthpreview
```

The generated APK is `app/build/outputs/apk/authpreview/app-authpreview.apk`, package
`com.example.ironpath.authpreview`. Without private preview configuration it cannot
contact live Firebase. Final evidence belongs in the PR: focused JVM tests, formatting,
lint, debug/release/authpreview assembly, JVM/core coverage, instrumentation compilation,
Firebase software-emulator rules/transport tests and applicable cloud CI. Two fresh
sequential independent reviews remain required. Local Android emulator and Seeker
execution are deferred by BOSS; compilation is not device execution or human acceptance.

## Combined acceptance path

The execution owner prepares any configured candidate and arranges authorized device
and live-backend access. Do not overwrite frozen APKs or clear installed app data merely
to follow these steps. Use deliberately synthetic portfolio records.

1. Sign in, confirm identity alone uploads nothing, then explicitly preview and back up
   record A. Verify the latest complete summary.
2. Exercise manual sync and its explicit conflict choices using the existing combined
   sync walkthrough. Verify independent changes and cancellation.
3. Add local record B, preview whole-backup restore, check date/source and categorized
   impact, then cancel. Verify A and B and any prior undo remain unchanged.
4. Reopen the preview. A short press only shows hold guidance. Long-press Restore and
   verify the cloud snapshot replaces local records, with one undo available. The cloud
   summary and backup remain unchanged.
5. Close and reopen the app without clearing data, review the one undo and long-press it.
   Verify B returns, the slot is consumed and status is Local changes or Review required.
6. With an active workout, verify unchecked discard prevents restore; cancellation keeps
   the workout. Explicitly acknowledged restore may discard it, and undo cannot bring
   that session back. An active workout blocks undo through the normal workout flow.
7. Change a local record or remote generation after preview and confirm it requires a
   fresh review. Verify a failed/interrupted operation leaves a complete local graph
   and a usable prior undo, with no automatic cloud upload.

Human acceptance of this combined candidate is still required before merge.
