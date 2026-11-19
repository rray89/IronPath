# feat11.4.4 Firestore manual backup preview

This first usable slice adds explicit manual backup and metadata-only status queries to the isolated
`com.example.ironpath.authpreview` package. Debug keeps the deterministic demo; release remains inert.
Google sign-in, startup, resume and connectivity recovery never upload training data. Opening the
account screen or pressing Refresh explicitly queries the latest COMPLETE metadata. The local Room
profile remains authoritative. Sync, restore, undo and remote/account deletion stay unavailable.

## Confirmation and ownership

Back Up Now reads current cloud metadata, captures a consistent local included graph, and shows
entity counts. Cancel performs no cloud write or ownership change. Confirm rechecks the session,
installation/profile generation, local revision and snapshot, remote generation and significant
count reduction acknowledgement. An empty unclaimed profile without cloud data associates without
creating an empty backup.

For a nonempty profile, the final explicit confirmation first associates its owner in a conditional
Room transaction, then uploads. This durable association remains if upload fails; it is the user's
confirmed intent, not proof of upload success. A profile reset rotates installation identity and
profile generation. Foreign owners and unclaimed profiles with an existing complete cloud backup
cannot overwrite it. A newer complete backup from another installation requires review and cannot
be replaced in this slice.

An interruption, HTTP timeout, coroutine cancellation or lost response can occur after a remote
commit. The app does not claim that no upload occurred. Refresh discovers the durable COMPLETE
pointer; a fresh explicit preview can acknowledge an owned snapshot from this installation without
reuploading identical data. Edits after an uncertain receipt require a new explicit review. Matching
UPLOADING chunks can resume only for the same installation, digest, captured revision and observed
generation. Different young uploads are blocked; rules enforce the 24-hour reclamation lease.
Local-only context refresh preserves the uncertain-write state until explicit cloud inspection. Confirmed unchanged-data retries repair bounded retention without adding a snapshot or generation. Token-fetch network, auth and quota failures retain sanitized typed reasons. There is no application retry on timeout and no offline write queue.

## Transport and protocol

The authpreview-only REST transport obtains official Firebase ID tokens from the same named
Firebase Auth preview app. Firebase ID-token requests are evaluated by Firestore Security Rules;
there is no service account, Firebase CLI token or alternate authentication path. Credentials never
appear in URLs, logs, source or public artifacts.

Official contracts: [Firebase REST authentication](https://firebase.google.com/docs/firestore/use-rest-api),
[mobile/web optimistic transactions](https://firebase.google.com/docs/firestore/transaction-data-contention),
[batchGet and readTime](https://firebase.google.com/docs/firestore/reference/rest/v1/projects.databases.documents/batchGet),
[atomic commit](https://firebase.google.com/docs/firestore/reference/rest/v1/projects.databases.documents/commit),
[document preconditions](https://firebase.google.com/docs/firestore/reference/rest/v1/Precondition),
and [write transforms](https://firebase.google.com/docs/firestore/reference/rest/v1/Write).

The client uses optimistic concurrency without `beginTransaction`, `newTransaction`, transaction IDs
or rollback. The first POST batchGet is strongly consistent; its server `readTime` pins every later
read in that callback to the same snapshot, including missing documents. Read-only inspection ends
without calling commit. All reads precede mutations; each mutation target must have been read and
can be mutated once. One atomic commit attaches `currentDocument.updateTime` to existing targets or
`exists: false` to absent targets. Every read dependency that is not mutated receives a `verify`
operation, so generation/upload-slot reads also guard chunk writes and cleanup. This is the wire
operation used by the official [Firebase JS transaction implementation](https://github.com/firebase/firebase-js-sdk/blob/main/packages/firestore/src/core/transaction.ts)
and documented in the public [Firebase Android client protobuf](https://github.com/firebase/firebase-android-sdk/blob/master/firebase-firestore/src/proto/google/firestore/v1/write.proto)
(it is not listed on the general REST Write reference page). These operations are exercised against
the checked-in rules by the actual Kotlin REST emulator suite. No rules/schema change is required.

Commits still compare the observed generation, preserve chunk immutability and use REQUEST_TIME
server transforms. A structured HTTP 409 ABORTED permits at most three attempts inside the active
manual operation. A failed version precondition or ALREADY_EXISTS enters conflict review without
retry. Permission, quota, transport failures and unknown commit receipts never trigger replay.
Fixed-length HTTP streaming prevents buffered POST replay after a lost response. Callback failure
or cancellation before commit requires no lock cleanup; no server transaction was opened. Timeout
or cancellation after attempting commit still does not prove that no remote write occurred.

The [bounded protocol](../firebase/test/manual-backup.protocol.mjs) and checked-in rules preserve at most two
complete backups after successful retention, one upload slot, six immutable chunks per backup,
and a registry of at most four IDs. The snapshot is read back and domain validated before COMPLETE.
Old complete backups remain visible while an upload is interrupted. Cleanup removes known chunks
before manifests and registry entries; interrupted cleanup remains discoverable. Status inspection
reads only user metadata and its current manifest, never chunk payloads.

## Verification and private setup

Use JDK 21 and the Android SDK. Public commands need no private Firebase configuration:

```bash
./gradlew testDebugUnitTest testAuthpreviewUnitTest
npm ci --ignore-scripts --prefix firebase
./gradlew firebaseRulesTest
npm run test:transport --prefix firebase
./gradlew spotlessCheck :app:lintDebug :app:lintBenchmarkRelease :app:lintAuthpreview assembleDebug assembleRelease assembleAuthpreview
```

Actual Kotlin REST emulator tests use unsigned emulator-only identity tokens for `demo-ironpath`,
with owner, cross-UID and unauthenticated requests evaluated by the real checked-in rules. They
cover completion, generation conflicts, immutable resume, uncertain receipts and bounded retention.
Additional actual-transport regressions deny explicit BeginTransaction at an isolated HTTP proxy
while forwarding normal owner requests to the emulator rules. They exercise the real coordinator's
explicit refresh, preview/cancel and confirm chain, independent commit denial, lost HTTP COMPLETE
receipts and coordinator recreation. Direct rule-enforced tests also check historical readTime
consistency, competing first creation, and an unchanged-generation upload claim invalidating a
read-only dependency during cleanup. JVM tests cover coordinator and HTTP behavior; API 29 CI covers the real Room association transaction
and shared credential-free cloud presentation at 200% font scale. The live transport and Firebase
configuration remain absent from debug/release source sets.

The portfolio owner supplies private configuration outside this public repository as described in
[the identity preview](feat11.4.3-google-identity-auth-preview.md). Use Spark, no billing account,
Firestore `(default)`, Standard, regional `us-west1`. Publish the tested rules and indexes only through
the owner's controlled setup. Chunk payload indexing is exempted to avoid unnecessary index cost.
No Cloud Functions, Storage or paid service is required. Public CI remains emulator-only.

## November 18 transport incident and downstream boundary

BOSS's supplied live evidence under the same Seeker sign-in session/project: an ordinary owner
metadata GET was allowed, then explicit REST BeginTransaction returned HTTP 403 during Refresh,
before upload. This does not establish that every transaction fails or that the UID is empty.
The isolated regression failed on the old transport for that endpoint denial before this repair.
The repair removes that endpoint dependency from reads and writes; successful reads alone still do
not establish live write permission. A separate simulated commit denial must remain a typed error
and must never produce Up to date.

This slice keeps manual sync, restore, undo and deletion unavailable. The downstream
`codex/feat11.4.5-cloud-sync` and `codex/feat11.4.6-cloud-restore` work must integrate this transport
and revalidate their own operations, including the read-before-write/one-mutation-per-path contract.
Their code and PRs are not changed here; their live acceptance is not implied by this repair.

## First Usable Slice acceptance — pending

This repair worker has not operated a device, read private configuration or written to live Firebase. Do not
merge until BOSS accepts the configured candidate on the review device:

1. With one local record and Google signed in, open Account & Backup. Verify cloud status Refresh
   does not upload and local training data remains unchanged.
2. Open Back Up Now. Verify included counts and exclusion text; cancel, then Refresh and verify no
   complete backup was created.
3. Open a fresh preview and confirm. Verify manual cloud completion, timestamp/counts, Up to date,
   and the owner-scoped Firestore COMPLETE pointer. Restart and explicitly Refresh to rediscover it.
4. Add a local record; verify Local changes. Review/confirm explicitly, then verify generation
   advances once and the cloud counts change. Reconfirm unchanged data and verify no extra upload.
5. Interrupt connectivity during a confirmed upload. Verify local data remains usable and the UI
   directs Refresh/fresh preview recovery without asserting no remote write. Restore connectivity,
   Refresh, and explicitly retry; verify an existing COMPLETE remains visible and no wrong generation
   is overwritten.
6. Sign out with Keep, sign into a different account, and verify foreign-owned data cannot be
   backed up. Return to the original account and verify explicit Refresh/retry works. Sign out with
   confirmed Remove, then sign back in: existing cloud data must require review and cannot silently
   repopulate or overwrite the new local profile.

Live acceptance is separate from passing emulator rules, public CI or the prior identity acceptance.
