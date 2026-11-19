# feat11.4.3 Google identity auth preview

The `authpreview` build type is an explicit, isolated Google identity preview. Its application ID
is `com.example.ironpath.authpreview`. It includes Firebase Auth and Credential Manager, while the
default `debug` build keeps the deterministic demo account/backup/deletion graph and `release`
remains inert.

## Build without private configuration

Run:

```bash
./gradlew assembleAuthpreview
```

This produces an auth preview with Google sign-in disabled. The app does not initialize Firebase
without valid generated preview resources. The account screen explains that sign-in is unavailable.

## Build with a private configuration file

Pass an explicit absolute path to a private JSON file outside the repository:

```bash
./gradlew assembleAuthpreview -PironpathAuthPreviewConfig=/absolute/private/path/firebase-authpreview.json
```

The file must contain nonblank string fields named `androidPackage`, `firebaseApplicationId`,
`apiKey`, `projectId`, and `webClientId`. `androidPackage` must be
`com.example.ironpath.authpreview`. Keep this file out of source control, build logs, and shared
artifacts. The build validates it and generates resources under the app build directory; that
generation task does not use the Gradle build cache. A relative, missing, malformed, incomplete, or
wrong-package file fails the auth preview build without printing its values.

The original implementation build did not use a private Firebase configuration. A separate
configured preview build was later used for the product acceptance recorded below. No Firebase
configuration values are stored in this repository.

## Behavior and limits

The preview uses the explicit Google button flow. Credential selection remains in memory until the
account gateway accepts its request generation and session epoch, then Firebase Auth persists the
session. Firebase UID is the account identity. Startup and provider-session changes read only local
Firebase session state and local account context; they do not fetch remote backup data or associate
local workouts.

This document records the identity-only baseline. The subsequent [feat11.4.4 manual cloud backup](feat11.4.4-firestore-manual-backup.md) adds explicitly confirmed backup and status refresh. Google account deletion remains unavailable. Signing in does not
upload, restore, merge, or link training data. Sign-out offers Keep and confirmed Remove choices;
Remove uses the existing durable local removal journal and recovery flow. It does not delete the
Google account.

## Product acceptance walkthrough

### Current candidate without private configuration

Install `app/build/outputs/apk/authpreview/app-authpreview.apk` on a review device and launch it.
Confirm the account entry and Google Account screen state that Google sign-in is unavailable until
private Firebase preview configuration is supplied. Confirm Cloud backup is described as
unavailable, and no backup, restore, data association, demo preview, or Google account deletion
action is offered.

### Configured Google identity flow — accepted 2026-09-30

BOSS confirmed all six steps passed on Seeker using a separately configured `authpreview` build from
revision `d88803917cfc57695a1825904f58006220fc494b`. The installed APK SHA-256 was
`f3f450d918bb926e45a72730f81985a86bf13a40b045b4a29145327882120cf7`. Configuration remained
private outside the repository.

1. Open the preview, continue on this device, complete first-use setup, and create personal Record A.
2. Open the account entry and start Google sign-in. Cancel the Google chooser once; verify the app
   remains signed out and Record A remains. Start sign-in again, select the Google account, and
   verify the displayed identity matches it while Record A remains.
3. Remove the preview from recents and reopen it. Verify the identity and Record A persist and Cloud
   backup remains unavailable.
4. Sign out with Keep selected. Verify the identity clears and Record A remains. Sign in again and
   verify Record A still remains.
5. Sign out with Remove selected. On the removal prompt choose Back, then cancel; verify the session
   and Record A are unchanged. Repeat, confirm Remove data and sign out, and verify the session is
   cleared and Record A is gone.
6. Sign in again with the same Google account. Verify Records is empty and no old demo backup
   appears. Restart the preview and verify the identity remains while Records stays empty.

This acceptance covers Google identity, local session persistence, and the app's local Keep/Remove
data behavior. It does not cover cloud backup, restore, training-data association, or deletion of a
Firebase or Google account.

The build command is:

```bash
./gradlew assembleAuthpreview
```

Add the absolute `-PironpathAuthPreviewConfig=...` argument only when building with the private
configuration file.
