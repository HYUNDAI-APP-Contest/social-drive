import fs from "node:fs/promises";
import path from "node:path";

const emptyState = () => ({ version: 2, jobs: {}, approvals: {}, usage: {}, outbox: {} });

export class Store {
  constructor(file) { this.file = file; this.state = emptyState(); this.queue = Promise.resolve(); }

  async load() {
    try { this.state = JSON.parse(await fs.readFile(this.file, "utf8")); }
    catch (error) { if (error.code !== "ENOENT") throw error; }
    this.state.version = 2;
    this.state.jobs ??= {};
    this.state.approvals ??= {};
    this.state.usage ??= {};
    this.state.outbox ??= {};
    for (const job of Object.values(this.state.jobs)) {
      if (job.status === "publishing" && job.commit && job.workspace && job.branch) job.status = "handoff_deferred";
      else if (["preparing", "running", "publishing"].includes(job.status)) job.status = "retry";
    }
    for (const entry of Object.values(this.state.outbox)) {
      if (entry.state === "sending") {
        entry.state = "pending";
        entry.nextAttemptAt = 0;
      }
    }
    await this.save();
    return this.state;
  }

  async update(mutator) {
    this.queue = this.queue.then(async () => { await mutator(this.state); await this.save(); });
    return this.queue;
  }

  async save() {
    await fs.mkdir(path.dirname(this.file), { recursive: true });
    const temporary = `${this.file}.${process.pid}.tmp`;
    await fs.writeFile(temporary, `${JSON.stringify(this.state, null, 2)}\n`, { mode: 0o600 });
    await fs.rename(temporary, this.file);
  }
}

export const utcDay = (date = new Date()) => date.toISOString().slice(0, 10);

