import { createHash, timingSafeEqual } from "node:crypto";
import { FieldPath } from "firebase-admin/firestore";

const OPERATION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export const PROTOCOL = "ironpath-account-deletion-v2";

export class RequestError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

export function validateOperationId(operationId) {
  if (typeof operationId !== "string" || !OPERATION_ID.test(operationId)) {
    throw new RequestError(400, "INVALID_REQUEST");
  }
}

export function hashReceiptSecret(secret) {
  if (typeof secret !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(secret)) {
    throw new RequestError(400, "INVALID_REQUEST");
  }
  const bytes = Buffer.from(secret, "base64url");
  if (bytes.length !== 32 || bytes.toString("base64url") !== secret) {
    throw new RequestError(400, "INVALID_REQUEST");
  }
  return createHash("sha256").update(bytes).digest("hex");
}

export function subjectBinding(projectId, serviceInstanceId, operationId, uid) {
  const hash = createHash("sha256");
  for (const field of ["ironpath-delete-v2", projectId, serviceInstanceId, operationId, uid]) {
    const bytes = Buffer.from(field, "utf8");
    const length = Buffer.alloc(4);
    length.writeUInt32BE(bytes.length);
    hash.update(length).update(bytes);
  }
  return hash.digest("hex");
}

// Firebase clients and immutable configuration are process-owned. Fresh proof
// exists only in the current request. Neither UID nor authority comes from a body.
export class DeletionService {
  constructor({ firestore, auth, projectId, serviceInstanceId, now = Date.now,
    reservationLimit = 100, reservationWindowLimit = 20, reservationWindowMs = 3600000 }) {
    for (const value of [projectId]) {
      if (typeof value !== "string" || !value.trim() || value !== value.trim() || value.length > 256) {
        throw new Error("EXPLICIT_SERVICE_BINDING_REQUIRED");
      }
    }
    if (typeof serviceInstanceId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(serviceInstanceId)) {
      throw new Error("EXPLICIT_SERVICE_BINDING_REQUIRED");
    }
    for (const value of [reservationLimit, reservationWindowLimit, reservationWindowMs]) {
      if (!Number.isSafeInteger(value) || value < 1) throw new Error("INVALID_RESERVATION_LIMIT");
    }
    this.firestore = firestore;
    this.auth = auth;
    Object.defineProperties(this, { projectId: { value: projectId }, serviceInstanceId: { value: serviceInstanceId } });
    this.now = now;
    this.reservationLimit = reservationLimit;
    this.reservationWindowLimit = reservationWindowLimit;
    this.reservationWindowMs = reservationWindowMs;
    this.receipts = firestore.collection("accountDeletionReceipts");
    this.quotas = firestore.collection("accountDeletionReservationQuotas");
    this.jobs = firestore.collection("accountDeletionJobs");
    this.tombstones = firestore.collection("accountDeletionTombstones");
    this.scanCursor = undefined;
    this.running = undefined;
  }

  capabilities() {
    return { protocol: PROTOCOL, projectId: this.projectId, serviceInstanceId: this.serviceInstanceId,
      authoritative: true, resumable: true };
  }

  transaction(work) {
    return this.firestore.runTransaction(work, { maxAttempts: 5 });
  }

  async verifiedIdentity(idToken) {
    if (typeof idToken !== "string" || !idToken || idToken.length > 16384) {
      throw new RequestError(401, "REAUTHENTICATION_REQUIRED");
    }
    let claims;
    try { claims = await this.auth.verifyIdToken(idToken, true); }
    catch (error) { throw tokenError(error); } // No revoked/deleted-token fallback.
    const age = Math.floor(this.now() / 1000) - claims.auth_time;
    if (typeof claims.uid !== "string" || !claims.uid || claims.uid.includes("/") ||
        claims.aud !== this.projectId || claims.iss !== `https://securetoken.google.com/${this.projectId}` ||
        claims.firebase?.sign_in_provider !== "google.com" ||
        !Number.isInteger(claims.auth_time) || age < 0 || age > 300) {
      throw new RequestError(401, "REAUTHENTICATION_REQUIRED");
    }
    return claims.uid;
  }

  response(operationId, data, state) {
    return { protocol: PROTOCOL, projectId: this.projectId, serviceInstanceId: this.serviceInstanceId,
      operationId, subjectBinding: data.subjectBinding, state,
      version: state === "RESERVED" ? 1 : state === "COMPLETE" ? 3 : 2 };
  }

  validateReceipt(snapshot, capabilityHash) {
    if (!snapshot.exists) throw new RequestError(404, "NOT_FOUND");
    const data = snapshot.data();
    if (!/^[0-9a-f]{64}$/.test(data.capabilityHash ?? "")) throw new RequestError(503, "UNAVAILABLE");
    if (!timingSafeEqual(Buffer.from(data.capabilityHash, "hex"), Buffer.from(capabilityHash, "hex"))) {
      throw new RequestError(401, "RECEIPT_REQUIRED");
    }
    if (data.protocol !== PROTOCOL || data.projectId !== this.projectId ||
        data.serviceInstanceId !== this.serviceInstanceId) throw new RequestError(409, "BINDING_MISMATCH");
    if (typeof data.uid !== "string" || !data.uid || data.uid.includes("/") ||
        data.subjectBinding !== subjectBinding(this.projectId, this.serviceInstanceId, snapshot.id, data.uid) ||
        !["RESERVED", "ACTIVE", "CANCELLED_NO_DELETE"].includes(data.state)) {
      throw new RequestError(503, "UNAVAILABLE");
    }
    return data;
  }

  assertCanonical(job, uid) {
    const data = job.data();
    if (!job.exists || data.uid !== uid || data.canonicalOperationId ||
        data.protocol !== PROTOCOL || data.projectId !== this.projectId ||
        data.serviceInstanceId !== this.serviceInstanceId || data.kind !== "CANONICAL" ||
        data.activated !== true || !["PENDING", "COMPLETE"].includes(data.state)) {
      throw new RequestError(503, "UNAVAILABLE");
    }
  }

  // Receipt, UID fence (including absence), and canonical job always share one
  // serializable transaction. Cancellation/activation therefore have one order.
  async canonical(transaction, uid) {
    const fence = await transaction.get(this.tombstones.doc(uid));
    if (!fence.exists) return undefined;
    const operationId = fence.data().operationId;
    if (typeof operationId !== "string" || !OPERATION_ID.test(operationId)) throw new RequestError(503, "UNAVAILABLE");
    const job = await transaction.get(this.jobs.doc(operationId));
    this.assertCanonical(job, uid);
    return job;
  }

  async reserve(operationId, secret, idToken) {
    validateOperationId(operationId);
    const capabilityHash = hashReceiptSecret(secret);
    const uid = await this.verifiedIdentity(idToken);
    return this.transaction(async (transaction) => {
      const ref = this.receipts.doc(operationId);
      const existing = await transaction.get(ref);
      if (existing.exists) {
        let data;
        try { data = this.validateReceipt(existing, capabilityHash); }
        catch (error) {
          if (error.code === "RECEIPT_REQUIRED") throw new RequestError(409, "OPERATION_CONFLICT");
          throw error;
        }
        if (data.uid !== uid) throw new RequestError(409, "OPERATION_CONFLICT");
        if (data.state === "CANCELLED_NO_DELETE") return this.response(operationId, data, data.state);
        const canonical = await this.canonical(transaction, uid);
        if (!canonical && data.state === "ACTIVE") throw new RequestError(503, "UNAVAILABLE");
        return this.response(operationId, data, canonical?.data().state ?? "RESERVED");
      }
      // Never reinterpret a v1 operation (or a lost v2 receipt) as a new identity.
      const [oldJob, quota] = await transaction.getAll(this.jobs.doc(operationId), this.quotas.doc(uid));
      if (oldJob.exists) throw new RequestError(409, "OPERATION_CONFLICT");
      const canonical = await this.canonical(transaction, uid);
      const timestamp = this.now();
      const budget = quota.exists ? quota.data() : { total: 0, windowCount: 0, windowStartedAt: timestamp };
      if (![budget.total, budget.windowCount, budget.windowStartedAt].every((value) => Number.isSafeInteger(value) && value >= 0)) {
        throw new RequestError(503, "UNAVAILABLE");
      }
      const expired = timestamp - budget.windowStartedAt >= this.reservationWindowMs;
      const windowCount = expired ? 0 : budget.windowCount;
      if (budget.total >= this.reservationLimit || windowCount >= this.reservationWindowLimit) {
        throw new RequestError(429, "RESERVATION_LIMIT");
      }
      const data = { protocol: PROTOCOL, projectId: this.projectId, serviceInstanceId: this.serviceInstanceId,
        uid, capabilityHash, subjectBinding: subjectBinding(this.projectId, this.serviceInstanceId, operationId, uid),
        state: canonical ? "ACTIVE" : "RESERVED", version: canonical ? 2 : 1, createdAt: timestamp };
      if (canonical) data.canonicalOperationId = canonical.id;
      transaction.create(ref, data);
      transaction.set(quota.ref, { total: budget.total + 1, windowCount: windowCount + 1,
        windowStartedAt: expired ? timestamp : budget.windowStartedAt });
      return this.response(operationId, data, canonical?.data().state ?? "RESERVED");
    });
  }

  async status(operationId, secret) {
    validateOperationId(operationId);
    const hash = hashReceiptSecret(secret);
    return this.transaction(async (transaction) => {
      const data = this.validateReceipt(await transaction.get(this.receipts.doc(operationId)), hash);
      if (data.state === "CANCELLED_NO_DELETE") return this.response(operationId, data, data.state);
      const canonical = await this.canonical(transaction, data.uid);
      if (!canonical && data.state === "ACTIVE") throw new RequestError(503, "UNAVAILABLE");
      return this.response(operationId, data, canonical?.data().state ?? "RESERVED");
    });
  }

  async activate(operationId, secret, idToken) {
    validateOperationId(operationId);
    const hash = hashReceiptSecret(secret);
    // Verify exactly once at request admission, before bounded Firestore retries.
    // Later revocation/expiry does not cancel this admitted in-flight request.
    // A restart discards admission; only another explicit fresh request can admit.
    const uid = await this.verifiedIdentity(idToken);
    return this.transaction(async (transaction) => {
      const ref = this.receipts.doc(operationId);
      const data = this.validateReceipt(await transaction.get(ref), hash);
      if (data.uid !== uid) throw new RequestError(409, "OPERATION_CONFLICT");
      if (data.state === "CANCELLED_NO_DELETE") return this.response(operationId, data, data.state);
      const canonical = await this.canonical(transaction, uid);
      if (!canonical && data.state === "ACTIVE") throw new RequestError(503, "UNAVAILABLE");
      if (!canonical) {
        transaction.create(this.tombstones.doc(uid), { operationId, createdAt: this.now() });
        transaction.create(this.jobs.doc(operationId), { protocol: PROTOCOL, projectId: this.projectId,
          serviceInstanceId: this.serviceInstanceId, kind: "CANONICAL", activated: true,
          uid, state: "PENDING", createdAt: this.now(), nextAttemptAt: 0, failures: 0 });
      }
      transaction.update(ref, { state: "ACTIVE", version: 2, canonicalOperationId: canonical?.id ?? operationId });
      return this.response(operationId, data, canonical?.data().state ?? "PENDING");
    });
  }

  async cancelUnactivated(operationId, secret) {
    validateOperationId(operationId);
    const hash = hashReceiptSecret(secret);
    return this.transaction(async (transaction) => {
      const ref = this.receipts.doc(operationId);
      const data = this.validateReceipt(await transaction.get(ref), hash);
      if (data.state === "CANCELLED_NO_DELETE") return this.response(operationId, data, data.state);
      const canonical = await this.canonical(transaction, data.uid);
      if (canonical) return this.response(operationId, data, canonical.data().state);
      if (data.state !== "RESERVED") throw new RequestError(503, "UNAVAILABLE");
      transaction.update(ref, { state: "CANCELLED_NO_DELETE", version: 2, cancelledAt: this.now() });
      return this.response(operationId, data, "CANCELLED_NO_DELETE");
    });
  }

  // Only already accepted legacy jobs retain their old UUID recovery capability.
  // This read-only route can never reveal a v2 receipt or create/activate a job.
  async resumeLegacy(operationId) {
    validateOperationId(operationId);
    return this.transaction(async (transaction) => {
      let job = await transaction.get(this.jobs.doc(operationId));
      if (!job.exists || job.data().protocol !== undefined) throw new RequestError(404, "NOT_FOUND");
      const uid = job.data().uid;
      if (typeof uid !== "string" || !uid || uid.includes("/")) throw new RequestError(503, "UNAVAILABLE");
      if (job.data().canonicalOperationId) job = await transaction.get(this.jobs.doc(job.data().canonicalOperationId));
      if (!job.exists || job.data().protocol !== undefined || job.data().canonicalOperationId || job.data().uid !== uid) {
        throw new RequestError(503, "UNAVAILABLE");
      }
      const fence = await transaction.get(this.tombstones.doc(uid));
      if (fence.data()?.operationId !== job.id) throw new RequestError(503, "UNAVAILABLE");
      return { ...receipt(job), operationId };
    });
  }

  // A bounded sweep runs at startup and periodically even with no HTTP requests.
  // Cursor pagination prevents a repeatedly failing account starving later jobs.
  runPending() {
    if (!this.running) this.running = this.sweep().finally(() => { this.running = undefined; });
    return this.running;
  }

  async sweep() {
    let query = this.jobs.where("state", "==", "PENDING").orderBy(FieldPath.documentId()).limit(10);
    if (this.scanCursor) query = query.startAfter(this.scanCursor);
    const jobs = await query.get();
    this.scanCursor = jobs.size === 10 ? jobs.docs.at(-1) : undefined;
    for (const job of jobs.docs) {
      const config = job.data();
      if (config.protocol !== undefined && (config.protocol !== PROTOCOL ||
          config.projectId !== this.projectId || config.serviceInstanceId !== this.serviceInstanceId)) continue;
      if (job.data().nextAttemptAt > this.now()) continue;
      try {
        await this.process(job);
      } catch {
        // Never persist or log exception strings: they can contain UID/data paths.
        await this.firestore.runTransaction(async (transaction) => {
          const current = await transaction.get(job.ref);
          if (current.data()?.state !== "PENDING") return;
          const failures = Math.min((current.data().failures ?? 0) + 1, 16);
          transaction.update(job.ref, { failures, nextAttemptAt: this.now() + Math.min(300000, 1000 * 2 ** failures) });
        });
      }
    }
  }

  async process(job) {
    const { uid } = job.data();
    // Legacy aliases remain receipt-only. Only activated canonical v2 jobs
    // or previously accepted canonical v1 jobs can perform destructive work.
    if (job.data().canonicalOperationId) return;
    if (job.data().protocol !== undefined) this.assertCanonical(job, uid);
    const tombstone = await this.tombstones.doc(uid).get();
    if (tombstone.data()?.operationId !== job.id) throw new Error("FENCE_MISSING");
    const root = this.firestore.doc(`users/${uid}`);
    // recursiveDelete discovers descendants even under missing/malformed parents;
    // neither backupIds nor manifest shape is trusted as an enumeration source.
    const writer = this.firestore.bulkWriter({ throttling: { initialOpsPerSecond: 50, maxOpsPerSecond: 100 } });
    writer.onWriteError(() => false);
    try {
      await this.firestore.recursiveDelete(root, writer);
    } finally {
      await writer.close();
    }
    await this.assertPurged(root);
    try {
      await this.auth.deleteUser(uid);
    } catch (error) {
      // A restart after Auth deletion but before receipt commit is successful.
      if (error.code !== "auth/user-not-found") throw error;
    }
    await job.ref.update({ state: "COMPLETE", completedAt: this.now(), nextAttemptAt: 0 });
  }

  async assertPurged(root) {
    if ((await root.get()).exists) throw new Error("PURGE_INCOMPLETE");
    // listDocuments includes missing documents that have descendants. An empty
    // manifest query alone cannot detect unregistered orphan chunks/subcollections.
    for (const collection of await root.listCollections()) {
      if ((await collection.listDocuments()).length !== 0) throw new Error("PURGE_INCOMPLETE");
    }
  }
}

function tokenError(error) {
  // Backend outages must not be misrepresented as user credential failures.
  if (["auth/argument-error", "auth/invalid-id-token", "auth/id-token-expired", "auth/id-token-revoked", "auth/user-disabled", "auth/user-not-found", "auth/tenant-id-mismatch"].includes(error.code)) {
    return new RequestError(401, "REAUTHENTICATION_REQUIRED");
  }
  return new RequestError(503, "UNAVAILABLE");
}

function receipt(job) {
  const state = job.data().state;
  if (state !== "PENDING" && state !== "COMPLETE") throw new RequestError(503, "UNAVAILABLE");
  return { operationId: job.id, state };
}

export function startWorker(service, { intervalMs = 5000, onError = () => {} } = {}) {
  let stopped = false;
  let timer;
  let active;
  const tick = () => {
    active = service.runPending().catch(onError).finally(() => {
      if (!stopped) timer = setTimeout(tick, intervalMs);
    });
  };
  tick();
  return async () => {
    stopped = true;
    clearTimeout(timer);
    await active;
  };
}
