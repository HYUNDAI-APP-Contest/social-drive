import test from "node:test";
import assert from "node:assert/strict";
import { DynamoRepository, S3Assets } from "./aws-adapter-core.mjs";

const command = (kind) => class {
  constructor(input) {
    this.kind = kind;
    this.input = input;
  }
};

const dynamoCommands = {
  Get: command("Get"),
  Put: command("Put"),
  Query: command("Query"),
  TransactWrite: command("TransactWrite"),
  Update: command("Update"),
};

const s3Commands = {
  DeleteObject: command("DeleteObject"),
  GetObject: command("GetObject"),
  HeadObject: command("HeadObject"),
  PutObject: command("PutObject"),
};

const digest = "a".repeat(64);
const checksum = Buffer.from(digest, "hex").toString("base64");
const routeDocument = JSON.stringify({
  schemaVersion: 1,
  path: [
    { horizontal: 0.1, vertical: 0.8 },
    { horizontal: 0.9, vertical: 0.2 },
  ],
  highlights: [],
});

const drive = {
  id: "drive-demo-001",
  assets: [
    { kind: "COVER", contentType: "image/jpeg", sizeBytes: 1024, sha256: digest },
    { kind: "ROUTE", contentType: "application/json", sizeBytes: Buffer.byteLength(routeDocument), sha256: digest },
  ],
};

const draftRequest = {
  userId: "user-demo-a",
  idempotencyKey: "idempotency-demo-001",
  payloadHash: "payload-hash-demo",
  driveId: "drive-demo-001",
  draft: { title: "안전한 데모 경로", assets: drive.assets },
  now: "2026-11-01T10:00:00.000Z",
  expiresAtEpoch: 1793530800,
};

test("presigns the exact required upload headers as signed, unhoisted request data", async () => {
  const calls = [];
  const assets = new S3Assets({
    bucket: "private-demo-bucket",
    client: { send: async () => ({}) },
    commands: s3Commands,
    presign: async (client, signedCommand, options) => {
      calls.push({ client, signedCommand, options });
      return "https://upload.invalid/signed";
    },
  });

  const uploads = await assets.issueUploads({ ...drive, assets: [drive.assets[0]] });
  const [{ signedCommand, options }] = calls;

  assert.equal(signedCommand.kind, "PutObject");
  assert.equal(signedCommand.input.ContentType, "image/jpeg");
  assert.equal(signedCommand.input.ChecksumSHA256, checksum);
  assert.deepEqual([...options.signableHeaders], ["content-type"]);
  assert.deepEqual([...options.unhoistableHeaders], ["x-amz-checksum-sha256"]);
  assert.deepEqual(uploads[0].requiredHeaders, {
    "Content-Type": "image/jpeg",
    "x-amz-checksum-sha256": checksum,
  });
});

test("requests S3-computed checksums and validates checksum, size, type, and route", async () => {
  const sent = [];
  const client = {
    async send(sentCommand) {
      sent.push(sentCommand);
      if (sentCommand.kind === "HeadObject") {
        const route = sentCommand.input.Key.endsWith("/route");
        return {
          ContentLength: route ? Buffer.byteLength(routeDocument) : 1024,
          ContentType: route ? "application/json" : "image/jpeg",
          ChecksumSHA256: checksum,
        };
      }
      if (sentCommand.kind === "GetObject") {
        return { Body: { transformToString: async () => routeDocument } };
      }
      throw new Error(`unexpected command ${sentCommand.kind}`);
    },
  };
  const assets = new S3Assets({
    bucket: "private-demo-bucket",
    client,
    commands: s3Commands,
    presign: async () => "https://read.invalid/signed",
  });

  const result = await assets.verifyUploads(drive);

  assert.deepEqual(result, { complete: true, missing: [], invalid: [] });
  const heads = sent.filter((entry) => entry.kind === "HeadObject");
  assert.equal(heads.length, 2);
  assert.ok(heads.every((entry) => entry.input.ChecksumMode === "ENABLED"));
  assert.equal(sent.some((entry) => entry.kind === "GetObject"), true);
});

test("rejects an S3 object when the service checksum differs from the manifest", async () => {
  const client = {
    async send(sentCommand) {
      if (sentCommand.kind === "HeadObject") {
        const route = sentCommand.input.Key.endsWith("/route");
        return {
          ContentLength: route ? Buffer.byteLength(routeDocument) : 1024,
          ContentType: route ? "application/json" : "image/jpeg",
          ChecksumSHA256: route ? checksum : Buffer.from("b".repeat(64), "hex").toString("base64"),
        };
      }
      return { Body: { transformToString: async () => routeDocument } };
    },
  };
  const assets = new S3Assets({ bucket: "private-demo-bucket", client, commands: s3Commands, presign: async () => "unused" });

  const result = await assets.verifyUploads(drive);

  assert.equal(result.complete, false);
  assert.deepEqual(result.invalid, ["COVER"]);
});

test("replays a committed idempotent create after the idempotency condition loses", async () => {
  const existingDrive = { ...draftRequest.draft, id: draftRequest.driveId, ownerId: draftRequest.userId, status: "DRAFT", version: 1 };
  const commands = [];
  const client = {
    async send(sentCommand) {
      commands.push(sentCommand);
      if (sentCommand.kind === "TransactWrite") {
        throw {
          name: "TransactionCanceledException",
          CancellationReasons: [{ Code: "None" }, { Code: "ConditionalCheckFailed" }],
        };
      }
      if (sentCommand.input.Key.SK.startsWith("IDEMPOTENCY#")) {
        return { Item: { payloadHash: draftRequest.payloadHash, driveId: draftRequest.driveId } };
      }
      return { Item: { PK: `DRIVE#${draftRequest.driveId}`, SK: "META", entity: "DRIVE", ...existingDrive } };
    },
  };
  const repository = new DynamoRepository({ tableName: "demo", client, commands: dynamoCommands, sleep: async () => {} });

  const result = await repository.createDraft(draftRequest);

  assert.equal(result.created, false);
  assert.deepEqual(result.drive, existingDrive);
  assert.ok(commands.filter((entry) => entry.kind === "Get").every((entry) => entry.input.ConsistentRead === true));
});

test("retries DynamoDB transaction conflicts with bounded exponential jitter", async () => {
  let transactions = 0;
  const delays = [];
  const client = {
    async send(sentCommand) {
      if (sentCommand.kind === "TransactWrite") {
        transactions += 1;
        if (transactions < 3) {
          throw {
            name: "TransactionCanceledException",
            CancellationReasons: [{ Code: "TransactionConflict" }, { Code: "None" }],
          };
        }
        return {};
      }
      return {};
    },
  };
  const repository = new DynamoRepository({
    tableName: "demo",
    client,
    commands: dynamoCommands,
    sleep: async (delay) => delays.push(delay),
    random: () => 0.5,
  });

  const result = await repository.createDraft(draftRequest);

  assert.equal(result.created, true);
  assert.equal(transactions, 3);
  assert.deepEqual(delays, [10, 20, 10, 40]);
});

test("waits briefly for a committed idempotency record and drive to become visible", async () => {
  let idempotencyReads = 0;
  let driveReads = 0;
  const delays = [];
  const client = {
    async send(sentCommand) {
      if (sentCommand.kind === "TransactWrite") {
        throw {
          name: "TransactionCanceledException",
          CancellationReasons: [{ Code: "None" }, { Code: "ConditionalCheckFailed" }],
        };
      }
      if (sentCommand.input.Key.SK.startsWith("IDEMPOTENCY#")) {
        idempotencyReads += 1;
        return idempotencyReads === 1 ? {} : { Item: { payloadHash: draftRequest.payloadHash, driveId: draftRequest.driveId } };
      }
      driveReads += 1;
      return driveReads === 1 ? {} : { Item: { PK: `DRIVE#${draftRequest.driveId}`, SK: "META", entity: "DRIVE", id: draftRequest.driveId, status: "DRAFT" } };
    },
  };
  const repository = new DynamoRepository({ tableName: "demo", client, commands: dynamoCommands, sleep: async (delay) => delays.push(delay) });

  const result = await repository.createDraft(draftRequest);

  assert.equal(result.created, false);
  assert.equal(result.drive.id, draftRequest.driveId);
  assert.deepEqual(delays, [10, 10]);
});

test("does not misclassify unrelated DynamoDB transaction cancellations", async () => {
  const cancellation = {
    name: "TransactionCanceledException",
    CancellationReasons: [{ Code: "ProvisionedThroughputExceeded" }, { Code: "None" }],
  };
  const repository = new DynamoRepository({
    tableName: "demo",
    client: { send: async () => { throw cancellation; } },
    commands: dynamoCommands,
    sleep: async () => {},
  });

  await assert.rejects(repository.createDraft(draftRequest), (error) => error === cancellation);
});
