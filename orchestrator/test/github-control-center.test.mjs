import test from "node:test";
import assert from "node:assert/strict";
import { GitHub } from "../src/github.mjs";

const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
  text: async () => JSON.stringify(body),
});

const config = {
  githubOwner: "demo-org",
  githubRepo: "demo-repo",
  githubToken: "test-token",
  baseBranch: "main",
};

test("pins an approved merge request to the exact reviewed commit", async () => {
  const calls = [];
  const github = new GitHub(config, { fetchImpl: async (url, options) => {
    calls.push({ url, options });
    return response({ merged: true, sha: "b".repeat(40) });
  } });
  const reviewed = "a".repeat(40);

  await github.mergePullRequest(19, reviewed);

  assert.equal(calls[0].options.method, "PUT");
  assert.deepEqual(JSON.parse(calls[0].options.body), { merge_method: "squash", sha: reviewed });
  assert.throws(() => github.mergePullRequest(19, "not-a-sha"), /exact commit SHA/);
});

test("upserts the one marked progress comment instead of appending", async () => {
  const calls = [];
  const github = new GitHub(config, { fetchImpl: async (url, options) => {
    calls.push({ url, options });
    if (options.method === "GET") return response([{ id: 77, body: "<!-- social-drive-control-center:issue-42 -->\nold" }]);
    return response({ id: 77, body: JSON.parse(options.body).body });
  } });

  const id = await github.upsertProgressComment(42, "<!-- social-drive-control-center -->\nnew");

  assert.equal(id, 77);
  assert.equal(calls.length, 2);
  assert.match(calls[0].url, /issues\/42\/comments/);
  assert.match(calls[1].url, /issues\/comments\/77/);
  assert.equal(calls[1].options.method, "PATCH");
});

test("PATCH 404 searches every comment page and reuses the canonical marker", async () => {
  const calls = [];
  const pageOne = Array.from({ length: 100 }, (_, index) => ({ id: index + 1, body: `ordinary ${index}` }));
  const github = new GitHub(config, { fetchImpl: async (url, options) => {
    calls.push({ url, options });
    if (options.method === "PATCH" && url.endsWith("/issues/comments/404")) return response({ message: "Not Found" }, 404);
    if (options.method === "GET" && new URL(url).searchParams.get("page") === "1") return response(pageOne);
    if (options.method === "GET" && new URL(url).searchParams.get("page") === "2") return response([{ id: 555, body: "<!-- social-drive-control-center:issue-42 -->\nold" }]);
    if (options.method === "PATCH" && url.endsWith("/issues/comments/555")) return response({ id: 555 });
    throw new Error(`unexpected ${options.method} ${url}`);
  } });

  const id = await github.upsertProgressComment(42, "<!-- social-drive-control-center:issue-42 -->\nnew", 404);

  assert.equal(id, 555);
  assert.equal(calls.filter((call) => call.options.method === "GET").length, 2);
  assert.equal(calls.some((call) => call.options.method === "POST"), false);
  assert.match(calls.at(-1).url, /issues\/comments\/555$/);
});

test("reconciles only managed stage and persona labels", async () => {
  const calls = [];
  const github = new GitHub(config, { fetchImpl: async (url, options) => {
    calls.push({ url, options });
    if (options.method === "GET") return response([{ name: "bug" }, { name: "agent:stage:planning" }, { name: "agent:persona:aki" }]);
    return response(options.method === "DELETE" ? null : [] , options.method === "DELETE" ? 204 : 200);
  } });

  await github.reconcileControlLabels(
    42,
    ["agent:stage:build", "agent:persona:pixie"],
    ["agent:stage:planning", "agent:stage:build", "agent:persona:aki", "agent:persona:pixie"],
  );

  const add = calls.find((call) => call.options.method === "POST");
  assert.deepEqual(JSON.parse(add.options.body), { labels: ["agent:stage:build", "agent:persona:pixie"] });
  const deletes = calls.filter((call) => call.options.method === "DELETE").map((call) => decodeURIComponent(call.url));
  assert.ok(deletes.some((url) => url.endsWith("agent:stage:planning")));
  assert.ok(deletes.some((url) => url.endsWith("agent:persona:aki")));
  assert.equal(deletes.some((url) => url.endsWith("bug")), false);
});

test("adds the configured ready label to an approved follow-up issue", async () => {
  const calls = [];
  const github = new GitHub({ ...config, readyLabel: "agent:ready" }, { fetchImpl: async (url, options) => {
    calls.push({ url, options });
    return response([]);
  } });

  await github.addReadyLabel(9);

  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /issues\/9\/labels$/);
  assert.equal(calls[0].options.method, "POST");
  assert.deepEqual(JSON.parse(calls[0].options.body), { labels: ["agent:ready"] });
});

test("follow-up validation fails closed for closed issues and pull requests", async () => {
  for (const body of [
    { number: 9, state: "closed" },
    { number: 9, state: "open", pull_request: { url: "https://example.invalid" } },
  ]) {
    const github = new GitHub(config, { fetchImpl: async () => response(body) });
    await assert.rejects(() => github.requireOpenIssue(9), /not open|pull request/);
  }
  const github = new GitHub(config, { fetchImpl: async () => response({ number: 9, state: "open" }) });
  assert.equal((await github.requireOpenIssue(9)).number, 9);
  await assert.rejects(() => github.requireOpenIssue(0), /positive integer/);
});

test("follow-up audit comment uses one stable marker instead of appending", async () => {
  const calls = [];
  const github = new GitHub(config, { fetchImpl: async (url, options) => {
    calls.push({ url, options });
    if (options.method === "GET") return response([{ id: 91, body: "<!-- social-drive-follow-up:pr-2:issue-9 -->\nold" }]);
    return response({ id: 91 });
  } });
  const id = await github.upsertFollowUpComment(9, { pr: 2, readyLabel: "agent:ready" });
  assert.equal(id, 91);
  assert.equal(calls.some((call) => call.options.method === "POST"), false);
  assert.match(JSON.parse(calls.at(-1).options.body).body, /social-drive-follow-up:pr-2:issue-9/);
});

test("writes mapped values through fixed GitHub Project GraphQL operations", async () => {
  const graphqlBodies = [];
  const projectConfig = { ...config, githubProjectId: "PVT_demo" };
  const fields = [
    { id: "status", name: "Status", dataType: "SINGLE_SELECT", options: [{ id: "status-review", name: "Review" }] },
    { id: "phase", name: "Phase", dataType: "SINGLE_SELECT", options: [{ id: "phase-review", name: "Review" }] },
    { id: "health", name: "Health", dataType: "SINGLE_SELECT", options: [{ id: "health-track", name: "On track" }] },
    { id: "owner", name: "Current owner", dataType: "SINGLE_SELECT", options: [{ id: "owner-sentinel", name: "센티넬" }] },
    { id: "progress", name: "Progress", dataType: "NUMBER" },
    { id: "tokens", name: "Tokens", dataType: "NUMBER" },
    { id: "next", name: "Next action", dataType: "TEXT" },
  ];
  const github = new GitHub(projectConfig, { fetchImpl: async (url, options) => {
    assert.equal(url, "https://api.github.com/graphql");
    const body = JSON.parse(options.body);
    graphqlBodies.push(body);
    if (body.query.includes("query ControlCenterProject")) return response({ data: { node: { fields: { nodes: fields }, items: { nodes: [] } } } });
    if (body.query.includes("mutation AddControlCenterItem")) return response({ data: { addProjectV2ItemById: { item: { id: "PVTI_demo" } } } });
    return response({ data: { updateProjectV2ItemFieldValue: { projectV2Item: { id: "PVTI_demo" } } } });
  } });

  const result = await github.syncProjectSnapshot({
    issue: 42,
    issueNodeId: "I_demo",
    status: "Review",
    phase: "Review",
    health: "On track",
    persona: "센티넬",
    progress: 70,
    tokens: 1234,
    attempts: 1,
    reviewRound: 1,
    approvalType: "None",
    approvalExpiresAt: null,
    updatedAt: "2026-11-01T10:00:00.000Z",
    nextAction: "검토합니다.",
  });

  assert.deepEqual(result, { skipped: false, itemId: "PVTI_demo" });
  const updates = graphqlBodies.filter((body) => body.query.includes("UpdateControlCenterField"));
  assert.ok(updates.some((body) => body.variables.input.fieldId === "status" && body.variables.input.value.singleSelectOptionId === "status-review"));
  assert.ok(updates.some((body) => body.variables.input.fieldId === "progress" && body.variables.input.value.number === 70));
  assert.ok(updates.some((body) => body.variables.input.fieldId === "next" && body.variables.input.value.text === "검토합니다."));
});

test("clears approval expiry from the Project when approval ends", async () => {
  const bodies = [];
  const github = new GitHub({ ...config, githubProjectId: "PVT_demo" }, { fetchImpl: async (url, options) => {
    const body = JSON.parse(options.body);
    bodies.push(body);
    if (body.query.includes("query ControlCenterProject")) return response({ data: { node: {
      fields: { nodes: [{ id: "expiry", name: "Approval expires", dataType: "DATE" }] },
      items: { nodes: [{ id: "PVTI_demo", content: { id: "I_demo" } }] },
    } } });
    if (body.query.includes("ClearControlCenterField")) return response({ data: { clearProjectV2ItemFieldValue: { projectV2Item: { id: "PVTI_demo" } } } });
    return response({ data: { updateProjectV2ItemFieldValue: { projectV2Item: { id: "PVTI_demo" } } } });
  } });

  const snapshot = {
    issue: 42,
    issueNodeId: "I_demo",
    status: "Approval needed",
    phase: "Merge",
    health: "At risk",
    persona: "Human",
    progress: 90,
    tokens: 100,
    attempts: 1,
    reviewRound: 1,
    approvalType: "Merge",
    approvalExpiresAt: "2026-11-02T10:00:00.000Z",
    updatedAt: "2026-11-01T10:00:00.000Z",
    nextAction: "승인 필요",
  };
  await github.syncProjectSnapshot(snapshot);
  await github.syncProjectSnapshot({ ...snapshot, status: "Done", health: "On track", persona: "Orchestrator", progress: 100, approvalType: "None", approvalExpiresAt: null, nextAction: "완료" });

  const clear = bodies.find((body) => body.query.includes("ClearControlCenterField"));
  const dateUpdate = bodies.find((body) => body.query.includes("UpdateControlCenterField") && body.variables.input.fieldId === "expiry");
  assert.deepEqual(dateUpdate.variables.input.value, { date: "2026-11-02" });
  assert.deepEqual(clear.variables.input, { projectId: "PVT_demo", itemId: "PVTI_demo", fieldId: "expiry" });
});
