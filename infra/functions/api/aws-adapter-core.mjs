import { ApiError } from "./app.mjs";
import { validateRouteDocument } from "./route-validation.mjs";

const driveKey = (id) => ({ PK: `DRIVE#${id}`, SK: "META" });
const cursorKey = (hash) => ({ PK: `CURSOR#${hash}`, SK: "META" });
const cleanDrive = (item) => {
  if (!item) return null;
  const { PK, SK, FeedPK, FeedSK, entity, ...drive } = item;
  return drive;
};
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const cancellationCodes = (error) => (error?.CancellationReasons ?? []).map((reason) => reason?.Code ?? "None");

export class DynamoRepository {
  constructor({ tableName, client, commands, sleep = wait, random = Math.random, maxTransactionAttempts = 4 }) {
    this.tableName = tableName; this.client = client; this.commands = commands; this.sleep = sleep; this.random = random; this.maxTransactionAttempts = maxTransactionAttempts;
  }
  command(name, input) { return new this.commands[name](input); }

  async readReplay(key, payloadHash, { attempts = 4 } = {}) {
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      const result = await this.client.send(this.command("Get", { TableName: this.tableName, Key: key, ConsistentRead: true }));
      if (result.Item) {
        if (result.Item.payloadHash !== payloadHash) throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "같은 초안을 안전하게 확인하지 못했어요. 원본은 보관되어 있습니다.");
        for (let driveAttempt = 0; driveAttempt < attempts; driveAttempt += 1) {
          const drive = await this.getDrive(result.Item.driveId);
          if (drive) return { created: false, drive };
          if (driveAttempt + 1 < attempts) await this.sleep(10 * (2 ** driveAttempt));
        }
        throw new ApiError(503, "COMMIT_VISIBILITY_DELAY", "초안 생성을 확인하고 있어요. 잠시 후 다시 시도해 주세요.", { retryable: true });
      }
      if (attempt + 1 < attempts) await this.sleep(10 * (2 ** attempt));
    }
    return null;
  }

  async createDraft({ userId, idempotencyKey, payloadHash, driveId, draft, now, expiresAtEpoch }) {
    const idempotencyKeyRecord = { PK: `USER#${userId}`, SK: `IDEMPOTENCY#${idempotencyKey}` };
    const drive = { ...draft, id: driveId, ownerId: userId, status: "DRAFT", version: 1, createdAt: now, updatedAt: now };
    const input = { TransactItems: [
      { Put: { TableName: this.tableName, Item: { ...driveKey(driveId), entity: "DRIVE", ...drive }, ConditionExpression: "attribute_not_exists(PK)" } },
      { Put: { TableName: this.tableName, Item: { ...idempotencyKeyRecord, entity: "IDEMPOTENCY", driveId, payloadHash, expiresAtEpoch }, ConditionExpression: "attribute_not_exists(PK)" } },
    ] };

    for (let attempt = 0; attempt < this.maxTransactionAttempts; attempt += 1) {
      try {
        await this.client.send(this.command("TransactWrite", input));
        return { created: true, drive };
      } catch (error) {
        if (error?.name !== "TransactionCanceledException") throw error;
        const codes = cancellationCodes(error);
        if (codes[1] === "ConditionalCheckFailed") {
          const replay = await this.readReplay(idempotencyKeyRecord, payloadHash);
          if (replay) return replay;
          throw new ApiError(503, "COMMIT_VISIBILITY_DELAY", "초안 생성을 확인하고 있어요. 잠시 후 다시 시도해 주세요.", { retryable: true });
        }
        if (codes.includes("TransactionConflict")) {
          const replay = await this.readReplay(idempotencyKeyRecord, payloadHash, { attempts: 2 });
          if (replay) return replay;
          if (attempt + 1 < this.maxTransactionAttempts) {
            const backoff = Math.round((20 * (2 ** attempt)) * (0.75 + this.random() * 0.5));
            await this.sleep(backoff);
            continue;
          }
          throw new ApiError(503, "TRANSACTION_CONFLICT", "초안 저장 요청이 겹쳤어요. 자동으로 다시 시도할게요.", { retryable: true });
        }
        if (codes[0] === "ConditionalCheckFailed") throw new ApiError(409, "RESOURCE_CONFLICT", "초안 식별자가 겹쳤어요. 다시 시도해 주세요.", { retryable: true });
        throw error;
      }
    }
    throw new ApiError(503, "TRANSACTION_CONFLICT", "초안 저장 요청이 겹쳤어요. 자동으로 다시 시도할게요.", { retryable: true });
  }

  async getDrive(driveId) {
    const result = await this.client.send(this.command("Get", { TableName: this.tableName, Key: driveKey(driveId), ConsistentRead: true }));
    return cleanDrive(result.Item);
  }

  async publishDrive({ driveId, userId, expectedVersion, now }) {
    const current = await this.getDrive(driveId);
    if (!current || current.ownerId !== userId || current.status === "DELETED") throw new ApiError(404, "NOT_FOUND", "드라이브를 찾을 수 없어요.");
    if (current.status === "PUBLISHED") return current;
    try {
      const result = await this.client.send(this.command("Update", {
        TableName: this.tableName, Key: driveKey(driveId), ReturnValues: "ALL_NEW",
        ConditionExpression: "ownerId = :owner AND #status = :draft AND #version = :expected",
        UpdateExpression: "SET #status = :published, #version = :next, publishedAt = :now, updatedAt = :now, FeedPK = :feed, FeedSK = :sort",
        ExpressionAttributeNames: { "#status": "status", "#version": "version" },
        ExpressionAttributeValues: { ":owner": userId, ":draft": "DRAFT", ":published": "PUBLISHED", ":expected": expectedVersion, ":next": expectedVersion + 1, ":now": now, ":feed": "FEED#PUBLIC", ":sort": `${now}#${driveId}` },
      }));
      return cleanDrive(result.Attributes);
    } catch (error) {
      if (error?.name !== "ConditionalCheckFailedException") throw error;
      const latest = await this.getDrive(driveId);
      if (latest?.ownerId === userId && latest.status === "PUBLISHED") return latest;
      throw new ApiError(409, "VERSION_CONFLICT", "다른 변경이 있어 최신 내용을 확인해 주세요.");
    }
  }

  async updateDrive({ driveId, userId, expectedVersion, changes, now }) {
    const names = { "#version": "version" }; const values = { ":owner": userId, ":expected": expectedVersion, ":next": expectedVersion + 1, ":now": now, ":deleted": "DELETED" };
    const sets = ["#version = :next", "updatedAt = :now"];
    for (const [key, value] of Object.entries(changes)) { names[`#${key}`] = key; values[`:${key}`] = value; sets.push(`#${key} = :${key}`); }
    try {
      const result = await this.client.send(this.command("Update", { TableName: this.tableName, Key: driveKey(driveId), ReturnValues: "ALL_NEW", ConditionExpression: "ownerId = :owner AND #version = :expected AND #status <> :deleted", UpdateExpression: `SET ${sets.join(", ")}`, ExpressionAttributeNames: { ...names, "#status": "status" }, ExpressionAttributeValues: values }));
      return cleanDrive(result.Attributes);
    } catch (error) {
      if (error?.name !== "ConditionalCheckFailedException") throw error;
      const latest = await this.getDrive(driveId);
      if (!latest || latest.ownerId !== userId || latest.status === "DELETED") throw new ApiError(404, "NOT_FOUND", "드라이브를 찾을 수 없어요.");
      throw new ApiError(409, "VERSION_CONFLICT", "다른 변경이 있어 최신 내용을 확인해 주세요.");
    }
  }

  async deleteDrive({ driveId, userId, now }) {
    const current = await this.getDrive(driveId);
    if (!current || current.ownerId !== userId) throw new ApiError(404, "NOT_FOUND", "드라이브를 찾을 수 없어요.");
    if (current.status === "DELETED") return current;
    try {
      const result = await this.client.send(this.command("Update", { TableName: this.tableName, Key: driveKey(driveId), ReturnValues: "ALL_OLD", ConditionExpression: "ownerId = :owner AND #status <> :deleted", UpdateExpression: "SET #status = :deleted, #version = #version + :one, updatedAt = :now REMOVE FeedPK, FeedSK", ExpressionAttributeNames: { "#status": "status", "#version": "version" }, ExpressionAttributeValues: { ":owner": userId, ":deleted": "DELETED", ":one": 1, ":now": now } }));
      return cleanDrive(result.Attributes);
    } catch (error) {
      if (error?.name !== "ConditionalCheckFailedException") throw error;
      const latest = await this.getDrive(driveId);
      if (latest?.ownerId === userId && latest.status === "DELETED") return latest;
      throw new ApiError(409, "VERSION_CONFLICT", "다른 변경이 있어 최신 내용을 확인해 주세요.");
    }
  }

  async listPublished({ limit, startKey }) {
    const result = await this.client.send(this.command("Query", { TableName: this.tableName, IndexName: "FeedIndex", KeyConditionExpression: "FeedPK = :feed", ExpressionAttributeValues: { ":feed": "FEED#PUBLIC" }, ScanIndexForward: false, Limit: limit, ExclusiveStartKey: startKey || undefined }));
    return { items: (result.Items ?? []).map(cleanDrive), nextKey: result.LastEvaluatedKey ?? null };
  }
  async saveCursor(cursor) { await this.client.send(this.command("Put", { TableName: this.tableName, Item: { ...cursorKey(cursor.tokenHash), entity: "CURSOR", ...cursor } })); }
  async loadCursor(tokenHash, nowEpoch) {
    const result = await this.client.send(this.command("Get", { TableName: this.tableName, Key: cursorKey(tokenHash), ConsistentRead: true }));
    return result.Item && result.Item.expiresAtEpoch > nowEpoch ? { startKey: result.Item.startKey, limit: result.Item.limit, expiresAtEpoch: result.Item.expiresAtEpoch } : null;
  }
}

export class S3Assets {
  constructor({ bucket, client, commands, presign, uploadTtlSeconds = 600, readTtlSeconds = 300 }) {
    this.bucket = bucket; this.client = client; this.commands = commands; this.presign = presign; this.uploadTtlSeconds = uploadTtlSeconds; this.readTtlSeconds = readTtlSeconds;
  }
  command(name, input) { return new this.commands[name](input); }
  key(drive, kind) { return `drives/${drive.id}/${kind.toLowerCase()}`; }
  async issueUploads(drive) {
    return Promise.all(drive.assets.map(async (asset) => {
      const checksum = Buffer.from(asset.sha256, "hex").toString("base64");
      const command = this.command("PutObject", { Bucket: this.bucket, Key: this.key(drive, asset.kind), ContentType: asset.contentType, ChecksumSHA256: checksum });
      const options = { expiresIn: this.uploadTtlSeconds, signableHeaders: new Set(["content-type"]), unhoistableHeaders: new Set(["x-amz-checksum-sha256"]) };
      return { kind: asset.kind, method: "PUT", url: await this.presign(this.client, command, options), requiredHeaders: { "Content-Type": asset.contentType, "x-amz-checksum-sha256": checksum }, maxBytes: asset.sizeBytes, expiresInSeconds: this.uploadTtlSeconds };
    }));
  }
  async verifyUploads(drive) {
    const missing = []; const invalid = [];
    for (const asset of drive.assets) {
      try {
        const expectedChecksum = Buffer.from(asset.sha256, "hex").toString("base64");
        const result = await this.client.send(this.command("HeadObject", { Bucket: this.bucket, Key: this.key(drive, asset.kind), ChecksumMode: "ENABLED" }));
        if (result.ContentLength !== asset.sizeBytes || result.ContentType !== asset.contentType || result.ChecksumSHA256 !== expectedChecksum) invalid.push(asset.kind);
      } catch (error) {
        if (error?.$metadata?.httpStatusCode === 404 || error?.name === "NotFound") missing.push(asset.kind); else throw error;
      }
    }
    if (!missing.includes("ROUTE") && !invalid.includes("ROUTE")) {
      const route = await this.client.send(this.command("GetObject", { Bucket: this.bucket, Key: this.key(drive, "ROUTE") }));
      const validation = validateRouteDocument(await route.Body.transformToString());
      if (!validation.valid) invalid.push("ROUTE");
    }
    return { complete: missing.length === 0 && invalid.length === 0, missing, invalid };
  }
  issueReadUrl(drive, kind) { return this.presign(this.client, this.command("GetObject", { Bucket: this.bucket, Key: this.key(drive, kind) }), { expiresIn: this.readTtlSeconds }); }
  async deleteAssets(drive) { await Promise.all(drive.assets.map((asset) => this.client.send(this.command("DeleteObject", { Bucket: this.bucket, Key: this.key(drive, asset.kind) })))); }
}
