import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { fileURLToPath } from "node:url";
import { verifyDiscordChannel } from "../../scripts/verify-discord-channel.mjs";

const setup = fileURLToPath(new URL("../../scripts/setup-orchestrator.sh", import.meta.url));
const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
});

test("setup invite includes history permission and invokes the strong probe", () => {
  const source = fs.readFileSync(setup, "utf8");
  assert.match(source, /permissions=67193856/);
  assert.match(source, /verify-discord-channel\.mjs/);
  assert.match(source, /Re-add the bot[\s\S]*\$invite_url/);
  assert.doesNotMatch(source, /messages\?limit=1/);
});

test("POST success and exact GET success prove permissions and clean up", async () => {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options });
    if (options.method === "POST") return response({ id: "message-1" });
    if (options.method === "GET") return response({ id: "message-1" });
    if (options.method === "DELETE") return response(null, 204);
    throw new Error("unexpected request");
  };
  const result = await verifyDiscordChannel({ token: "test-token", channelId: "channel-1", fetchImpl });
  assert.deepEqual(result, { verified: true, messageId: "message-1" });
  assert.deepEqual(calls.map((call) => call.options.method), ["POST", "GET", "DELETE"]);
  assert.match(calls[1].url, /channels\/channel-1\/messages\/message-1$/);
  assert.deepEqual(JSON.parse(calls[0].options.body).allowed_mentions, { parse: [] });
  assert.doesNotMatch(calls[0].options.body, /test-token/);
});

test("exact GET 403 fails installation and still cleans the probe message", async () => {
  const methods = [];
  await assert.rejects(
    verifyDiscordChannel({
      token: "test-token",
      channelId: "channel-1",
      fetchImpl: async (_url, options) => {
        methods.push(options.method);
        if (options.method === "POST") return response({ id: "message-2" });
        if (options.method === "GET") return response({}, 403);
        return response(null, 204);
      },
    }),
    /exact message GET failed: HTTP 403/,
  );
  assert.deepEqual(methods, ["POST", "GET", "DELETE"]);
});

test("POST error, malformed response, and transport error fail closed", async () => {
  await assert.rejects(
    verifyDiscordChannel({ token: "t", channelId: "c", fetchImpl: async () => response({}, 403) }),
    /probe POST failed: HTTP 403/,
  );
  await assert.rejects(
    verifyDiscordChannel({ token: "t", channelId: "c", fetchImpl: async () => response({}) }),
    /did not include a message id/,
  );
  await assert.rejects(
    verifyDiscordChannel({ token: "t", channelId: "c", fetchImpl: async () => { throw new TypeError("network down"); } }),
    /network down/,
  );
});

test("cleanup failure warns without invalidating a proven exact read", async () => {
  const warnings = [];
  const result = await verifyDiscordChannel({
    token: "t",
    channelId: "c",
    warn: (message) => warnings.push(message),
    fetchImpl: async (_url, options) => {
      if (options.method === "POST") return response({ id: "message-3" });
      if (options.method === "GET") return response({ id: "message-3" });
      return response({}, 403);
    },
  });
  assert.equal(result.verified, true);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /non-sensitive verification message may remain/);
  assert.doesNotMatch(warnings[0], /test-token|channel-1/);
});
