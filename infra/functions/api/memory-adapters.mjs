import { ApiError } from "./app.mjs";

const copy = (value) => value === undefined ? undefined : structuredClone(value);

export class MemoryRepository {
  constructor() { this.drives = new Map(); this.idempotency = new Map(); this.cursors = new Map(); }

  async createDraft({ userId, idempotencyKey, payloadHash, driveId, draft, now, expiresAtEpoch }) {
    const key = `${userId}:${idempotencyKey}`; const existing = this.idempotency.get(key);
    if (existing) {
      if (existing.payloadHash !== payloadHash) throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "같은 초안을 안전하게 확인하지 못했어요. 원본은 보관되어 있습니다.");
      return { created: false, drive: copy(this.drives.get(existing.driveId)) };
    }
    const drive = { ...copy(draft), id: driveId, ownerId: userId, status: "DRAFT", version: 1, createdAt: now, updatedAt: now };
    this.drives.set(driveId, drive); this.idempotency.set(key, { driveId, payloadHash, expiresAtEpoch });
    return { created: true, drive: copy(drive) };
  }

  async getDrive(driveId) { return copy(this.drives.get(driveId)); }

  async publishDrive({ driveId, userId, expectedVersion, now }) {
    const drive = this.drives.get(driveId);
    if (!drive || drive.ownerId !== userId || drive.status === "DELETED") throw new ApiError(404, "NOT_FOUND", "드라이브를 찾을 수 없어요.");
    if (drive.status === "PUBLISHED") return copy(drive);
    if (drive.status !== "DRAFT" || drive.version !== expectedVersion) throw new ApiError(409, "VERSION_CONFLICT", "다른 변경이 있어 최신 내용을 확인해 주세요.");
    Object.assign(drive, { status: "PUBLISHED", version: drive.version + 1, publishedAt: now, updatedAt: now });
    return copy(drive);
  }

  async updateDrive({ driveId, userId, expectedVersion, changes, now }) {
    const drive = this.drives.get(driveId);
    if (!drive || drive.ownerId !== userId || drive.status === "DELETED") throw new ApiError(404, "NOT_FOUND", "드라이브를 찾을 수 없어요.");
    if (drive.version !== expectedVersion) throw new ApiError(409, "VERSION_CONFLICT", "다른 변경이 있어 최신 내용을 확인해 주세요.");
    Object.assign(drive, copy(changes), { version: drive.version + 1, updatedAt: now });
    return copy(drive);
  }

  async deleteDrive({ driveId, userId, now }) {
    const drive = this.drives.get(driveId);
    if (!drive || drive.ownerId !== userId) throw new ApiError(404, "NOT_FOUND", "드라이브를 찾을 수 없어요.");
    if (drive.status === "DELETED") return copy(drive);
    drive.status = "DELETED"; drive.version += 1; drive.updatedAt = now;
    return copy(drive);
  }

  async listPublished({ limit, startKey }) {
    const items = [...this.drives.values()].filter((drive) => drive.status === "PUBLISHED")
      .sort((left, right) => right.publishedAt.localeCompare(left.publishedAt) || right.id.localeCompare(left.id));
    const start = startKey ? Math.max(0, items.findIndex((drive) => drive.id === startKey) + 1) : 0;
    const page = items.slice(start, start + limit); const nextKey = start + limit < items.length ? page.at(-1).id : null;
    return { items: copy(page), nextKey };
  }

  async saveCursor(cursor) { this.cursors.set(cursor.tokenHash, copy(cursor)); }
  async loadCursor(tokenHash, nowEpoch) {
    const cursor = this.cursors.get(tokenHash);
    return cursor && cursor.expiresAtEpoch > nowEpoch ? copy(cursor) : null;
  }
}

export class MemoryAssets {
  constructor() { this.uploaded = new Set(); this.deleted = new Set(); this.invalid = new Set(); }
  markUploaded(driveId, kind) { this.uploaded.add(`${driveId}:${kind}`); }
  markInvalid(driveId, kind) { this.invalid.add(`${driveId}:${kind}`); }
  async issueUploads(drive) {
    return drive.assets.map((asset) => ({
      kind: asset.kind, method: "PUT", url: `https://upload.invalid/${drive.id}/${asset.kind.toLowerCase()}`,
      requiredHeaders: { "Content-Type": asset.contentType }, maxBytes: asset.sizeBytes, expiresInSeconds: 600,
    }));
  }
  async verifyUploads(drive) {
    const missing = drive.assets.filter((asset) => !this.uploaded.has(`${drive.id}:${asset.kind}`)).map((asset) => asset.kind);
    const invalid = drive.assets.filter((asset) => this.invalid.has(`${drive.id}:${asset.kind}`)).map((asset) => asset.kind);
    return { complete: missing.length === 0 && invalid.length === 0, missing, invalid };
  }
  async issueReadUrl(drive, kind) { return `https://read.invalid/${drive.id}/${kind.toLowerCase()}`; }
  async deleteAssets(drive) { drive.assets.forEach((asset) => this.deleted.add(`${drive.id}:${asset.kind}`)); }
}
