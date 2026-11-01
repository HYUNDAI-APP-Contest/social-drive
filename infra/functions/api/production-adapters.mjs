import { randomUUID } from "node:crypto";
import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { DynamoDBDocumentClient, GetCommand, PutCommand, QueryCommand, TransactWriteCommand, UpdateCommand } from "@aws-sdk/lib-dynamodb";
import { DeleteObjectCommand, GetObjectCommand, HeadObjectCommand, PutObjectCommand, S3Client } from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import { ApiError } from "./app.mjs";
import { validateRouteDocument } from "./route-validation.mjs";

const driveKey = (id) => ({ PK: `DRIVE#${id}`, SK: "META" });
const cursorKey = (hash) => ({ PK: `CURSOR#${hash}`, SK: "META" });
const cleanDrive = (item) => {
  if (!item) return null;
  const { PK, SK, FeedPK, FeedSK, entity, ...drive } = item;
  return drive;
};
const isConditional = (error) => error?.name === "ConditionalCheckFailedException" || error?.name === "TransactionCanceledException";

export class DynamoRepository {
  constructor({ tableName, client }) { this.tableName = tableName; this.client = client; }

  async createDraft({ userId, idempotencyKey, payloadHash, driveId, draft, now, expiresAtEpoch }) {
    const idempotencyKeyRecord = { PK: `USER#${userId}`, SK: `IDEMPOTENCY#${idempotencyKey}` };
    const drive = { ...draft, id: driveId, ownerId: userId, status: "DRAFT", version: 1, createdAt: now, updatedAt: now };
    try {
      await this.client.send(new TransactWriteCommand({ TransactItems: [
        { Put: { TableName: this.tableName, Item: { ...driveKey(driveId), entity: "DRIVE", ...drive }, ConditionExpression: "attribute_not_exists(PK)" } },
        { Put: { TableName: this.tableName, Item: { ...idempotencyKeyRecord, entity: "IDEMPOTENCY", driveId, payloadHash, expiresAtEpoch }, ConditionExpression: "attribute_not_exists(PK)" } },
      ] }));
      return { created: true, drive };
    } catch (error) {
      if (!isConditional(error)) throw error;
      const result = await this.client.send(new GetCommand({ TableName: this.tableName, Key: idempotencyKeyRecord, ConsistentRead: true }));
      if (!result.Item || result.Item.payloadHash !== payloadHash) throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "같은 초안을 안전하게 확인하지 못했어요. 원본은 보관되어 있습니다.");
      const existing = await this.getDrive(result.Item.driveId);
      if (!existing) throw new ApiError(409, "IDEMPOTENCY_CONFLICT", "초안 상태를 안전하게 확인하지 못했어요.");
      return { created: false, drive: existing };
    }
  }

  async getDrive(driveId) {
    const result = await this.client.send(new GetCommand({ TableName: this.tableName, Key: driveKey(driveId), ConsistentRead: true }));
    return cleanDrive(result.Item);
  }

  async publishDrive({ driveId, userId, expectedVersion, now }) {
    const current = await this.getDrive(driveId);
    if (!current || current.ownerId !== userId || current.status === "DELETED") throw new ApiError(404, "NOT_FOUND", "드라이브를 찾을 수 없어요.");
    if (current.status === "PUBLISHED") return current;
    try {
      const result = await this.client.send(new UpdateCommand({
        TableName: this.tableName, Key: driveKey(driveId), ReturnValues: "ALL_NEW",
        ConditionExpression: "ownerId = :owner AND #status = :draft AND #version = :expected",
        UpdateExpression: "SET #status = :published, #version = :next, publishedAt = :now, updatedAt = :now, FeedPK = :feed, FeedSK = :sort",
        ExpressionAttributeNames: { "#status": "status", "#version": "version" },
        ExpressionAttributeValues: { ":owner": userId, ":draft": "DRAFT", ":published": "PUBLISHED", ":expected": expectedVersion, ":next": expectedVersion + 1, ":now": now, ":feed": "FEED#PUBLIC", ":sort": `${now}#${driveId}` },
      }));
      return cleanDrive(result.Attributes);
    } catch (error) {
      if (!isConditional(error)) throw error;
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
      const result = await this.client.send(new UpdateCommand({
        TableName: this.tableName, Key: driveKey(driveId), ReturnValues: "ALL_NEW",
        ConditionExpression: "ownerId = :owner AND #version = :expected AND #status <> :deleted",
        UpdateExpression: `SET ${sets.join(", ")}`,
        ExpressionAttributeNames: { ...names, "#status": "status" }, ExpressionAttributeValues: values,
      }));
      return cleanDrive(result.Attributes);
    } catch (error) {
      if (!isConditional(error)) throw error;
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
      const result = await this.client.send(new UpdateCommand({
        TableName: this.tableName, Key: driveKey(driveId), ReturnValues: "ALL_OLD",
        ConditionExpression: "ownerId = :owner AND #status <> :deleted",
        UpdateExpression: "SET #status = :deleted, #version = #version + :one, updatedAt = :now REMOVE FeedPK, FeedSK",
        ExpressionAttributeNames: { "#status": "status", "#version": "version" },
        ExpressionAttributeValues: { ":owner": userId, ":deleted": "DELETED", ":one": 1, ":now": now },
      }));
      return cleanDrive(result.Attributes);
    } catch (error) {
      if (isConditional(error)) {
        const latest = await this.getDrive(driveId);
        if (latest?.ownerId === userId && latest.status === "DELETED") return latest;
        throw new ApiError(409, "VERSION_CONFLICT", "다른 변경이 있어 최신 내용을 확인해 주세요.");
      }
      throw error;
    }
  }

  async listPublished({ limit, startKey }) {
    const result = await this.client.send(new QueryCommand({
      TableName: this.tableName, IndexName: "FeedIndex", KeyConditionExpression: "FeedPK = :feed",
      ExpressionAttributeValues: { ":feed": "FEED#PUBLIC" }, ScanIndexForward: false, Limit: limit, ExclusiveStartKey: startKey || undefined,
    }));
    return { items: (result.Items ?? []).map(cleanDrive), nextKey: result.LastEvaluatedKey ?? null };
  }

  async saveCursor(cursor) {
    await this.client.send(new PutCommand({ TableName: this.tableName, Item: { ...cursorKey(cursor.tokenHash), entity: "CURSOR", ...cursor } }));
  }
  async loadCursor(tokenHash, nowEpoch) {
    const result = await this.client.send(new GetCommand({ TableName: this.tableName, Key: cursorKey(tokenHash), ConsistentRead: true }));
    return result.Item && result.Item.expiresAtEpoch > nowEpoch ? { startKey: result.Item.startKey, limit: result.Item.limit, expiresAtEpoch: result.Item.expiresAtEpoch } : null;
  }
}

export class S3Assets {
  constructor({ bucket, client, uploadTtlSeconds = 600, readTtlSeconds = 300 }) { this.bucket = bucket; this.client = client; this.uploadTtlSeconds = uploadTtlSeconds; this.readTtlSeconds = readTtlSeconds; }
  key(drive, kind) { return `drives/${drive.id}/${kind.toLowerCase()}`; }
  async issueUploads(drive) {
    return Promise.all(drive.assets.map(async (asset) => {
      const checksum = Buffer.from(asset.sha256, "hex").toString("base64");
      const command = new PutObjectCommand({ Bucket: this.bucket, Key: this.key(drive, asset.kind), ContentType: asset.contentType, ChecksumSHA256: checksum, Metadata: { sha256: asset.sha256 } });
      return { kind: asset.kind, method: "PUT", url: await getSignedUrl(this.client, command, { expiresIn: this.uploadTtlSeconds }), requiredHeaders: { "Content-Type": asset.contentType, "x-amz-checksum-sha256": checksum }, maxBytes: asset.sizeBytes, expiresInSeconds: this.uploadTtlSeconds };
    }));
  }
  async verifyUploads(drive) {
    const missing = []; const invalid = [];
    for (const asset of drive.assets) {
      try {
        const result = await this.client.send(new HeadObjectCommand({ Bucket: this.bucket, Key: this.key(drive, asset.kind) }));
        if (result.ContentLength !== asset.sizeBytes || result.ContentType !== asset.contentType || result.Metadata?.sha256 !== asset.sha256) missing.push(asset.kind);
      } catch (error) {
        if (error?.$metadata?.httpStatusCode === 404 || error?.name === "NotFound") missing.push(asset.kind); else throw error;
      }
    }
    if (!missing.includes("ROUTE")) {
      const route = await this.client.send(new GetObjectCommand({ Bucket: this.bucket, Key: this.key(drive, "ROUTE") }));
      const validation = validateRouteDocument(await route.Body.transformToString());
      if (!validation.valid) invalid.push("ROUTE");
    }
    return { complete: missing.length === 0 && invalid.length === 0, missing, invalid };
  }
  issueReadUrl(drive, kind) { return getSignedUrl(this.client, new GetObjectCommand({ Bucket: this.bucket, Key: this.key(drive, kind) }), { expiresIn: this.readTtlSeconds }); }
  async deleteAssets(drive) { await Promise.all(drive.assets.map((asset) => this.client.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: this.key(drive, asset.kind) })))); }
}

export function createProductionServices() {
  const tableName = process.env.TABLE_NAME; const bucket = process.env.MEDIA_BUCKET;
  if (!tableName || !bucket) throw new Error("TABLE_NAME and MEDIA_BUCKET are required");
  const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}), { marshallOptions: { removeUndefinedValues: true } });
  return {
    repository: new DynamoRepository({ tableName, client: ddb }),
    assets: new S3Assets({ bucket, client: new S3Client({}), uploadTtlSeconds: Number(process.env.UPLOAD_URL_TTL_SECONDS ?? 600), readTtlSeconds: Number(process.env.READ_URL_TTL_SECONDS ?? 300) }),
    ids: { driveId: randomUUID },
  };
}
