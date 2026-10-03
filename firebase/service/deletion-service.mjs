import { FieldPath } from "firebase-admin/firestore";

const OPERATION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export const PROTOCOL = "ironpath-account-deletion-v1";

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

// Admin credentials and project selection belong to the process, never a request.
// The only retained identity is the verified Firebase UID; no email or token is stored.
export class DeletionService {
  constructor({ firestore, auth, projectId, now = Date.now }) {
    this.firestore = firestore;
    this.auth = auth;
    this.projectId = projectId;
    this.now = now;
    this.jobs = firestore.collection("accountDeletionJobs");
    this.tombstones = firestore.collection("accountDeletionTombstones");
    this.scanCursor = undefined;
    this.running = undefined;
  }

  capabilities() {
    return { protocol: PROTOCOL, projectId: this.projectId, authoritative: true, resumable: true };
  }

  async start(operationId, idToken) {
    validateOperationId(operationId);
    const { uid, receiptOnly } = await this.verifiedIdentity(idToken);
    const jobRef = this.jobs.doc(operationId);
    const tombstoneRef = this.tombstones.doc(uid);
    return this.firestore.runTransaction(async (transaction) => {
      const [job, tombstone] = await transaction.getAll(jobRef, tombstoneRef);
      if (job.exists) {
        if (job.data().uid !== uid) throw new RequestError(409, "OPERATION_CONFLICT");
        const canonical = job.data().canonicalOperationId
          ? await transaction.get(this.jobs.doc(job.data().canonicalOperationId))
          : job;
        this.assertCanonical(canonical, uid);
        if (receiptOnly && canonical.data().state !== "COMPLETE") {
          throw new RequestError(401, "REAUTHENTICATION_REQUIRED");
        }
        return { operationId, state: canonical.data().state };
      }
      const createdAt = this.now();
      if (tombstone.exists) {
        // Another device may have confirmed the same account's deletion with a
        // different private capability. Give it its own receipt, never the first
        // device's capability, and never start a second destructive worker.
        const canonical = await transaction.get(this.jobs.doc(tombstone.data().operationId));
        this.assertCanonical(canonical, uid);
        const state = canonical.data().state;
        if (receiptOnly && state !== "COMPLETE") {
          throw new RequestError(401, "REAUTHENTICATION_REQUIRED");
        }
        transaction.create(jobRef, {
          uid, canonicalOperationId: canonical.id, state, createdAt,
          nextAttemptAt: 0, failures: 0,
        });
        return { operationId, state };
      }
      // A signed but revoked/deleted identity may recover completed proof only.
      // It can never create a new fence or initiate pending/destructive work.
      if (receiptOnly) throw new RequestError(401, "REAUTHENTICATION_REQUIRED");
      transaction.create(tombstoneRef, { operationId, createdAt });
      transaction.create(jobRef, { uid, state: "PENDING", createdAt, nextAttemptAt: 0, failures: 0 });
      return { operationId, state: "PENDING" };
    });
  }

  async verifiedIdentity(idToken) {
    if (typeof idToken !== "string" || !idToken || idToken.length > 16384) {
      throw new RequestError(401, "REAUTHENTICATION_REQUIRED");
    }
    let claims;
    let receiptOnly = false;
    try {
      claims = await this.auth.verifyIdToken(idToken, true);
    } catch (error) {
      if (["auth/user-not-found", "auth/id-token-revoked"].includes(error.code)) {
        // Auth can disappear between a second device's confirmation and its POST.
        // Still verify the signature, audience, issuer and expiry. The transaction
        // additionally requires an already COMPLETE canonical receipt for this UID.
        try {
          claims = await this.auth.verifyIdToken(idToken, false);
          receiptOnly = true;
        } catch (verificationError) {
          throw tokenError(verificationError);
        }
      } else {
        throw tokenError(error);
      }
    }
    const age = Math.floor(this.now() / 1000) - claims.auth_time;
    if (typeof claims.uid !== "string" || !claims.uid || claims.uid.includes("/") ||
        claims.firebase?.sign_in_provider !== "google.com" ||
        !Number.isInteger(claims.auth_time) || age < 0 || age > 300) {
      throw new RequestError(401, "REAUTHENTICATION_REQUIRED");
    }
    return { uid: claims.uid, receiptOnly };
  }

  assertCanonical(job, uid) {
    if (!job.exists || job.data().uid !== uid || job.data().canonicalOperationId ||
        !["PENDING", "COMPLETE"].includes(job.data().state)) {
      throw new RequestError(503, "UNAVAILABLE");
    }
  }

  async resume(operationId) {
    validateOperationId(operationId);
    const job = await this.jobs.doc(operationId).get();
    if (!job.exists) throw new RequestError(404, "NOT_FOUND");
    if (job.data().canonicalOperationId) {
      const canonical = await this.jobs.doc(job.data().canonicalOperationId).get();
      this.assertCanonical(canonical, job.data().uid);
      return { operationId, state: canonical.data().state };
    }
    return receipt(job);
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
    if (job.data().canonicalOperationId) {
      const canonical = await this.jobs.doc(job.data().canonicalOperationId).get();
      this.assertCanonical(canonical, uid);
      if (canonical.data().state === "COMPLETE") {
        await job.ref.update({ state: "COMPLETE", completedAt: this.now(), nextAttemptAt: 0 });
      }
      return; // Aliases never purge data, change tombstones, or delete Auth.
    }
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
