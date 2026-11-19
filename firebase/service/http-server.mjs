import { createServer } from "node:http";
import { RequestError } from "./deletion-service.mjs";

export function createDeletionServer(service) {
  const server = createServer(async (request, response) => {
    response.setHeader("Content-Type", "application/json");
    response.setHeader("Cache-Control", "no-store");
    try {
      let result;
      if (request.method === "GET" && request.url === "/v2/capabilities") {
        result = service.capabilities();
      } else if (request.method === "POST" && request.url === "/v2/reservations") {
        const body = await readBody(request);
        if (Object.keys(body).length !== 1 || !("operationId" in body)) throw new RequestError(400, "INVALID_REQUEST");
        result = await service.reserve(body.operationId, request.headers["deletion-receipt"], bearer(request));
      } else if (request.method === "POST" && /^\/v2\/operations\/[^/]+\/(status|activate|cancel-unactivated)$/.test(request.url)) {
        const body = await readBody(request);
        if (Object.keys(body).length !== 0) throw new RequestError(400, "INVALID_REQUEST");
        const [, , , operationId, action] = request.url.split("/");
        const secret = request.headers["deletion-receipt"];
        if (action === "status") result = await service.status(operationId, secret);
        else if (action === "cancel-unactivated") result = await service.cancelUnactivated(operationId, secret);
        else {
          result = await service.activate(operationId, secret, bearer(request));
          // Only fresh, explicitly admitted activation may accelerate the worker.
          // Reservation, status and cancellation never enqueue or trigger work.
          if (result.state === "PENDING") void service.runPending().catch(() => {});
        }
      } else if (request.method === "POST" && /^\/v1\/deletions\/[^/]+\/resume$/.test(request.url)) {
        const body = await readBody(request, true);
        if (Object.keys(body).length !== 0) throw new RequestError(400, "INVALID_REQUEST");
        result = await service.resumeLegacy(request.url.split("/")[3]);
      } else {
        // v1 capabilities/new-start are deliberately unavailable. Unknown old
        // operations cannot silently acquire v2 authority or a new identity.
        throw new RequestError(404, "NOT_FOUND");
      }
      response.statusCode = result.state === "PENDING" ? 202 : 200;
      if (!response.destroyed && !response.writableEnded) response.end(JSON.stringify(result));
    } catch (error) {
      response.statusCode = error instanceof RequestError ? error.status : 503;
      if (!response.destroyed && !response.writableEnded) {
        response.end(JSON.stringify({ error: error instanceof RequestError ? error.code : "UNAVAILABLE" }));
      }
    }
  });
  server.requestTimeout = 10000;
  server.headersTimeout = 10000;
  server.maxConnections = 64;
  return server;
}

function bearer(request) {
  return /^Bearer ([^\s]+)$/.exec(request.headers.authorization ?? "")?.[1];
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
