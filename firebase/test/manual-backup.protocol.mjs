import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import {
  deleteDoc,
  doc,
  getDoc,
  getDocFromServer,
  runTransaction,
  serverTimestamp,
  setDoc,
} from "firebase/firestore";

const fixtures = JSON.parse(
  await readFile(new URL("./fixtures/manual-backup-v1.json", import.meta.url), "utf8"),
);
const entityTypes = [
  "WeeklyPlan", "PlannedWorkout", "PlannedExercise", "WorkoutLog",
  "LoggedExercise", "LoggedSet", "PersonalRecord",
];
const maxChunkBytes = 750 * 1024;

/** Emulator protocol evidence only. The Android debug adapter uses local files, not this SDK. */
export function registerManualProtocolTests(environment) {
  const owner = (installation = "installation-a") =>
    new EmulatorManualBackupStore(
      environment().authenticatedContext("manual-owner").firestore(),
      "manual-owner",
      installation,
    );

  test("manual protocol uploads Kotlin-codec bytes and survives a new client", async () => {
    const first = owner();
    assert.equal(await first.latest(), null);
    const completed = await first.publish(fixtures.base, 0, "base");
    assert.equal(completed.generation, 1);
    assert.deepEqual(completed.snapshot, fixtures.base);
    const recreated = owner();
    assert.deepEqual(await recreated.latest(), completed);
    const metadata = (await getDoc(recreated.user)).data();
    assert.equal(metadata.activeUploadBackupId, null);
    assert.deepEqual(metadata.backupIds, ["base"]);
    assert.equal(metadata.latestCompleteBackupId, "base");
  });

  test("manual protocol publishes the reviewed merge preserving both installations' changes", async () => {
    const deviceA = owner("installation-a");
    const deviceB = owner("installation-b");
    await deviceA.publish(fixtures.base, 0, "base");
    await deviceB.publish(fixtures.remote, 1, "remote-change");
    const beforeConfirmation = await deviceA.latest();
    assert.deepEqual(beforeConfirmation.snapshot, fixtures.remote);
    assert.equal(beforeConfirmation.generation, 2);

    // The app merger is verified on the JVM; this is its reviewed result crossing real rules.
    const merged = await deviceA.publish(fixtures.merged, 2, "confirmed-merge");
    const records = entities(merged.snapshot);
    assert.equal(records.find((record) => record.id === "record-squat").payload.weightKg, 110.5);
    assert.equal(records.find((record) => record.id === "record-bench").payload.weightKg, 65.5);
    assert.equal(merged.generation, 3);
    assert.equal(merged.source, "installation-a");
    assert.deepEqual((await deviceB.latest()).snapshot, fixtures.merged);
    assert.deepEqual((await getDoc(deviceA.user)).data().backupIds, ["remote-change", "confirmed-merge"]);
    assert.equal((await getDoc(deviceA.manifest("base"))).exists(), false);
    assert.equal((await getDoc(deviceA.chunk("base", 0))).exists(), false);
  });

  test("manual protocol stale generation and simultaneous claims never force-write", async () => {
    const deviceA = owner("installation-a");
    const deviceB = owner("installation-b");
    await deviceA.publish(fixtures.base, 0, "base");
    const attempts = await Promise.allSettled([
      deviceA.publish(fixtures.local, 1, "local-winner"),
      deviceB.publish(fixtures.remote, 1, "remote-winner"),
    ]);
    assert.equal(attempts.filter((result) => result.status === "fulfilled").length, 1);
    const failure = attempts.find((result) => result.status === "rejected");
    assert.ok(failure.reason instanceof GenerationConflict, `Unexpected race failure: ${failure.reason?.name} ${failure.reason?.code} ${failure.reason?.message}`);
    const current = await deviceA.latest();
    assert.equal(current.generation, 2);
    await assert.rejects(deviceB.publish(fixtures.merged, 1, "stale-confirmation"), GenerationConflict);
    assert.deepEqual(await deviceA.latest(), current);
    assert.equal((await getDoc(deviceA.manifest("stale-confirmation"))).exists(), false);
  });

  test("manual protocol interruption leaves only the previous COMPLETE snapshot visible", async () => {
    const store = owner();
    const previous = await store.publish(fixtures.base, 0, "base");
    await assert.rejects(
      store.publish(fixtures.local, 1, "interrupted", {
        afterChunk() { throw new Error("simulated process interruption"); },
      }),
      /simulated process interruption/,
    );
    assert.deepEqual(await owner().latest(), previous);
    const metadata = (await getDoc(store.user)).data();
    assert.equal(metadata.generation, 1);
    assert.equal(metadata.activeUploadBackupId, "interrupted");
    assert.equal((await getDoc(store.manifest("interrupted"))).data().state, "UPLOADING");
    await assert.rejects(owner("installation-b").publish(fixtures.remote, 1, "blocked"), GenerationConflict);
    assert.deepEqual(await store.latest(), previous);
  });

  test("manual protocol validates digest before completion even when rules accept the bounded chunk", async () => {
    const store = owner();
    const previous = await store.publish(fixtures.base, 0, "base");
    await assert.rejects(
      store.publish(fixtures.local, 1, "tampered", {
        chunk(document) { return { ...document, chunkDigest: "0".repeat(64) }; },
      }),
      /Chunk digest mismatch/,
    );
    assert.deepEqual(await store.latest(), previous);
    assert.equal((await getDoc(store.manifest("tampered"))).data().state, "UPLOADING");
  });

  test("manual protocol read and publication are denied to guest and cross-account clients", async () => {
    const store = owner();
    const previous = await store.publish(fixtures.base, 0, "base");
    for (const client of [
      environment().unauthenticatedContext().firestore(),
      environment().authenticatedContext("different-owner").firestore(),
    ]) {
      const denied = new EmulatorManualBackupStore(client, "manual-owner", "intruder");
      await assert.rejects(denied.latest(), (error) => error.code === "permission-denied");
      await assert.rejects(denied.publish(fixtures.local, 1, "denied"), (error) => error.code === "permission-denied");
    }
    assert.deepEqual(await store.latest(), previous);
    assert.equal((await getDoc(store.manifest("denied"))).exists(), false);
  });

  test("manual protocol rejects oversized, extra-chunk, and future-format input before claiming", async () => {
    const store = owner();
    const oversized = structuredClone(fixtures.base);
    oversized.chunks[0].payload = "x".repeat(maxChunkBytes + 1);
    oversized.chunks[0].encodedByteCount = maxChunkBytes + 1;
    const tooMany = { ...fixtures.base, chunks: Array(7).fill(fixtures.base.chunks[0]) };
    const future = { ...fixtures.base, formatVersion: 2 };
    for (const snapshot of [oversized, tooMany, future]) {
      await assert.rejects(store.publish(snapshot, 0, "invalid"));
    }
    assert.equal((await getDoc(store.user)).exists(), false);
  });
}

class GenerationConflict extends Error {}

/** A test-only Firestore adapter for the shared claim/chunk/CAS/retention protocol. */
class EmulatorManualBackupStore {
  constructor(firestore, uid, installation) {
    this.firestore = firestore;
    this.uid = uid;
    this.installation = installation;
    this.user = doc(firestore, `users/${uid}`);
  }

  manifest(id) { return doc(this.firestore, `users/${this.uid}/backups/${id}`); }
  chunk(id, index) {
    return doc(this.firestore, `users/${this.uid}/backups/${id}/chunks/${String(index).padStart(3, "0")}`);
  }

  async latest() {
    const metadata = await getDoc(this.user);
    if (!metadata.exists() || metadata.data().latestCompleteBackupId === null) return null;
    const pointer = metadata.data();
    const id = pointer.latestCompleteBackupId;
    const manifest = (await getDoc(this.manifest(id))).data();
    assert.equal(manifest.state, "COMPLETE");
    assert.equal(manifest.observedRemoteGeneration + 1, pointer.generation);
    assert.equal(manifest.sourceInstallationId, pointer.latestSourceInstallationId);
    assert.equal(manifest.completedAt.toMillis(), pointer.latestCompletedAt.toMillis());
    return { id, generation: pointer.generation, source: manifest.sourceInstallationId, snapshot: await this.readSnapshot(id, manifest) };
  }

  async publish(snapshot, expectedGeneration, id, hooks = {}) {
    validateSnapshot(snapshot);
    await runTransaction(this.firestore, async (transaction) => {
      if (!(await transaction.get(this.user)).exists()) transaction.set(this.user, emptyMetadata());
    });
    await runTransaction(this.firestore, async (transaction) => {
      const current = (await transaction.get(this.user)).data();
      if (current.generation !== expectedGeneration || current.activeUploadBackupId !== null) throw new GenerationConflict();
      transaction.set(this.manifest(id), {
        backupId: id,
        formatVersion: snapshot.formatVersion,
        appVersion: "emulator-protocol-test",
        sourceInstallationId: this.installation,
        state: "UPLOADING",
        createdAt: serverTimestamp(),
        completedAt: null,
        chunkCount: snapshot.chunks.length,
        encodedByteCount: snapshot.encodedByteCount,
        entityCounts: snapshot.entityCounts,
        contentDigest: snapshot.contentDigest,
        capturedLocalRevision: snapshot.localChangeRevision,
        observedRemoteGeneration: expectedGeneration,
      });
      transaction.update(this.user, { backupIds: [...current.backupIds, id], activeUploadBackupId: id });
    }).catch(async (failure) => {
      // Rules can reject a racing stale claim before the SDK retries its transaction.
      // Reclassify only after an authorized, fresh read proves the precondition changed.
      if (failure.code === "permission-denied") {
        let current;
        try {
          current = (await getDocFromServer(this.user)).data();
        } catch {
          throw failure;
        }
        if (current && (current.generation !== expectedGeneration || current.activeUploadBackupId !== null)) {
          throw new GenerationConflict("Remote generation or upload slot changed");
        }
      }
      throw failure;
    });
    for (const chunk of snapshot.chunks) {
      const document = {
        formatVersion: snapshot.formatVersion,
        chunkIndex: chunk.index,
        encodedByteCount: chunk.encodedByteCount,
        chunkDigest: chunk.digest,
        payload: chunk.payload,
      };
      await setDoc(this.chunk(id, chunk.index), hooks.chunk ? hooks.chunk(document) : document);
      hooks.afterChunk?.();
    }
    const manifest = (await getDoc(this.manifest(id))).data();
    assert.deepEqual(await this.readSnapshot(id, manifest), snapshot);
    await runTransaction(this.firestore, async (transaction) => {
      const current = (await transaction.get(this.user)).data();
      if (current.generation !== expectedGeneration || current.activeUploadBackupId !== id) throw new GenerationConflict();
      transaction.update(this.manifest(id), { state: "COMPLETE", completedAt: serverTimestamp() });
      transaction.update(this.user, {
        generation: expectedGeneration + 1,
        activeUploadBackupId: null,
        latestCompleteBackupId: id,
        latestCompletedAt: serverTimestamp(),
        latestSourceInstallationId: this.installation,
      });
    });
    await this.retainTwo();
    return this.latest();
  }

  async readSnapshot(id, manifest) {
    const chunks = [];
    for (let index = 0; index < manifest.chunkCount; index += 1) {
      const document = (await getDoc(this.chunk(id, index))).data();
      assert.equal(document.formatVersion, manifest.formatVersion);
      chunks.push({ index: document.chunkIndex, payload: document.payload, encodedByteCount: document.encodedByteCount, digest: document.chunkDigest });
    }
    const snapshot = {
      formatVersion: manifest.formatVersion,
      localChangeRevision: manifest.capturedLocalRevision,
      chunks,
      entityCounts: manifest.entityCounts,
      encodedByteCount: manifest.encodedByteCount,
      contentDigest: manifest.contentDigest,
    };
    validateSnapshot(snapshot);
    return snapshot;
  }

  async retainTwo() {
    const metadata = (await getDoc(this.user)).data();
    assert.equal(metadata.activeUploadBackupId, null);
    for (const id of metadata.backupIds.slice(0, -2)) {
      const manifest = (await getDoc(this.manifest(id))).data();
      for (let index = 0; index < manifest.chunkCount; index += 1) await deleteDoc(this.chunk(id, index));
      await deleteDoc(this.manifest(id));
      await runTransaction(this.firestore, async (transaction) => {
        const current = (await transaction.get(this.user)).data();
        assert.equal(current.activeUploadBackupId, null);
        transaction.update(this.user, { backupIds: current.backupIds.filter((candidate) => candidate !== id) });
      });
    }
  }
}

function emptyMetadata() {
  return {
    latestCompleteBackupId: null, generation: 0, backupIds: [], activeUploadBackupId: null,
    latestCompletedAt: null, latestSourceInstallationId: null,
  };
}

function entities(snapshot) {
  return snapshot.chunks.flatMap((chunk) => JSON.parse(chunk.payload).entities);
}

function validateSnapshot(snapshot) {
  assert.equal(snapshot.formatVersion, 1);
  assert.ok(Number.isSafeInteger(snapshot.localChangeRevision) && snapshot.localChangeRevision >= 0);
  assert.ok(snapshot.chunks.length > 0 && snapshot.chunks.length <= 6);
  let byteCount = 0;
  snapshot.chunks.forEach((chunk, index) => {
    assert.equal(chunk.index, index);
    assert.equal(chunk.encodedByteCount, Buffer.byteLength(chunk.payload, "utf8"));
    assert.ok(chunk.encodedByteCount <= maxChunkBytes);
    assert.equal(chunk.digest, digest(chunk.payload), "Chunk digest mismatch");
    const payload = JSON.parse(chunk.payload);
    assert.equal(payload.formatVersion, snapshot.formatVersion);
    assert.equal(payload.localChangeRevision, snapshot.localChangeRevision);
    assert.equal(payload.chunkIndex, index);
    byteCount += chunk.encodedByteCount;
  });
  assert.equal(snapshot.encodedByteCount, byteCount);
  const envelopes = entities(snapshot);
  assert.equal(snapshot.contentDigest, digest(JSON.stringify(envelopes)));
  const counts = Object.fromEntries(entityTypes.map((type) => [type, 0]));
  const keys = new Set();
  for (const envelope of envelopes) {
    assert.ok(entityTypes.includes(envelope.type));
    assert.equal(envelope.entityVersion, 1);
    const key = `${envelope.type}:${envelope.id}`;
    assert.equal(keys.has(key), false);
    keys.add(key);
    counts[envelope.type] += 1;
  }
  assert.deepEqual(snapshot.entityCounts, counts);
}

function digest(value) { return createHash("sha256").update(value, "utf8").digest("hex"); }
