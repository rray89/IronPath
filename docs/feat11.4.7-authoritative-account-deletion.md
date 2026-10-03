# feat11.4.7 — Authoritative account deletion candidate

Status: implementation candidate, stacked on the frozen feat11.4.6 restore candidate.
Technical verification and independent review belong to the PR. Product acceptance,
production deployment and real-account deletion have not been performed.

BOSS authorized continued implementation on October 2, 2026 and moved the product
checkpoint to the combined candidate. This slice implements the client/service contract
and an authoritative Firebase Admin service using isolated synthetic software emulators.
It does not waive V5's zero-cost rule or authorize Blaze, Cloud Run/Functions deployment,
service-account provisioning, IAM changes or live Firebase operations.

## Deletion contract

The two confirmation layers identify the current account and state that deletion removes
the IronPath account, every backup, all local training records, active workouts and the
restore/undo state. There is no keep-data option. The Google account itself is unaffected.
The final confirmation requests a fresh Google credential and reauthenticates the existing
Firebase user; it never signs in a different account to perform deletion. Cancelling or
failing reauthentication before the durable deletion journal leaves data unchanged.

The client requires a configured HTTPS service origin, a matching Firebase project and
capability response before preparing deletion. A Room v7→v8 migration adds a nullable
service-binding fingerprint to the existing journal. The fingerprint binds retries to the
same project and endpoint. Existing demo journals retain a null binding and keep their
prior behavior. Production release still binds the unavailable deletion manager.

After the journal commits PREPARED, training/profile writes remain blocked. There is no
cancel action. The journal's cryptographically random UUID v4 is the operation identifier
and recovery capability; no Google or Firebase token is persisted in it. A lost submission
receipt is resolved through the same operation. Startup never opens a Google chooser.
An unknown server operation requires an explicit Retry and another recent reauthentication
before submission; changing service configuration cannot silently redirect the operation.
The current client does not retain the initial credential after the submission call returns,
so a warm Retry cannot reuse that proof even while it would still be recent.

The service verifies a current, nonrevoked Firebase ID token, `google.com` provider and
`auth_time` no older than five minutes. The UID is derived exclusively from the verified
token. It atomically creates a permanent UID tombstone and durable deletion job. Checked-in
Firestore rules deny all client access to a tombstoned UID, including attempts by another
installation to publish an upload. Clients cannot read, create or remove jobs/tombstones.

A worker resumes pending jobs independently of HTTP requests, including after restart or
client uninstall. It recursively removes the entire `users/{uid}` subtree without trusting
manifest shape or the backup registry. This includes complete and interrupted snapshots,
chunks below missing manifests, malformed metadata and unexpected descendants. It verifies
the root and descendants are absent before deleting the Firebase Auth user. Only then does
it publish COMPLETE. A crash after Auth deletion but before COMPLETE safely repeats cleanup.
Permanent tombstones fence stale tokens; a later Google sign-in receives a fresh Firebase
UID and cannot recover the retired UID's backups.

Concurrent same-account confirmations receive separate capabilities linked to the same
canonical server job. Only recently verified same-UID credentials may attach another
receipt; an already completed job also permits signature-verified recent proof of the
retired UID, without starting destructive work. Firebase Admin's emulator forces an
existence/revocation check even for `verifyIdToken(false)`, so this completion-only branch
has a test-only signature-result seam and still needs approved production verification.

There is one explicit recovery limit in both the current warm process and after a cold
restart: if a second operation was never accepted and another device already deleted Auth,
the client has no retained recent credential with which to authenticate the unknown
capability, and fresh reauthentication of that retired Firebase user cannot succeed.
The completion-only server alias path therefore does not by itself solve this client gap.
Deletion stays pending with local data locked. A future deployment must provide an
authoritative receipt-recovery procedure; the client never guesses success, blindly erases
local data or signs up a new UID as a substitute. Already accepted jobs and receipts do not
have this limitation, because their existing operation capability remains resumable.
This remains an unresolved correctness gate for reliable multi-device deletion. Documenting
it does not resolve the review finding or establish product acceptance. A proposed bounded,
memory-only credential retry has not been applied; even that proposal would still require
an authoritative recovery design for process death or expired proof.

The client accepts only an exact operation receipt from its configured service. COMPLETE
advances the local journal through remote verification, then the shared Room transaction
clears the full training graph, active state, ownership, baseline and undo and advances the
profile generation. It rechecks the journal, local owner/profile and current session before
cleanup. A different session is never signed out. Marker or local cleanup failures remain
retryable without repeating an already confirmed remote deletion.

## Source and emulator verification

Use JDK 21 and bounded Gradle resources; no local Android/qemu emulator or Seeker action is
part of this executor's verification. Device tests run in CI.

```bash
./gradlew --no-daemon --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2' \
  spotlessCheck testDebugUnitTest testAuthpreviewUnitTest \
  createDebugUnitTestCoverageReport verifyCoreCoverage -PenableCoverage \
  :app:lintDebug :app:lintBenchmarkRelease :app:lintAuthpreview \
  assembleDebug assembleRelease assembleAuthpreview assembleDebugAndroidTest
npm ci --ignore-scripts --prefix firebase
npm test --prefix firebase
npm run test:transport --prefix firebase
npm run test:deletion --prefix firebase
npm run test:deletion-transport --prefix firebase
```

The runnable service entrypoint refuses non-emulator mode. The synthetic deletion suite
uses `demo-ironpath-deletion`, Auth port 9197 and Firestore port 8187. No live credentials or
real accounts are used. Tests cover cancellation, reauthentication, wrong owner/project,
interruption before/after acceptance, lost receipts, duplicate operations, orphan cleanup,
write fencing, restart, revoked/stale/non-Google identity and fresh identity after deletion.
Room and Compose CI tests cover persistence, atomic cleanup, migration, unavailable and
pending UI, confirmation actions and 200% font scale. These are automated evidence and do
not claim manual Google chooser or live deployment acceptance.

The source-build APK is `app/build/outputs/apk/authpreview/app-authpreview.apk`, package
`com.example.ironpath.authpreview`. Without private config, Google and cloud actions are
unavailable. With the previous identity/backup config alone, backup/sync/restore retain
their behavior and account deletion remains unavailable.

## Exact activation prerequisite packet

No deployment command should be run under this feature's authorization. The remaining
integration prerequisite is an approved durable trusted service host with HTTPS, a worker
supervisor, least-privilege Firestore/Auth administration and the reviewed tombstone rules
installed in the same project before enabling the endpoint. V5 currently prohibits a
custom live service/paid backend; the project owner must first resolve that product and
hosting constraint. Do not add a client-only deletion fallback.

A future activation needs:

1. Explicit hosting/cost approval and a project-scoped service identity. No Admin credential
   may be placed in the APK, repository or ordinary CI. Hosting and service identity are
   not provisioned by this candidate.
2. Verified publication of `firebase/firestore.rules`, including the tombstone guard, plus
   adversarial live-isolated deployment proof before accepting deletion requests. Merely
   pointing at a Firebase project is not proof that its rules are safe.
3. A reviewed production entrypoint replacing the deliberately emulator-only runner,
   durable worker restart guarantees, monitoring/retry handling and a recovery procedure
   for a permanently failing job. Keep the tombstone and receipt until an independently
   reviewed retention policy can preserve replay safety.
4. TLS without redirects and sanitized access/error logs. Operation UUIDs are recovery
   capabilities; proxy/server logs must not retain deletion URL paths, tokens or payloads.
5. Add optional `deletionServiceEndpoint` (an HTTPS origin with no path/query/credentials)
   to the owner's private `ironpathAuthPreviewConfig` file. Its capability response must
   report `ironpath-account-deletion-v1`, the configured project, authoritative cleanup and
   resumable jobs. Build a new private candidate; do not overwrite frozen APK evidence.
6. Run the combined synthetic-account QA below and record the exact source/APK hashes.
   An external deletion-request path and public privacy package remain separate scope.

## Combined QA walkthrough

The root owner controls configured builds, live resources and review-device operations.
Do not clear/uninstall a device app to follow this guide.

1. Complete the existing backup, manual-sync, whole-restore and one-undo walkthrough on
   synthetic data. Confirm ordinary sign-out still offers Keep/Remove and preserves backup.
2. With deletion service absent, confirm account deletion is explicitly unavailable;
   signing in must never trigger upload or deletion.
3. After separately approved service activation, open Delete account, verify the identity
   and full scope, and cancel each confirmation. Verify all local data and backups remain.
4. Confirm both layers, then cancel the Google chooser. Verify the account, local graph,
   active workout, undo and remote backup are unchanged.
5. Reopen and reauthenticate the matching Google account. Interrupt connectivity or restart
   after submission. Verify pending UI blocks training and backup/sync/restore/sign-out,
   and Retry resumes the same operation without claiming success prematurely.
6. Verify server cleanup and identity deletion complete, then local data clears and Home
   reopens with usable new Records navigation. Sign in with the same Google identity and
   verify a fresh IronPath account with no old backup or training data appears.
7. Verify another installation's stale upload cannot recreate the old account's backups,
   and a different local owner cannot enter this destructive flow.
