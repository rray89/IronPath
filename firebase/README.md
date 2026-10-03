# Firebase backup test foundation

This directory contains local Auth/Firestore emulator configurations, Security Rules, and the
authoritative account-deletion service core. Its executable entrypoint permits only a synthetic
`demo-*` project and loopback emulators. Production startup is refused. Tests need no Google
account or service credential, and this directory stores no live identifiers or credentials. The
authpreview manual-backup transport is documented in [feat11.4.4](../docs/feat11.4.4-firestore-manual-backup.md).

## Local and CI verification

Requirements: Node 22, JDK 21, and the repository Gradle wrapper.

```bash
npm ci --prefix firebase
./gradlew firebaseRulesTest
npm run test:transport --prefix firebase
npm run test:deletion --prefix firebase
npm run test:deletion-transport --prefix firebase
```

The Gradle task starts the Firestore emulator with Firebase's emulator-only `demo-ironpath` project
ID,
runs the pinned rules test suite, and stops the emulator. Tests cover owner CRUD/list access,
unauthenticated and cross-account denial, schema bounds, state transitions, orphan chunks, and
unknown paths. No Firebase login or network access to a live backend is used.

## Future private live setup

For the isolated authpreview slice, the portfolio owner controls private live setup:

1. Create one dedicated project on Spark with no linked billing account.
2. Enable only Google Authentication and the default Firestore Standard database in `us-west1`.
3. Deploy the checked-in `firestore.rules` and `firestore.indexes.json` after emulator tests pass.
4. Keep the real project identifiers and `google-services.json` outside this public repository.
5. Use live access only for explicit manual Seeker evidence; CI must continue using the emulator.

Service-account keys, Firebase CLI tokens, and private App Check debug tokens must never be added
to the app, this directory, or CI.

## Authoritative deletion protocol (feat11.4.7)

To initiate deletion, `service/deletion-service.mjs` requires a Firebase Admin-verified, non-revoked ID token whose
`firebase.sign_in_provider` is `google.com` and whose `auth_time` is no more than 300 seconds old
(and not in the future). Refreshing an old session's token does not satisfy this requirement.
The UID comes exclusively from that verified token. Requests may not supply a UID.

| Request | Result |
| --- | --- |
| `GET /v1/capabilities` | `200 {"protocol":"ironpath-account-deletion-v1","projectId":"configured-project","authoritative":true,"resumable":true}` |
| `POST /v1/deletions`, `Authorization: Bearer <Firebase ID token>`, exact body `{"operationId":"lowercase-UUID-v4"}` | `202 {"operationId":"...","state":"PENDING"}` after the durable job and permanent fence commit atomically; `200` for a previously completed matching operation |
| `POST /v1/deletions/<UUID>/resume`, empty body or `{}` | `202 PENDING`, `200 COMPLETE`, or `404 NOT_FOUND`; no account identity or backup data is returned |

The client persists a cryptographically random UUID v4 before starting deletion. This UUID is
also its recovery capability after Auth removal. It must stay private (never log URL paths).
The resume endpoint cannot create a job, change its UID, or initiate another deletion. A caller
without the capability cannot enumerate receipts. Responses use `Cache-Control: no-store`.
Clients must match the advertised project to their configured Firebase project, require HTTPS
outside loopback tests, disallow redirects, and reject unexpected operation IDs/states.

Errors are sanitized JSON: `{"error":"INVALID_REQUEST"}` (400),
`REAUTHENTICATION_REQUIRED` (401), `NOT_FOUND` (404), `OPERATION_CONFLICT` (409), or
`UNAVAILABLE` (503). A service/verification outage is not reported as an authentication failure.
Invalid credentials never initiate a deletion or create a fence. Reusing an operation ID for
another verified UID fails without rebinding either operation. A second device that confirms
deletion of the same UID receives a separate durable alias under its own operation ID. That
receipt follows the original canonical job; it never exposes the first device's recovery
capability or runs a second destructive cleanup.

There is one narrow completion-proof recovery case: when recent token verification fails only
because Auth is already missing or the token was revoked, the service verifies its signature,
audience, issuer, expiry, Google provider and five-minute `auth_time` again. Such a token may
only attach a receipt to an already COMPLETE canonical deletion for that exact UID. It cannot
create a fence, initiate or attach to pending deletion, or mutate any current unrelated UID.
Unavailable/disabled/invalid/expired credentials do not enter this recovery path.

The transaction creates `accountDeletionTombstones/{uid}` and
`accountDeletionJobs/{operationId}`. Both are admin-only. The job retains UID, timestamps,
coarse state and bounded retry bookkeeping; it stores no email, token, or workout payload.
Alias jobs additionally retain only the canonical operation ID. Workers resolve aliases but
never perform data/Auth deletion for them.
The tombstone and completion receipt are permanent: never enable TTL or cleanup for them.

All owner rules consult the permanent fence, including reads, upload claims, chunks,
completion, and deletes. After the fence commits, old authenticated clients cannot recreate
the subtree. Admin recursive deletion discovers all descendants under `users/{uid}`, including
malformed manifests, arbitrary nested collections, unregistered chunks and missing parents.
After purge, independent root and subcollection enumeration must prove emptiness before Auth
deletion. Missing Auth on retry is successful. Only then is the receipt marked COMPLETE.
The same Google identity signs in later with a new Firebase UID; the old UID remains fenced
even if a privileged administrator manually recreates it.

The worker scans durable pending jobs at startup and every five seconds without a client
request. Each sweep processes at most ten jobs, uses cursor pagination, and caps failure
backoff at five minutes. Deletes use a BulkWriter capped at 100 operations/second. Failures
retain PENDING; startup resumes partial purge and interruption after Auth removal. Concurrent
workers are safe because the fence is permanent and purge/Auth deletion are idempotent.
No worker exception text or private operation path is logged.

Run a temporary local service inside the emulator lifecycle, then stop it when testing ends:

```bash
cd firebase
IRONPATH_DELETION_MODE=emulator npx firebase emulators:exec \
  --only auth,firestore --project demo-ironpath-deletion \
  --config firebase.deletion.json 'npm run start:deletion'
```

The emulator CLI supplies the project and emulator-host environment variables. `test:deletion-transport`
starts the service on an ephemeral port, passes `IRONPATH_DELETION_ENDPOINT` to the real Kotlin
HTTP transport test, then stops the service and emulators. Run these emulator commands serially.
The service
binds `127.0.0.1:8787` (override `PORT` if needed). Dedicated test ports are Auth 9197,
Firestore 8187, hub 4487 and logging 4587. The tests spawn and stop their own service process,
use only synthetic identities, and verify fresh-process autonomous completion. Auth emulator
tokens are deliberately unsigned; tests exercise Admin verification and provider/time/revocation
policy but cannot replace production signature-verification or live Google reauthentication
evidence. Firebase Admin performs production signature verification when used in an approved
production runtime without emulator variables.

The Admin SDK's emulator mode additionally forces an Auth user/revocation lookup even when
`checkRevoked=false`. The completion-only signed-token fallback therefore uses a test-only
verification result captured before Auth deletion; real token verification, Firestore and Auth
deletion cover the other paths. No production signature-verifier bypass exists. This specific
post-Auth alias case still requires approved production verification before activation.

A second device can still be interrupted after saving a local operation ID but before the
service accepts it, after another device has already removed Auth. If that device also loses
its recent signed token, its unknown operation cannot be automatically authenticated or bound
to the completed receipt. It must remain pending until a separately approved authoritative
receipt-recovery path exists. This slice supplies no blind local erase or new-account sign-in
fallback, and stores no provider subject to bypass that proof gap. Already accepted canonical
or alias receipts remain recoverable without a token and canonical cleanup remains autonomous.

### Deployment remains unavailable

This slice does not deploy or enable a live deletion endpoint. `main.mjs` rejects every
production configuration, even if ADC or a live project is present. Future activation requires
separate approval and implementation of all of these together:

- a continuously supervised host that restarts the worker, with durable Firestore job storage
  and health/alerting for pending jobs; uninstalling the Android app must not stop that host
- a verified deployment of these exact fence rules before accepting any deletion, and a
  policy excluding every other privileged writer from fenced UID subtrees
- HTTPS termination, bounded ingress/rate limiting, and disabled access logs for recovery
  capability paths; no token, UID, email, payload or capability in logs
- a dedicated least-privilege workload identity with the reviewed Firestore traversal/delete
  and Firebase Auth user-verification/delete permissions, supplied by the host; no downloaded
  service-account key in the repository, Android app or CI
- a safe production configuration path with fixed expected Firebase project, no emulator
  variables, and end-to-end live verification of Google reauthentication and identity rebirth
- the existing V5 zero-cost/billing/product decision and any required privacy/request-path work

Official references: [Admin token verification](https://firebase.google.com/docs/auth/admin/verify-id-tokens),
[Auth emulator behavior](https://firebase.google.com/docs/emulator-suite/connect_auth), and
[Firestore recursive deletion](https://cloud.google.com/nodejs/docs/reference/firestore/latest/firestore/firestore#recursiveDelete).
