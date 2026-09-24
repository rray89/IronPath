# feat11.3.3 whole-backup restore and one undo review

This slice adds a read-only review of the latest complete backup, a held whole-backup
replacement, and one durable undo. It uses the accepted demo adapter stored inside this
app on this device. It is not a live Google or Firebase backup and does not protect data
from uninstall or device loss. Restore is latest-complete-only; there is no historical
backup picker. Product acceptance remains pending BOSS's walkthrough.

## Walkthrough

Use a disposable API 29 emulator or another device whose app data can be replaced. Keep
the existing feat11.3.2 app data and account backup; do not clear it or sign out.

1. Open **Account & Backup**. Sign in to the demo account only if needed. Check that the
   existing completed backup time and counts still appear; sign-in alone does not change
   them. This demo data is stored inside this app on this device and does not protect
   against uninstall or device loss.
2. In **History → Records**, add record A. Return to **Account & Backup**, choose
   **Back Up Now**, and confirm. Check that the new completed demo backup includes A.
3. Add record B in **History → Records**. Open **Preview whole-backup restore**. Check
   the latest backup's time and source and that B is listed as a personal record to be
   replaced. No older backup can be selected. A short tap on Restore gives hold guidance
   without changing data; Back cancels the review and leaves B in History.
4. Reopen the review and hold Restore. Check that B disappears and exactly one
   **Preview one undo** action is available. Optional active-workout check: with a
   workout open, the review names it and the discard acknowledgement starts unchecked.
   Cancel to preserve it, or acknowledge and hold Restore; the active workout is
   discarded and will not return with undo.
5. Recreate the app and confirm the one undo is still available. Preview it, review the
   impact, and hold **Undo**. Record B returns, the undo slot is consumed, and undo leaves
   the demo backup unchanged. A new active workout blocks undo until it is finished or
   discarded through its normal workout flow.
6. Check that the account reports Local changes or Review required after undo. Use the
   bottom navigation to visit Home and then **History → Records**; confirm the restored
   records remain visible. An explicit manual backup or sync is required to reestablish
   current lineage.

## Automated evidence

Focused JVM tests cover typed impacts, account changes during a suspended remote read,
same-route sign-in lookup, the preserved shared baseline and explicit conflict choice,
and the account/ViewModel state machine. The shared-base regression constructs a 50 →
60 local / 70 remote same-record conflict, then checks that restore/undo keeps the prior
shared base and requires an explicit sync choice. The focused API 29 managed-emulator
run passed 41 tests covering the account screen, Home/History journey, Room restore and
undo transactions, cold Room reopen, migration, and accessibility semantics. A
follow-up Room run passed 18/18 tests, including failed-undo rollback and retry plus
populated undo-slot clearing during reset and installation transfer. The complete JVM
suite passed 361/361 tests; core coverage is line 93.24% and branch 78.29%. The API 36
accessibility package passed 39 tests. Final-source `spotlessCheck`, `lintDebug`,
`lintBenchmarkRelease`, `assembleDebug`, and `assembleRelease` all passed. Release APK
inspection found the release-bound `LocalOnlyBackupCoordinator`; debug-only backup
transport, DI module, directory, and preview-screen classes do not appear in the release
mapping. Node 22 Firebase verification remains a CI check.

Firebase protocol tests exercise corrupt or missing chunks of artifacts already marked
COMPLETE under the emulator rules; their local 25-test run used bundled Node 24.19.0
because Node 22 was unavailable, so the pinned Node 22 runtime remains a CI check.

This guide records behavior for review; it does not claim product acceptance, a Seeker
test, or live Firebase integration.
