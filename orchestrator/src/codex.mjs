import { spawn } from "node:child_process";
import readline from "node:readline";
import { safeWorkerEnvironment } from "./process.mjs";

export class CodexAppServer {
  constructor(config, { workspace, onEvent, onApproval }) {
    this.config = config; this.workspace = workspace; this.onEvent = onEvent; this.onApproval = onApproval;
    this.pending = new Map(); this.nextId = 1; this.completed = null;
  }

  send(message) { this.child.stdin.write(`${JSON.stringify(message)}\n`); }
  notify(method, params = {}) { this.send({ jsonrpc: "2.0", method, params }); }
  request(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => { this.pending.set(id, { resolve, reject }); this.send({ jsonrpc: "2.0", id, method, params }); });
  }

  async start(prompt) {
    this.child = spawn("bash", ["-lc", this.config.codexCommand], { cwd: this.workspace, env: safeWorkerEnvironment(), stdio: ["pipe", "pipe", "pipe"] });
    this.child.stderr.on("data", (chunk) => this.onEvent({ event: "stderr", text: String(chunk) }));
    readline.createInterface({ input: this.child.stdout }).on("line", (line) => this.handleLine(line));
    this.child.once("exit", (code) => this.failAll(new Error(`codex app-server exited ${code}`)));

    await this.request("initialize", { clientInfo: { name: "social_drive_orchestrator", title: "Social Drive Orchestrator", version: "0.1.0" }, capabilities: { experimentalApi: false, requestAttestation: false } });
    this.notify("initialized");
    const thread = await this.request("thread/start", {
      cwd: this.workspace,
      model: this.config.codexModel,
      approvalPolicy: "never",
      sandbox: "workspace-write",
      serviceName: "social-drive-orchestrator",
      developerInstructions: "Follow AGENTS.md and WORKFLOW.md. Never push, merge, deploy, or seek credentials. Work autonomously inside this workspace and commit the completed change locally.",
    });
    const threadId = thread.thread.id;
    const completion = new Promise((resolve, reject) => { this.completed = { resolve, reject, threadId }; });
    await this.request("turn/start", {
      threadId,
      cwd: this.workspace,
      approvalPolicy: "never",
      sandboxPolicy: { type: "workspaceWrite", writableRoots: [this.workspace], networkAccess: true, excludeTmpdirEnvVar: false, excludeSlashTmp: false },
      input: [{ type: "text", text: prompt, text_elements: [] }],
    });
    const timeout = setTimeout(() => this.completed?.reject(new Error("Codex turn timed out")), this.config.turnTimeoutMs);
    try { return await completion; } finally { clearTimeout(timeout); this.child.kill("SIGTERM"); }
  }

  async handleLine(line) {
    let message;
    try { message = JSON.parse(line); } catch { this.onEvent({ event: "malformed", text: line }); return; }
    if (message.id !== undefined && (message.result !== undefined || message.error)) {
      const pending = this.pending.get(message.id); if (!pending) return; this.pending.delete(message.id);
      message.error ? pending.reject(new Error(message.error.message ?? "app-server error")) : pending.resolve(message.result); return;
    }
    if (message.id !== undefined && message.method) {
      const decision = await this.onApproval(message.method, message.params);
      this.send({ jsonrpc: "2.0", id: message.id, result: decision }); return;
    }
    this.onEvent({ event: message.method ?? "notification", params: message.params });
    if (message.method === "turn/completed" && message.params?.threadId === this.completed?.threadId) {
      const turn = message.params.turn;
      if (turn?.status === "completed") this.completed.resolve(turn);
      else {
        const error = new Error(turn?.error?.message ?? `Codex turn ${turn?.status ?? "failed"}`);
        error.codexErrorInfo = turn?.error?.codexErrorInfo ?? null;
        this.completed.reject(error);
      }
    }
  }

  failAll(error) {
    for (const pending of this.pending.values()) pending.reject(error); this.pending.clear(); this.completed?.reject(error);
  }
}

