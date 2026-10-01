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
[beginTransaction](https://cloud.google.com/firestore/docs/reference/rest/v1/projects.databases.documents/beginTransaction),
[commit](https://cloud.google.com/firestore/docs/reference/rest/v1/projects.databases.documents/commit),
[write transforms](https://firebase.google.com/docs/firestore/reference/rest/v1/Write).
Transactional reads use official POST [batchGet](https://firebase.google.com/docs/firestore/reference/rest/v1/projects.databases.documents/batchGet), keeping transaction bytes in the JSON body; GET with a transaction query exposes [an emulator decoder bug](https://github.com/firebase/firebase-tools/issues/3293). Transactions commit chunks and metadata against observed generations. Server transforms use
REQUEST_TIME. Only a structured ABORTED response permits a bounded retry inside the user's active
manual operation. Fixed-length HTTP streaming prevents buffered POST replay after a lost response.
HTTP timeout/cancellation does not prove server cancellation.

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
JVM tests cover coordinator and HTTP behavior; API 29 CI covers the real Room association transaction
and shared credential-free cloud presentation at 200% font scale. The live transport and Firebase
configuration remain absent from debug/release source sets.

The portfolio owner supplies private configuration outside this public repository as described in
[the identity preview](feat11.4.3-google-identity-auth-preview.md). Use Spark, no billing account,
Firestore `(default)`, Standard, regional `us-west1`. Publish the tested rules and indexes only through
the owner's controlled setup. Chunk payload indexing is exempted to avoid unnecessary index cost.
No Cloud Functions, Storage or paid service is required. Public CI remains emulator-only.

## First Usable Slice acceptance — pending

No real configured device or live service has been exercised by the implementation worker. Do not
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
