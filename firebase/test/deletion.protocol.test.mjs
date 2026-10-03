import assert from "node:assert/strict";
import { test } from "node:test";
import { DeletionService, hashReceiptSecret, subjectBinding } from "../service/deletion-service.mjs";

test("v2 capabilities bind an explicitly configured immutable service instance", () => {
  const service = new DeletionService({
    firestore: { collection: () => ({}) }, auth: {},
    projectId: "demo-protocol", serviceInstanceId: "protocol-instance-v2",
  });
  assert.deepEqual(service.capabilities(), {
    protocol: "ironpath-account-deletion-v2", projectId: "demo-protocol",
    serviceInstanceId: "protocol-instance-v2", authoritative: true, resumable: true,
  });
});


test("service binding is immutable and rejects client-incompatible instance IDs", () => {
  const args = { firestore: { collection: () => ({}) }, auth: {}, projectId: "demo-protocol", serviceInstanceId: "valid-instance_2" };
  const service = new DeletionService(args);
  assert.throws(() => { service.serviceInstanceId = "replacement"; }, TypeError);
  for (const instance of ["", "contains space", "invalid.dot", "x".repeat(129)]) {
    assert.throws(() => new DeletionService({ ...args, serviceInstanceId: instance }), /EXPLICIT_SERVICE_BINDING_REQUIRED/);
  }
  assert.throws(() => new DeletionService({ ...args, reservationLimit: 0 }), /INVALID_RESERVATION_LIMIT/);
});

test("receipt secrets are exactly 32 canonical base64url bytes and are stored only as a hash", () => {
  const secret = "A".repeat(43);
  assert.equal(hashReceiptSecret(secret), "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925");
  for (const invalid of [undefined, "A".repeat(42), "A".repeat(44), "A".repeat(42) + "B", "A".repeat(42) + "="]) {
    assert.throws(() => hashReceiptSecret(invalid), (error) => error.status === 400);
  }
});

test("subject binding uses four-byte big-endian UTF-8 lengths with an independent non-ASCII vector", () => {
  assert.equal(subjectBinding("demo-protocol", "protocol-instance-v2", "00000000-0000-4000-8000-000000000000", "uid-王"),
    "04ab513a3f4062703716a81d81aeafa1e636354f31acec0f04502b8a8f7015b8");
  assert.notEqual(subjectBinding("ab", "c", "operation", "uid"), subjectBinding("a", "bc", "operation", "uid"));
});
