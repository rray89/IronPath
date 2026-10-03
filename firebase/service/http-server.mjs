import { createServer } from "node:http";
import { RequestError } from "./deletion-service.mjs";

export function createDeletionServer(service) {
  const server = createServer(async (request, response) => {
    response.setHeader("Content-Type", "application/json");
    response.setHeader("Cache-Control", "no-store");
    try {
      let result;
      if (request.method === "GET" && request.url === "/v1/capabilities") {
        result = service.capabilities();
      } else if (request.method === "POST" && request.url === "/v1/deletions") {
        const body = await readBody(request);
        if (!body || typeof body !== "object" || Array.isArray(body) || Object.keys(body).length !== 1 || !("operationId" in body)) {
          throw new RequestError(400, "INVALID_REQUEST");
        }
        const match = /^Bearer ([^\s]+)$/.exec(request.headers.authorization ?? "");
        result = await service.start(body.operationId, match?.[1]);
        // Reduce ordinary latency; the independent startup/periodic worker remains
        // responsible for durable recovery if this request/process disappears.
        if (result.state === "PENDING") void service.runPending().catch(() => {});
      } else if (request.method === "POST" && /^\/v1\/deletions\/[^/]+\/resume$/.test(request.url)) {
        // The UUID is an unguessable persisted recovery capability. This endpoint
        // only returns coarse status; it cannot create/rebind a job or reveal UID.
        const body = await readBody(request, true);
        if (Object.keys(body).length !== 0) throw new RequestError(400, "INVALID_REQUEST");
        result = await service.resume(request.url.split("/")[3]);
      } else {
        throw new RequestError(404, "NOT_FOUND");
      }
      response.statusCode = result.state === "PENDING" ? 202 : 200;
      response.end(JSON.stringify(result));
    } catch (error) {
      response.statusCode = error instanceof RequestError ? error.status : 503;
      response.end(JSON.stringify({ error: error instanceof RequestError ? error.code : "UNAVAILABLE" }));
    }
  });
  server.requestTimeout = 10000;
  server.headersTimeout = 10000;
  server.maxConnections = 64;
  return server;
}

async function readBody(request, allowEmpty = false) {
  let body = "";
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 1024) throw new RequestError(400, "INVALID_REQUEST");
    body += chunk;
  }
  if (!body && allowEmpty) return {};
  try {
    const value = JSON.parse(body);
    if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error();
    return value;
  } catch {
    throw new RequestError(400, "INVALID_REQUEST");
  }
}
