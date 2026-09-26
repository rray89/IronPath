# feat11.4.1 Acceptance Guide

Review APK: `app/build/outputs/apk/debug/app-debug.apk`. Use a disposable emulator. Do not install this review APK on Seeker while the owner is doing physical acceptance.

## Account choice after sign-in

Start with an unclaimed local profile that has no included training rows and a complete demo backup containing two personal records.

1. Open Account & Backup and sign in with the deterministic demo account. Confirm the signed-in session and the three choices: **RESTORE BACKUP**, **KEEP THIS DEVICE EMPTY**, and **DECIDE LATER**.
2. Choose **RESTORE BACKUP**. Confirm that the existing whole-backup preview opens. Use Back to cancel the preview. Local rows, ownership, lineage metadata, session, and remote backup should remain unchanged.
3. Choose **DECIDE LATER**. Home should open and the demo session should remain present. Restart the app, return to Account & Backup, and confirm the same choices are available; local rows, owner, lineage metadata, and remote backup remain unchanged.
4. Choose **KEEP THIS DEVICE EMPTY**. Confirm the local profile now belongs to the signed-in demo account, while training rows remain empty and every lineage/baseline field stays unchanged. The choice panel closes, the complete remote backup remains available, and no empty snapshot is created.
5. Open **PREVIEW WHOLE-BACKUP RESTORE**. Review the impact, then use the hold confirmation. The two backup records should appear together, the remote snapshot should remain unchanged, and one local undo should be available.
6. If an active workout exists, confirm that **KEEP THIS DEVICE EMPTY** is unavailable. A foreign local owner must remain blocked even when included training tables are empty.
7. With an unsupported local demo-session fixture, confirm Back leaves it untouched. Use **CLEAR INVALID DEMO SESSION** and confirm the account returns to local-only while training rows and Room lineage remain unchanged.

## Explicit sign-out

1. Open Account & Backup with owned local training data and an active workout. Open **SIGN OUT** and confirm **Keep data on this device** is selected. Dismiss with **CANCEL** or system Back; the account session and training data should remain unchanged.
2. Submit Keep-data sign-out, then sign back into the same demo account. The training data and its account ownership should still be present; no backup or restore should start automatically.
3. Open sign-out again, select **Remove data from this device**, choose **CONTINUE**, then confirm **REMOVE DATA AND SIGN OUT**. The active workout and local training data should be gone. On the second dialog, **BACK** returns to the choice without signing out.
4. Verify the remote backup remains after removal. The adapter is deterministic and does not contact Google, Firebase, or a live cloud service; remote preservation and reset recovery are also covered by the automated Room and journey tests.
