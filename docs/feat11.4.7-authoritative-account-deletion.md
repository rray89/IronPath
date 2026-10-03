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

The two confirmation layers name the current account and full deletion scope: the
IronPath account, all backups, local training, active workout, and restore/undo state.
The Google account itself is unaffected. There is no keep-data option once deletion
starts. BOSS explicitly approved v2 source and isolated tests on October 3, 2026,
including recovery receipt storage, cancellation before activation, and authpreview
system-backup/device-transfer exclusion. Deployment, billing and real data operations
remain outside this authorization.

The client validates an HTTPS origin and a v2 capability response with the configured
Firebase project and immutable service instance. It stores a nonblocking DRAFT with
UUID v4, a random 256-bit receipt secret, and the captured owner, profile generation,
installation and service binding. Fresh Google reauthentication must verify the same
Firebase UID. Reservation is non-destructive: it creates no tombstone, job or worker.
Only a validated server acknowledgment can promote that exact draft to PREPARED in
Room and close local write admission. A lost acknowledgment or crash before promotion
leaves local training available; an orphan reservation cannot delete data.

A receipt grants only status and cancellation of an unactivated reservation. It cannot
activate deletion. Activation follows an explicit confirmation or Retry with fresh
same-UID Google proof, after the journal commits and context is rechecked. The client
never stores or automatically replays an ID token; cold startup only reads status and
never opens a Google chooser. Authentication is checked at server request admission,
including expiry, five-minute auth_time, project, provider and revocation. Auth changes
after admission cannot atomically revoke an in-flight Firestore transaction: it may
still commit. Cancellation and activation transact against the same UID fence, so the
server decides which happened first.

If status is RESERVED, the user may explicitly ask to cancel. Only the atomic server
result CANCELLED_NO_DELETE retires the matching local journal and preserves every
training, active, ownership, baseline and undo row. Local account/installation
stabilization must finish before normal admission reopens. This result means this
reservation did not start deletion; another device may start deletion later. A cancelled
receipt remains terminal even after that later deletion and can never authorize cleanup.
If another device already activated, cancellation returns PENDING or COMPLETE instead.
Unknown, malformed, stale or mismatched receipts leave the barrier closed.

Activation atomically creates the permanent UID tombstone and canonical deletion job.
Firestore rules deny client access to that UID, including stale uploads from another
installation. Clients cannot read or mutate receipt, quota, job or tombstone records.
The independent worker recursively purges the entire users/{uid} subtree, including
orphan chunks, interrupted snapshots and malformed descendants. It independently
verifies absence, deletes Firebase Auth, then durably publishes COMPLETE. Restart or
loss of the HTTP client does not stop an already activated job.

An acknowledged RESERVED receipt observes another device's PENDING or COMPLETE without
Auth or a retained token. This closes the v1 unknown-operation lock when the other
device removes Auth before this device activates. Status uses one serializable snapshot
of receipt, fence and canonical job. Terminal cancellation is checked before later
canonical work. No receipt mapping TTL is used. Synthetic creation quotas reject new
reservations before ACK and never block existing receipt status/cancel/activation or
already activated workers; live retention, cost and operational ownership still need
separate approval.

The Room v8→v9 migration adds the DRAFT table and nullable v2 receipt, subject, version,
state and installation fields. It preserves old v1 journals with null authority. A
service-bound v1 journal cannot auto-upgrade, silently reopen writes or authorize
cleanup; it requires explicit integrity recovery help. Null-bound debug/demo journals
retain their existing contract. The release deletion manager remains unavailable.

Only matching COMPLETE permits atomic cleanup of the captured local graph, with exact
journal/owner/profile/installation checks and monotonic receipt versions. Session state
is independent: a foreign Firebase session is preserved and does not prevent cleanup
of the confirmed old graph. Scope replacement or corruption fails closed. Marker and
local cleanup failures remain retryable. Pending state guards the full app and survives
recreation; scroll, safe insets and Back handling retain the recovery actions.

DRAFT, receipt secret and journal share the same Room database. The authpreview variant
excludes the entire database and its WAL/SHM sidecars from Android cloud backup and
device transfer, including legacy rules and device-protected domains. API31+ transfer
allows only non-secret onboarding preferences; its include allowlist disables all
other default domains, including database sidecars ([Android backup rules](https://developer.android.com/identity/data/autobackup#IncludeExclude)).
Debug and release policy is unchanged.
Training transfer remains available through the application's explicit cloud
backup/restore workflow. Training exports and logs must never include receipt secrets.
Uninstall/data loss, permanent service loss, and legacy transferred journals require
separate recovery assistance; they are not proof of deletion completion.

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
The actual Kotlin transport scenarios pass through the application authpreview service
wrapper, including receipt-only recovery and cancellation without authentication.
Room and Compose CI tests cover persistence, atomic cleanup, migration, unavailable and
pending UI, confirmation actions and 200% font scale. Ordinary startup also covers
interrupted sign-out with same, absent and foreign sessions, unreadable-session recovery,
and explicit deletion actions returning Idle without authorizing admission. These are automated evidence and do
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
   for a permanently failing job. Keep permanent tombstones and all acknowledged receipt
   identity mappings without TTL. Approve retention/privacy, new-reservation abuse limits
   and operational cost; limits must never strand existing recovery or active jobs.
4. TLS without redirects and sanitized access/error logs. Operation UUIDs are recovery
   identifiers; the separate secret is the recovery capability. Proxy/server logs must not
   retain deletion URL paths, receipt headers, tokens or payloads.
5. Add optional `deletionServiceEndpoint` (an HTTPS origin with no path/query/credentials)
   to the owner's private `ironpathAuthPreviewConfig` file. Its capability response must
   report `ironpath-account-deletion-v2`, the configured project, an immutable valid
   serviceInstanceId, authoritative cleanup and resumable jobs. The instance and original
   recovery route must remain pinned for all acknowledged receipts. Build a new private candidate; do not overwrite frozen APK evidence.
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
5. Reopen and reauthenticate the matching Google account. Interrupt connectivity at
   reservation/activation or restart after PREPARED. DRAFT alone must leave training
   available; acknowledged pending state must block training/backup/sync/restore/sign-out.
   Cold startup reads status only. If still RESERVED, Retry requires fresh same-UID proof;
   Cancel asks the server for CANCELLED_NO_DELETE and preserves every local row. Confirm
   activation wins are reported as pending/completed rather than as cancelled.
6. With two installations, reserve on one and activate/complete on the other. Confirm
   the first can verify completion without Auth, and a previously cancelled receipt
   never turns into COMPLETE. A different current session must remain signed in.
7. Verify server cleanup and identity deletion complete, then local data clears and Home
   reopens with usable new Records navigation. Sign in with the same Google identity and
   verify a fresh IronPath account with no old backup or training data appears.
8. Verify another installation's stale upload cannot recreate the old account's backups,
   and a different local owner cannot enter this destructive flow.
