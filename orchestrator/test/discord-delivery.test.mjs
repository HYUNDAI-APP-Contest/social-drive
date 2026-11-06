import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { Discord } from "../src/discord.mjs";
import { DurableOutbox } from "../src/outbox.mjs";
import { Store } from "../src/store.mjs";

const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
  text: async () => JSON.stringify(body),
});

test("POST accepted then crash before state save reconciles to zero visible duplicates", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "social-drive-discord-crash-"));
  const file = path.join(directory, "state.json");
  const messages = [];
  let postCount = 0;
  const fetchImpl = async (url, options) => {
    if (options.method === "GET" && url.includes("/messages?limit=100")) return response(messages);
    if (options.method === "POST" && url.endsWith("/messages")) {
      postCount += 1;
      const body = JSON.parse(options.body);
      const byNonce = messages.find((message) => message.nonce === body.nonce);
      if (body.enforce_nonce && byNonce) return response(byNonce);
      const message = { id: String(messages.length + 1), content: body.content, nonce: body.nonce };
      messages.push(message);
      return response(message);
    }
    throw new Error(`unexpected request ${options.method} ${url}`);
  };
  const config = {
    githubToken: "",
    discordBotToken: "test-bot-token",
    discordWebhookUrl: "https://discord.invalid/webhook",
    discordChannelId: "channel-1",
    discordApprovers: new Set(),
  };
  const discord = new Discord(config, { fetchImpl });
  const firstStore = new Store(file);
  await firstStore.load();
  const handlers = { "discord-report": ({ content }, entry) => discord.reportOnce(entry.key, content) };
  const firstOutbox = new DurableOutbox({ store: firstStore, handlers, clock: () => 1000 });
  await firstOutbox.enqueue({ key: "token:2026-11-01:60", destination: "discord-report", payload: { content: "30% · 60%" } });
  const claimed = await firstOutbox.claim();
  await firstOutbox.deliver(claimed);
  assert.equal(messages.length, 1);
  assert.equal(firstStore.state.outbox[claimed.key].state, "sending");

  // Simulated process death: the accepted Discord response was never marked delivered.
  const restartedStore = new Store(file);
  await restartedStore.load();
  const restartedOutbox = new DurableOutbox({ store: restartedStore, handlers, clock: () => 2000 });
  await restartedOutbox.drain();

  assert.equal(messages.length, 1);
  assert.equal(postCount, 1);
  assert.match(messages[0].content, /cc-event:[a-f0-9]{20}/);
  assert.equal(restartedStore.state.outbox[claimed.key].state, "delivered");
  await fs.rm(directory, { recursive: true, force: true });
});

test("approval delivery uses a stable marker and nonce across retries", async () => {
  const messages = [];
  let postCount = 0;
  const fetchImpl = async (url, options) => {
    if (options.method === "GET") return response(messages);
    const body = JSON.parse(options.body);
    postCount += 1;
    const message = { id: "1", content: body.content, nonce: body.nonce };
    messages.push(message);
    return response(message);
  };
  const discord = new Discord({
    githubToken: "", discordBotToken: "token", discordWebhookUrl: "https://discord.invalid/webhook",
    discordChannelId: "channel-1", discordApprovers: new Set(),
  }, { fetchImpl });
  const approval = { id: "pr-22", title: "Merge", description: "Reviewed" };

  await discord.requestApproval(approval);
  await discord.requestApproval(approval);

  assert.equal(postCount, 1);
  assert.match(messages[0].content, /approval-event:[a-f0-9]{20}/);
  assert.match(messages[0].nonce, /^ap[a-f0-9]{20}$/);
});
