import { EventEmitter } from "node:events";
import { timingSafeEqual } from "node:crypto";
import { createHash } from "node:crypto";
import fs from "node:fs/promises";

const commands = [
  { name: "agent-approve", description: "Codex PR 병합을 승인합니다", options: [{ type: 3, name: "id", description: "승인 ID (예: pr-12)", required: true }] },
  { name: "agent-reject", description: "Codex PR 병합을 거부합니다", options: [{ type: 3, name: "id", description: "승인 ID (예: pr-12)", required: true }] },
  { name: "agent-claim", description: "최초 Discord 승인자로 등록합니다", options: [{ type: 3, name: "code", description: "로컬 설치 시 출력된 등록 코드", required: true }] },
  { name: "agent-status", description: "자율 개발 오케스트레이터 상태를 확인합니다" },
  { name: "agent-test-approval", description: "실제 병합 없이 Discord 승인 게이트를 테스트합니다" },
  { name: "agent-deploy", description: "현재 main으로 AWS dev 변경 세트를 준비합니다" },
];

const equalSecret = (left, right) => {
  const a = Buffer.from(left ?? ""); const b = Buffer.from(right ?? "");
  return a.length === b.length && timingSafeEqual(a, b);
};

export class Discord extends EventEmitter {
  constructor(config) { super(); this.config = config; this.socket = null; this.sequence = null; this.heartbeat = null; }
  authorize(userId) { this.config.discordApprovers.add(userId); }

  async brandVersion() {
    const avatar = await fs.readFile(this.config.discordAvatarPath);
    return createHash("sha256").update(this.config.discordDisplayName).update(avatar).digest("hex");
  }

  async updateBrand() {
    const avatar = await fs.readFile(this.config.discordAvatarPath);
    const data = `data:image/png;base64,${avatar.toString("base64")}`;
    await this.api("PATCH", "/users/@me", { avatar: data });
    await this.api("PATCH", `/guilds/${this.config.discordGuildId}/members/@me`, { nick: this.config.discordDisplayName });
    const response = await fetch(this.config.discordWebhookUrl, {
      method: "PATCH", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: this.config.discordDisplayName, avatar: data }), signal: AbortSignal.timeout(15_000),
    });
    if (!response.ok) throw new Error(`Discord webhook branding: ${response.status} ${await response.text()}`);
  }

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
    let safe = String(content);
    for (const secret of [this.config.githubToken, this.config.discordBotToken, this.config.discordWebhookUrl].filter(Boolean)) safe = safe.replaceAll(secret, "[REDACTED]");
    safe = safe.slice(0, 1900);
    const response = await fetch(this.config.discordWebhookUrl, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: this.config.discordDisplayName, content: safe, allowed_mentions: { parse: [] } }), signal: AbortSignal.timeout(15_000),
    });
    if (!response.ok) throw new Error(`Discord webhook: ${response.status}`);
  }

  async requestApproval({ id, title, description, url = null }) {
    const fields = [{ name: "승인 ID", value: `\`${id}\``, inline: true }];
    if (url) fields.push({ name: "검토", value: `[변경사항 열기](${url})`, inline: true });
    await this.api("POST", `/channels/${this.config.discordChannelId}/messages`, {
      content: "🔐 **사용자 승인이 필요합니다**",
      embeds: [{ title, description, color: 0x002c5f, fields, footer: { text: "일반 개발 작업은 자동 진행되며 중요한 변경만 묻습니다." } }],
      components: [{ type: 1, components: [
        { type: 2, style: 3, label: "승인", emoji: { name: "✅" }, custom_id: `approval:approve:${id}` },
        { type: 2, style: 4, label: "거부", emoji: { name: "🛑" }, custom_id: `approval:reject:${id}` },
      ] }],
      allowed_mentions: { parse: [] },
    });
  }

  persona(role) {
    const personas = {
      architect: { name: "아키(기획·설계)", color: 0x3b82f6, avatarPath: this.config.discordArchitectAvatarPath },
      designer: { name: "픽시(디자인·개발)", color: 0xd96cff, avatarPath: this.config.discordDesignerAvatarPath },
      reviewer: { name: "센티넬(검토자)", color: 0xffb547, avatarPath: this.config.discordReviewerAvatarPath },
    };
    return personas[role];
  }

  async personaReport(role, title, content) {
    const persona = this.persona(role);
    if (!persona) throw new Error(`Unknown Discord persona: ${role}`);
    const avatar = await fs.readFile(persona.avatarPath);
    const filename = `${role}.png`;
    const form = new FormData();
    form.set("payload_json", JSON.stringify({
      embeds: [{ author: { name: persona.name, icon_url: `attachment://${filename}` }, title, description: String(content).slice(0, 3800), color: persona.color }],
      attachments: [{ id: 0, filename }],
      allowed_mentions: { parse: [] },
    }));
    form.set("files[0]", new Blob([avatar], { type: "image/png" }), filename);
    const response = await fetch(`https://discord.com/api/v10/channels/${this.config.discordChannelId}/messages`, {
      method: "POST", headers: { Authorization: `Bot ${this.config.discordBotToken}` }, body: form, signal: AbortSignal.timeout(20_000),
    });
    if (!response.ok) throw new Error(`Discord persona report: ${response.status} ${await response.text()}`);
  }

  async disableApprovalButtons(interaction) {
    const components = (interaction.message?.components ?? []).map((row) => ({ ...row, components: row.components.map((component) => ({ ...component, disabled: true })) }));
    if (interaction.message?.id) await this.api("PATCH", `/channels/${interaction.channel_id}/messages/${interaction.message.id}`, { components });
  }

  async registerCommands() {
    await this.api("PUT", `/applications/${this.config.discordApplicationId}/guilds/${this.config.discordGuildId}/commands`, commands);
  }

  async registerCommandsWithRetry() {
    let lastError;
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      try { await this.registerCommands(); return; }
      catch (error) { lastError = error; await new Promise((resolve) => setTimeout(resolve, attempt * 1_000)); }
    }
    throw lastError;
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
      try { await this.registerCommandsWithRetry(); this.emit("ready"); }
      catch (error) { this.emit("gatewayError", error); this.socket.close(4000, "command registration failed"); }
      return;
    }
    if (packet.t !== "INTERACTION_CREATE" || packet.d.channel_id !== this.config.discordChannelId) return;
    const interaction = packet.d;
    const userId = interaction.member?.user?.id ?? interaction.user?.id;

    if (interaction.type === 3) {
      const match = interaction.data.custom_id?.match(/^approval:(approve|reject):(.+)$/);
      if (!match) return;
      if (!this.config.discordApprovers.has(userId)) return void this.reply(interaction, "승인 권한이 없습니다.");
      await this.disableApprovalButtons(interaction).catch((error) => this.emit("gatewayError", error));
      this.emit("approval", { action: match[1], id: match[2], userId });
      await this.reply(interaction, `${match[1] === "approve" ? "승인" : "거부"} 요청을 접수했습니다.`);
      return;
    }
    if (interaction.type !== 2) return;
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
    if (name === "agent-test-approval") {
      if (!this.config.discordApprovers.has(userId)) return void this.reply(interaction, "승인 권한이 없습니다.");
      this.emit("testApproval", { userId });
      await this.reply(interaction, "건식 승인 요청을 생성했습니다. 보고 채널에서 승인 ID를 확인하세요.");
      return;
    }
    if (name === "agent-deploy") {
      if (!this.config.discordApprovers.has(userId)) return void this.reply(interaction, "승인 권한이 없습니다.");
      this.emit("deploy", { userId });
      await this.reply(interaction, "AWS dev 변경 세트 준비를 시작했습니다. 준비가 끝나면 이 채널에 승인 버튼이 나타납니다.");
      return;
    }
    if (!["agent-approve", "agent-reject"].includes(name)) return;
    if (!this.config.discordApprovers.has(userId)) return void this.reply(interaction, "승인 권한이 없습니다.");
    this.emit("approval", { action: name === "agent-approve" ? "approve" : "reject", id: String(value), userId });
    await this.reply(interaction, `요청을 접수했습니다: ${name === "agent-approve" ? "승인" : "거부"} \`${value}\``);
  }
}
