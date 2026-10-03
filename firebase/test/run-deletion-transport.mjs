import { fork, spawn } from "node:child_process";
import { once } from "node:events";

// Called only by emulators:exec; main.mjs validates the synthetic project/hosts.
const service = fork("service/main.mjs", [], {
  env: { ...process.env, IRONPATH_DELETION_MODE: "emulator", IRONPATH_DELETION_SERVICE_INSTANCE_ID: "ironpath-deletion-emulator-v2", PORT: "0" },
  stdio: ["ignore", "inherit", "inherit", "ipc"],
});
let gradle;
try {
  const port = await new Promise((resolve, reject) => {
    const deadline = setTimeout(() => reject(new Error("Deletion service startup deadline")), 15000);
    service.once("message", (message) => {
      clearTimeout(deadline);
      if (!Number.isInteger(message.port) || message.port < 1) reject(new Error("Invalid service port"));
      else resolve(message.port);
    });
    service.once("exit", () => { clearTimeout(deadline); reject(new Error("Deletion service startup refused")); });
    service.once("error", (error) => { clearTimeout(deadline); reject(error); });
  });
  gradle = spawn("../gradlew", ["--no-daemon", "--max-workers=2", "--project-dir", "..", ":app:deletionTransportTest", "--console=plain"], {
    env: { ...process.env, IRONPATH_DELETION_ENDPOINT: `http://127.0.0.1:${port}` },
    stdio: "inherit",
  });
  const [code] = await once(gradle, "exit");
  process.exitCode = code ?? 1;
} finally {
  if (gradle && gradle.exitCode === null) gradle.kill("SIGTERM");
  if (service.exitCode === null) {
    const exited = once(service, "exit");
    service.kill("SIGTERM");
    await exited;
  }
}
