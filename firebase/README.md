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

## Authoritative deletion protocol v2 (feat11.4.7)

The client persists a nonblocking DRAFT containing a UUID v4, a random 256-bit receipt
secret encoded as canonical unpadded base64url, and the captured account/profile/installation.
It pins the capability response to its configured Firebase project and immutable service
instance. Only a valid durable reservation acknowledgment promotes that exact draft to the
blocking Room journal. A lost reservation response leaves training available; an orphan
reservation creates no job, fence or worker activity.

| Request | Authority and result |
| --- | --- |
| `GET /v2/capabilities` | `200` with `protocol: ironpath-account-deletion-v2`, `projectId`, immutable `serviceInstanceId`, `authoritative: true`, `resumable: true` |
| `POST /v2/reservations`, exact body `{"operationId":"lowercase-UUID-v4"}` | Fresh Google Firebase ID token in `Authorization: Bearer ...` and secret in `Deletion-Receipt`; non-destructive `RESERVED`, or an existing canonical `PENDING`/`COMPLETE` |
| `POST /v2/operations/<UUID>/status`, body `{}` | `Deletion-Receipt` only; reads the existing reservation's status without Auth, activation or worker scheduling |
| `POST /v2/operations/<UUID>/activate`, body `{}` | The same secret and a fresh same-UID Google Firebase ID token; atomically activates the canonical job/fence or observes its current state |
| `POST /v2/operations/<UUID>/cancel-unactivated`, body `{}` | `Deletion-Receipt` only; atomically returns `CANCELLED_NO_DELETE` if activation has not won, otherwise canonical `PENDING`/`COMPLETE` |

Successful operation responses include the exact protocol, project, instance, operation ID,
UID-bound `subjectBinding`, state and version. `RESERVED` is version 1; `PENDING` and
`CANCELLED_NO_DELETE` are version 2; `COMPLETE` is version 3. HTTP status is 202 for PENDING
and 200 for the other states. Responses use `Cache-Control: no-store`. The Android client
requires HTTPS outside isolated loopback tests, rejects redirects and binding/identity/version
mismatches, and never exports or logs the receipt secret. The service stores only its SHA-256
hash. The UUID alone cannot read, cancel or activate a v2 receipt.

Reservation and activation require Firebase Admin verification with revocation checking,
the configured audience/issuer, `google.com` provider and `auth_time` no more than 300 seconds
old or in the future. UID comes exclusively from the verified token, never the request body.
Refreshing an old session token is insufficient. Proof is admitted once before bounded
Firestore retries; a later Auth change cannot atomically cancel an already admitted request.
A restarted request requires new explicit fresh proof. No ID token is persisted or automatically
replayed, and there is no revoked/deleted-token completion fallback.

Status and cancellation use one serializable snapshot of receipt, UID fence and canonical
job. A cancelled receipt stays terminal even if another device later deletes the UID; it never
grants cleanup authority. A pre-reserved device can observe another device's PENDING or COMPLETE
after Firebase Auth disappears, without a token. Unknown/mismatched receipts fail closed.
Reusing an operation with a different secret or UID cannot rebind it.

Activation creates `accountDeletionTombstones/{uid}` and a canonical
`accountDeletionJobs/{operationId}` atomically. Clients cannot access these or
`accountDeletionReceipts`/`accountDeletionReservationQuotas`. Every owner rule consults the
permanent UID fence, including reads, upload claims, chunks, completion and deletes. Admin
recursive deletion discovers the whole `users/{uid}` subtree, including malformed descendants,
orphan chunks and missing parents. Independent root/subcollection enumeration proves absence
before Firebase Auth deletion, then the canonical job durably becomes COMPLETE. Existing
activated work continues independently of the client, and only matching COMPLETE authorizes
scoped local cleanup. A foreign current session is preserved.

Local terminal CANCELLED/COMPLETE authority remains blocking until account, profile and
installation verification succeeds. An exact Room journal acknowledgment then retires only
that journal before normal admission opens. Interrupted acknowledgment stays recoverable;
provider/local observations cannot bypass it. Historical completed/cancelled work therefore
cannot trap a later ordinary sign-out recovery. This local acknowledgment never deletes a
server receipt, tombstone or job and changes no training/session/undo rows.

The worker scans durable pending jobs at startup and every five seconds, processes at most
ten per sweep with cursor pagination, and caps failure backoff at five minutes. A BulkWriter
is capped at 100 operations/second. Partial purge and interruption after Auth removal remain
retryable. Permanent fencing and idempotent purge/Auth deletion make concurrent workers safe.
Logs contain no exception text or private operation paths.

There is no TTL for acknowledged receipt mappings or UID tombstones. Synthetic default quotas
allow at most 100 new reservations per UID and 20 within a one-hour window. Limits reject new
creation before ACK and never block existing receipt recovery, cancellation, activation or
active workers. Live retention, abuse limits and costs need separate approval. Sanitized errors
include INVALID_REQUEST (400), REAUTHENTICATION_REQUIRED/RECEIPT_REQUIRED (401), NOT_FOUND (404),
OPERATION_CONFLICT/BINDING_MISMATCH (409), RESERVATION_LIMIT (429) and UNAVAILABLE (503).

New v1 capabilities/start are unavailable. The legacy resume route only continues already
accepted legacy work; an unknown v1 operation cannot acquire v2 authority. Legacy service-bound
Android journals without an acknowledged v2 receipt require explicit recovery help. There is
no blind erase, new-account sign-in or cached-proof fallback.

Run a temporary local service within the software emulator lifecycle, then stop it when done:

```bash
cd firebase
IRONPATH_DELETION_MODE=emulator \
IRONPATH_DELETION_SERVICE_INSTANCE_ID=ironpath-deletion-emulator-v2 \
  npx firebase emulators:exec \
  --only auth,firestore --project demo-ironpath-deletion \
  --config firebase.deletion.json 'npm run start:deletion'
```

The CLI supplies synthetic project/emulator hosts. The runner refuses non-emulator mode,
non-demo projects and non-loopback hosts. Its default address is `127.0.0.1:8787` (override
PORT). Dedicated ports are Auth 9197, Firestore 8187, hub 4487 and logging 4587. Run emulator
commands serially. `test:deletion-transport` starts a service on an ephemeral port, passes
IRONPATH_DELETION_ENDPOINT to six actual Kotlin application-wrapper flows, then stops service
and emulators. The deletion suite covers actual process restart, Auth-independent reservation
recovery/cancel, cancellation/activation ordering, delayed replies, quotas, purge and rules.
Auth emulator tokens are unsigned; these tests exercise Admin emulator/provider/time/revocation
behavior and do not establish production signature or live Google chooser acceptance.

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
