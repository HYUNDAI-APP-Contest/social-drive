import fs from "node:fs/promises";
import path from "node:path";
import { buildProgressSnapshot } from "./control-center.mjs";
import { utcDay } from "./store.mjs";

export const safeJson = (value) => JSON.stringify(value)
  .replace(/</g, "\\u003c")
  .replace(/>/g, "\\u003e")
  .replace(/&/g, "\\u0026")
  .replace(/\u2028/g, "\\u2028")
  .replace(/\u2029/g, "\\u2029");

export function buildDashboardModel(state, config, { now = new Date(), activeCount = 0, deploymentActive = false } = {}) {
  const refreshIntervalMs = Math.max(1_000, Number(config.dashboardIntervalMs ?? 10_000));
  const usage = state.usage?.[utcDay(now)] ?? { tokens: 0, estimatedUsd: 0 };
  const accountUsage = state.accountUsage ?? null;
  const pending = Object.values(state.approvals ?? {}).filter((item) => item.status === "pending");
  const jobs = Object.values(state.jobs ?? {}).map((job) => {
    const approval = pending.find((item) => Number(item.issue) === Number(job.issue)) ?? null;
    const snapshot = buildProgressSnapshot(job, { dailyUsage: usage, dailyTokenLimit: config.dailyTokenLimit, approval, now });
    return { ...snapshot, workspaceState: job.workspace ? "격리됨" : "준비 전" };
  }).sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt));
  return {
    generatedAt: now.toISOString(),
    repository: `${config.githubOwner}/${config.githubRepo}`,
    projectUrl: config.githubProjectUrl || null,
    activeCount,
    maxParallel: config.maxParallel,
    deploymentActive,
    refreshSeconds: Math.max(1, Math.ceil(refreshIntervalMs / 1_000)),
    staleAfterMs: Math.max(30_000, refreshIntervalMs * 3),
    usage: {
      tokens: Number(usage.tokens ?? 0),
      tokenLimit: config.dailyTokenLimit,
      estimatedUsd: Number(usage.estimatedUsd ?? 0),
      costLimitUsd: config.dailyCostLimitUsd,
      accountUsedPercent: accountUsage?.usedPercent ?? null,
      accountRemainingPercent: accountUsage?.remainingPercent ?? null,
      accountResetsAt: accountUsage?.resetsAt ?? null,
      accountTodayTokens: accountUsage?.todayTokens ?? null,
      accountLifetimeTokens: accountUsage?.lifetimeTokens ?? null,
    },
    summary: {
      approval: jobs.filter((job) => job.status === "Approval needed").length,
      blocked: jobs.filter((job) => job.status === "Blocked").length,
      active: jobs.filter((job) => ["In progress", "Review"].includes(job.status)).length,
      done: jobs.filter((job) => job.status === "Done").length,
    },
    jobs,
  };
}

export function renderDashboard(model) {
  const data = safeJson(model);
  const refreshSeconds = Math.max(1, Math.ceil(Number(model.refreshSeconds ?? 10)));
  const staleAfterMs = Math.max(30_000, Number(model.staleAfterMs ?? refreshSeconds * 3_000));
  return `<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="generator" content="social-drive-orchestrator-v1">
  <meta http-equiv="refresh" content="${refreshSeconds}">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; connect-src 'none'; base-uri 'none'; form-action 'none'">
  <title>Social Drive · Development Control Center</title>
  <style>
    :root{color-scheme:dark;--bg:#07111f;--panel:#0d1d31;--line:#1d3550;--muted:#94a8bf;--text:#f3f7fb;--blue:#5aa7ff;--amber:#ffbd59;--red:#ff7082;--green:#5bd2a2;--lilac:#d68cff}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 90% 0,#12335a 0,transparent 34%),var(--bg);color:var(--text);font:15px/1.55 ui-sans-serif,-apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo",sans-serif}main{width:min(1180px,calc(100% - 32px));margin:0 auto;padding:38px 0 64px}header{display:flex;justify-content:space-between;gap:24px;align-items:end;margin-bottom:24px}h1{font-size:clamp(24px,4vw,42px);line-height:1.12;margin:5px 0}.eyebrow,.meta{color:var(--muted);font-size:13px}.heartbeat{display:none;margin:0 0 18px;padding:12px 16px;border:1px solid #713441;border-radius:14px;background:#2c1119;color:#ffb8c2}.heartbeat.stale{display:block}.summary{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:22px 0}.metric,.card,.budget{background:color-mix(in srgb,var(--panel) 94%,transparent);border:1px solid var(--line);border-radius:18px;box-shadow:0 18px 50px #0004}.metric{padding:16px}.metric strong{display:block;font-size:26px}.toolbar{display:flex;justify-content:space-between;gap:16px;align-items:center;margin:26px 0 12px}.budget{padding:16px 18px;margin:16px 0}.track{height:8px;border-radius:999px;background:#152942;overflow:hidden;margin-top:10px}.fill{height:100%;border-radius:inherit;background:linear-gradient(90deg,var(--blue),var(--lilac));transition:width .3s}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.card{padding:20px}.top{display:flex;justify-content:space-between;gap:16px}.issue{font-weight:750;font-size:18px}.badge{border:1px solid currentColor;border-radius:999px;padding:3px 9px;font-size:12px;white-space:nowrap}.row{display:flex;flex-wrap:wrap;gap:8px 16px;color:var(--muted);margin:12px 0}.next{border-top:1px solid var(--line);padding-top:12px}.empty{grid-column:1/-1;text-align:center;padding:44px;color:var(--muted)}a{color:#8ec5ff}.Blocked{color:var(--red)}.Approval-needed{color:var(--amber)}.Done{color:var(--green)}.Review{color:var(--lilac)}@media(max-width:720px){header{align-items:start;flex-direction:column}.summary,.grid{grid-template-columns:1fr 1fr}}@media(max-width:440px){main{width:min(100% - 20px,1180px);padding-top:24px}.summary,.grid{grid-template-columns:1fr}}
  </style>
</head>
<body><main>
  <header><div><div class="eyebrow">LOCAL · READ ONLY</div><h1>Development Control Center</h1><div id="repo" class="meta"></div></div><div id="updated" class="meta"></div></header>
  <section id="heartbeat" class="heartbeat" role="alert"></section>
  <section id="summary" class="summary" aria-label="작업 요약"></section>
  <section class="budget"><div class="top"><strong>Codex 실제 사용량</strong><span id="budgetText" class="meta"></span></div><div class="track"><div id="budgetFill" class="fill"></div></div></section>
  <div class="toolbar"><strong>Agent 작업</strong><span id="runtime" class="meta"></span></div>
  <section id="jobs" class="grid" aria-live="polite"></section>
</main><script>
const model=${data};
const text=(tag,className,value)=>{const node=document.createElement(tag);if(className)node.className=className;node.textContent=String(value);return node};
document.getElementById('repo').textContent=model.repository;
document.getElementById('updated').textContent='마지막 갱신 '+new Date(model.generatedAt).toLocaleString('ko-KR');
const heartbeatAge=Math.max(0,Date.now()-Date.parse(model.generatedAt));
const heartbeat=document.getElementById('heartbeat');
const staleAfterMs=${staleAfterMs};
const heartbeatStale=!Number.isFinite(heartbeatAge)||heartbeatAge>staleAfterMs;
if(heartbeatStale){heartbeat.classList.add('stale');heartbeat.textContent='오케스트레이터 heartbeat가 '+Math.ceil(staleAfterMs/1000)+'초 이상 갱신되지 않았습니다. 아래 상태는 마지막 저장 시점의 스냅샷이며 현재 실행 상태가 아닐 수 있습니다.'}
document.getElementById('runtime').textContent=(heartbeatStale?'Heartbeat 지연 · ':'정상 · ')+'Agent '+model.activeCount+'/'+model.maxParallel+' · AWS '+(model.deploymentActive?'작업 중':'대기');
const metrics=[['결정 필요',model.summary.approval],['진행 중',model.summary.active],['차단',model.summary.blocked],['완료',model.summary.done]];
for(const [label,value] of metrics){const card=text('article','metric','');card.append(text('span','meta',label),text('strong','',value));document.getElementById('summary').append(card)}
const hasAccountUsage=Number.isFinite(Number(model.usage.accountUsedPercent));
const percent=hasAccountUsage?Number(model.usage.accountUsedPercent):(model.usage.tokenLimit?Math.min(100,Math.floor(model.usage.tokens/model.usage.tokenLimit*100)):0);
document.getElementById('budgetText').textContent=hasAccountUsage?('7일 한도 '+percent+'% 사용 · '+Number(model.usage.accountRemainingPercent)+'% 남음 · 오늘 '+Number(model.usage.accountTodayTokens||0).toLocaleString()+' tokens · 초기화 '+(model.usage.accountResetsAt?new Date(model.usage.accountResetsAt*1000).toLocaleString('ko-KR'):'확인 불가')):(model.usage.tokens.toLocaleString()+' / '+model.usage.tokenLimit.toLocaleString()+' · '+percent+'%');
document.getElementById('budgetFill').style.width=percent+'%';
const jobs=document.getElementById('jobs');
if(!model.jobs.length)jobs.append(text('div','card empty','대기 중 · 아직 기록된 Agent 작업이 없습니다.'));
for(const job of model.jobs){const card=text('article','card','');const top=text('div','top','');const title=text('div','issue','#'+job.issue+' · '+job.title);const badge=text('span','badge '+job.status.replaceAll(' ','-'),job.status);top.append(title,badge);const row=text('div','row','');for(const item of [job.persona,job.phase,job.health,job.progress+'%',job.tokens.toLocaleString()+' tokens',job.workspaceState])row.append(text('span','',item));const next=text('div','next',job.nextAction);card.append(top,row,next);jobs.append(card)}
</script></body></html>`;
}

export class DashboardWriter {
  constructor({ file, store, config, runtime = () => ({}) }) {
    this.file = file;
    this.store = store;
    this.config = config;
    this.runtime = runtime;
    this.queue = Promise.resolve();
  }

  write() {
    this.queue = this.queue.then(async () => {
      const model = buildDashboardModel(this.store.state, this.config, { now: new Date(), ...this.runtime() });
      const html = renderDashboard(model);
      await fs.mkdir(path.dirname(this.file), { recursive: true });
      const temporary = `${this.file}.${process.pid}.tmp`;
      await fs.writeFile(temporary, html, { mode: 0o600 });
      await fs.rename(temporary, this.file);
    });
    return this.queue;
  }
}
