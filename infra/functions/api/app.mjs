import { createHash, randomBytes, randomUUID } from "node:crypto";

const ASSET_LIMITS = {
  COVER: { types: new Set(["image/jpeg", "image/webp"]), maxBytes: 10 * 1024 * 1024 },
  ROUTE: { types: new Set(["application/json"]), maxBytes: 1024 * 1024 },
};
const EDITABLE_FIELDS = new Set(["title", "summary"]);

export class ApiError extends Error {
  constructor(status, code, message, { retryable = false, fields = [] } = {}) {
    super(message); this.status = status; this.code = code; this.retryable = retryable; this.fields = fields;
  }
}

const response = (statusCode, body, requestId) => ({
  statusCode,
  headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-request-id": requestId },
  body: body === null ? "" : JSON.stringify(body),
});

const canonical = (value) => {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}`;
  return JSON.stringify(value);
};
const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const safeId = (value) => typeof value === "string" && /^[A-Za-z0-9_-]{8,128}$/.test(value);
const header = (event, name) => Object.entries(event?.headers ?? {}).find(([key]) => key.toLowerCase() === name.toLowerCase())?.[1] ?? null;

function parseBody(event) {
  const raw = event?.body ?? "";
  if (Buffer.byteLength(raw) > 64 * 1024) throw new ApiError(413, "REQUEST_TOO_LARGE", "요청 크기를 줄여 주세요.");
  try {
    const parsed = raw ? JSON.parse(raw) : {};
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("body must be an object");
    return parsed;
  }
  catch { throw new ApiError(400, "INVALID_JSON", "요청 형식을 확인해 주세요."); }
}

function normalizeAssets(assets) {
  if (!Array.isArray(assets) || assets.length < 1 || assets.length > 2) throw new ApiError(422, "VALIDATION_ERROR", "업로드할 파일 정보를 확인해 주세요.", { fields: ["assets"] });
  const seen = new Set();
  return assets.map((asset, index) => {
    const kind = String(asset?.kind ?? "").toUpperCase();
    const limit = ASSET_LIMITS[kind];
    if (!limit || seen.has(kind)) throw new ApiError(422, "VALIDATION_ERROR", "업로드할 파일 정보를 확인해 주세요.", { fields: [`assets.${index}.kind`] });
    seen.add(kind);
    const sizeBytes = Number(asset.sizeBytes);
    const contentType = String(asset.contentType ?? "").toLowerCase();
    const digest = String(asset.sha256 ?? "").toLowerCase();
    if (!Number.isSafeInteger(sizeBytes) || sizeBytes < 1 || sizeBytes > limit.maxBytes) throw new ApiError(413, "ASSET_TOO_LARGE", "파일 크기를 줄인 뒤 다시 시도해 주세요.", { fields: [`assets.${index}.sizeBytes`] });
    if (!limit.types.has(contentType)) throw new ApiError(415, "UNSUPPORTED_MEDIA", "지원하지 않는 파일 형식이에요.", { fields: [`assets.${index}.contentType`] });
    if (!/^[a-f0-9]{64}$/.test(digest)) throw new ApiError(422, "VALIDATION_ERROR", "파일 정보를 확인해 주세요.", { fields: [`assets.${index}.sha256`] });
    return { kind, sizeBytes, contentType, sha256: digest };
  }).sort((left, right) => left.kind.localeCompare(right.kind));
}

function normalizeDraft(body) {
  const title = typeof body.title === "string" ? body.title.trim() : "";
  const summary = typeof body.summary === "string" ? body.summary.trim() : "";
  const distanceMeters = Number(body.distanceMeters); const durationSeconds = Number(body.durationSeconds); const highlightCount = Number(body.highlightCount);
  const consent = body.consent ?? {}; const fields = [];
  if (!safeId(body.clientDraftId)) fields.push("clientDraftId");
  if (body.schemaVersion !== 1) fields.push("schemaVersion");
  if (!title || [...title].length > 40) fields.push("title");
  if ([...summary].length > 160) fields.push("summary");
  if (body.visibility !== "PUBLIC") fields.push("visibility");
  if (!Number.isSafeInteger(distanceMeters) || distanceMeters < 0 || distanceMeters > 2_000_000) fields.push("distanceMeters");
  if (!Number.isSafeInteger(durationSeconds) || durationSeconds < 0 || durationSeconds > 259_200) fields.push("durationSeconds");
  if (!Number.isSafeInteger(highlightCount) || highlightCount < 0 || highlightCount > 100) fields.push("highlightCount");
  if (consent.routeShare !== true || consent.coverShare !== true || typeof consent.policyVersion !== "string" || consent.policyVersion.length < 1 || consent.policyVersion.length > 32) fields.push("consent");
  if (fields.length) throw new ApiError(422, "VALIDATION_ERROR", "입력 내용을 다시 확인해 주세요.", { fields });
  const assets = normalizeAssets(body.assets);
  if (!assets.some((asset) => asset.kind === "COVER") || !assets.some((asset) => asset.kind === "ROUTE")) throw new ApiError(422, "VALIDATION_ERROR", "대표 이미지와 공개용 경로 파일이 필요해요.", { fields: ["assets"] });
  return { clientDraftId: body.clientDraftId, schemaVersion: 1, title, summary, visibility: "PUBLIC", distanceMeters, durationSeconds, highlightCount, consent: { routeShare: true, coverShare: true, policyVersion: consent.policyVersion }, assets };
}

function publicDrive(drive) {
  return {
    id: drive.id, status: drive.status, version: drive.version, title: drive.title, summary: drive.summary,
    distanceMeters: drive.distanceMeters, durationSeconds: drive.durationSeconds, highlightCount: drive.highlightCount,
    createdAt: drive.createdAt, ...(drive.publishedAt ? { publishedAt: drive.publishedAt } : {}),
  };
}

async function uploadResponse(assets, drive, now) {
  const uploads = await assets.issueUploads(drive);
  return uploads.map(({ expiresInSeconds, ...upload }) => ({ ...upload, expiresAt: new Date(now.getTime() + expiresInSeconds * 1000).toISOString() }));
}

export function createHandler({ repository, assets, clock = () => new Date(), ids = {}, logger = console }) {
  const newDriveId = ids.driveId ?? (() => randomUUID()); const newCursor = ids.cursor ?? (() => randomBytes(32).toString("base64url"));
  return async function apiHandler(event) {
    const requestId = event?.requestContext?.requestId || randomUUID(); const routeKey = event?.requestContext?.routeKey ?? "UNKNOWN";
    let status = 500; let errorCode = null;
    try {
      const userId = event?.requestContext?.authorizer?.jwt?.claims?.sub;
      if (!userId) throw new ApiError(401, "UNAUTHORIZED", "로그인이 만료됐어요. 다시 연결해 주세요.");
      const driveId = event?.pathParameters?.driveId; const now = clock(); let result;

      if (routeKey === "GET /users/me") {
        result = response(200, { id: userId }, requestId);
      } else if (routeKey === "POST /drives") {
        const idempotencyKey = header(event, "idempotency-key");
        if (!safeId(idempotencyKey)) throw new ApiError(400, "IDEMPOTENCY_KEY_REQUIRED", "초안 식별 정보를 확인해 주세요.");
        const draft = normalizeDraft(parseBody(event)); const payloadHash = sha256(canonical(draft));
        const created = await repository.createDraft({ userId, idempotencyKey, payloadHash, driveId: newDriveId(), draft, now: now.toISOString(), expiresAtEpoch: Math.floor(now.getTime() / 1000) + 2_592_000 });
        result = response(created.created ? 201 : 200, { drive: publicDrive(created.drive), uploads: await uploadResponse(assets, created.drive, now) }, requestId);
      } else if (routeKey === "POST /drives/{driveId}/uploads") {
        if (!safeId(driveId)) throw new ApiError(404, "NOT_FOUND", "드라이브를 찾을 수 없어요.");
        const drive = await repository.getDrive(driveId);
        if (!drive || drive.ownerId !== userId || drive.status !== "DRAFT") throw new ApiError(404, "NOT_FOUND", "드라이브를 찾을 수 없어요.");
        const requested = normalizeAssets(parseBody(event).assets);
        for (const asset of requested) {
          const expected = drive.assets.find((item) => item.kind === asset.kind);
          if (!expected || canonical(expected) !== canonical(asset)) throw new ApiError(409, "ASSET_MISMATCH", "파일 정보를 안전하게 확인하지 못했어요.");
        }
        result = response(200, { drive: publicDrive(drive), uploads: await uploadResponse(assets, { ...drive, assets: requested }, now) }, requestId);
      } else if (routeKey === "POST /drives/{driveId}/publish") {
        const drive = await repository.getDrive(driveId);
        if (!drive || drive.ownerId !== userId) throw new ApiError(404, "NOT_FOUND", "드라이브를 찾을 수 없어요.");
        const body = parseBody(event);
        if (!Number.isSafeInteger(body.expectedVersion)) throw new ApiError(422, "VALIDATION_ERROR", "게시 정보를 확인해 주세요.", { fields: ["expectedVersion"] });
        if (drive.status !== "PUBLISHED") {
          const verified = await assets.verifyUploads(drive);
          if (verified.invalid?.length) throw new ApiError(422, "ROUTE_ASSET_INVALID", "공개용 경로 파일에서 공유할 수 없는 정보를 발견했어요.", { fields: verified.invalid });
          if (!verified.complete) throw new ApiError(409, "UPLOAD_INCOMPLETE", "남은 파일 업로드를 이어서 진행할게요.", { retryable: true, fields: verified.missing ?? [] });
        }
        const published = await repository.publishDrive({ driveId, userId, expectedVersion: body.expectedVersion, now: now.toISOString() });
        result = response(200, { drive: publicDrive(published) }, requestId);
      } else if (routeKey === "GET /feed") {
        const rawLimit = event?.queryStringParameters?.limit; const limit = rawLimit === undefined ? 20 : Number(rawLimit);
        if (!Number.isSafeInteger(limit) || limit < 1 || limit > 50) throw new ApiError(400, "VALIDATION_ERROR", "페이지 크기를 확인해 주세요.", { fields: ["limit"] });
        const cursor = event?.queryStringParameters?.cursor; let startKey = null;
        if (cursor) {
          if (!/^[A-Za-z0-9_-]{32,128}$/.test(cursor)) throw new ApiError(400, "INVALID_CURSOR", "피드를 처음부터 다시 불러올게요.");
          const saved = await repository.loadCursor(sha256(cursor), Math.floor(now.getTime() / 1000));
          if (!saved || saved.limit !== limit) throw new ApiError(400, "INVALID_CURSOR", "피드를 처음부터 다시 불러올게요.");
          startKey = saved.startKey;
        }
        const page = await repository.listPublished({ limit, startKey });
        const items = await Promise.all(page.items.map(async (drive) => ({ ...publicDrive(drive), coverUrl: await assets.issueReadUrl(drive, "COVER") })));
        let nextCursor = null;
        if (page.nextKey) {
          nextCursor = newCursor();
          await repository.saveCursor({ tokenHash: sha256(nextCursor), startKey: page.nextKey, limit, expiresAtEpoch: Math.floor(now.getTime() / 1000) + 900 });
        }
        result = response(200, { items, nextCursor }, requestId);
      } else if (routeKey === "GET /drives/{driveId}") {
        const drive = await repository.getDrive(driveId);
        if (!drive || (drive.status !== "PUBLISHED" && drive.ownerId !== userId) || drive.status === "DELETED") throw new ApiError(404, "NOT_FOUND", "드라이브를 찾을 수 없어요.");
        const detail = publicDrive(drive);
        detail.coverUrl = await assets.issueReadUrl(drive, "COVER"); detail.routeUrl = await assets.issueReadUrl(drive, "ROUTE"); detail.routeSchemaVersion = 1;
        result = response(200, { drive: detail }, requestId);
      } else if (routeKey === "PATCH /drives/{driveId}") {
        const body = parseBody(event); const keys = Object.keys(body);
        if (!Number.isSafeInteger(body.expectedVersion) || keys.length < 2 || keys.some((key) => key !== "expectedVersion" && !EDITABLE_FIELDS.has(key))) throw new ApiError(422, "VALIDATION_ERROR", "수정할 내용을 확인해 주세요.");
        const changes = {};
        if ("title" in body) { changes.title = String(body.title).trim(); if (!changes.title || [...changes.title].length > 40) throw new ApiError(422, "VALIDATION_ERROR", "제목을 확인해 주세요.", { fields: ["title"] }); }
        if ("summary" in body) { changes.summary = String(body.summary).trim(); if ([...changes.summary].length > 160) throw new ApiError(422, "VALIDATION_ERROR", "설명을 확인해 주세요.", { fields: ["summary"] }); }
        const updated = await repository.updateDrive({ driveId, userId, expectedVersion: body.expectedVersion, changes, now: now.toISOString() });
        result = response(200, { drive: publicDrive(updated) }, requestId);
      } else if (routeKey === "DELETE /drives/{driveId}") {
        const deleted = await repository.deleteDrive({ driveId, userId, now: now.toISOString() });
        if (deleted) {
          try { await assets.deleteAssets(deleted); }
          catch { logger.warn?.({ requestId, routeKey, status: 204, errorCode: "ASSET_CLEANUP_PENDING" }); }
        }
        result = response(204, null, requestId);
      } else {
        throw new ApiError(404, "NOT_FOUND", "요청한 기능을 찾을 수 없어요.");
      }
      status = result.statusCode; return result;
    } catch (error) {
      const safe = error instanceof ApiError ? error : new ApiError(500, "INTERNAL_ERROR", "연결에 문제가 생겼어요. 초안은 기기에 안전하게 보관되어 있습니다.", { retryable: true });
      status = safe.status; errorCode = safe.code;
      return response(safe.status, { error: { code: safe.code, message: safe.message, retryable: safe.retryable, requestId, ...(safe.fields.length ? { fields: safe.fields } : {}) } }, requestId);
    } finally {
      try { logger.info?.({ requestId, routeKey, status, ...(errorCode ? { errorCode } : {}) }); } catch {}
    }
  };
}

let productionHandler;
export async function handler(event) {
  if (!productionHandler) {
    const { createProductionServices } = await import("./production-adapters.mjs");
    productionHandler = createHandler(createProductionServices());
  }
  return productionHandler(event);
}
