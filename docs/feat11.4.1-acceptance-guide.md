# feat11.4.1 Acceptance Guide

Review APK: `app/build/outputs/apk/debug/app-debug.apk`. Use a disposable emulator.

1. Open Account & Backup, sign in with the deterministic demo account, and create local training data. Start an active workout if the active-session case is being checked.
2. Open **SIGN OUT** and confirm **Keep data on this device** is selected. Dismiss with **CANCEL** or system Back; the account session and training data should remain unchanged.
3. Submit Keep-data sign-out, then sign back into the same demo account. The training data and its account ownership should still be present; no backup or restore should start automatically.
4. Open sign-out again, select **Remove data from this device**, choose **CONTINUE**, then confirm **REMOVE DATA AND SIGN OUT**. The active workout and local training data should be gone. On the second dialog, **BACK** returns to the choice without signing out.
5. If a remote backup is present in the demo fixture, verify it remains. The adapter is deterministic and does not contact Google, Firebase, or a live cloud service; remote preservation and reset recovery are also covered by the automated Room and journey tests.
