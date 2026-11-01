import { randomUUID } from "node:crypto";
import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import {
  DynamoDBDocumentClient,
  GetCommand,
  PutCommand,
  QueryCommand,
  TransactWriteCommand,
  UpdateCommand,
} from "@aws-sdk/lib-dynamodb";
import {
  DeleteObjectCommand,
  GetObjectCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
} from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import { DynamoRepository, S3Assets } from "./aws-adapter-core.mjs";

export { DynamoRepository, S3Assets };

const dynamoCommands = {
  Get: GetCommand,
  Put: PutCommand,
  Query: QueryCommand,
  TransactWrite: TransactWriteCommand,
  Update: UpdateCommand,
};

const s3Commands = {
  DeleteObject: DeleteObjectCommand,
  GetObject: GetObjectCommand,
  HeadObject: HeadObjectCommand,
  PutObject: PutObjectCommand,
};

export function createProductionServices() {
  const tableName = process.env.TABLE_NAME;
  const bucket = process.env.MEDIA_BUCKET;
  if (!tableName || !bucket) throw new Error("TABLE_NAME and MEDIA_BUCKET are required");

  const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}), {
    marshallOptions: { removeUndefinedValues: true },
  });

  return {
    repository: new DynamoRepository({
      tableName,
      client: ddb,
      commands: dynamoCommands,
    }),
    assets: new S3Assets({
      bucket,
      client: new S3Client({}),
      commands: s3Commands,
      presign: getSignedUrl,
      uploadTtlSeconds: Number(process.env.UPLOAD_URL_TTL_SECONDS ?? 600),
      readTtlSeconds: Number(process.env.READ_URL_TTL_SECONDS ?? 300),
    }),
    ids: { driveId: randomUUID },
  };
}
