import assert from "node:assert/strict";
import { randomBytes, randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { after, before, beforeEach, test } from "node:test";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, serverTimestamp, setDoc, writeBatch } from "firebase/firestore";
import { DeletionService, startWorker, subjectBinding } from "../service/deletion-service.mjs";
import { createDeletionServer } from "../service/http-server.mjs";

const projectId = "demo-ironpath-deletion";
const serviceInstanceId = "ironpath-deletion-emulator-v2";
const secrets = new Map();
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
  secrets.clear();
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
  const subject = new DeletionService({ firestore, auth, projectId, serviceInstanceId, now: () => instant, ...overrides });
  // Existing purge/fence regression scenarios use a test-only convenience flow.
  // Production exposes only reserve and fresh explicit activation separately.
  subject.start = async (operationId, token) => {
    const secret = receiptSecret(operationId);
    await subject.reserve(operationId, secret, token);
    const result = await subject.activate(operationId, secret, token);
    return { operationId, state: result.state };
  };
  subject.resume = async (operationId) => {
    const result = await subject.status(operationId, receiptSecret(operationId));
    return { operationId, state: result.state };
  };
  return subject;
}

function receiptSecret(operationId) {
  if (!secrets.has(operationId)) secrets.set(operationId, randomBytes(32).toString("base64url"));
  return secrets.get(operationId);
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
  assert.equal(job.kind, "CANONICAL");
  assert.equal(job.activated, true);
  assert.equal(JSON.stringify(job).includes(owner.token), false);
  assert.equal(JSON.stringify(job).includes(receiptSecret(operationId)), false);
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

test("v2 HTTP contract separates reservation from activation and never accepts body UID or v1 starts", async () => {
  const owner = await googleIdentity();
  const subject = service();
  const server = createDeletionServer(subject);
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const url = `http://127.0.0.1:${server.address().port}`;
    const operationId = randomUUID();
    const secret = receiptSecret(operationId);
    const call = (path, body, token, key = secret) => fetch(url + path, { method: "POST",
      headers: { "Content-Type": "application/json", "Deletion-Receipt": key, ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });
    assert.deepEqual(await (await fetch(url + "/v2/capabilities")).json(), {
      protocol: "ironpath-account-deletion-v2", projectId, serviceInstanceId, authoritative: true, resumable: true,
    });
    assert.equal((await call("/v1/deletions", { operationId }, owner.token)).status, 404);
    assert.equal((await call("/v2/reservations", { operationId, uid: "victim" }, owner.token)).status, 400);
    assert.equal((await call("/v2/reservations", { operationId }, "invalid")).status, 401);
    const reserved = await call("/v2/reservations", { operationId }, owner.token);
    assert.equal(reserved.status, 200);
    const body = await reserved.json();
    assert.deepEqual(body, expectedReceipt(operationId, owner.uid, "RESERVED"));
    assert.equal((await subject.jobs.get()).empty, true);
    assert.equal((await call(`/v2/operations/${operationId}/activate`, {})).status, 401);
    assert.equal((await call(`/v2/operations/${operationId}/status`, {}, undefined, randomBytes(32).toString("base64url"))).status, 401);
    const activated = await call(`/v2/operations/${operationId}/activate`, {}, owner.token);
    assert.equal(activated.status, 202);
    await subject.runPending();
    const status = await call(`/v2/operations/${operationId}/status`, {});
    assert.equal(status.headers.get("cache-control"), "no-store");
    assert.deepEqual(await status.json(), expectedReceipt(operationId, owner.uid, "COMPLETE"));
    assert.equal((await call(`/v1/deletions/${operationId}/resume`, {})).status, 404);
    assert.equal((await call(`/v2/operations/${operationId}/status`, { uid: owner.uid })).status, 400);
  } finally { await new Promise((resolve) => server.close(resolve)); }
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
  const child = spawn(process.execPath, ["service/main.mjs"], { cwd: process.cwd(), env: { ...process.env, IRONPATH_DELETION_MODE: "emulator", IRONPATH_DELETION_SERVICE_INSTANCE_ID: serviceInstanceId, GCLOUD_PROJECT: projectId, PORT: "0" }, stdio: "pipe" });
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
  for (const override of [{ IRONPATH_DELETION_SERVICE_INSTANCE_ID: "" }, { IRONPATH_DELETION_MODE: "" }, { IRONPATH_DELETION_MODE: "emulator", GCLOUD_PROJECT: "live-project" }, { IRONPATH_DELETION_MODE: "emulator", FIRESTORE_EMULATOR_HOST: "remote.invalid:8187" }]) {
    const child = spawn(process.execPath, ["service/main.mjs"], { cwd: process.cwd(), env: { ...process.env, GCLOUD_PROJECT: projectId, IRONPATH_DELETION_SERVICE_INSTANCE_ID: serviceInstanceId, ...override }, stdio: "ignore" });
    const [code] = await once(child, "exit");
    assert.notEqual(code, 0);
  }
});

function expectedReceipt(operationId, uid, state) {
  return { protocol: "ironpath-account-deletion-v2", projectId, serviceInstanceId, operationId,
    subjectBinding: subjectBinding(projectId, serviceInstanceId, operationId, uid), state,
    version: state === "RESERVED" ? 1 : state === "COMPLETE" ? 3 : 2 };
}

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

function gatedTransactions(entered, release) {
  return new Proxy(firestore, { get(target, key) {
    if (key === "runTransaction") return async (work, options) => {
      assert.equal(options.maxAttempts, 5);
      entered.resolve();
      await release.promise;
      return target.runTransaction(work, options);
    };
    const value = Reflect.get(target, key);
    return typeof value === "function" ? value.bind(target) : value;
  } });
}

async function revoke(owner) {
  const claims = await auth.verifyIdToken(owner.token);
  const response = await fetch(`http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/projects/${projectId}/accounts:update`, {
    method: "POST", headers: { "Content-Type": "application/json", Authorization: "Bearer owner" },
    body: JSON.stringify({ localId: owner.uid, validSince: String(claims.auth_time + 1) }),
  });
  assert.equal(response.status, 200);
}

test("reservation ACK is durable but creates no fence/job or data/Auth side effects, including after worker restart", async () => {
  const owner = await googleIdentity();
  const root = await seed(owner.uid);
  const before = (await root.get()).data();
  const subject = service();
  const operationId = randomUUID();
  const secret = receiptSecret(operationId);
  const reserved = await subject.reserve(operationId, secret, owner.token);
  assert.deepEqual(reserved, expectedReceipt(operationId, owner.uid, "RESERVED"));
  assert.deepEqual(await subject.reserve(operationId, secret, owner.token), reserved);
  const stored = (await subject.receipts.doc(operationId).get()).data();
  assert.equal(stored.capabilityHash.length, 64);
  assert.equal(JSON.stringify(stored).includes(secret), false);
  assert.equal(JSON.stringify(stored).includes(owner.token), false);
  assert.equal(Object.hasOwn(stored, "email"), false);
  const stop = startWorker(service());
  await stop();
  assert.equal((await subject.jobs.get()).empty, true);
  assert.equal((await subject.tombstones.get()).empty, true);
  assert.deepEqual((await root.get()).data(), before);
  assert.equal((await auth.getUser(owner.uid)).uid, owner.uid);
  assert.deepEqual(await service().status(operationId, secret), reserved);
  await assert.rejects(subject.activate(operationId, secret), (error) => error.status === 401);
  await auth.deleteUser(owner.uid);
  assert.deepEqual(await subject.cancelUnactivated(operationId, secret), expectedReceipt(operationId, owner.uid, "CANCELLED_NO_DELETE"));
  assert.deepEqual((await root.get()).data(), before);
});

test("a pre-reserved second device observes another device's completion after Auth removal with no token replay", async () => {
  const owner = await googleIdentity();
  const subject = service();
  const first = randomUUID();
  const second = randomUUID();
  await subject.reserve(first, receiptSecret(first), owner.token);
  await subject.reserve(second, receiptSecret(second), owner.token);
  await subject.activate(first, receiptSecret(first), owner.token);
  assert.deepEqual(await subject.status(second, receiptSecret(second)), expectedReceipt(second, owner.uid, "PENDING"));
  await subject.runPending();
  await noAuth(owner.uid);
  instant += 301000;
  assert.deepEqual(await service().status(second, receiptSecret(second)), expectedReceipt(second, owner.uid, "COMPLETE"));
  await assert.rejects(subject.activate(second, receiptSecret(second), owner.token), (error) => error.status === 401);
  assert.equal((await subject.jobs.get()).size, 1);
});

test("lost cancellation ACK is immutable on every endpoint before and after future canonical deletion", async () => {
  const owner = await googleIdentity();
  const subject = service();
  const cancelled = randomUUID();
  const active = randomUUID();
  await subject.reserve(cancelled, receiptSecret(cancelled), owner.token);
  await subject.reserve(active, receiptSecret(active), owner.token);
  await subject.cancelUnactivated(cancelled, receiptSecret(cancelled)); // ACK lost
  await subject.activate(active, receiptSecret(active), owner.token);
  const terminal = expectedReceipt(cancelled, owner.uid, "CANCELLED_NO_DELETE");
  assert.deepEqual(await subject.status(cancelled, receiptSecret(cancelled)), terminal);
  assert.deepEqual(await subject.reserve(cancelled, receiptSecret(cancelled), owner.token), terminal);
  assert.deepEqual(await subject.activate(cancelled, receiptSecret(cancelled), owner.token), terminal);
  assert.deepEqual(await subject.cancelUnactivated(cancelled, receiptSecret(cancelled)), terminal);
  await subject.runPending();
  assert.deepEqual(await service().status(cancelled, receiptSecret(cancelled)), terminal);
  assert.deepEqual(await service().cancelUnactivated(cancelled, receiptSecret(cancelled)), terminal);
  assert.equal((await subject.receipts.doc(cancelled).get()).data().state, "CANCELLED_NO_DELETE");
});

test("concurrent same-operation activation/cancellation has one serializable winner", async () => {
  const owner = await googleIdentity();
  const subject = service();
  const operationId = randomUUID();
  const secret = receiptSecret(operationId);
  await subject.reserve(operationId, secret, owner.token);
  const [activated, cancelled] = await Promise.all([
    subject.activate(operationId, secret, owner.token), subject.cancelUnactivated(operationId, secret),
  ]);
  assert.equal(activated.state, cancelled.state);
  assert.ok(["PENDING", "CANCELLED_NO_DELETE"].includes(activated.state));
  assert.equal((await subject.jobs.get()).size, activated.state === "PENDING" ? 1 : 0);
  assert.equal((await subject.tombstones.get()).size, activated.state === "PENDING" ? 1 : 0);
  assert.equal((await subject.status(operationId, secret)).state, activated.state);
});

test("status receipt/fence reads share a transaction while cancellation and future activation contend", { timeout: 15000 }, async () => {
  const owner = await googleIdentity();
  const subject = service();
  const operationId = randomUUID();
  const future = randomUUID();
  const secret = receiptSecret(operationId);
  await subject.reserve(operationId, secret, owner.token);
  await subject.reserve(future, receiptSecret(future), owner.token);
  const read = deferred();
  const release = deferred();
  let held = false;
  const store = new Proxy(firestore, { get(target, key) {
    if (key === "runTransaction") return (work, options) => target.runTransaction((transaction) => work(new Proxy(transaction, { get(tx, method) {
      if (method === "get") return async (ref) => {
        const value = await tx.get(ref);
        if (!held && ref.path === `accountDeletionReceipts/${operationId}`) {
          held = true; read.resolve(); await release.promise;
        }
        return value;
      };
      const value = Reflect.get(tx, method);
      return typeof value === "function" ? value.bind(tx) : value;
    } })), options);
    const value = Reflect.get(target, key);
    return typeof value === "function" ? value.bind(target) : value;
  } });
  const reading = service({ firestore: store }).status(operationId, secret);
  await read.promise;
  const cancellation = subject.cancelUnactivated(operationId, secret);
  const laterActivation = cancellation.then(() => subject.activate(future, receiptSecret(future), owner.token));
  release.resolve();
  assert.ok(["RESERVED", "CANCELLED_NO_DELETE"].includes((await reading).state));
  assert.equal((await cancellation).state, "CANCELLED_NO_DELETE");
  assert.equal((await laterActivation).state, "PENDING");
  await subject.runPending();
  assert.equal((await subject.status(operationId, secret)).state, "CANCELLED_NO_DELETE");
});

test("revocation and expiry after request admission do not revoke in-flight activation; a new request is rejected", { timeout: 15000 }, async () => {
  const owner = await googleIdentity();
  const normal = service();
  const operationId = randomUUID();
  const secret = receiptSecret(operationId);
  await normal.reserve(operationId, secret, owner.token);
  const admitted = deferred();
  const release = deferred();
  let verifications = 0;
  const inFlight = service({ firestore: gatedTransactions(admitted, release), auth: {
    verifyIdToken: (...args) => { verifications++; return auth.verifyIdToken(...args); },
    deleteUser: (...args) => auth.deleteUser(...args),
  } });
  const activation = inFlight.activate(operationId, secret, owner.token);
  await admitted.promise;
  await revoke(owner);
  instant += 301000;
  release.resolve();
  assert.equal((await activation).state, "PENDING");
  assert.equal(verifications, 1);
  await assert.rejects(normal.activate(operationId, secret, owner.token), (error) => error.status === 401);
  await normal.runPending();
  assert.equal((await normal.status(operationId, secret)).state, "COMPLETE");
});

test("cancellation can win before an already admitted activation reaches Firestore", { timeout: 15000 }, async () => {
  const owner = await googleIdentity();
  const subject = service();
  const operationId = randomUUID();
  const secret = receiptSecret(operationId);
  await subject.reserve(operationId, secret, owner.token);
  const admitted = deferred();
  const release = deferred();
  const activation = service({ firestore: gatedTransactions(admitted, release) }).activate(operationId, secret, owner.token);
  await admitted.promise;
  assert.equal((await subject.cancelUnactivated(operationId, secret)).state, "CANCELLED_NO_DELETE");
  release.resolve();
  assert.equal((await activation).state, "CANCELLED_NO_DELETE");
  assert.equal((await subject.jobs.get()).empty, true);
  assert.equal((await subject.tombstones.get()).empty, true);
});

test("client connection death does not cancel admitted activation; durable status recovers its result", { timeout: 20000 }, async () => {
  const owner = await googleIdentity();
  const operationId = randomUUID();
  const secret = receiptSecret(operationId);
  await service().reserve(operationId, secret, owner.token);
  const admitted = deferred();
  const release = deferred();
  const subject = service({ firestore: gatedTransactions(admitted, release) });
  const server = createDeletionServer(subject);
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const controller = new AbortController();
    const request = fetch(`http://127.0.0.1:${server.address().port}/v2/operations/${operationId}/activate`, {
      method: "POST", signal: controller.signal,
      headers: { "Content-Type": "application/json", "Deletion-Receipt": secret, Authorization: `Bearer ${owner.token}` }, body: "{}",
    });
    const abandoned = assert.rejects(request, (error) => error.name === "AbortError");
    await admitted.promise;
    controller.abort();
    await abandoned;
    let unsubscribe;
    const committed = new Promise((resolve, reject) => {
      const deadline = setTimeout(() => reject(new Error("activation commit deadline")), 15000);
      unsubscribe = firestore.doc(`accountDeletionJobs/${operationId}`).onSnapshot((snapshot) => {
        if (snapshot.exists) { clearTimeout(deadline); resolve(); }
      }, (error) => { clearTimeout(deadline); reject(error); });
    });
    release.resolve();
    try { await committed; } finally { unsubscribe?.(); }
    await subject.runPending();
    assert.equal((await service().status(operationId, secret)).state, "COMPLETE");
  } finally { release.resolve(); await new Promise((resolve) => server.close(resolve)); }
});

test("wrong receipt secret, UID, service instance and project cannot read/rebind/activate a reservation", async () => {
  const owner = await googleIdentity();
  const other = await googleIdentity();
  const subject = service();
  const operationId = randomUUID();
  const secret = receiptSecret(operationId);
  const wrong = randomBytes(32).toString("base64url");
  await subject.reserve(operationId, secret, owner.token);
  await assert.rejects(subject.reserve(operationId, wrong, owner.token), (error) => error.status === 409);
  await assert.rejects(subject.reserve(operationId, secret, other.token), (error) => error.status === 409);
  await assert.rejects(subject.status(operationId, wrong), (error) => error.status === 401);
  await assert.rejects(subject.cancelUnactivated(operationId, wrong), (error) => error.status === 401);
  await assert.rejects(subject.activate(operationId, secret, other.token), (error) => error.status === 409);
  await assert.rejects(service({ serviceInstanceId: "different-instance" }).status(operationId, secret), (error) => error.status === 409);
  await assert.rejects(service({ projectId: "demo-different" }).status(operationId, secret), (error) => error.status === 409);
  assert.equal((await subject.jobs.get()).empty, true);
  assert.equal((await subject.tombstones.get()).empty, true);
});

test("new-reservation quotas are transactional and never strand acknowledged recovery/activation/cancellation", async () => {
  const owner = await googleIdentity();
  const subject = service({ reservationLimit: 2, reservationWindowLimit: 1, reservationWindowMs: 1000 });
  const first = randomUUID();
  const second = randomUUID();
  const outcomes = await Promise.allSettled([
    subject.reserve(first, receiptSecret(first), owner.token), subject.reserve(second, receiptSecret(second), owner.token),
  ]);
  assert.equal(outcomes.filter((value) => value.status === "fulfilled").length, 1);
  assert.equal(outcomes.find((value) => value.status === "rejected").reason.status, 429);
  const accepted = outcomes[0].status === "fulfilled" ? first : second;
  const next = accepted === first ? second : first;
  assert.equal((await subject.reserve(accepted, receiptSecret(accepted), owner.token)).state, "RESERVED");
  instant += 1000;
  await subject.reserve(next, receiptSecret(next), owner.token);
  instant += 1000;
  const excess = randomUUID();
  await assert.rejects(subject.reserve(excess, receiptSecret(excess), owner.token), (error) => error.status === 429);
  assert.equal((await subject.receipts.get()).size, 2);
  assert.equal((await subject.cancelUnactivated(next, receiptSecret(next))).state, "CANCELLED_NO_DELETE");
  assert.equal((await subject.activate(accepted, receiptSecret(accepted), owner.token)).state, "PENDING");
  await subject.runPending();
  assert.equal((await subject.status(accepted, receiptSecret(accepted))).state, "COMPLETE");
  assert.equal((await subject.cancelUnactivated(next, receiptSecret(next))).state, "CANCELLED_NO_DELETE");
});

test("only previously accepted v1 canonical work retains recovery; unknown legacy IDs and v2 UUID-only access fail closed", async () => {
  const owner = await googleIdentity();
  const subject = service();
  const legacy = randomUUID();
  await subject.jobs.doc(legacy).create({ uid: owner.uid, state: "PENDING", createdAt: instant, nextAttemptAt: 0, failures: 0 });
  await subject.tombstones.doc(owner.uid).create({ operationId: legacy, createdAt: instant });
  assert.deepEqual(await subject.resumeLegacy(legacy), { operationId: legacy, state: "PENDING" });
  await assert.rejects(subject.reserve(legacy, receiptSecret(legacy), owner.token), (error) => error.status === 409);
  await subject.runPending();
  assert.deepEqual(await subject.resumeLegacy(legacy), { operationId: legacy, state: "COMPLETE" });
  await assert.rejects(subject.resumeLegacy(randomUUID()), (error) => error.status === 404);
  const fresh = await googleIdentity();
  const v2 = randomUUID();
  await subject.start(v2, fresh.token);
  await assert.rejects(subject.resumeLegacy(v2), (error) => error.status === 404);
});

async function freshHttpProcess() {
  const child = spawn(process.execPath, ["service/main.mjs"], {
    cwd: process.cwd(),
    env: { ...process.env, IRONPATH_DELETION_MODE: "emulator", IRONPATH_DELETION_SERVICE_INSTANCE_ID: serviceInstanceId, GCLOUD_PROJECT: projectId, PORT: "0" },
    stdio: ["ignore", "ignore", "ignore", "ipc"],
  });
  const stop = async () => {
    if (child.exitCode !== null || child.signalCode !== null) return;
    const exited = once(child, "exit");
    child.kill("SIGTERM");
    const deadline = setTimeout(() => child.kill("SIGKILL"), 5000);
    try { await exited; } finally { clearTimeout(deadline); }
  };
  try {
    const port = await new Promise((resolve, reject) => {
      const deadline = setTimeout(() => reject(new Error("synthetic service startup deadline")), 15000);
      child.once("message", (message) => {
        clearTimeout(deadline);
        if (Number.isInteger(message.port) && message.port > 0) resolve(message.port);
        else reject(new Error("invalid synthetic service port"));
      });
      child.once("exit", () => { clearTimeout(deadline); reject(new Error("synthetic service exited")); });
      child.once("error", (error) => { clearTimeout(deadline); reject(error); });
    });
    return { url: `http://127.0.0.1:${port}`, stop };
  } catch (error) { await stop(); throw error; }
}

async function receiptHttp(url, path, secret, token, body = {}) {
  const response = await fetch(url + path, {
    method: "POST", headers: { "Content-Type": "application/json", "Deletion-Receipt": secret,
      ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body),
  });
  assert.ok([200, 202].includes(response.status), `unexpected receipt HTTP ${response.status}`);
  return response.json();
}

test("independent server processes retain a reservation and cancel it after Auth disappears without client credentials", { timeout: 30000 }, async () => {
  const owner = await googleIdentity();
  const root = await seed(owner.uid);
  const operationId = randomUUID();
  const secret = receiptSecret(operationId);
  let process;
  try {
    process = await freshHttpProcess();
    const reserved = await receiptHttp(process.url, "/v2/reservations", secret, owner.token, { operationId });
    assert.deepEqual(reserved, expectedReceipt(operationId, owner.uid, "RESERVED"));
    await process.stop();
    process = undefined;
    assert.equal((await service().jobs.get()).empty, true);
    assert.equal((await service().tombstones.get()).empty, true);
    await auth.deleteUser(owner.uid);

    process = await freshHttpProcess();
    assert.deepEqual(await receiptHttp(process.url, `/v2/operations/${operationId}/status`, secret), reserved);
    const terminal = expectedReceipt(operationId, owner.uid, "CANCELLED_NO_DELETE");
    assert.deepEqual(await receiptHttp(process.url, `/v2/operations/${operationId}/cancel-unactivated`, secret), terminal);
    await process.stop();
    process = undefined;

    process = await freshHttpProcess();
    assert.deepEqual(await receiptHttp(process.url, `/v2/operations/${operationId}/status`, secret), terminal);
    assert.equal((await service().jobs.get()).empty, true);
    assert.equal((await service().tombstones.get()).empty, true);
    assert.equal((await root.get()).exists, true);
    assert.equal((await root.collection("backups").doc("missing-parent").collection("chunks").doc("orphan").get()).exists, true);
  } finally { await process?.stop(); }
});

test("delayed cancellation HTTP reply stays terminal after another device activates and completes", { timeout: 20000 }, async () => {
  const owner = await googleIdentity();
  const subject = service();
  const cancelled = randomUUID();
  const active = randomUUID();
  await subject.reserve(cancelled, receiptSecret(cancelled), owner.token);
  await subject.reserve(active, receiptSecret(active), owner.token);
  const committed = deferred();
  const release = deferred();
  const delayed = new Proxy(subject, { get(target, key) {
    if (key === "cancelUnactivated") return async (...args) => {
      const receipt = await target.cancelUnactivated(...args);
      committed.resolve();
      await release.promise;
      return receipt;
    };
    const value = Reflect.get(target, key);
    return typeof value === "function" ? value.bind(target) : value;
  } });
  const server = createDeletionServer(delayed);
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const response = receiptHttp(`http://127.0.0.1:${server.address().port}`, `/v2/operations/${cancelled}/cancel-unactivated`, receiptSecret(cancelled));
    await committed.promise;
    await subject.activate(active, receiptSecret(active), owner.token);
    await subject.runPending();
    await noAuth(owner.uid);
    release.resolve();
    const terminal = expectedReceipt(cancelled, owner.uid, "CANCELLED_NO_DELETE");
    assert.deepEqual(await response, terminal);
    assert.deepEqual(await service().status(cancelled, receiptSecret(cancelled)), terminal);
    assert.equal((await subject.status(active, receiptSecret(active))).state, "COMPLETE");
  } finally { release.resolve(); await new Promise((resolve) => server.close(resolve)); }
});
