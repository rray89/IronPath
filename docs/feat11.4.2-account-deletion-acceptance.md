# feat11.4.2 Account Deletion Acceptance

This is a debug-only demonstration using the local deterministic account and backup
adapters. It does not contact Google, Firebase, Firestore, or a network service. Release
account deletion remains unavailable. The Google identity is not deleted.

## Managed emulator walkthrough

Use the managed Pixel2 API 29 emulator with the debug APK. Do not clear data on Seeker or
another physical device.

1. On Home, tap the top bar title five times within two seconds to open DevTools. Seed a
   plan for today, history logs, and personal records, then return Home.
2. Open the menu and choose **Account & Backup**. Choose **Sign in with Google**; the
   debug adapter selects the clearly labelled Demo Athlete identity.
3. Choose **Back Up Now** and confirm the preview. This associates the local sample data
   with the demo account and creates its on-device demo backup. Confirm the account shows
   **Signed in** and **DELETE ACCOUNT**.
4. Choose **DELETE ACCOUNT**. Review the current identity and complete deletion scope.
   Choose **CANCEL** and verify the account, plan, history, records, and active workout
   remain unchanged. Before confirming deletion, switch to History and back to Home so
   the previous History screen is saved in navigation state.
5. Reopen the flow, continue, then choose **DELETE ACCOUNT AND ALL DATA**. Wait for the
   progress UI to finish. Confirm IronPath returns Home and the account session is gone.
   Navigate to History, open Records, and save a new record to verify the previous
   profile's saved page state was discarded.
6. Open Account & Backup again and sign in with the demo identity. It is a new IronPath
   account incarnation: the old backup is absent and the deleted local training data
   does not return.
7. Regression check ordinary **SIGN OUT** separately: **Keep data on this device** and
   **Remove data from this device** retain their existing behavior and do not delete the
   demo backup.
8. On a fresh local profile with training data that has not been associated with an
   account, sign in to the demo identity and choose **DELETE ACCOUNT**. Confirm the dialog
   names unclaimed local data, then verify deletion clears it and returns Home. A profile
   owned by a different account must not offer this action.

## BOSS manual acceptance — 2026-09-29

BOSS confirmed, “都测过了，没有问题,” after completing the six-step account-deletion
walkthrough. The accepted candidate was commit
`4f3e66f8469a865ee51f73dd14afa76f939d77f9`, with debug APK SHA-256
`b4ff206c2bda03544f5e197b640fe664179d3f4b5085de9b78ca2f13af716114`. The parent chat
installed that APK over the existing app with `adb install -r` at 16:51 PDT, verified the
device APK hash, and confirmed the app launched.

The manual walkthrough covered:

1. Create record A and back it up to the Demo Athlete account.
2. Cancel at both deletion confirmation layers and verify nothing changes.
3. Confirm deletion; verify the account session, backup, and local training data are
   cleared and IronPath returns Home.
4. Without restarting, open Records and successfully add record B.
5. Sign in again as the same Demo Athlete; verify the old backup and record A do not
   return while record B remains.
6. Restart the app, sign in again, and verify record B remains.

This is manual product acceptance for the exact APK above. It is separate from automated
test execution. The new account-deletion journey was compiled and packaged, but was not
run as an instrumented test. The API 29 260/260 result belongs to older revision
`1452472` and does not cover the navigation-reset fix in this accepted candidate.

## Recovery checks

Automated tests inject remote purge failure and local Room cleanup failure, recreate the
deletion manager, and verify retry resumes from the durable journal stage. A malformed
remote index is replaced by a tombstone under the account lock; purge does not require
test-side deletion of the corrupt file. The remote store test also interrupts after the
tombstone is durable, retries through a new store instance, verifies no backup payload
remains, and rejects later publication to that account incarnation.

After PREPARED, there is no cancellation. A pending deletion blocks normal app startup
and training/profile writes until retry finishes. Successful deletion advances the local
profile generation so delayed work captured by the old screen cannot repopulate the new
profile. Restore undo must preserve that generation. Removing local data through ordinary
sign-out or account deletion also clears old navigation entries; a newly opened History
screen must accept a new record while rejecting work from the previous profile generation.
