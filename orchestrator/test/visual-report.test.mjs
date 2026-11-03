import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import { buildVisualReport, renderVisualReport, visualReportKey } from "../src/visual-report.mjs";
import { Discord } from "../src/discord.mjs";

const repository = "HYUNDAI-APP-Contest/social-drive";

test("builds a bounded mobile report without turning blocked work into pass", () => {
  const report = buildVisualReport({
    issue: 42,
    stage: "IMPLEMENTATION_EVIDENCE",
    role: "designer",
    headline: "충돌 검토 화면",
    changes: ["화면 추가", "/Users/private/workspace 숨김"],
    tests: [{ state: "PASS", name: "Kotlin", detail: "62/62" }, { state: "BLOCKED", name: "Gradle", detail: "DNS" }],
    nextStep: "센티넬 검토",
    revision: "abc123",
    links: ["https://github.com/HYUNDAI-APP-Contest/social-drive/pull/1", "https://attacker.invalid/x?token=y"],
    evidenceKind: "UNVERIFIED_PREVIEW",
  }, { repository });
  const rendered = renderVisualReport(report, { eventId: "event-1" });
  const serialized = JSON.stringify(rendered);
  assert.match(serialized, /픽시\(디자인·개발\)/);
  assert.match(serialized, /✅ PASS · Kotlin/);
  assert.match(serialized, /⛔ BLOCKED · Gradle/);
  assert.doesNotMatch(serialized, /attacker|\/Users\/private/);
  assert.equal(report.links.length, 1);
  assert.equal("components" in rendered, false);
  assert.deepEqual(rendered.allowed_mentions, { parse: [] });
});

test("visual report identity is stable but changes with revision", () => {
  const first = buildVisualReport({ issue: 2, stage: "PR_READY", role: "team", revision: "a" }, { repository });
  assert.equal(visualReportKey(first, { repository, runId: "2:1" }), visualReportKey(first, { repository, runId: "2:1" }));
  const second = buildVisualReport({ issue: 2, stage: "PR_READY", role: "team", revision: "b" }, { repository });
  assert.notEqual(visualReportKey(first, { repository, runId: "2:1" }), visualReportKey(second, { repository, runId: "2:1" }));
});

test("renders review feedback as a revision request rather than a failed test", () => {
  const report = buildVisualReport({
    issue: 15,
    stage: "REVIEW_CHANGES_REQUIRED",
    role: "reviewer",
    headline: "독립 검토 · 수정 필요",
    tests: [{ state: "REVIEW", name: "센티넬 품질 게이트", detail: "수정 요청" }],
    revision: "review-1",
  }, { repository });
  const serialized = JSON.stringify(renderVisualReport(report, { eventId: "review-request" }));
  assert.match(serialized, /🟡 REVIEW · 센티넬 품질 게이트/);
  assert.doesNotMatch(serialized, /❌ FAIL · 센티넬 품질 게이트/);
});

test("redacts every repeated path and signed query value and never attaches an image", () => {
  const report = buildVisualReport({
    issue: 7,
    stage: "REVIEW_CHANGES_REQUIRED",
    role: "reviewer",
    headline: "경로 /Users/alice/a X-Amz-Signature=first",
    changes: ["두 번째 /Users/alice/b", "X-Amz-Signature=supersecret&safe=no"],
    revision: "r1",
    evidenceKind: "MOCKUP",
  }, { repository });
  const rendered = renderVisualReport(report, { eventId: "redaction" });
  const serialized = JSON.stringify(rendered);
  assert.doesNotMatch(serialized, /\/Users\/alice|supersecret|X-Amz-Signature/i);
  assert.equal("attachments" in rendered, false);
  assert.match(serialized, /자동 이미지 첨부는 픽셀 안전 검증 전까지 비활성/);
});

test("Discord visual report reconciles a repeated durable event without approval components", async () => {
  const messages = [];
  let posts = 0;
  const fetchImpl = async (url, options) => {
    if (options.method === "GET") return { ok: true, status: 200, json: async () => messages };
    posts += 1;
    const body = JSON.parse(options.body);
    assert.equal("components" in body, false);
    const message = { id: "1", content: body.content, embeds: body.embeds, nonce: body.nonce };
    messages.push(message);
    return { ok: true, status: 200, json: async () => message };
  };
  const discord = new Discord({
    githubToken: "test-github", discordBotToken: "test-bot", discordWebhookUrl: "https://discord.invalid/webhook",
    discordChannelId: "channel", discordApprovers: new Set(),
  }, { fetchImpl });
  const report = buildVisualReport({ issue: 9, stage: "PR_READY", role: "team", headline: "검토 완료", revision: "abc" }, { repository });
  await discord.visualReportOnce("visual:9:abc", report);
  await discord.visualReportOnce("visual:9:abc", report);
  assert.equal(posts, 1);
  assert.match(messages[0].content, /visual-event:/);
});

test("production workflow has no routine startup or raw role transcript reporting path", async () => {
  const source = await fs.readFile(new URL("../src/index.mjs", import.meta.url), "utf8");
  assert.doesNotMatch(source, /DISCORD_DETAILED_ROLE_REPORTS|discordDetailedRoleReports/);
  assert.doesNotMatch(source, /3인 개발 회의를 시작합니다|\*\*개발자 시작\*\*/);
  assert.match(source, /await queueVisualOutcome\(key,/);
  assert.equal((source.match(/await queueVisualOutcome\(key,/g) ?? []).length, 4);
  const issueFlow = source.slice(source.indexOf("async function runIssue"), source.indexOf("async function poll"));
  assert.doesNotMatch(issueFlow, /discord\.report\(/);
  assert.match(issueFlow, /await queueDiscordNotice\(/);
});
