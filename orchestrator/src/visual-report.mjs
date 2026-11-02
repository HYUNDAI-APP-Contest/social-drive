import { createHash } from "node:crypto";

const roles = Object.freeze({
  architect: { name: "아키(기획·설계)", color: 0x3b82f6 },
  designer: { name: "픽시(디자인·개발)", color: 0xd96cff },
  reviewer: { name: "센티넬(검토자)", color: 0xffb547 },
  team: { name: "아키 · 픽시 · 센티넬", color: 0x35d0ba },
});

const evidenceLabels = Object.freeze({
  EVIDENCE: "✅ 실행 증거",
  MOCKUP: "🎨 목업 · 실제 앱 실행 증거 아님",
  UNVERIFIED_PREVIEW: "🧪 미검증 프리뷰 · compile/runtime 확인 전",
  NONE: "🚫 첨부 없음",
});

const testStates = new Set(["PASS", "FAIL", "NOT_RUN", "BLOCKED"]);
const forbidden = [
  /https:\/\/discord\.com\/api\/webhooks\/[^\s]+/gi,
  /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/g,
  /\b(?:ghp_|github_pat_)[A-Za-z0-9_]{16,}\b/g,
  /\bBearer\s+[A-Za-z0-9._~+/=-]{12,}/gi,
  /-----BEGIN [A-Z ]*PRIVATE KEY-----/g,
  /\bX-Amz-(?:Credential|Signature|Security-Token)(?:=|%3D)?[^&\s]*/gi,
  /\/Users\/[^\s]+/g,
  /file:\/\/[^\s]+/gi,
];

const line = (value, max = 180) => {
  let text = String(value ?? "").replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ").trim();
  for (const pattern of forbidden) text = text.replace(pattern, "[REDACTED]");
  return text.slice(0, max);
};

const safeHttpsLink = (value, repository) => {
  try {
    const url = new URL(String(value));
    if (url.protocol !== "https:" || url.username || url.password || url.search) return null;
    if (url.hostname !== "github.com") return null;
    const prefix = `/${repository}/`;
    return url.pathname.startsWith(prefix) ? url.toString() : null;
  } catch { return null; }
};

export function buildVisualReport(input, { repository }) {
  const role = roles[input.role] ?? roles.team;
  const tests = (input.tests ?? []).slice(0, 4).map((test) => ({
    state: testStates.has(test.state) ? test.state : "NOT_RUN",
    name: line(test.name, 90),
    detail: line(test.detail, 80),
  }));
  const links = (input.links ?? []).map((link) => safeHttpsLink(link, repository)).filter(Boolean).slice(0, 2);
  const evidenceKind = evidenceLabels[input.evidenceKind] ? input.evidenceKind : "NONE";
  return Object.freeze({
    issue: Math.max(0, Number(input.issue ?? 0)),
    stage: line(input.stage, 40),
    role: input.role in roles ? input.role : "team",
    roleName: role.name,
    color: role.color,
    headline: line(input.headline, 120),
    changes: (input.changes ?? []).slice(0, 3).map((item) => line(item, 130)).filter(Boolean),
    tests,
    nextStep: line(input.nextStep, 150),
    revision: line(input.revision, 64),
    links,
    evidenceKind,
    evidenceLabel: evidenceLabels[evidenceKind],
  });
}

export function visualReportKey(report, { repository, runId }) {
  const canonical = ["visual-report:v1", repository, report.issue, line(runId, 80), report.stage, report.role, report.revision, report.evidenceKind].join("\n");
  return `visual:${report.issue}:${createHash("sha256").update(canonical).digest("hex").slice(0, 24)}`;
}

export function renderVisualReport(report, { eventId = "" } = {}) {
  const statusIcon = { PASS: "✅", FAIL: "❌", NOT_RUN: "⚪", BLOCKED: "⛔" };
  const fields = [];
  if (report.changes.length) fields.push({ name: "달라진 점", value: report.changes.map((item) => `• ${item}`).join("\n").slice(0, 950), inline: false });
  fields.push({ name: "자료", value: report.evidenceKind === "NONE" ? report.evidenceLabel : `${report.evidenceLabel}\n자동 이미지 첨부는 픽셀 안전 검증 전까지 비활성`, inline: false });
  if (report.tests.length) fields.push({
    name: "검증",
    value: report.tests.map((test) => `${statusIcon[test.state]} ${test.state} · ${test.name}${test.detail ? ` — ${test.detail}` : ""}`).join("\n").slice(0, 950),
    inline: false,
  });
  if (report.nextStep) fields.push({ name: "다음", value: report.nextStep, inline: false });
  if (report.links.length) fields.push({ name: "확인", value: report.links.map((link, index) => `[${index === 0 ? "결과 열기" : "추가 증거"}](${link})`).join(" · "), inline: false });
  const footerId = createHash("sha256").update(String(eventId)).digest("hex").slice(0, 12);
  const embed = {
    title: `📌 #${report.issue} · ${report.headline || report.stage}`,
    description: `${report.roleName}\n\n병합 결정은 별도의 🔐 승인 메시지에서 진행됩니다.`,
    color: report.color,
    fields,
    footer: { text: `성과 ID ${footerId} · 승인과 분리된 정보 보고` },
  };
  return { content: `📊 **개발 성과 보고**\n-# visual-event:${footerId}`, embeds: [embed], allowed_mentions: { parse: [] } };
}

