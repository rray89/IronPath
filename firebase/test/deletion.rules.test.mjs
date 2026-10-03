import { after, before, test } from "node:test";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { deleteDoc, doc, getDoc, setDoc } from "firebase/firestore";

let environment;
before(async () => {
  environment = await initializeTestEnvironment({
    projectId: "demo-ironpath-deletion",
    firestore: { host: "127.0.0.1", port: 8187 },
  });
});
after(async () => environment?.cleanup());

test("permanent tombstone blocks a still-valid owner from recreating a deleted subtree", async () => {
  const owner = environment.authenticatedContext("tombstone-owner").firestore();
  const user = doc(owner, "users/tombstone-owner");
  await assertSucceeds(setDoc(user, { generation: 0, backupIds: [], activeUploadBackupId: null, latestCompleteBackupId: null, latestCompletedAt: null, latestSourceInstallationId: null }));
  await environment.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "accountDeletionTombstones/tombstone-owner"), { operationId: "receipt" });
    await deleteDoc(doc(context.firestore(), "users/tombstone-owner"));
  });
  await assertFails(setDoc(user, { generation: 0, backupIds: [], activeUploadBackupId: null, latestCompleteBackupId: null, latestCompletedAt: null, latestSourceInstallationId: null }));
  await assertFails(getDoc(user));
});

test("tombstones and durable deletion jobs are admin-only for every client", async () => {
  for (const context of [environment.unauthenticatedContext(), environment.authenticatedContext("tombstone-owner"), environment.authenticatedContext("other")]) {
    for (const path of ["accountDeletionTombstones/tombstone-owner", "accountDeletionJobs/receipt", "accountDeletionReceipts/receipt", "accountDeletionReservationQuotas/tombstone-owner"]) {
      const ref = doc(context.firestore(), path);
      await assertFails(setDoc(ref, { operationId: "receipt" }));
      await assertFails(getDoc(ref));
      await assertFails(deleteDoc(ref));
    }
  }
});
