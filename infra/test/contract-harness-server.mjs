#!/usr/bin/env node
import { createHash } from "node:crypto";
import { createInterface } from "node:readline";
import { createHandler } from "../functions/api/app.mjs";
import { MemoryRepository } from "../functions/api/memory-adapters.mjs";
import { validateRouteDocument } from "../functions/api/route-validation.mjs";

const NOW = new Date("2026-11-01T10:00:00Z");
const DRIVE_ID = "contract-drive-0001";
const UPLOAD_HOST = "upload.contract.invalid";
const FORBIDDEN_KEYS = new Set(["lat", "lng", "latitude", "longitude", "vin", "speed", "velocity", "acceleration", "brake", "steering", "gear", "rpm", "vehicleid", "deviceid", "rawvehicle", "originalcontentref"]);
const SENTINELS = ["37.566535", "126.977969", "KMHXX00XXXX000001", "173.25", "raw-vehicle-secret", "device-private-001", "vehicle-private-001"];
const digestHex = (bytes) => createHash("sha256").update(bytes).digest("hex");
const digestBase64 = (hex) => Buffer.from(hex, "hex").toString("base64");

class ContractAssets {
  constructor() { this.objects = new Map(); }

  async issueUploads(drive) {
    return drive.assets.map((asset) => ({
      kind: asset.kind,
      method: "PUT",
      url: `https://${UPLOAD_HOST}/${drive.id}/${asset.kind.toLowerCase()}?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=CONTRACTACCESS001%2F20261101%2Fap-northeast-2%2Fs3%2Faws4_request&X-Amz-Date=20261101T100000Z&X-Amz-Expires=600&X-Amz-SignedHeaders=content-type%3Bhost%3Bx-amz-checksum-sha256&X-Amz-Signature=${"a".repeat(64)}`,
      requiredHeaders: {
        "Content-Type": asset.contentType,
        "x-amz-checksum-sha256": digestBase64(asset.sha256),
      },
      maxBytes: asset.sizeBytes,
      expiresInSeconds: 600,
    }));
  }

  put(method, url, headers, bytes) {
    if (method !== "PUT") return { status: 405 };
    if (url.host !== UPLOAD_HOST || headers.authorization) return { status: 403 };
    const match = url.pathname.match(/^\/([^/]+)\/(cover|route)$/);
    if (!match) return { status: 404 };
    const drive = repository.drives.get(match[1]);
    const kind = match[2].toUpperCase();
    const manifest = drive?.assets.find((asset) => asset.kind === kind);
    const contentType = header(headers, "content-type");
    const checksum = header(headers, "x-amz-checksum-sha256");
    if (!manifest || bytes.length !== manifest.sizeBytes || contentType !== manifest.contentType ||
        digestHex(bytes) !== manifest.sha256 || checksum !== digestBase64(manifest.sha256)) return { status: 422 };
    if (kind === "ROUTE" && !validateRouteDocument(bytes.toString("utf8")).valid) return { status: 422 };
    this.objects.set(`${drive.id}:${kind}`, Buffer.from(bytes));
    return { status: 200 };
  }

  async verifyUploads(drive) {
    const missing = []; const invalid = [];
    for (const asset of drive.assets) {
      const bytes = this.objects.get(`${drive.id}:${asset.kind}`);
      if (!bytes) missing.push(asset.kind);
      else if (bytes.length !== asset.sizeBytes || digestHex(bytes) !== asset.sha256 ||
        (asset.kind === "ROUTE" && !validateRouteDocument(bytes.toString("utf8")).valid)) invalid.push(asset.kind);
    }
    return { complete: missing.length === 0 && invalid.length === 0, missing, invalid };
  }

  async issueReadUrl(drive, kind) { return `https://read.contract.invalid/${drive.id}/${kind.toLowerCase()}`; }
  async deleteAssets(drive) { for (const asset of drive.assets) this.objects.delete(`${drive.id}:${asset.kind}`); }
}

const repository = new MemoryRepository();
const assets = new ContractAssets();
const logs = [];
const captures = [];
const responses = [];
const logger = {
  info(value) { logs.push({ requestId: value.requestId, routeKey: value.routeKey, status: value.status, ...(value.errorCode ? { errorCode: value.errorCode } : {}) }); },
  warn(value) { logs.push({ requestId: value.requestId, routeKey: value.routeKey, status: value.status, errorCode: value.errorCode }); },
};
const handler = createHandler({
  repository, assets, consentPolicyVersion: "2026-11-01", clock: () => new Date(NOW),
  ids: { driveId: () => DRIVE_ID, cursor: () => "contract_cursor_000000000000000000000000" }, logger,
});

function header(headers, name) {
  return Object.entries(headers ?? {}).find(([key]) => key.toLowerCase() === name.toLowerCase())?.[1] ?? null;
}

function routeFor(method, pathname) {
  if (method === "POST" && pathname === "/drives") return ["POST /drives", {}];
  if (method === "GET" && pathname === "/feed") return ["GET /feed", {}];
  const match = pathname.match(/^\/drives\/([^/]+)(?:\/(uploads|publish))?$/);
  if (!match) return ["UNKNOWN", {}];
  const suffix = match[2] ? `/${match[2]}` : "";
  return [`${method} /drives/{driveId}${suffix}`, { driveId: match[1] }];
}

function forbiddenKeys(value, found = new Set()) {
  if (Array.isArray(value)) value.forEach((item) => forbiddenKeys(item, found));
  else if (value && typeof value === "object") Object.entries(value).forEach(([key, child]) => {
    if (FORBIDDEN_KEYS.has(key.toLowerCase())) found.add(key.toLowerCase());
    forbiddenKeys(child, found);
  });
  return [...found].sort();
}

function safeSnapshot() {
  const parsedBodies = [...captures.map((capture) => capture.bodyText), ...responses].flatMap((bodyText) => {
    if (!bodyText) return [];
    try { return [JSON.parse(bodyText)]; } catch { return []; }
  });
  const routes = [...assets.objects.entries()].filter(([key]) => key.endsWith(":ROUTE")).map(([, bytes]) => JSON.parse(bytes.toString("utf8")));
  const repositoryValues = [...repository.drives.values()];
  const inspected = JSON.stringify([...parsedBodies, ...routes, ...repositoryValues, ...logs]);
  return {
    requestCount: captures.length,
    driveCount: repository.drives.size,
    objectCount: assets.objects.size,
    forbiddenKeys: forbiddenKeys([...parsedBodies, ...routes, ...repositoryValues, ...logs]),
    sentinelMatches: SENTINELS.filter((sentinel) => inspected.includes(sentinel)).length,
    routeKeys: routes.map((route) => Object.keys(route).sort()),
    pointKeys: routes.flatMap((route) => route.path.map((point) => Object.keys(point).sort())),
    objectAuthorizationCount: captures.filter((item) => item.target === "object" && item.hasAuthorization).length,
    logKeys: logs.map((entry) => Object.keys(entry).sort()),
    createStatuses: captures.filter((item) => item.routeKey === "POST /drives").map((item) => item.status),
    deleteStatuses: captures.filter((item) => item.routeKey === "DELETE /drives/{driveId}").map((item) => item.status),
    deleteIdempotencyKeys: captures.filter((item) => item.routeKey === "DELETE /drives/{driveId}").map((item) => item.idempotencyKey),
  };
}

async function dispatch(message) {
  if (message.target === "control" && message.pathAndQuery === "/snapshot") {
    return { status: 200, headers: { "content-type": "application/json" }, body: Buffer.from(JSON.stringify(safeSnapshot())) };
  }
  const body = Buffer.from(message.bodyBase64 ?? "", "base64");
  const headers = Object.fromEntries(Object.entries(message.headers ?? {}).map(([key, value]) => [key.toLowerCase(), value]));
  const capture = {
    target: message.target,
    method: message.method,
    path: new URL(message.pathAndQuery, "https://api.contract.invalid").pathname,
    hasAuthorization: Boolean(headers.authorization),
    bodyText: message.target === "api" ? body.toString("utf8") : "",
    routeKey: null,
    status: null,
    idempotencyKey: null,
  };
  captures.push(capture);
  if (message.target === "object") {
    const result = assets.put(message.method, new URL(message.pathAndQuery), headers, body);
    capture.status = result.status;
    return { status: result.status, headers: {}, body: Buffer.alloc(0) };
  }
  const url = new URL(message.pathAndQuery, "https://api.contract.invalid");
  const pathname = url.pathname.replace(/^\/contract(?=\/)/, "");
  const [routeKey, pathParameters] = routeFor(message.method, pathname);
  capture.routeKey = routeKey;
  if (routeKey === "DELETE /drives/{driveId}") capture.idempotencyKey = header(headers, "idempotency-key");
  const token = header(headers, "authorization");
  const claims = token === "Bearer contract-user-a" ? { sub: "contract-user-a" } : token === "Bearer contract-user-b" ? { sub: "contract-user-b" } : null;
  const queryStringParameters = Object.fromEntries(url.searchParams.entries());
  const event = {
    headers, body: body.toString("utf8"), pathParameters, queryStringParameters,
    requestContext: { requestId: `operation-${String(captures.length).padStart(3, "0")}`, routeKey, ...(claims ? { authorizer: { jwt: { claims } } } : {}) },
  };
  const result = await handler(event);
  capture.status = result.statusCode;
  return { status: result.statusCode, headers: result.headers, body: Buffer.from(result.body) };
}

const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
for await (const line of lines) {
  let id = "unknown";
  try {
    const message = JSON.parse(line); id = typeof message.id === "string" ? message.id : id;
    const result = await dispatch(message);
    if (message.target !== "control") responses.push(result.body.toString("utf8"));
    process.stdout.write(`${JSON.stringify({ id, status: result.status, headers: result.headers, bodyBase64: result.body.toString("base64") })}\n`);
  } catch {
    process.stdout.write(`${JSON.stringify({ id, status: 500, headers: {}, bodyBase64: Buffer.from('{"error":{"code":"HARNESS_ERROR","retryable":false}}').toString("base64") })}\n`);
  }
}
