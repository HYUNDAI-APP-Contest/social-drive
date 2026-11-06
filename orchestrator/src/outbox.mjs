const DESTINATIONS = new Set([
  "discord-report",
  "discord-visual",
  "discord-approval",
  "github-progress",
  "github-labels",
  "github-project",
  "github-follow-up-activation",
  "github-follow-up-audit",
]);

export function enqueueOutbox(state, { key, destination, payload }, now = Date.now()) {
  if (!DESTINATIONS.has(destination)) throw new Error(`Unsupported outbox destination: ${destination}`);
  state.outbox ??= {};
  const existing = state.outbox[key];
  state.outbox[key] = {
    key,
    destination,
    payload,
    state: "pending",
    attempts: existing?.state === "delivered" ? 0 : Number(existing?.attempts ?? 0),
    createdAt: existing?.createdAt ?? now,
    updatedAt: now,
    nextAttemptAt: now,
  };
  return state.outbox[key];
}

export class DurableOutbox {
  constructor({ store, handlers, clock = () => Date.now(), random = Math.random, maxAttempts = 8, logger = () => {} }) {
    this.store = store;
    this.handlers = handlers;
    this.clock = clock;
    this.random = random;
    this.maxAttempts = maxAttempts;
    this.logger = logger;
    this.draining = false;
  }

  async enqueue(item) {
    await this.store.update((state) => enqueueOutbox(state, item, this.clock()));
  }

  async claim() {
    let claimed = null;
    const now = this.clock();
    await this.store.update((state) => {
      const entries = Object.values(state.outbox ?? {})
        .filter((entry) => entry.state === "pending" && Number(entry.nextAttemptAt ?? 0) <= now)
        .sort((a, b) => Number(a.createdAt) - Number(b.createdAt));
      if (!entries.length) return;
      const entry = entries[0];
      entry.state = "sending";
      entry.attempts = Number(entry.attempts ?? 0) + 1;
      entry.updatedAt = now;
      claimed = structuredClone(entry);
    });
    return claimed;
  }

  async deliver(entry) {
    const handler = this.handlers[entry.destination];
    if (!handler) throw new Error(`Missing outbox handler: ${entry.destination}`);
    await handler(entry.payload, entry);
  }

  async markDelivered(entry) {
    const now = this.clock();
    await this.store.update((state) => {
      const current = state.outbox?.[entry.key];
      if (!current || current.updatedAt !== entry.updatedAt || current.state !== "sending") return;
      current.state = "delivered";
      current.deliveredAt = now;
      current.updatedAt = now;
      delete current.errorClass;
    });
  }

  async markFailed(entry, error) {
    const now = this.clock();
    await this.store.update((state) => {
      const current = state.outbox?.[entry.key];
      if (!current || current.updatedAt !== entry.updatedAt || current.state !== "sending") return;
      current.state = current.attempts >= this.maxAttempts ? "dead" : "pending";
      const exponential = Math.min(15 * 60_000, 1_000 * (2 ** Math.min(current.attempts - 1, 10)));
      current.nextAttemptAt = now + Math.round(exponential * (0.75 + this.random() * 0.5));
      current.updatedAt = now;
      current.errorClass = error?.name === "AbortError" ? "timeout" : "delivery";
    });
    this.logger("outbox_delivery_failed", { key: entry.key, destination: entry.destination, attempts: entry.attempts });
  }

  async drain({ limit = 25 } = {}) {
    if (this.draining) return;
    this.draining = true;
    try {
      for (let delivered = 0; delivered < limit; delivered += 1) {
        const entry = await this.claim();
        if (!entry) break;
        try {
          await this.deliver(entry);
          await this.markDelivered(entry);
        } catch (error) {
          await this.markFailed(entry, error);
        }
      }
    } finally {
      this.draining = false;
    }
  }
}
