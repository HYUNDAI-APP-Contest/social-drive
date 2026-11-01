import { pathToFileURL } from "node:url";

const api = "https://discord.com/api/v10";

export async function verifyDiscordChannel({
  token,
  channelId,
  fetchImpl = fetch,
  warn = console.warn,
}) {
  if (!token || !channelId) throw new Error("Discord probe configuration is incomplete");
  const headers = { Authorization: `Bot ${token}`, "Content-Type": "application/json" };
  let messageId = null;
  let verified = false;
  try {
    const posted = await fetchImpl(`${api}/channels/${channelId}/messages`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        content: "🔎 설치 권한을 확인하고 있어요. 이 메시지는 자동으로 정리됩니다.",
        allowed_mentions: { parse: [] },
      }),
      signal: AbortSignal.timeout(15_000),
    });
    if (!posted.ok) throw new Error(`Discord probe POST failed: HTTP ${posted.status}`);
    let created;
    try { created = await posted.json(); }
    catch { throw new Error("Discord probe POST returned invalid JSON"); }
    messageId = typeof created?.id === "string" && created.id ? created.id : null;
    if (!messageId) throw new Error("Discord probe POST response did not include a message id");

    const exact = await fetchImpl(`${api}/channels/${channelId}/messages/${messageId}`, {
      method: "GET",
      headers,
      signal: AbortSignal.timeout(15_000),
    });
    if (!exact.ok) throw new Error(`Discord exact message GET failed: HTTP ${exact.status}`);
    let fetched;
    try { fetched = await exact.json(); }
    catch { throw new Error("Discord exact message GET returned invalid JSON"); }
    if (fetched?.id !== messageId) throw new Error("Discord exact message GET returned a different message");
    verified = true;
  } finally {
    if (messageId) {
      try {
        const deleted = await fetchImpl(`${api}/channels/${channelId}/messages/${messageId}`, {
          method: "DELETE",
          headers,
          signal: AbortSignal.timeout(15_000),
        });
        if (!deleted.ok) warn(`Discord probe cleanup warning: HTTP ${deleted.status}; the non-sensitive verification message may remain.`);
      } catch {
        warn("Discord probe cleanup warning: the non-sensitive verification message may remain.");
      }
    }
  }
  return { verified, messageId };
}

async function main() {
  await verifyDiscordChannel({
    token: process.env.DISCORD_BOT_TOKEN,
    channelId: process.env.DISCORD_CHANNEL_ID,
  });
  console.log("Discord send and exact-history permissions verified.");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : "Discord permission probe failed");
    process.exitCode = 1;
  });
}
