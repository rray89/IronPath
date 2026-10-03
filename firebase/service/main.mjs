import { initializeApp, deleteApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { DeletionService, startWorker } from "./deletion-service.mjs";
import { createDeletionServer } from "./http-server.mjs";

// No production startup exists until deployment, TLS, worker supervision and the
// permanent-fence rules have been explicitly approved and verified together.
const env = process.env;
const loopback = /^127\.0\.0\.1:[1-9][0-9]{0,4}$/;
if (env.IRONPATH_DELETION_MODE !== "emulator" ||
    !/^demo-[a-z0-9-]+$/.test(env.GCLOUD_PROJECT ?? "") ||
    !loopback.test(env.FIRESTORE_EMULATOR_HOST ?? "") ||
    !loopback.test(env.FIREBASE_AUTH_EMULATOR_HOST ?? "")) {
  throw new Error("Deletion startup refused: explicit demo project and loopback Auth/Firestore emulators required.");
}
// Emulator execution must never probe the cloud metadata server for credentials.
env.METADATA_SERVER_DETECTION = "none";
const app = initializeApp({ projectId: env.GCLOUD_PROJECT });
const service = new DeletionService({ firestore: getFirestore(app), auth: getAuth(app), projectId: env.GCLOUD_PROJECT });
const stopWorker = startWorker(service);
const server = createDeletionServer(service);
server.listen(Number(env.PORT ?? 8787), "127.0.0.1", () => {
  process.send?.({ port: server.address().port });
});
let stopping = false;
async function shutdown() {
  if (stopping) return;
  stopping = true;
  await new Promise((resolve) => server.close(resolve));
  await stopWorker();
  await deleteApp(app);
}
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
