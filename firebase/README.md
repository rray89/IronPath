# Firebase backup test foundation

This directory contains only the local Firestore emulator configuration and the Security Rules
source intended for a future manual deployment. It cannot reach a live Firebase project, needs no
Google account or service credential, and does not enable IronPath sign-in or remote backup.

## Local and CI verification

Requirements: Node 20, JDK 21, and the repository Gradle wrapper.

```bash
npm ci --prefix firebase
./gradlew firebaseRulesTest
```

The Gradle task starts the Firestore emulator with Firebase's emulator-only `demo-ironpath` project
ID,
runs the pinned rules test suite, and stops the emulator. Tests cover owner CRUD/list access,
unauthenticated and cross-account denial, schema bounds, state transitions, orphan chunks, and
unknown paths. No Firebase login or network access to a live backend is used.

## Future private live setup

Live setup is intentionally deferred until the sign-in and remote-backup slice. At that point the
portfolio owner must separately:

1. Create one dedicated project on Spark with no linked billing account.
2. Enable only Google Authentication and the default Firestore Standard database in `us-west1`.
3. Deploy the checked-in `firestore.rules` and `firestore.indexes.json` after emulator tests pass.
4. Keep the real project identifiers and `google-services.json` outside this public repository.
5. Use live access only for explicit manual Seeker evidence; CI must continue using the emulator.

Service-account keys, Firebase CLI tokens, and private App Check debug tokens must never be added
to the app, this directory, or CI.
