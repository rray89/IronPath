import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  Timestamp,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  serverTimestamp,
  setDoc,
  updateDoc,
  writeBatch,
} from "firebase/firestore";
import { after, before, beforeEach, test } from "node:test";
import assert from "node:assert/strict";

const projectId = "demo-ironpath";
let testEnvironment;

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    projectId,
    firestore: { host: "127.0.0.1", port: 8080 },
  });
});

beforeEach(async () => {
  await testEnvironment.clearFirestore();
});

after(async () => {
  await testEnvironment.cleanup();
});

test("owner can atomically claim, upload, complete, list, and delete a backup", async () => {
  const owner = testEnvironment.authenticatedContext("owner-a").firestore();
  const { user, manifest, chunk } = backupRefs(owner);

  await assertSucceeds(setDoc(user, userMetadata()));
  await assertSucceeds(getDoc(user));
  await assertSucceeds(claimUpload(owner));
  await assertSucceeds(setDoc(chunk, validChunk()));
  await assertSucceeds(getDoc(chunk));
  await assertSucceeds(getDocs(collection(owner, "users/owner-a/backups")));
  await assertSucceeds(completeUpload(owner));
  await assertSucceeds(deleteDoc(chunk));
  await assertSucceeds(deleteDoc(manifest));
  await assertSucceeds(removeManifestFromRegistry(owner));
  await assertSucceeds(deleteDoc(user));
  assert.equal((await assertSucceeds(getDoc(manifest))).exists(), false);
});

test("unauthenticated clients cannot access account backup paths", async () => {
  await seedOwnerGraph();
  const guest = testEnvironment.unauthenticatedContext().firestore();

  await assertFails(getDoc(doc(guest, "users/owner-a")));
  await assertFails(setDoc(doc(guest, "users/guest"), userMetadata()));
  await assertFails(getDocs(collection(guest, "users/owner-a/backups")));
  await assertFails(deleteDoc(doc(guest, "users/owner-a/backups/backup-a")));
});

test("an authenticated user cannot read or mutate another user's backup graph", async () => {
  await seedOwnerGraph();
  const intruder = testEnvironment.authenticatedContext("owner-b").firestore();

  await assertFails(getDoc(doc(intruder, "users/owner-a")));
  await assertFails(getDocs(collection(intruder, "users/owner-a/backups")));
  await assertFails(updateDoc(doc(intruder, "users/owner-a"), { generation: 2 }));
  await assertFails(
    setDoc(doc(intruder, "users/owner-a/backups/intruder"), uploadingManifest("intruder")),
  );
  await assertFails(deleteDoc(doc(intruder, "users/owner-a/backups/backup-a")));
});

test("guest and different-user identities are denied every user, manifest, and chunk operation", async () => {
  await seedOwnerGraph({ withChunk: true });
  const clients = [
    testEnvironment.unauthenticatedContext().firestore(),
    testEnvironment.authenticatedContext("owner-b").firestore(),
  ];

  for (const client of clients) {
    const user = doc(client, "users/owner-a");
    const manifest = doc(client, "users/owner-a/backups/backup-a");
    const chunk = doc(client, "users/owner-a/backups/backup-a/chunks/000");
    const operations = [
      () => getDoc(user),
      () => setDoc(doc(client, "users/target-owner"), userMetadata()),
      () => updateDoc(user, { generation: 1 }),
      () => deleteDoc(user),
      () => getDoc(manifest),
      () => getDocs(collection(client, "users/owner-a/backups")),
      () => setDoc(doc(client, "users/owner-a/backups/new"), uploadingManifest("new")),
      () => updateDoc(manifest, { completedAt: serverTimestamp() }),
      () => deleteDoc(manifest),
      () => getDoc(chunk),
      () => getDocs(collection(client, "users/owner-a/backups/backup-a/chunks")),
      () => setDoc(doc(client, "users/owner-a/backups/backup-a/chunks/001"), validChunk()),
      () => updateDoc(chunk, { encodedByteCount: 1 }),
      () => deleteDoc(chunk),
    ];
    for (const operation of operations) await assertFails(operation());
  }
});

test("claim and completion require the matching atomic registry transition", async () => {
  const owner = testEnvironment.authenticatedContext("owner-a").firestore();
  const { user, manifest, chunk } = backupRefs(owner);
  await assertSucceeds(setDoc(user, userMetadata()));

  await assertFails(updateDoc(user, { generation: 1 }));
  await assertFails(setDoc(manifest, uploadingManifest()));
  await assertSucceeds(claimUpload(owner));
  await assertFails(claimUpload(owner, "backup-b"));
  await assertFails(
    updateDoc(manifest, { state: "COMPLETE", completedAt: serverTimestamp() }),
  );
  await assertFails(
    updateDoc(user, {
      generation: 1,
      activeUploadBackupId: null,
      latestCompleteBackupId: "backup-a",
      latestCompletedAt: serverTimestamp(),
      latestSourceInstallationId: "installation-a",
    }),
  );
  await assertFails(completeUpload(owner));
  await assertSucceeds(setDoc(chunk, validChunk()));
  await assertSucceeds(completeUpload(owner));
});

test("malformed schema, orphan chunks, and forbidden lifecycle transitions are denied", async () => {
  const owner = testEnvironment.authenticatedContext("owner-a").firestore();
  const { user, manifest, chunk } = backupRefs(owner);

  await assertFails(setDoc(user, { ...userMetadata(), unexpected: true }));
  await assertSucceeds(setDoc(user, userMetadata()));
  await assertFails(claimUpload(owner, "backup-a", [42]));
  await assertSucceeds(claimUpload(owner));
  await assertFails(updateDoc(manifest, { chunkCount: 2 }));
  await assertFails(updateDoc(manifest, { entityCounts: { WeeklyPlan: -1 } }));
  await assertFails(
    setDoc(chunk, { ...validChunk(), encodedByteCount: 768_001 }),
  );
  await assertFails(
    setDoc(doc(owner, "users/owner-a/backups/backup-a/chunks/001"), {
      ...validChunk(),
      chunkIndex: 1,
    }),
  );
  await assertFails(
    setDoc(doc(owner, "users/owner-a/backups/backup-a/chunks/001"), validChunk()),
  );
  await assertFails(
    setDoc(doc(owner, "users/owner-a/backups/missing/chunks/000"), validChunk()),
  );
  await assertFails(deleteDoc(manifest));
  await assertSucceeds(setDoc(chunk, validChunk()));
  await assertSucceeds(completeUpload(owner));
  await assertFails(removeManifestFromRegistry(owner));
  await assertFails(
    setDoc(doc(owner, "users/owner-a/backups/backup-a/chunks/001"), {
      ...validChunk(),
      chunkIndex: 1,
    }),
  );
  await assertFails(setDoc(doc(owner, "users/owner-a/private/value"), { value: true }));
});

test("only an expired interrupted upload can be reclaimed after its chunks are removed", async () => {
  await seedOwnerGraph({ withChunk: true });
  const owner = testEnvironment.authenticatedContext("owner-a").firestore();

  await assertFails(abandonUpload(owner));
  await assertSucceeds(
    deleteDoc(doc(owner, "users/owner-a/backups/backup-a/chunks/000")),
  );
  await assertSucceeds(deleteDoc(doc(owner, "users/owner-a/backups/backup-a")));
  await assertSucceeds(abandonUpload(owner));
});

test("a fresh interrupted upload cannot be reclaimed by another signed-in device", async () => {
  const owner = testEnvironment.authenticatedContext("owner-a").firestore();
  const { user, manifest, chunk } = backupRefs(owner);
  await assertSucceeds(setDoc(user, userMetadata()));
  await assertSucceeds(claimUpload(owner));
  await assertSucceeds(setDoc(chunk, validChunk()));

  await assertFails(deleteDoc(chunk));
  await assertFails(deleteDoc(manifest));
  await assertFails(abandonUpload(owner));
});

test("the latest backup cannot be removed while older registry entries remain", async () => {
  await seedCompleteHistory();
  const owner = testEnvironment.authenticatedContext("owner-a").firestore();
  const user = doc(owner, "users/owner-a");
  const latest = doc(owner, "users/owner-a/backups/latest");
  const latestChunk = doc(owner, "users/owner-a/backups/latest/chunks/000");
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(
      doc(context.firestore(), "users/owner-a/backups/latest/chunks/000"),
      validChunk(),
    );
  });
  const batch = writeBatch(owner);
  batch.delete(latest);
  batch.update(user, {
    backupIds: ["old"],
    latestCompleteBackupId: null,
    latestCompletedAt: null,
    latestSourceInstallationId: null,
  });

  await assertFails(deleteDoc(latestChunk));
  await assertFails(deleteDoc(latest));
  await assertFails(batch.commit());
});

test("complete cleanup is retryable after a manifest was deleted separately", async () => {
  await seedCompleteHistory();
  const owner = testEnvironment.authenticatedContext("owner-a").firestore();

  await assertSucceeds(deleteDoc(doc(owner, "users/owner-a/backups/old")));
  await assertSucceeds(
    updateDoc(doc(owner, "users/owner-a"), { backupIds: ["latest"] }),
  );
  await assertSucceeds(deleteDoc(doc(owner, "users/owner-a/backups/latest")));
  await assertSucceeds(removeManifestFromRegistry(owner, "latest"));
});

test("cleanup cannot reorder the surviving backup registry", async () => {
  await seedCompleteHistory();
  const owner = testEnvironment.authenticatedContext("owner-a").firestore();
  const user = doc(owner, "users/owner-a");
  const old = doc(owner, "users/owner-a/backups/old");
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    const firestore = context.firestore();
    const completedAt = Timestamp.fromMillis(1_700_000_100_000);
    await setDoc(doc(firestore, "users/owner-a/backups/middle"), {
      ...uploadingManifest("middle"),
      state: "COMPLETE",
      createdAt: Timestamp.fromMillis(1_700_000_000_000),
      completedAt,
    });
    await updateDoc(doc(firestore, "users/owner-a"), {
      backupIds: ["old", "middle", "latest"],
    });
  });
  const batch = writeBatch(owner);
  batch.delete(old);
  batch.update(user, { backupIds: ["latest", "middle"] });

  await assertFails(batch.commit());
});

test("retention can remove the oldest backup without changing the completion pointer", async () => {
  await seedThreeCompleteBackups();
  const owner = testEnvironment.authenticatedContext("owner-a").firestore();
  const batch = writeBatch(owner);
  batch.delete(doc(owner, "users/owner-a/backups/old"));
  batch.update(doc(owner, "users/owner-a"), {
    backupIds: ["middle", "latest"],
  });

  await assertSucceeds(batch.commit());
});

test("a registered orphan chunk can be recovered but an unregistered orphan cannot", async () => {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    const firestore = context.firestore();
    await setDoc(doc(firestore, "users/owner-a"), {
      ...userMetadata(),
      backupIds: ["orphan"],
    });
    await setDoc(
      doc(firestore, "users/owner-a/backups/orphan/chunks/000"),
      validChunk(),
    );
    await setDoc(
      doc(firestore, "users/owner-a/backups/unregistered/chunks/000"),
      validChunk(),
    );
  });
  const owner = testEnvironment.authenticatedContext("owner-a").firestore();

  await assertFails(
    deleteDoc(doc(owner, "users/owner-a/backups/unregistered/chunks/000")),
  );
  await assertSucceeds(deleteDoc(doc(owner, "users/owner-a/backups/orphan/chunks/000")));
  await assertSucceeds(
    updateDoc(doc(owner, "users/owner-a"), { backupIds: [] }),
  );
});

function backupRefs(firestore, backupId = "backup-a") {
  return {
    user: doc(firestore, "users/owner-a"),
    manifest: doc(firestore, `users/owner-a/backups/${backupId}`),
    chunk: doc(firestore, `users/owner-a/backups/${backupId}/chunks/000`),
  };
}

function claimUpload(firestore, backupId = "backup-a", backupIds = [backupId]) {
  const { user, manifest } = backupRefs(firestore, backupId);
  const batch = writeBatch(firestore);
  batch.update(user, { backupIds, activeUploadBackupId: backupId });
  batch.set(manifest, uploadingManifest(backupId));
  return batch.commit();
}

function completeUpload(firestore, backupId = "backup-a") {
  const { user, manifest } = backupRefs(firestore, backupId);
  const batch = writeBatch(firestore);
  batch.update(manifest, { state: "COMPLETE", completedAt: serverTimestamp() });
  batch.update(user, {
    generation: 1,
    activeUploadBackupId: null,
    latestCompleteBackupId: backupId,
    latestCompletedAt: serverTimestamp(),
    latestSourceInstallationId: "installation-a",
  });
  return batch.commit();
}

function removeManifestFromRegistry(firestore, backupId = "backup-a") {
  const { user } = backupRefs(firestore, backupId);
  return updateDoc(user, {
    backupIds: [],
    latestCompleteBackupId: null,
    latestCompletedAt: null,
    latestSourceInstallationId: null,
  });
}

function abandonUpload(firestore, backupId = "backup-a") {
  const { user } = backupRefs(firestore, backupId);
  return updateDoc(user, { backupIds: [], activeUploadBackupId: null });
}

function userMetadata() {
  return {
    latestCompleteBackupId: null,
    generation: 0,
    backupIds: [],
    activeUploadBackupId: null,
    latestCompletedAt: null,
    latestSourceInstallationId: null,
  };
}

function uploadingManifest(backupId = "backup-a") {
  return {
    backupId,
    formatVersion: 1,
    appVersion: "1.0",
    sourceInstallationId: "installation-a",
    state: "UPLOADING",
    createdAt: serverTimestamp(),
    completedAt: null,
    chunkCount: 1,
    encodedByteCount: 256,
    entityCounts: {
      WeeklyPlan: 1,
      PlannedWorkout: 0,
      PlannedExercise: 0,
      WorkoutLog: 0,
      LoggedExercise: 0,
      LoggedSet: 0,
      PersonalRecord: 0,
    },
    contentDigest: "a".repeat(64),
    capturedLocalRevision: 7,
    observedRemoteGeneration: 0,
  };
}

function validChunk() {
  return {
    formatVersion: 1,
    chunkIndex: 0,
    encodedByteCount: 256,
    chunkDigest: "b".repeat(64),
    payload: "{\"formatVersion\":1,\"chunkIndex\":0,\"entities\":[]}",
  };
}

async function seedOwnerGraph({ withChunk = false } = {}) {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    const firestore = context.firestore();
    await setDoc(doc(firestore, "users/owner-a"), {
      ...userMetadata(),
      backupIds: ["backup-a"],
      activeUploadBackupId: "backup-a",
    });
    await setDoc(
      doc(firestore, "users/owner-a/backups/backup-a"),
      {
        ...uploadingManifest(),
        createdAt: Timestamp.fromMillis(1_700_000_000_000),
      },
    );
    if (withChunk) {
      await setDoc(
        doc(firestore, "users/owner-a/backups/backup-a/chunks/000"),
        validChunk(),
      );
    }
  });
}

async function seedCompleteHistory() {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    const firestore = context.firestore();
    const completedAt = Timestamp.fromMillis(1_700_000_100_000);
    await setDoc(doc(firestore, "users/owner-a"), {
      ...userMetadata(),
      generation: 2,
      backupIds: ["old", "latest"],
      latestCompleteBackupId: "latest",
      latestCompletedAt: completedAt,
      latestSourceInstallationId: "installation-a",
    });
    for (const backupId of ["old", "latest"]) {
      await setDoc(doc(firestore, `users/owner-a/backups/${backupId}`), {
        ...uploadingManifest(backupId),
        state: "COMPLETE",
        createdAt: Timestamp.fromMillis(1_700_000_000_000),
        completedAt,
      });
    }
  });
}

async function seedThreeCompleteBackups() {
  await seedCompleteHistory();
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    const firestore = context.firestore();
    const completedAt = Timestamp.fromMillis(1_700_000_100_000);
    await setDoc(doc(firestore, "users/owner-a/backups/middle"), {
      ...uploadingManifest("middle"),
      state: "COMPLETE",
      createdAt: Timestamp.fromMillis(1_700_000_000_000),
      completedAt,
    });
    await updateDoc(doc(firestore, "users/owner-a"), {
      backupIds: ["old", "middle", "latest"],
    });
  });
}
