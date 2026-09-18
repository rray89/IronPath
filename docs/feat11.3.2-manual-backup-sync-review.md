# feat11.3.2 manual backup and sync review

This First Usable Slice continues the accepted account shell and Experience Direction.
It uses a **demo backup stored inside this app on this device**, with no live Google
or Firebase connection. Demo backup is not protection against uninstall or device
loss. Confirmed sync can change actual local training data. Whole-backup restore,
undo, sign-out/deletion and background operations remain outside this slice.

## Seeker walkthrough

Physical handoff is pending: the September 18 focused Gradle/UTP run passed its
40 isolated tests, but runner cleanup then uninstalled the target package. The
installed app and its data were not retained. No recoverable local copy has been
identified; further Seeker writes are stopped pending BOSS's recovery/reinstallation
decision. The APK is available from this branch. Physical testing now follows the
direct, non-uninstalling instrumentation instructions in `testing-strategy.md`.

Allow about five to ten minutes. Keep existing app storage; do not uninstall or clear
it. If the screen is locked, leave physical acceptance for the next session.

1. Open Menu → Account & Backup. Sign in to the labelled demo account if necessary.
   Confirm that signing in has not created a backup. The page explains the demo storage
   boundary. If there is no included training data, the backup preview must explain
   that confirmation only associates the account and creates no empty backup.
2. Choose **Back Up Now**. Check the actual included counts. Use toolbar/system Back
   or **Back to Account & Backup**; it returns to the account overview without running
   the backup or cancelling the pending account setup.
3. Open the backup preview again and confirm. With included data, check the completed
   demo backup time/counts and **Up to date** status. If an empty profile was associated,
   the correct outcome is **Signed in — no backup yet**.
4. Close/reopen the app without clearing storage. Check that account identity, latest
   complete backup and status reconstruct. Create or edit a test personal record using
   the normal app flow; the account page should now show **Local changes**. The previous
   backup must not change automatically.
5. Choose **Review manual sync**. Check the changes, cancel once, and confirm that the
   record and backup remain unchanged. Open a fresh preview and confirm. With no
   conflicting edits there is no conflict choice to make; after completion, the local
   record remains and the backup status becomes current.
6. Confirm return navigation to Home and History, then close/reopen once more. Training
   data and the latest completed state remain available. **Explore backup preview**
   still opens the separately labelled, non-mutating interaction fixture.

If a preview reports that data changed, open a new review. An active workout blocks
sync without discarding that workout. When replacing a previously nonempty backup
with empty data or more than a 50% reduction, confirmation also requires acknowledging
the reduced counts.

## Automated scope

The automated suite creates an isolated second installation to exercise real
same-record conflicts. It checks that neither choice is preselected, only one choice
can be selected, both outcomes preserve independent changes, and invalid merged
relationships remain blocked. No second physical account/device is needed for the
walkthrough above.

The same suite verifies stale previews, concurrent local/remote changes, publication
followed by an unsuccessful local commit, explicit retry, typed failure states,
transaction rollback, durable chunked shared baselines, and migration preservation.
Firebase emulator evidence exercises a test-only protocol adapter under real security
rules, using payload fixtures checked against IronPath's Kotlin codec and merge engine.
It does not claim Android Firebase integration or production authentication.

## Checkpoint

Product acceptance of this slice is pending. After acceptance and merge, the next core
flow is feat11.3.3 whole-backup restore and one-snapshot undo.
