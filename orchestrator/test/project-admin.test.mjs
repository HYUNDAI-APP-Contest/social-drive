import test from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { CONTROL_CENTER_SCHEMA } from "../src/control-center.mjs";
import { CONTROL_LABELS, ProjectAdmin, controlCenterPlan } from "../src/project-admin.mjs";

const complete = {
  ownerId: "O_demo",
  project: {
    id: "PVT_demo",
    public: false,
    fields: [{ id: "title", name: "Title", dataType: "TITLE", options: [] }, ...CONTROL_CENTER_SCHEMA.fields.map((field, index) => ({
      id: `field-${index}`,
      name: field.name,
      dataType: field.dataType,
      options: field.options?.map((name) => ({ name })) ?? [],
    }))],
    views: CONTROL_CENTER_SCHEMA.views.map((view, index) => ({ id: `view-${index}`, name: view.name, layout: view.layout, filter: view.filter, fields: view.fields })),
  },
  labels: CONTROL_LABELS.map((label) => label.name),
};

test("plans only missing project schema and managed labels", () => {
  assert.deepEqual(controlCenterPlan(complete), []);
  const drifted = structuredClone(complete);
  drifted.project.public = true;
  drifted.project.fields = drifted.project.fields.filter((field) => field.name !== "Health");
  drifted.project.fields.find((field) => field.name === "Status").options = [{ name: "Todo" }];
  drifted.project.views = drifted.project.views.filter((view) => view.name !== "Needs attention");
  drifted.labels.pop();
  const actions = controlCenterPlan(drifted);
  assert.ok(actions.some((action) => action.type === "set-private"));
  assert.ok(actions.some((action) => action.type === "create-field" && action.field.name === "Health"));
  assert.ok(actions.some((action) => action.type === "update-options" && action.field.name === "Status"));
  assert.ok(actions.some((action) => action.type === "create-view" && action.view.name === "Needs attention"));
  assert.ok(actions.some((action) => action.type === "create-label"));
});

test("apply re-inspects after project creation and is idempotent", async () => {
  const calls = [];
  class FakeAdmin extends ProjectAdmin {
    constructor() { super({ github: {}, owner: "demo", repo: "repo" }); this.inspections = 0; }
    async inspect() {
      this.inspections += 1;
      return this.inspections === 1 ? { ownerId: "O_demo", project: null, labels: complete.labels } : structuredClone(complete);
    }
    async applyAction(action) { calls.push(action); }
  }
  const result = await new FakeAdmin().apply();
  assert.deepEqual(calls.map((action) => action.type), ["create-project"]);
  assert.deepEqual(result.remaining, []);
});

test("creates a saved view then applies its safe project filter", async () => {
  const calls = [];
  const github = {
    async graphql(query, variables) {
      calls.push({ query, variables });
      if (query.includes("CreateControlCenterView")) return { createProjectV2View: { projectV2View: { id: "view-new" } } };
      return { updateProjectV2View: { projectV2View: { id: "view-new" } } };
    },
  };
  const admin = new ProjectAdmin({ github, owner: "demo", repo: "repo" });
  const view = CONTROL_CENTER_SCHEMA.views.find((item) => item.name === "Needs attention");
  await admin.applyAction({ type: "create-view", view }, complete);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].variables.input.layout, "TABLE_LAYOUT");
  assert.equal(calls[1].variables.input.filter, "status:\"Approval needed\",Blocked");
});

test("setup dry-run performs no network request and requires no token", () => {
  const script = fileURLToPath(new URL("../../scripts/setup-control-center.mjs", import.meta.url));
  const output = execFileSync(process.execPath, [script, "--dry-run"], {
    encoding: "utf8",
    env: { PATH: process.env.PATH },
  });
  assert.match(output, /No network request or mutation was performed/);
  assert.match(output, /create-project/);
});
