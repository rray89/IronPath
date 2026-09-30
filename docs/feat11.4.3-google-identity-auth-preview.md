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

No Firebase project, OAuth client, or live service was provisioned for this implementation. A
private configuration file was not supplied to the implementation build, so real Google sign-in
has not been exercised.

## Behavior and limits

The preview uses the explicit Google button flow. Credential selection remains in memory until the
account gateway accepts its request generation and session epoch, then Firebase Auth persists the
session. Firebase UID is the account identity. Startup and provider-session changes read only local
Firebase session state and local account context; they do not fetch remote backup data or associate
local workouts.

Cloud backup and Google account deletion are unavailable in this preview. Signing in does not
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

### Google identity flow with supplied configuration

After an authorized private configuration file is available, rebuild with the absolute-path command
above and verify the following on a review device:

1. Sign in with Google and confirm the displayed name/email comes from the authenticated Firebase
   user. Confirm no workout data, owner UID, backup lineage, or undo state changes during sign-in.
2. Restart IronPath and confirm the Firebase identity is restored from the local session while Cloud
   backup remains unavailable.
3. Sign out with Keep selected and confirm the Firebase identity clears while workouts and their
   existing local ownership remain unchanged.
4. Sign in again, then sign out with Remove selected. Confirm the explicit removal prompt, local
   workout reset, and completed sign-out. Confirm this does not delete the Google account.

The configured Google identity flow has not been exercised in this build because no private
configuration file was supplied.

The build command is:

```bash
./gradlew assembleAuthpreview
```

Add the absolute `-PironpathAuthPreviewConfig=...` argument only when building with the private
configuration file.
