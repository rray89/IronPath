import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { after, before, beforeEach, test } from "node:test";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, serverTimestamp, setDoc, writeBatch } from "firebase/firestore";
import { DeletionService, startWorker } from "../service/deletion-service.mjs";
import { createDeletionServer } from "../service/http-server.mjs";

const projectId = "demo-ironpath-deletion";
let app, auth, firestore, environment;
let instant;
const metadata = { generation: 0, backupIds: [], activeUploadBackupId: null, latestCompleteBackupId: null, latestCompletedAt: null, latestSourceInstallationId: null };

before(async () => {
  assert.equal(process.env.FIREBASE_AUTH_EMULATOR_HOST, "127.0.0.1:9197");
  assert.equal(process.env.FIRESTORE_EMULATOR_HOST, "127.0.0.1:8187");
  process.env.METADATA_SERVER_DETECTION = "none";
  app = initializeApp({ projectId });
  auth = getAuth(app);
  firestore = getFirestore(app);
  environment = await initializeTestEnvironment({ projectId, firestore: { host: "127.0.0.1", port: 8187 } });
});
beforeEach(async () => {
  instant = Date.now();
  await environment.clearFirestore();
  const result = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/emulator/v1/projects/${projectId}/accounts`, { method: "DELETE" });
  assert.equal(result.status, 200);
});
after(async () => {
  await environment?.cleanup();
  await deleteApp(app);
});

function service(overrides = {}) {
  return new DeletionService({ firestore, auth, projectId, now: () => instant, ...overrides });
}

async function googleIdentity(subject = randomUUID()) {
  const postBody = new URLSearchParams({ providerId: "google.com", id_token: JSON.stringify({ sub: subject, email: `${subject}@example.test`, email_verified: true }) });
  const response = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=demo-key`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ postBody: postBody.toString(), requestUri: "http://localhost", returnSecureToken: true }),
  });
  const value = await response.json();
  assert.equal(response.status, 200, JSON.stringify(value));
  // Match the fixed test clock to the emulator-issued authentication second.
  instant = JSON.parse(Buffer.from(value.idToken.split(".")[1], "base64url").toString()).auth_time * 1000;
  return { uid: value.localId, token: value.idToken, subject };
}

async function seed(uid) {
  const root = firestore.doc(`users/${uid}`);
  await root.set({ backupIds: "corrupt registry", formatVersion: 999 });
  await root.collection("backups").doc("malformed").set({ state: "INVALID" });
  await root.collection("backups").doc("malformed").collection("chunks").doc("900").set({ payload: "synthetic" });
  await root.collection("backups").doc("missing-parent").collection("chunks").doc("orphan").set({ payload: "unregistered" });
  await root.collection("unknown").doc("missing").collection("deeper").doc("descendant").set({ value: "synthetic" });
  return root;
}

async function noAuth(uid) {
  await assert.rejects(auth.getUser(uid), (error) => error.code === "auth/user-not-found");
}

test("verified Google identity owns atomic durable job and fence; all malformed/orphan descendants are purged before Auth", async () => {
  const owner = await googleIdentity();
  const other = await googleIdentity();
  const root = await seed(owner.uid);
  await seed(other.uid);
  let deleted = false;
  const subject = service({ auth: {
    verifyIdToken: (...args) => auth.verifyIdToken(...args),
    deleteUser: async (uid) => {
      await service().assertPurged(root);
      assert.equal(uid, owner.uid);
      deleted = true;
      return auth.deleteUser(uid);
    },
  } });
  const operationId = randomUUID();
  assert.deepEqual(await subject.start(operationId, owner.token), { operationId, state: "PENDING" });
  assert.equal((await subject.tombstones.doc(owner.uid).get()).data().operationId, operationId);
  assert.equal((await auth.getUser(owner.uid)).uid, owner.uid);
  const job = (await subject.jobs.doc(operationId).get()).data();
  assert.deepEqual(Object.keys(job).sort(), ["createdAt", "failures", "nextAttemptAt", "state", "uid"]);
  await subject.runPending();
  assert.equal(deleted, true);
  await noAuth(owner.uid);
  assert.deepEqual(await subject.resume(operationId), { operationId, state: "COMPLETE" });
  assert.equal((await firestore.doc(`users/${other.uid}`).get()).exists, true);
  assert.equal((await auth.getUser(other.uid)).uid, other.uid);
});

test("empty account deletion succeeds and same Google identity returns with fresh UID while old UID is forever fenced", async () => {
  const owner = await googleIdentity();
  const subject = service();
  const operationId = randomUUID();
  await subject.start(operationId, owner.token);
  await subject.runPending();
  const replacement = await googleIdentity(owner.subject);
  assert.notEqual(replacement.uid, owner.uid);
  const oldClient = environment.authenticatedContext(owner.uid).firestore();
  await assertFails(setDoc(doc(oldClient, `users/${owner.uid}`), metadata));
  // Even privileged recreation of the exact old UID cannot remove its fence.
  await auth.createUser({ uid: owner.uid });
  await assertFails(setDoc(doc(oldClient, `users/${owner.uid}`), metadata));
  const newClient = environment.authenticatedContext(replacement.uid).firestore();
  await assertSucceeds(setDoc(doc(newClient, `users/${replacement.uid}`), metadata));
  await assertFails(getDoc(doc(newClient, `users/${owner.uid}`)));
  assert.equal((await subject.tombstones.doc(owner.uid).get()).exists, true);
});

test("duplicate and concurrent start are idempotent; wrong account cannot steal an incarnation", async () => {
  const owner = await googleIdentity();
  const other = await googleIdentity();
  const subject = service();
  const operationId = randomUUID();
  const receipts = await Promise.all([subject.start(operationId, owner.token), subject.start(operationId, owner.token)]);
  assert.deepEqual(receipts[0], receipts[1]);
  await assert.rejects(subject.start(operationId, other.token), (error) => error.status === 409);
  assert.equal((await subject.jobs.get()).size, 1);
  assert.equal((await subject.tombstones.get()).size, 1);
  await Promise.all([subject.runPending(), service().runPending()]);
  assert.equal((await subject.resume(operationId)).state, "COMPLETE");
});

test("another confirmed operation for the same UID gets its own durable alias without restarting deletion", async () => {
  const owner = await googleIdentity();
  const other = await googleIdentity();
  await seed(other.uid);
  const removed = [];
  const subject = service({ auth: {
    verifyIdToken: (...args) => auth.verifyIdToken(...args),
    deleteUser: async (uid) => { removed.push(uid); return auth.deleteUser(uid); },
  } });
  const canonical = randomUUID();
  const alias = randomUUID();
  await subject.start(canonical, owner.token);
  assert.deepEqual(await subject.start(alias, owner.token), { operationId: alias, state: "PENDING" });
  await assert.rejects(subject.start(alias, other.token), (error) => error.status === 409);
  assert.equal((await subject.tombstones.doc(owner.uid).get()).data().operationId, canonical);
  assert.deepEqual(await subject.resume(alias), { operationId: alias, state: "PENDING" });
  await subject.runPending();
  assert.deepEqual(await service().resume(alias), { operationId: alias, state: "COMPLETE" });
  assert.equal((await subject.tombstones.get()).size, 1);
  assert.deepEqual(removed, [owner.uid]);
  assert.equal((await auth.getUser(other.uid)).uid, other.uid);
  assert.equal((await firestore.doc(`users/${other.uid}`).get()).exists, true);
  await noAuth(owner.uid);
});

test("recent signed token after Auth removal can only attach a receipt to completed canonical deletion", async () => {
  const owner = await googleIdentity();
  const verified = new Map([[owner.token, await auth.verifyIdToken(owner.token)]]);
  // Admin's Auth-emulator mode forces a user lookup even for checkRevoked=false.
  // Production does not. Only this signature-only verifier result is doubled,
  // using claims already verified by the real Admin SDK before deleting Auth.
  const subject = service({ auth: {
    verifyIdToken: async (token, checkRevoked) => {
      if (checkRevoked) return auth.verifyIdToken(token, true);
      assert.equal(verified.has(token), true);
      return verified.get(token);
    },
    deleteUser: (...args) => auth.deleteUser(...args),
  } });
  const canonical = randomUUID();
  const alias = randomUUID();
  await subject.start(canonical, owner.token);
  await auth.deleteUser(owner.uid); // interrupted before the worker marks completion
  await assert.rejects(subject.start(alias, owner.token), (error) => error.status === 401);
  assert.equal((await subject.jobs.doc(alias).get()).exists, false);
  await subject.runPending();
  assert.deepEqual(await subject.start(alias, owner.token), { operationId: alias, state: "COMPLETE" });
  assert.deepEqual(await subject.resume(alias), { operationId: alias, state: "COMPLETE" });
  assert.equal((await subject.tombstones.get()).size, 1);
  instant += 301000;
  await assert.rejects(subject.start(randomUUID(), owner.token), (error) => error.status === 401);
  const neverAccepted = await googleIdentity();
  verified.set(neverAccepted.token, await auth.verifyIdToken(neverAccepted.token));
  await auth.deleteUser(neverAccepted.uid);
  await assert.rejects(subject.start(randomUUID(), neverAccepted.token), (error) => error.status === 401);
  assert.equal((await subject.tombstones.doc(neverAccepted.uid).get()).exists, false);
});

test("invalid, missing, stale, future and non-Google credentials create neither job nor fence", async () => {
  const owner = await googleIdentity();
  const claims = await auth.verifyIdToken(owner.token);
  for (const token of [undefined, "garbage", ""]) {
    await assert.rejects(service().start(randomUUID(), token), (error) => error.status === 401);
  }
  const parts = owner.token.split(".");
  const foreignClaims = { ...claims, aud: "demo-foreign-project", iss: "https://securetoken.google.com/demo-foreign-project" };
  const foreignToken = `${parts[0]}.${Buffer.from(JSON.stringify(foreignClaims)).toString("base64url")}.${parts[2]}`;
  await assert.rejects(service().start(randomUUID(), foreignToken), (error) => error.status === 401);
  instant = (claims.auth_time + 301) * 1000;
  await assert.rejects(service().start(randomUUID(), owner.token), (error) => error.status === 401);
  instant = (claims.auth_time - 1) * 1000;
  await assert.rejects(service().start(randomUUID(), owner.token), (error) => error.status === 401);
  instant = Date.now();
  const response = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-key`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: "password@example.test", password: "synthetic-only-123", returnSecureToken: true }),
  });
  const password = await response.json();
  assert.equal(response.status, 200);
  await assert.rejects(service().start(randomUUID(), password.idToken), (error) => error.status === 401);
  assert.equal((await service().jobs.get()).empty, true);
  assert.equal((await service().tombstones.get()).empty, true);
});

test("recent reauthentication includes exactly 300 seconds, but disabled users are rejected", async () => {
  const owner = await googleIdentity();
  const claims = await auth.verifyIdToken(owner.token);
  instant = (claims.auth_time + 300) * 1000;
  await service().start(randomUUID(), owner.token);
  await auth.updateUser(owner.uid, { disabled: true });
  await assert.rejects(service().start(randomUUID(), owner.token), (error) => error.status === 401);
});

test("Admin revocation verification rejects a revoked Google token before writing a fence", async () => {
  const owner = await googleIdentity();
  const claims = await auth.verifyIdToken(owner.token);
  // Set the emulator's real revocation boundary deterministically, without sleeping.
  const response = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/projects/${projectId}/accounts:update`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: "Bearer owner" },
    body: JSON.stringify({ localId: owner.uid, validSince: String(claims.auth_time + 1) }),
  });
  assert.equal(response.status, 200);
  await assert.rejects(service().start(randomUUID(), owner.token), (error) => error.status === 401);
  assert.equal((await service().jobs.get()).empty, true);
  assert.equal((await service().tombstones.get()).empty, true);
});

test("partial purge failure preserves Auth and pending receipt, then new worker automatically resumes", async () => {
  const owner = await googleIdentity();
  const root = await seed(owner.uid);
  const failingStore = new Proxy(firestore, { get(target, key) {
    if (key === "recursiveDelete") return async () => { await root.delete(); throw new Error("synthetic interruption"); };
    const value = Reflect.get(target, key);
    return typeof value === "function" ? value.bind(target) : value;
  } });
  const subject = service({ firestore: failingStore });
  const operationId = randomUUID();
  await subject.start(operationId, owner.token);
  await subject.runPending();
  assert.equal((await subject.resume(operationId)).state, "PENDING");
  assert.equal((await auth.getUser(owner.uid)).uid, owner.uid);
  assert.equal((await root.collection("backups").doc("missing-parent").collection("chunks").doc("orphan").get()).exists, true);
  instant += 10000;
  const restarted = service();
  const stop = startWorker(restarted);
  await stop(); // waits for the autonomous startup sweep; no resume HTTP request
  assert.equal((await restarted.resume(operationId)).state, "COMPLETE");
  await noAuth(owner.uid);
});

test("purge verification rejects a silently incomplete delete including missing-parent descendants", async () => {
  const owner = await googleIdentity();
  const root = await seed(owner.uid);
  const lyingStore = new Proxy(firestore, { get(target, key) {
    if (key === "recursiveDelete") return async () => root.delete();
    const value = Reflect.get(target, key);
    return typeof value === "function" ? value.bind(target) : value;
  } });
  const subject = service({ firestore: lyingStore });
  const operationId = randomUUID();
  await subject.start(operationId, owner.token);
  await subject.runPending();
  assert.equal((await subject.resume(operationId)).state, "PENDING");
  assert.equal((await auth.getUser(owner.uid)).uid, owner.uid);
});

test("interruption after Auth removal resumes using durable capability without a token", async () => {
  const owner = await googleIdentity();
  const operationId = randomUUID();
  const subject = service({ auth: {
    verifyIdToken: (...args) => auth.verifyIdToken(...args),
    deleteUser: async (uid) => { await auth.deleteUser(uid); throw new Error("process died before receipt commit"); },
  } });
  await subject.start(operationId, owner.token);
  await subject.runPending();
  await noAuth(owner.uid);
  assert.equal((await subject.resume(operationId)).state, "PENDING");
  instant += 10000;
  await service().runPending();
  assert.deepEqual(await service().resume(operationId), { operationId, state: "COMPLETE" });
});

test("fence linearizes upload race: pre-fence data is purged; claim, chunk, completion and stale-owner reads are denied afterwards", async () => {
  const owner = await googleIdentity();
  const client = environment.authenticatedContext(owner.uid).firestore();
  const user = doc(client, `users/${owner.uid}`);
  const manifest = doc(client, `users/${owner.uid}/backups/race`);
  const chunk = doc(client, `users/${owner.uid}/backups/race/chunks/000`);
  await setDoc(user, metadata);
  const data = { backupId: "race", formatVersion: 1, appVersion: "1", sourceInstallationId: "synthetic", state: "UPLOADING", createdAt: serverTimestamp(), completedAt: null, chunkCount: 1, encodedByteCount: 2, entityCounts: { WeeklyPlan: 0, PlannedWorkout: 0, PlannedExercise: 0, WorkoutLog: 0, LoggedExercise: 0, LoggedSet: 0, PersonalRecord: 0 }, contentDigest: "a".repeat(64), capturedLocalRevision: 0, observedRemoteGeneration: 0 };
  const claim = writeBatch(client);
  claim.update(user, { backupIds: ["race"], activeUploadBackupId: "race" });
  claim.set(manifest, data);
  await assertSucceeds(claim.commit());
  const chunkData = { formatVersion: 1, chunkIndex: 0, encodedByteCount: 2, chunkDigest: "b".repeat(64), payload: "{}" };
  await assertSucceeds(setDoc(chunk, chunkData));
  const subject = service();
  const operationId = randomUUID();
  await subject.start(operationId, owner.token);
  const completion = writeBatch(client);
  completion.update(manifest, { state: "COMPLETE", completedAt: serverTimestamp() });
  completion.update(user, { generation: 1, activeUploadBackupId: null, latestCompleteBackupId: "race", latestCompletedAt: serverTimestamp(), latestSourceInstallationId: "synthetic" });
  await assertFails(completion.commit());
  await assertFails(getDoc(chunk));
  await assertFails(getDoc(manifest));
  await assertFails(getDoc(user));
  await assertFails(getDocs(collection(client, `users/${owner.uid}/backups`)));
  await assertFails(deleteDoc(user));
  // Model server purge racing a late upload. The identical payload succeeded
  // above; the manifest is still UPLOADING and this document is now absent, so
  // create would otherwise be allowed rather than denied as an immutable update.
  const adminChunk = firestore.doc(chunk.path);
  await adminChunk.delete();
  assert.equal((await adminChunk.get()).exists, false);
  assert.equal((await auth.verifyIdToken(owner.token, true)).uid, owner.uid);
  await assertFails(setDoc(chunk, chunkData));
  assert.equal((await adminChunk.get()).exists, false);
  await subject.runPending();
  await assertFails(setDoc(user, metadata));
  await service().assertPurged(firestore.doc(`users/${owner.uid}`));
});

test("a simultaneous user write and fence cannot leave data after completed deletion", async () => {
  const owner = await googleIdentity();
  const client = environment.authenticatedContext(owner.uid).firestore();
  const subject = service();
  const operationId = randomUUID();
  const [write, fence] = await Promise.allSettled([
    setDoc(doc(client, `users/${owner.uid}`), metadata),
    subject.start(operationId, owner.token),
  ]);
  assert.equal(fence.status, "fulfilled");
  if (write.status === "rejected") assert.equal(write.reason.code, "permission-denied");
  await subject.runPending();
  await subject.assertPurged(firestore.doc(`users/${owner.uid}`));
  assert.equal((await subject.resume(operationId)).state, "COMPLETE");
  await assertFails(setDoc(doc(client, `users/${owner.uid}`), metadata));
});

test("HTTP contract validates exact bodies, never trusts a UID and returns coarse recovery status only", async () => {
  const owner = await googleIdentity();
  const subject = service();
  const server = createDeletionServer(subject);
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const url = `http://127.0.0.1:${server.address().port}`;
    const call = (path, body, token = owner.token) => fetch(url + path, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` }, body: JSON.stringify(body) });
    const capabilities = await (await fetch(url + "/v1/capabilities")).json();
    assert.deepEqual(capabilities, { protocol: "ironpath-account-deletion-v1", projectId, authoritative: true, resumable: true });
    const operationId = randomUUID();
    assert.equal((await call("/v1/deletions", { operationId, uid: "victim" })).status, 400);
    assert.equal((await call("/v1/deletions", { operationId: "not-uuid" })).status, 400);
    assert.equal((await call("/v1/deletions", { operationId }, "invalid")).status, 401);
    assert.equal((await call(`/v1/deletions/${operationId}/resume`, {})).status, 404);
    const start = await call("/v1/deletions", { operationId });
    assert.equal(start.status, 202);
    assert.deepEqual(await start.json(), { operationId, state: "PENDING" });
    await subject.runPending();
    const resume = await fetch(`${url}/v1/deletions/${operationId}/resume`, { method: "POST" });
    assert.equal(resume.status, 200);
    assert.equal(resume.headers.get("cache-control"), "no-store");
    assert.deepEqual(await resume.json(), { operationId, state: "COMPLETE" });
    assert.equal((await call(`/v1/deletions/${operationId}/resume`, { uid: "victim" })).status, 400);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test("verification service outage maps to 503 without creating destructive state", async () => {
  const subject = service({ auth: { verifyIdToken: async () => { throw Object.assign(new Error("synthetic backend outage"), { code: "auth/internal-error" }); } } });
  await assert.rejects(subject.start(randomUUID(), "opaque"), (error) => error.status === 503);
  assert.equal((await subject.jobs.get()).empty, true);
  assert.equal((await subject.tombstones.get()).empty, true);
});

test("fresh server process autonomously completes an accepted job with no client or recovery request", async () => {
  const owner = await googleIdentity();
  await seed(owner.uid);
  const operationId = randomUUID();
  await service().start(operationId, owner.token);
  const child = spawn(process.execPath, ["service/main.mjs"], { cwd: process.cwd(), env: { ...process.env, IRONPATH_DELETION_MODE: "emulator", GCLOUD_PROJECT: projectId, PORT: "0" }, stdio: "pipe" });
  let unsubscribe;
  try {
    await new Promise((resolve, reject) => {
      const deadline = setTimeout(() => reject(new Error("autonomous worker deadline")), 15000);
      unsubscribe = firestore.doc(`accountDeletionJobs/${operationId}`).onSnapshot((snapshot) => {
        if (snapshot.data()?.state === "COMPLETE") { clearTimeout(deadline); resolve(); }
      }, (error) => { clearTimeout(deadline); reject(error); });
      child.once("exit", () => { clearTimeout(deadline); reject(new Error("worker exited before completion")); });
    });
    await noAuth(owner.uid);
    assert.equal((await service().resume(operationId)).state, "COMPLETE");
  } finally {
    unsubscribe?.();
    child.kill("SIGTERM");
    await once(child, "exit");
  }
});

test("entrypoint refuses absent mode, production project and non-loopback emulators before startup", async () => {
  for (const override of [{ IRONPATH_DELETION_MODE: "" }, { IRONPATH_DELETION_MODE: "emulator", GCLOUD_PROJECT: "live-project" }, { IRONPATH_DELETION_MODE: "emulator", FIRESTORE_EMULATOR_HOST: "remote.invalid:8187" }]) {
    const child = spawn(process.execPath, ["service/main.mjs"], { cwd: process.cwd(), env: { ...process.env, GCLOUD_PROJECT: projectId, ...override }, stdio: "ignore" });
    const [code] = await once(child, "exit");
    assert.notEqual(code, 0);
  }
});
