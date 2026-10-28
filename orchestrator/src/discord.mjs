import { EventEmitter } from "node:events";
import { timingSafeEqual } from "node:crypto";

const commands = [
  { name: "agent-approve", description: "Codex PR 병합을 승인합니다", options: [{ type: 3, name: "id", description: "승인 ID (예: pr-12)", required: true }] },
  { name: "agent-reject", description: "Codex PR 병합을 거부합니다", options: [{ type: 3, name: "id", description: "승인 ID (예: pr-12)", required: true }] },
  { name: "agent-claim", description: "최초 Discord 승인자로 등록합니다", options: [{ type: 3, name: "code", description: "로컬 설치 시 출력된 등록 코드", required: true }] },
  { name: "agent-status", description: "자율 개발 오케스트레이터 상태를 확인합니다" },
];

const equalSecret = (left, right) => {
  const a = Buffer.from(left ?? ""); const b = Buffer.from(right ?? "");
  return a.length === b.length && timingSafeEqual(a, b);
};

export class Discord extends EventEmitter {
  constructor(config) { super(); this.config = config; this.socket = null; this.sequence = null; this.heartbeat = null; }
  authorize(userId) { this.config.discordApprovers.add(userId); }

  async api(method, path, body) {
    const response = await fetch(`https://discord.com/api/v10${path}`, {
      method,
      headers: { Authorization: `Bot ${this.config.discordBotToken}`, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15_000),
    });
    if (!response.ok) throw new Error(`Discord API ${method} ${path}: ${response.status} ${await response.text()}`);
    return response.status === 204 ? null : response.json();
  }

  async report(content) {
    const safe = String(content).replaceAll(this.config.githubToken, "[REDACTED]").slice(0, 1900);
    const response = await fetch(this.config.discordWebhookUrl, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "Social Drive AI", content: safe, allowed_mentions: { parse: [] } }), signal: AbortSignal.timeout(15_000),
    });
    if (!response.ok) throw new Error(`Discord webhook: ${response.status}`);
  }

  async registerCommands() {
    await this.api("PUT", `/applications/${this.config.discordApplicationId}/guilds/${this.config.discordGuildId}/commands`, commands);
  }

  connect() {
    this.socket = new WebSocket("wss://gateway.discord.gg/?v=10&encoding=json");
    this.socket.addEventListener("message", (event) => void this.onMessage(JSON.parse(event.data)).catch((error) => this.emit("gatewayError", error)));
    this.socket.addEventListener("close", () => { clearInterval(this.heartbeat); setTimeout(() => this.connect(), 5_000).unref(); });
  }

  async reply(interaction, content) {
    const response = await fetch(`https://discord.com/api/v10/interactions/${interaction.id}/${interaction.token}/callback`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ type: 4, data: { content, flags: 64, allowed_mentions: { parse: [] } } }), signal: AbortSignal.timeout(15_000),
    });
    if (!response.ok) throw new Error(`Discord interaction response: ${response.status}`);
  }

  async onMessage(packet) {
    if (packet.s) this.sequence = packet.s;
    if (packet.op === 10) {
      this.heartbeat = setInterval(() => this.socket.send(JSON.stringify({ op: 1, d: this.sequence })), packet.d.heartbeat_interval);
      this.socket.send(JSON.stringify({ op: 2, d: { token: this.config.discordBotToken, intents: 1, properties: { os: process.platform, browser: "social-drive-orchestrator", device: "social-drive-orchestrator" } } }));
      return;
    }
    if (packet.t === "READY") {
      await this.registerCommands(); this.emit("ready"); return;
    }
    if (packet.t !== "INTERACTION_CREATE" || packet.d.type !== 2 || packet.d.channel_id !== this.config.discordChannelId) return;
    const interaction = packet.d;
    const userId = interaction.member?.user?.id ?? interaction.user?.id;
    const name = interaction.data.name;
    const value = interaction.data.options?.[0]?.value;

    if (name === "agent-claim") {
      if (this.config.discordApprovers.size > 0) return void this.reply(interaction, "승인자가 이미 등록되어 있습니다.");
      if (!equalSecret(String(value), this.config.discordBootstrapCode)) return void this.reply(interaction, "등록 코드가 올바르지 않습니다.");
      this.emit("claim", { userId });
      return void this.reply(interaction, "승인자로 등록되었습니다. 이제 `/agent-approve`와 `/agent-reject`를 사용할 수 있습니다.");
    }
    if (name === "agent-status") {
      if (!this.config.discordApprovers.has(userId)) return void this.reply(interaction, "승인 권한이 없습니다.");
      this.emit("status", { interaction, userId }); return;
    }
    if (!["agent-approve", "agent-reject"].includes(name)) return;
    if (!this.config.discordApprovers.has(userId)) return void this.reply(interaction, "승인 권한이 없습니다.");
    this.emit("approval", { action: name === "agent-approve" ? "approve" : "reject", id: String(value), userId });
    await this.reply(interaction, `요청을 접수했습니다: ${name === "agent-approve" ? "승인" : "거부"} \`${value}\``);
  }
}
