import { EventEmitter } from "node:events";

const intents = 1 << 9 | 1 << 15;

export class Discord extends EventEmitter {
  constructor(config) { super(); this.config = config; this.socket = null; this.sequence = null; this.heartbeat = null; }

  async report(content) {
    const safe = String(content).replaceAll(this.config.githubToken, "[REDACTED]").slice(0, 1900);
    const response = await fetch(this.config.discordWebhookUrl, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ content: safe, allowed_mentions: { parse: [] } }), signal: AbortSignal.timeout(15_000),
    });
    if (!response.ok) throw new Error(`Discord webhook: ${response.status}`);
  }

  connect() {
    this.socket = new WebSocket("wss://gateway.discord.gg/?v=10&encoding=json");
    this.socket.addEventListener("message", (event) => this.onMessage(JSON.parse(event.data)));
    this.socket.addEventListener("close", () => { clearInterval(this.heartbeat); setTimeout(() => this.connect(), 5_000).unref(); });
  }

  onMessage(packet) {
    if (packet.s) this.sequence = packet.s;
    if (packet.op === 10) {
      this.heartbeat = setInterval(() => this.socket.send(JSON.stringify({ op: 1, d: this.sequence })), packet.d.heartbeat_interval);
      this.socket.send(JSON.stringify({ op: 2, d: { token: this.config.discordBotToken, intents, properties: { os: process.platform, browser: "social-drive-orchestrator", device: "social-drive-orchestrator" } } }));
      return;
    }
    if (packet.t !== "MESSAGE_CREATE" || packet.d.channel_id !== this.config.discordChannelId || packet.d.author?.bot) return;
    const match = packet.d.content.trim().match(/^!(approve|reject)\s+([A-Za-z0-9_-]+)$/i);
    if (!match) return;
    if (!this.config.discordApprovers.has(packet.d.author.id)) {
      void this.report(`⛔ 승인 권한이 없는 사용자입니다: <@${packet.d.author.id}>`);
      return;
    }
    this.emit("approval", { action: match[1].toLowerCase(), id: match[2], userId: packet.d.author.id });
  }
}

