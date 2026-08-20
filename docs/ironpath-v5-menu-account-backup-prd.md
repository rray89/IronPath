# IronPath v5 Menu, Account, and Backup PRD

Date: 2026-07-26
Last updated: 2026-08-19
Status: Feat11.3 Experience Direction approved; review checkpoint ready

## Purpose

This document defines IronPath's next major product phase after v4 AI planning. V5
turns the non-functional top-left menu and disabled Google sign-in affordance into a
coherent secondary-navigation, help, account, and cloud-backup experience.

V5 remains deliberately portfolio-scoped:

- the app is not planned for public release in this phase
- the Firebase project must remain on the no-cost Spark plan with no billing account
- Room remains the source of truth and every workout flow remains useful offline
- sign-in is optional and exists to identify the account for explicit manual backup,
  sync, and restore, not to gate the app or imply upload consent
- V5 starts with manual single-account backup, revision-aware manual sync, and
  whole-backup restore rather than realtime or background synchronization

Older PRDs remain authoritative for their established areas:

- `docs/ironpath-mvp-prd.md` defines the local-first workout lifecycle and core routes.
- `docs/ironpath-v3-prd.md` defines the workout and history review flows and identifies
  login, backup, multi-device sync, and guest migration as a separate platform phase.
- `docs/ironpath-v4-ai-planning-prd.md` defines AI planning, deterministic validation,
  provider fallback, and the release privacy boundary.
- `docs/testing-strategy.md` defines the project-wide test and quality requirements
  inherited by every V5 implementation.

## Product thesis

Login alone is not a product feature. IronPath should ask for a Google account only
when it can offer a concrete benefit: an explicit, inspectable cloud backup that can
restore durable training data after local data loss.

The menu is the visible shell for that platform capability. It should also repair the
current dead-end hamburger affordance and make IronPath's workout, AI, privacy, and
data behavior understandable without duplicating the four primary bottom-navigation
destinations.

The portfolio story should be:

- guest-first and fully useful without an account
- local-first, with Room remaining authoritative
- an optional Google account backed by a small authentication interface
- versioned, failure-safe snapshots and explicit manual sync rather than ambiguous
  background behavior
- explicit guest-to-account and restore decisions with no silent overwrite
- no paid infrastructure and no dependency on a live backend in CI

## Current product baseline

V5 starts from these established facts:

- the top app bar exposes a hamburger icon whose click handler is intentionally empty
- Home, Plan, Active, and History already own primary navigation through the bottom bar
- the entry screen always starts first, offers `Get Started`, shows a disabled
  `Sign in with Google` affordance, and does not persist completed onboarding
- the entry screen says `Your data stays on this device`
- the manifest enables Android backup while the backup-rule files remain templates
- all durable workout data is stored locally in Room using UUID identifiers
- the Room graph contains weekly plans, planned workouts and exercises, active
  sessions, completed logs with exercise/set snapshots, and personal records
- planning intake, AI drafts, provider metadata, debug Remote AI credentials, device
  capability, and UI navigation state are not durable user workout data
- release AI planning sends no training data to a remote provider

The existing privacy copy and Android backup configuration must be reconciled before
V5 claims a precise local-only or cloud-backup state.

## Cost and infrastructure constraint

V5 has a hard expected infrastructure cost of zero.

Allowed:

- Firebase Authentication with Google sign-in
- one default Cloud Firestore Standard database
- Firebase Local Emulator Suite for development, CI, and security-rule tests
- the Firebase Spark plan without a linked Cloud Billing account
- no-cost Android Credential Manager integration

Not allowed in V5:

- upgrading the Firebase project to Blaze
- linking a payment method or Cloud Billing account
- Cloud Storage for Firebase
- deployed Cloud Functions, Cloud Run, or another custom server
- Firebase Authentication with Identity Platform upgrade
- phone or SMS authentication
- paid backup, point-in-time recovery, clone, or TTL features
- production remote AI routing through Firebase

Reaching a Spark quota is a recoverable backup failure. It must never block local
planning, active workouts, history, records, or rule-based/on-device AI. Any future
proposal that requires billing needs a separate product decision and PRD update.

The July 2026 Spark budget used by this PRD is one free database, 1 GiB stored,
50,000 document reads/day, 20,000 writes/day, 20,000 deletes/day, and 10 GiB/month
outbound transfer. The implementation must verify these values against the linked
official quota page before Firebase setup because quotas can change. An IronPath
snapshot is limited to six payload chunks of at most 750 KiB encoded each. A normal
full backup therefore uses at most ten writes (six chunks, two manifest writes, and
two user-metadata writes), and retention uses at most seven deletes. If a representative
portfolio data set no longer fits that budget, backup pauses with `Needs attention`;
V5 does not fragment into an unbounded number of documents or enable billing.

## V5 goals

- make the top-left menu functional and useful
- provide an offline Manual that explains the core product and AI behavior
- expose accurate privacy and data-location information
- preserve guest-first access and remember completed onboarding
- provide optional Google sign-in through Android Credential Manager
- connect sign-in to explicit manual backup, sync, and restore benefits without
  treating identity as upload consent
- back up durable Room data as versioned Firestore snapshots
- restore a validated snapshot without leaving partial or corrupt local data
- define safe guest-to-account, same-account return, account-switch, sign-out, and
  account-deletion behavior
- keep all automated tests independent of Google accounts and live Firebase

## V5 non-goals

V5 does not include:

- realtime or background two-way multi-device synchronization
- automatically scheduled backup or sync in the first release
- silent conflict resolution or a granular per-record conflict editor
- resuming an in-progress workout on another device
- web, iOS, Wear OS, or desktop clients
- email/password, passkey, phone, anonymous Firebase, or social providers other than
  Google
- public profile, username, followers, sharing, social feeds, or leaderboards
- subscriptions, payments, premium tiers, or billing
- Google Drive or Google Sheets as a source of truth
- user-uploaded images, videos, or other Cloud Storage content
- production analytics, crash reporting, push notifications, or marketing messaging
- a production-complete legal/policy package for Google Play
- a production server-side account-deletion worker
- remote storage of raw AI prompts, provider responses, API keys, or unaccepted drafts

---

## Locked product decisions

### Local-first and account-optional

- A local profile is not a Firebase anonymous account.
- Users can complete every workout flow without signing in.
- `Continue on this device` is the primary first-run action.
- `Sign in with Google` is secondary and can also be started later from the menu.
- Cancelling or failing sign-in returns to the fully functional local app.
- The app must not upload existing local data merely because a credential was selected.
  The user first sees and confirms the applicable backup or restore decision.

### Manual backup, revision-aware sync, and restore

- `Backup` creates a complete cloud snapshot, `restore` applies one complete snapshot,
  and `manual sync` is the only first-release operation that merges local and cloud
  changes. The terms are not interchangeable in user-facing copy.
- Sign-in establishes account identity only. It never starts an upload, download, or
  merge.
- A manual sync compares persisted lineage, entity revisions, and remote generation.
  Device wall-clock timestamps may be shown for context but never decide which record
  wins.
- Changes made on only one side since the shared revision merge automatically after
  the user confirms the manual-sync preview.
- If the same record changed on both sides, the preview recommends `Merge and keep
  local conflict versions` to preserve newer offline work. The user must explicitly
  choose that merge outcome or `Overwrite this device from cloud`.
- A conflict is never resolved silently. The first release summarizes conflicting
  records by category and count; it does not provide granular per-record editing.
- Whole-backup restore never merges. It previews the complete local impact and replaces
  local durable data only after validation and a final long-press confirmation.

### Feat11.3 approved Experience Direction

The approved checkpoint is intentionally fixture-backed and debug-only. It makes no
Google, Firebase, Room-mutation, upload, download, merge, or restore call. It exists to
review the following first-release journey before durable implementation:

1. Entry offers both `Continue on this device` and optional Google sign-in.
2. Entry copy states that sign-in identifies the account but does not upload data.
3. The hamburger drawer owns `Account & Backup`, manual sync, backup, and restore.
4. Manual sync previews non-conflicting and conflicting changes before confirmation.
5. Whole-backup restore shows backup source/time and categorized `Added`, `Updated`,
   and `Replaced` counts, then requires a long-press confirmation.
6. A successful restore atomically retains exactly one pre-restore local snapshot for
   one undo; the next successful restore replaces that snapshot.

Automatic/background backup, automatic/background sync, per-item conflict editing,
sign-out and account-deletion lifecycle hardening, and production Firebase activation
are deferred beyond the V5 manual account/backup release.

### Free-tier enforcement

- V5 implementation and documentation target Firebase Spark only.
- CI uses Firebase emulators and never accesses a live Firebase project.
- Live Firebase verification is limited to explicit manual portfolio evidence.
- The app handles quota exhaustion as `Backup paused` while keeping local mutations
  available.
- No implementation may silently enable a paid Firebase product.

### Privacy

- Before sign-in and confirmation, IronPath sends no workout data to Firestore.
- Google authentication shares the account identity required for sign-in.
- Enabling backup uploads only the documented durable backup scope.
- Release AI planning retains the v4 on-device/rule-based privacy boundary.
- Debug Remote AI remains a separate, explicitly enabled experiment and is never
  included in an account backup.
- UI copy must distinguish local Room storage, Android device-to-device transfer,
  IronPath Firestore backup, and debug-only remote AI processing.

---

## Feature 1: Navigation drawer and Manual

### Context

The hamburger icon currently promises a menu but performs no action. Primary product
navigation already exists in the bottom bar, so the drawer should contain secondary,
global destinations instead of a second copy of Home, Plan, Active, and History.

### Drawer information architecture

The drawer contains:

1. Account and backup header
2. `Manual`
3. `AI & Privacy`
4. `About IronPath`

`Settings` is not shown until IronPath has at least one real user setting worth
managing. DevTools, debug provider controls, and test-data actions remain outside the
production drawer.

### Account header states

Local-only:

- label: `LOCAL PROFILE`
- supporting text: `Stored on this device`
- action: `Back up your training data`, which opens `Account & Backup` rather than
  starting authentication or upload directly

Signed in and ready:

- Google avatar when available, otherwise deterministic initials
- display name and email
- latest complete manual-backup or manual-sync time
- status: `Local changes`, `Up to date`, `Offline`, or `Needs attention`
- action opens `Account & Backup`

The header must remain usable without a network connection. A missing or stale avatar
never blocks account state.

### Manual scope

The Manual is an offline, app-owned Compose destination with topic navigation:

- Getting started
- Planning a week
- Reviewing and accepting a plan
- Starting and completing a workout
- History and personal records
- AI planning and validation
- On-device availability and rule-based fallback
- Backup, restore, and local-only behavior

Manual text uses PRD terminology rather than raw Figma or provider copy. It does not
download remote content and does not require a Markdown rendering dependency.

### AI & Privacy scope

The destination explains:

- the current device's on-device AI availability
- the normal release provider order and fallback behavior
- that accepted structured plans persist, while unaccepted AI drafts do not
- that release planning history stays local
- that debug Remote AI can send summarized planning context only when explicitly
  enabled
- whether account backup is off, pending, or active
- exactly which durable data an active backup contains

This screen is explanatory, not a provider selector.

### Interaction and navigation rules

- The hamburger opens a Material 3 modal navigation drawer.
- System back and scrim tap close the drawer before leaving the current destination.
- Selecting a drawer destination closes the drawer and performs single-top navigation.
- Back from Manual, AI & Privacy, About, or Account & Backup returns to the previous
  primary destination.
- Opening or closing the drawer never mutates an active workout or planner state.
- Drawer destinations hide the bottom bar when they are full-screen secondary routes.
- The shared scaffold has independent top-bar and bottom-bar visibility predicates.
- The top-bar navigation icon is destination-specific: hamburger on the four primary
  destinations and back arrow on Manual, AI & Privacy, About, Account & Backup, and
  existing workout detail routes. `feat11.1` migrates the existing detail routes to
  this shared back-arrow behavior.

### Accessibility

- Drawer state, selected destination, account state, and backup status are exposed
  semantically.
- TalkBack focus moves into the open drawer and returns to the menu button when closed.
- Account status is not communicated by color alone.
- All drawer and Manual layouts support 200% font scale, compact portrait, and
  landscape without clipped actions.

---

## Feature 2: Onboarding and optional Google account

### Startup behavior

V5 persists whether onboarding has completed:

- first launch or explicit full local reset starts at Entry
- returning users start at Home
- an authenticated account session may be restored without showing Entry
- lack of network never redirects a returning user to Entry

### Entry behavior

The V5 end-state Entry, implemented beginning in `feat11.3.1`, presents:

- primary: `Continue on this device`
- secondary: `Sign in with Google`
- privacy copy: `Signing in identifies your account. Your training data stays local
  until you choose a manual backup or restore.`

In the Experience Direction checkpoint, the secondary action opens only a deterministic
signed-in fixture. It does not complete onboarding, authenticate, or move data.

The current inert terms sentence is removed unless real Terms and Privacy destinations
exist and are actionable. Entry never claims that Android or IronPath performs no
backup while a cloud backup path is active.

The interim `feat11.1` Entry removes the inaccurate `Your data stays on this device`
and inert Terms copy, but keeps Google sign-in absent or visibly unavailable. It does
not promise IronPath cloud backup before the backup, restore, and ownership contract
ships across `feat11.3.1`–`feat11.3.3`. The Android-backup exclusions introduced in
`feat11.1` create an accepted private-portfolio window in which neither Android cloud
backup nor IronPath cloud backup protects workout data.

### Authentication interface

The app owns a small account interface that exposes:

- current account state as a Flow
- start Google sign-in
- reauthenticate when required
- sign out
- delete the authenticated account after remote data deletion succeeds

Android Credential Manager and Firebase Authentication are adapters behind that seam.
ViewModels and workout repositories do not depend directly on Firebase classes.

### Supported authentication

- Google is the only V5 provider.
- Credential Manager supplies the account chooser and credential result.
- Firebase Authentication validates and maintains the app session.
- No password is collected or stored by IronPath.
- No Firebase anonymous user is created for local-only use.
- Authentication cancellation is a normal outcome, not an error toast.

### Account state

At minimum, the domain distinguishes:

- `LocalOnly`
- `SigningIn`
- `AwaitingDataChoice`
- `SignedIn`
- `NeedsReauthentication`
- `SigningOut`
- `DeletingAccount`
- recoverable error with a sanitized reason

Repeated sign-in, sign-out, restore, backup, and delete actions are serialized.
Late credential or network results cannot replace a newer account operation.
`AwaitingDataChoice` is derived from the authenticated Firebase session plus persisted
local ownership/lineage metadata; it is never an in-memory-only state. Cancelling the
choice signs out of Firebase and returns deterministically to `LocalOnly`, including
after process death.

---

## Feature 3: Free Firebase backup foundation

### Architecture

Room remains the only source read by product screens and ViewModels.

```text
Screens and ViewModels
          |
          v
Room repositories and database  <-->  Backup coordinator  <-->  Firestore adapter
                                             ^
                                             |
                                      Account interface
```

The backup coordinator exposes a small interface:

- observe backup status
- create a backup now
- inspect the latest complete remote backup summary
- preview and execute a user-confirmed revision-aware manual sync
- restore a selected complete backup
- delete all remote backup data for the authenticated account

Firestore-specific collections, snapshots, exceptions, and timestamps stay inside the
remote adapter. Tests exercise the same coordinator interface using deterministic
in-memory adapters.

### Firebase project rules

- use one dedicated portfolio Firebase project
- keep the project on Spark with no linked billing account
- use the default Firestore Standard database, which receives the free quota
- enable only Google authentication and Firestore
- deny all Firestore access by default
- allow an authenticated user to read and write only paths below their own UID
- deploy no Functions or Storage resources
- document local emulator setup and live manual setup separately
- treat Firebase client configuration as public configuration, never as authorization
- never place service-account credentials in the app, repository, or CI
- keep the real `google-services.json` and live project identifiers out of the public
  repository; commit only an emulator/placeholder configuration that cannot reach the
  live project, and supply the private config locally for explicit Seeker evidence
- do not distribute a build containing the private live configuration during V5
- create the default database in regional `us-west1` (Oregon), close to the
  Vancouver-based portfolio owner; record the choice because it cannot be changed
  later

### Remote data layout

The remote store is scoped under:

```text
users/{uid}
users/{uid}/backups/{backupId}
users/{uid}/backups/{backupId}/chunks/{chunkIndex}
```

The user document contains only account-backup metadata:

- `latestCompleteBackupId`
- monotonic remote `generation`
- an authoritative ordered `backupIds` registry containing two complete IDs in steady
  state, transiently a third complete ID while ordered cleanup runs, and at most one
  in-progress ID
- latest completion timestamp and source installation ID

The registry, not subcollection discovery, is the work list for retention and account
deletion. Firestore client SDKs cannot enumerate arbitrary subcollections, and deleting
a parent document does not delete its subcollections.

Each backup manifest contains:

- backup ID
- backup format version, independent of the Room schema version
- app version
- source installation ID generated by IronPath, never a hardware identifier
- state: `UPLOADING` or `COMPLETE`
- created and completed server timestamps
- chunk count, byte count, and entity counts by type
- deterministic content digest or equivalent integrity metadata
- captured local change revision
- the remote generation observed before upload

Each ordered chunk document contains:

- backup format version
- zero or more typed, versioned entity envelopes
- each envelope's entity type, stable UUID, parent UUID where applicable, and typed
  backup payload
- chunk index, encoded byte count, and chunk digest

Entity keys inside the payload include their type so UUID reuse across tables cannot
collide. Chunking is deterministic, each document stays at or below 750 KiB encoded,
and a snapshot may contain no more than six chunks. Backup format `1` is the oldest
version supported by V5.

### Snapshot completion contract

0. Read `users/{uid}` and capture `latestCompleteBackupId`, its source installation,
   and the monotonic remote `generation`. If a newer complete snapshot from a different
   installation exists since this installation's last observation, abort before any
   upload and enter the data-choice flow.
1. In one Room transaction, read a consistent export bundle plus the current monotonic
   local change revision.
2. Validate all FK-backed relationships, supported values, and app-level invariants,
   including at most one `WeeklyPlan` with `status = Active`.
3. If the content digest equals the latest complete manifest, skip the upload, record
   that the observed backup is current locally, and spend no payload writes.
4. Refuse a manual backup when a previously non-empty lineage becomes empty or its total
   included-entity count drops by more than 50 percent unless the `Back Up Now` preview
   shows counts and receives an explicit destructive confirmation.
5. In a Firestore transaction conditioned on the step-0 generation and an empty upload
   slot, create one `UPLOADING` manifest and add its ID to the registry/upload slot.
   A concurrent claimant fails and backs off. An existing upload is reclaimed only
   when its server-timestamped lease is older than 24 hours; reclamation uses the
   registry and strict chunks-first deletion. At most one upload may exist for a user.
6. Upload the complete deterministic chunk set and verify chunk/entity counts, byte
   counts, and digests.
7. Re-read the local change revision. If it advanced after step 1, completion may
   continue for the captured snapshot, but the UI remains `Local changes` and requires
   another manual operation. The first release does not enqueue background work.
8. In one Firestore transaction, conditioned on the remote `generation` still matching
   step 0 and the upload slot still naming this backup, mark the manifest `COMPLETE`,
   update the latest-complete pointer, clear the slot, and increment `generation`.
   The registry retains every not-yet-deleted manifest ID. A failed precondition never
   force-writes; it re-enters another-device detection.
9. Only after completion, remove backups older than the two newest complete snapshots.
   Every removal is ordered chunks → manifest → registry entry. A failed cleanup
   remains discoverable through the registry and is retried.

Restore ignores every `UPLOADING`, unknown-version, incomplete, or invalid snapshot.
The previous complete snapshot remains restorable until the replacement completes.
Interrupted upload or cleanup is retried without blocking local use.

V5 does not use Firestore's paid managed backup/PITR feature. IronPath backup snapshots
are ordinary authenticated Firestore documents within the Spark quota.

### First-release manual operations

The V5 manual account/backup release, implemented across `feat11.3.1`–`feat11.3.3`,
performs remote work only after one of these explicit user actions and its applicable
preview/confirmation:

- `Back Up Now`
- `Review manual sync` followed by `Confirm manual sync`
- `Preview whole-backup restore` followed by the long-press Restore confirmation

Successful account setup, plan acceptance, workout completion, personal-record
creation, app launch/resume, and network recovery never start a remote operation.
Every included-data mutation still increments a durable local revision in the same
Room transaction so the screen can show `Local changes` and later compute a manual
sync safely. The first release creates no unique background work, debounce, or
automatic retry. A failed manual operation remains available for explicit retry.

### Backup status

The user-visible status distinguishes:

- `Local only`
- `Signed in — no backup yet`
- `Local changes`
- `Preparing manual backup`
- `Backing up now`
- `Review required`
- `Up to date` with completion time
- `Offline — try again when connected`
- `Backup paused — service quota or rate limit`
- `Needs sign-in`
- `Needs attention` with a retry action

Only a `COMPLETE` snapshot whose captured local revision still equals the current
revision may produce `Up to date`.

---

## Feature 4: Backup scope and restore

### Included durable data

V5 backs up:

- `WeeklyPlan`
- `PlannedWorkout`
- `PlannedExercise`
- `WorkoutLog`
- `LoggedExercise`
- `LoggedSet`
- `PersonalRecord`
- future stable training preferences only after a later schema explicitly adds them

Archived plans remain included because they are durable user history.

### Excluded data

V5 does not back up:

- `ActiveSession`
- `SessionExercise`
- `SessionSet`
- unaccepted AI plan drafts
- planning intake kept only in memory or saved state
- AI prompts, provider responses, validation tokens, and provider diagnostics
- Remote AI API keys
- Firebase credentials or Credential Manager data
- account tokens
- device capability results
- debug flags, seeded data markers, DevTools state, caches, navigation state, or
  transient errors

An in-progress workout continues to survive process recreation through Room on the
same device, but V5 does not promise uninstall or cross-device recovery for that
session.

### Restore validation

Before local replacement, the app:

- downloads only a `COMPLETE` supported snapshot
- validates the manifest, chunk/entity counts, type allowlist, and integrity metadata
- requires every FK-backed parent/child reference to resolve
- treats nullable provenance references without Room foreign keys as best-effort;
  dangling `WorkoutLog.sourcePlannedWorkoutId` and
  `PersonalRecord.sourceWorkoutLogId` values are nulled and recorded as non-fatal
  validation notes
- validates enum, numeric, date, uniqueness, and required-field constraints
- validates app-level invariants not enforced by SQLite, including at most one
  `WeeklyPlan` with `status = Active`
- rejects unknown future schema versions and instructs the user to update the app
- maps older supported backup formats through explicit migrations
- constructs the complete restore bundle before modifying Room
- previews the complete backup date, source installation, and categorized counts of
  local records that will be `Added`, `Updated`, or `Replaced`
- states that restore applies the whole backup and offers no per-record editor

Restore cannot proceed silently while an `ActiveSession` exists. The user may return
to the workout or choose `Discard active workout and restore`; that confirmation names
the loss. The final Restore action requires a long press after the impact preview.
Immediately before a confirmed successful restore, the same Room transaction captures
the complete pre-restore local durable-data bundle, replaces the prior undo snapshot,
deletes the active session and cascaded children when applicable, replaces the included
local tables, updates ownership and lineage metadata, and records the restored
revision. Exactly one pre-restore snapshot is retained and supports one atomic undo;
the next successful restore replaces it. Any download, parse, validation, confirmation
cancellation, or transaction failure leaves the pre-restore local database, active
session, and prior undo snapshot unchanged.

### Sign-in and manual data-choice matrix

Sign-in establishes identity and reads only the metadata needed to describe available
manual actions. It does not attach, upload, merge, or replace workout data.

1. `Account(otherUid)` local ownership blocks backup, sync, and restore even when the
   workout tables are empty. Account-switch and retained-data cleanup remain lifecycle
   hardening in `feat11.4`, after the V5 manual account/backup release.
2. With no complete remote snapshot:
   - empty `Unclaimed` data may associate with the account after explicit manual-backup
     confirmation, but no empty snapshot is created
   - non-empty `Unclaimed` data stays local until the user previews and confirms
     `Back Up Now`
   - same-UID local data resumes its lineage without starting remote work
3. With a complete remote snapshot, the Account & Backup screen offers two distinct
   operations:
   - `Review manual sync` compares the shared revision lineage. One-sided changes merge
     after confirmation; same-record conflicts require the user to choose `Merge and
     keep local conflict versions` or `Overwrite this device from cloud`.
   - `Preview whole-backup restore` never merges. It validates the complete snapshot,
     shows backup date/source and categorized impact counts, and proceeds only after
     the final long-press confirmation.
4. Canceling any preview leaves local data, remote data, ownership, lineage, and the
   existing pre-restore undo snapshot unchanged.

Copy never calls one side `newer` from wall-clock time alone. Persisted entity revision,
shared-sync revision, remote generation, and source installation establish the
comparison. The local-conflict recommendation protects offline work but is still only
a recommendation; no conflict choice is pre-executed.

### Local ownership metadata

IronPath adds a Room `AccountBackupMetadata` table in the v2→v3 database migration.
It is separate from workout entities but shares the same Room database and transaction
boundary. It stores:

- ownership: `Unclaimed` or `Account(uid)`
- app-generated installation ID
- monotonic local change revision and last-complete local revision
- last-observed remote backup ID, generation, digest, source installation, and time

Signing out while retaining data preserves `Account(uid)`. Subsequent edits remain
local and may resume backup only after the same UID signs in again. A different UID
can never inherit or upload that retained data without an explicit destructive
replacement choice.

Installation and ownership identifiers are app-generated. IronPath does not use IMEI,
Android ID, advertising ID, or another hardware identifier. Onboarding completion may
use a second Room app-metadata row; no cross-store transaction is relied upon for
ownership or backup lineage.

Every full local reset, DevTools reset, and `Remove data from this device` uses one
repository transaction that clears workout data and account-backup metadata together.
The current direct `database.clearAllTables()` demo path is replaced, and account-linked
reset tooling is debug-only rather than release-shipping.

For API 31+ device transfer, the Room file may contain transferred ownership metadata.
A per-installation sentinel in `noBackupFilesDir` is never transferred. Ownership
present with a missing or mismatched sentinel is therefore treated as a transferred
installation: in one Room transaction IronPath rotates the installation ID, sets
ownership to `Unclaimed`, and requires a fresh data choice before any upload. Sentinel
creation is fail-closed and idempotent: a crash that leaves it mismatched can only
repeat the `Unclaimed` transition, never authorize an upload.

The sentinel is validated once per cold process start after Room opens and before
signed-in manual operations are enabled. It is validated again immediately before any
manual backup, sync, or restore request is honored. Until validation succeeds for the
current process launch, every remote mutation remains blocked. This ordering prevents
a transferred Room ownership row from authorizing even the first post-transfer upload.

### Another-device detection

The latest complete manifest records its source installation ID. In the first release,
IronPath checks the remote pointer only when Account & Backup opens or refreshes and at
the start of an explicit manual backup, sync, or restore. Signed-in app resume never
starts an operation.

When another installation advanced the remote generation, the next manual-sync preview
uses shared revisions to classify one-sided and same-record changes. Non-conflicting
changes are eligible for the confirmed merge; conflicts require the explicit local or
cloud outcome. A manual backup never force-writes across an unobserved remote
generation. The Firestore compare-and-set remains the final race guard if two devices
act concurrently.

---

## Feature 5: Account lifecycle, deletion, and platform privacy

### Account & Backup screen

Signed out:

- explain local-only behavior
- `Sign in with Google`
- show the last local mutation state without implying a cloud copy exists

Signed in:

- name, email, and avatar/initials
- latest complete manual-backup/manual-sync time and included-data summary
- `Back Up Now`
- `Review manual sync`
- restore availability and last remote snapshot summary

`Sign out` and `Delete cloud account and backup` are not exposed in the
`feat11.3.1`–`feat11.3.3` implementation sequence. They remain specified below for the
later lifecycle-hardening slice.

No UI claims `Up to date` based only on authentication state.

### Sign-out behavior

Deferred to `feat11.4`, beyond the V5 manual account/backup release.

Sign out offers:

- `Keep data on this device` — default, non-destructive, ownership remains bound to the
  signed-out UID
- `Remove data from this device` — destructive confirmation, transactionally clears
  included and active local data plus ownership/account metadata

Sign-out never deletes the remote backup. Pending remote writes are cancelled before
the Firebase session is cleared.

### Delete-account behavior

Deferred to `feat11.4`, beyond the V5 manual account/backup release.

Because V5 forbids Cloud Functions, deletion is an authenticated client-owned flow:

1. Require recent Google authentication before destructive work.
2. Read the authoritative `backupIds` registry for the current UID.
3. For every registered backup, delete all chunk documents, then its manifest, then
   remove its registry entry; every phase is bounded, retryable, and idempotent.
4. Verify the registry is empty and a query over `users/{uid}/backups` returns zero
   manifest documents.
5. Delete `users/{uid}` only after step 4 succeeds.
6. Delete the Firebase Authentication account.
7. Ask whether to erase the device or retain its data as a new unclaimed local profile.

The UI reports completion only after remote deletion and Firebase account deletion
succeed. Recoverable failure retains enough authenticated state to retry and never
claims the account was deleted.

Deleting a Firebase Auth account and later signing in with the same Google identity is
treated as a new account with a potentially different UID. Remote residue under the
deleted UID would then be unrecoverable by the client, which is why remote verification
must succeed before Auth deletion. The same chunks → manifest → registry ordering is
mandatory for ordinary retention cleanup.

This client-owned deletion is acceptable for a private portfolio phase. Before any
public release, IronPath must add a production deletion mechanism that remains
available after uninstall, an external deletion-request path, a published privacy
policy, and any required server-side cleanup.

### Android platform backup

V5 replaces the template Android backup files with an explicit policy:

- Android cloud backup must not silently upload the Room database or account metadata
  outside the IronPath account-backup consent flow.
- Android 12+ `data_extraction_rules.xml` excludes `domain="database" path="."` from
  cloud backup and excludes shared preferences/files that could contain account,
  credential, API-key, debug, or provider state. Its device-transfer section may
  include the Room database and non-secret preferences.
- Android 11 and lower `backup_rules.xml` cannot express different cloud-backup and
  device-transfer policies. It excludes `domain="database" path="."` plus account,
  credential, API-key, debug, and provider state from both. Workout data therefore
  does not survive Android device transfer on API 29–30; this is an accepted V5
  limitation.
- If DataStore is later introduced, both rule files explicitly cover
  `domain="file" path="datastore/"`; V5 does not rely on an empty template rule.
- Credentials, account tokens, API keys, debug state, and the no-backup installation
  sentinel are never transferred. A transferred Room installation ID is invalidated
  and rotated through the sentinel rule in Local ownership metadata.
- Restored device-to-device data is treated as local data and passes the same ownership
  and account-choice rules before Firestore upload.

The Entry, Manual, AI & Privacy, and Account & Backup copy must match this policy.

### Error and offline behavior

- Authentication or backup failure never logs a user out unless the credential is
  definitively invalid.
- Offline sign-in presents a retryable state and leaves the local app available.
- Offline local mutations stay local, advance their revision, and never schedule
  background work. A later manual operation can be retried when connected.
- Quota/rate-limit, permission, malformed-remote-data, version, and reauthentication
  failures have distinct typed states and sanitized user copy. Because Firestore may
  use `RESOURCE_EXHAUSTED` for more than daily free-quota exhaustion, the adapter does
  not claim the exact cause unless it can prove it; otherwise it shows the generic
  service quota/rate-limit status.
- Firebase exception strings, tokens, UIDs, email addresses, and payloads are not
  written to production logs.
- A denied Firestore request is not retried indefinitely.

---

## Security and trust rules

- Firestore Security Rules are part of the feature, not console-only setup.
- Rules deny unauthenticated access and cross-UID access.
- Rule tests cover read, create, update, list, and delete operations for owner,
  different-user, and unauthenticated identities.
- Remote payloads are untrusted input and pass format and domain validation before
  Room persistence.
- Backup payloads contain no secrets or authentication material.
- UI and logging redact account tokens and backend payloads.
- Debug and release Firebase configurations must not grant different data access.
- Production code owns interfaces; Hilt modules provide only Firebase adapter and
  interface bindings.
- Firebase SDKs do not leak into workout domain models, Room entities, ViewModels, or
  screen parameters.
- Security Rules remain the authorization layer; Firebase client configuration values
  are not treated as secrets.
- Availability protection also matters: the private live Firebase configuration is
  not committed or distributed, so a public repository cannot be used directly to
  consume the live Spark quota.
- App Check is free and remains an available hardening layer. V5 emulator and private
  sideload testing may use its debug provider only with an uncommitted private debug
  token and a debug-only dependency. Play Integrity enforcement is evaluated before
  any build with live configuration is distributed; deferral is a private-portfolio
  scope choice, not a claim that sideloaded builds are unsupported.

## Suggested implementation order

1. `feat11: add V5 Menu, Account, and Backup PRD`
2. `feat11.1: add navigation drawer, Manual, privacy surfaces, onboarding persistence,
   and explicit Android backup rules`
3. `feat11.2: add account and backup contracts, the Room v2→v3 metadata migration,
   revisioned/chunked export and restore, Firestore emulator, and tested Security Rules
   behind a disabled account surface`
4. `feat11.3: approve and review the fixture-backed Account & Backup Experience
   Direction`; this first PR updates the decision source of truth and adds only a
   debug-gated preview with no authentication, remote data, or Room mutation
5. `feat11.3.1: implement the Account & Backup shell and persisted account/data-choice
   state with deterministic credential fixtures`; keep live Google/Firebase activation
   absent
6. `feat11.3.2: implement revision-aware manual backup/sync preview and explicit
   conflict outcomes against deterministic and emulator adapters`; add no background
   scheduling
7. `feat11.3.3: implement whole-backup restore preview, long-press confirmation, atomic
   replacement, exactly one pre-restore undo snapshot, and the fixture/emulator
   end-to-end journey`
8. `feat11.4: add sign-out, account deletion, quota/offline/recreation hardening,
   production Firebase activation, live Seeker evidence, and portfolio documentation`

The fixture-backed feat11.3 preview is debug-only. The real authenticated surface stays
disabled or absent until feat11.3.1–feat11.3.3 complete account state, revision-aware
manual sync, whole-backup restore, and undo safely. Sign-out and deletion controls stay
absent until their feat11.4 lifecycle contract ships.

## Testing strategy

V5 inherits `docs/testing-strategy.md`. Every implementation slice starts with a test
that fails for the intended reason before production code changes.

### Feature proof matrix

| Implementation | Required proof |
| --- | --- |
| `feat11.1` drawer and Manual | JVM navigation-state tests where applicable; isolated Compose and real NavHost tests for open, close, selection, per-destination navigation icon, back, recreation, active-session safety, semantics, TalkBack order, 200% font scale, portrait, and landscape; XML assertions that both Android rule files exclude `domain="database" path="."` from cloud/full backup and a manifest assertion for `android:allowBackup` |
| `feat11.2` account/backup contracts | JVM state-machine and serialization tests; real Room export/restore and migration tests; Firebase Emulator Security Rules tests; Hilt debug/release graph resolution; proof that no live credentials or backend are required |
| `feat11.3` Experience Direction checkpoint | Debug-only fixture preview; Entry and drawer route proof; Compose interaction/semantics for manual sync choices and whole-restore impact; long-press confirmation; 200% font-scale reachability; proof that the preview entry, route, and fixture copy are absent from the minified release artifact; no auth, Firebase, Room mutation, or remote test dependency |
| `feat11.3.1` account shell and state | Credential-boundary tests with deterministic fakes; ViewModel and Compose coverage for local-only, signed-in, and data-choice state; process recreation; no live Google/Firebase configuration |
| `feat11.3.2` revision-aware manual backup/sync | JVM merge classification and revision-lineage tests; Compose preview and explicit conflict outcomes; emulator integration for manual upload/merge/denial; no background work |
| `feat11.3.3` whole-backup restore and undo | Categorized impact preview; long-press semantics; real Room atomic restore and one-snapshot undo tests; emulator and real NavHost journey proof |
| `feat11.4` lifecycle hardening | delete/sign-out/re-auth JVM and emulator tests; cancellation, interruption, duplicate action, another-account, another-device, corrupt snapshot, unsupported version, cleanup retry, and process recreation; Seeker Google sign-in and backup/restore smoke |

### Account and state-machine tests

Cover:

- first-run local continuation and remembered onboarding
- returning local user startup while offline
- successful, cancelled, failed, repeated, and recreated sign-in
- process death during `AwaitingDataChoice`, plus cancellation that leaves no Firebase
  session
- same-account return after sign-out with retained local data
- different-account attempt against retained owned data
- local-only mutation during offline account state
- serialized duplicate backup, restore, sign-out, and delete actions
- late credential and remote results ignored after replacement or cancellation
- reauthentication required before deletion
- sanitized failure mapping with no token, UID, email, or payload leakage

### Backup and restore tests

Cover:

- empty, small, and representative large Room graphs
- every included entity and every excluded entity
- complete snapshot creation and latest-complete pointer update
- unchanged-digest backup skipped without payload writes
- deterministic chunking at the size/count boundary
- upload interruption before manifest completion
- abandoned `UPLOADING` reclamation with exactly one in-progress registry entry
- two installations completing concurrently; the losing generation precondition enters
  data choice and neither complete snapshot is silently lost
- a mutation committed between export and `COMPLETE` schedules a follow-up revision
- previous complete snapshot preservation
- retention cleanup failure and retry
- manual backup requires extra confirmation after an empty or greater-than-50-percent
  local drop
- entity count or integrity mismatch
- missing parent, duplicate ID, invalid enum/date/number, unknown entity type, and
  unsupported format
- two `Active` weekly plans rejected
- dangling nullable provenance restored as `null` with a non-fatal note
- oldest-supported-to-current restore migration
- atomic restore rollback after a failing insert or constraint
- restore attempted with an active session: cancel preserves it; confirmed discard and
  replacement share one transaction
- completion after a restore can never retain a removed source planned-workout ID
- revision-aware one-sided merge, same-record conflict classification, explicit
  local-conflict or cloud-overwrite choice, and cancel paths
- local ownership for unclaimed, same UID, and different UID
- local reset atomically clears workout data, ownership, lineage, and installation ID
- a simulated API 31+ device transfer with copied `Account(uid)` metadata and an absent
  sentinel rotates to `Unclaimed` before the first manual operation can run
- another-device remote update with one-sided changes and same-record conflicts
- categorized whole-backup impact preview plus long-press confirmation
- exactly one atomic pre-restore undo snapshot, replaced only by the next successful
  restore and preserved across failed or cancelled restore attempts
- free-quota-shaped failure that leaves the local app usable

### Firebase emulator and rules tests

Automated tests never call live Firebase. Emulator proof includes:

- owner can read and write only the allowed schema below their UID
- unauthenticated access is denied
- cross-account access is denied
- malformed or forbidden paths are denied
- registry-driven bounded deletion works when chunks outlive a missing manifest, and
  Auth deletion remains blocked until registry and manifest-query verification pass
- interrupted uploads remain non-restorable
- the emulator loads the same checked-in rules source intended for manual deployment

Rules tests live under `firebase/` using a pinned Node package lock,
`@firebase/rules-unit-testing`, and `firebase-tools`. A named Gradle `Exec` task invokes
the package script, and CI installs the pinned Node version and runs it in a dedicated
job. This toolchain is explicit because it is not an Android/Gradle library.

Managed API 29/36 images are AOSP and do not perform real Google sign-in. Instrumented
account/backup journeys use the Firebase Auth emulator over `10.0.2.2` and a stub
credential adapter. Only the manual Seeker smoke uses a real Google account.

### UI, accessibility, and navigation tests

Cover:

- local-only, signing-in, awaiting-choice, backing-up, offline-pending, up-to-date,
  needs-reauthentication, quota-paused, error, signing-out, and deleting states
- accurate enabled/disabled/loading semantics for every action
- drawer focus entry/return and system-back priority
- hamburger appears only on primary destinations; secondary destinations retain the
  top bar with a back arrow while hiding the bottom bar
- no duplicate primary navigation in the drawer
- no inaccessible status conveyed only by color
- destructive confirmation with explicit target and consequences
- 200% font scale, compact portrait, and landscape for Entry, drawer, Manual,
  data-choice, Account & Backup, and deletion confirmation

### Persistence and journey tests

- Room stays authoritative before, during, and after backup
- the Room v2→v3 migration preserves workout data and initializes account-backup
  metadata safely
- manual remote operations never change the result of an unrelated local product
  transaction
- a real-app journey covers local onboarding, representative local data, fake sign-in,
  manual sync, process recreation, whole-backup restore, one undo, and restored
  Home/History state
- a second journey covers retained owned data followed by a different-account attempt
- a destructive journey covers authenticated remote deletion and both local-retention
  choices using emulators and deterministic credentials
- nightly repeated journeys start from fresh app and emulator state

### Build and device gates

- Spotless, lint, debug/release assembly, JVM tests, core coverage, and applicable API
  29/36 suites remain required
- release assembly proves debug Firebase/emulator helpers are absent
- CI and benchmarks require no Google account, OAuth consent, network, Firebase quota,
  or billing
- Seeker is the manual physical target for drawer, real Credential Manager Google
  sign-in, backup, clear-local-data, and restore evidence
- a live Firebase smoke uses synthetic or deliberately seeded portfolio data and
  verifies the project remains on Spark
- screenshots and PR artifacts redact the Google display name, email, UID, and avatar

## Portfolio demo story

A strong V5 demo shows:

1. Open the new drawer and Manual from an existing local workout state.
2. Show the AI & Privacy explanation and local-only account status.
3. Sign in with Google and show that identity alone performs no upload.
4. Open the manual-sync preview. Show the non-conflicting changes and the explicit
   choice for same-record conflicts, with local offline work recommended.
5. Confirm the manual operation and show the completion time and included counts.
6. Preview a whole-backup restore with backup source/time and categorized `Added`,
   `Updated`, and `Replaced` counts.
7. Long-press Restore, show the restored Home/History state, then show that exactly one
   pre-restore snapshot is available for one undo.
8. Explain that the app remains Room-first and offline, uses revision lineage rather
   than wall-clock time for manual sync, and intentionally defers background behavior.

The story is not "Firebase made the app online." It is that IronPath introduced a
small remote adapter and a failure-safe data contract without allowing authentication,
backend availability, or cost to infect the workout domain.

## Pre-public-release requirements

V5 is acceptable for a private portfolio project, not a public production launch.
Before Google Play distribution, a separate release-readiness decision must cover:

- production privacy policy and Terms
- Google Play Data safety declarations
- in-app and external account-deletion paths
- server-side cleanup that works after uninstall
- abuse protection and App Check strategy
- operational monitoring, incident response, and support
- data retention and backup recovery policy
- Firebase region/data-residency review
- billing-risk decision if free-tier limits are no longer adequate
- production OAuth branding and verification
- multi-device conflict policy if the product claims synchronization

## Deferred decisions

Feat11.3 resolves the implementation-blocking first-release choices: Google sign-in is
identity rather than upload consent; every remote operation is manual; one-sided
revision changes may merge after preview; same-record conflicts require an explicit
local or cloud outcome; restore applies a whole validated backup after long press; and
exactly one pre-restore local snapshot supports one undo.

Automatic/background backup and sync, cadence and scheduling, granular per-record
conflict editing, sign-out/account-deletion lifecycle hardening, production Firebase
activation, larger-history support, and richer multi-device behavior remain deferred.
They may not weaken the no-silent-overwrite, atomic-restore, ownership, privacy, or
Spark-budget limits in this PRD.

## External references

- Android Credential Manager:
  https://developer.android.com/identity/credential-manager
- Sign in with Google and Credential Manager:
  https://developer.android.com/identity/sign-in/credential-manager-siwg
- Firebase Authentication for Google sign-in:
  https://firebase.google.com/docs/auth/android/google-signin
- Firebase Spark and Blaze plans:
  https://firebase.google.com/docs/projects/billing/firebase-pricing-plans
- Cloud Firestore pricing and free quota:
  https://cloud.google.com/firestore/pricing
- Cloud Firestore usage and limits:
  https://firebase.google.com/docs/firestore/quotas
- Cloud Firestore locations:
  https://firebase.google.com/docs/firestore/locations
- Delete Firestore data and nested subcollections:
  https://firebase.google.com/docs/firestore/manage-data/delete-data
- Cloud Firestore Security Rules:
  https://firebase.google.com/docs/firestore/security/get-started
- Firebase Local Emulator Suite:
  https://firebase.google.com/docs/emulator-suite
- Firebase App Check debug provider:
  https://firebase.google.com/docs/app-check/android/debug-provider
- Android Auto Backup:
  https://developer.android.com/identity/data/autobackup
