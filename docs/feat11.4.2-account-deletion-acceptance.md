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
