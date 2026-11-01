const webhook = process.env.DISCORD_WEBHOOK_URL;
if (!webhook) throw new Error("DISCORD_WEBHOOK_URL is missing");

const response = await fetch(webhook, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    username: process.env.DISCORD_DISPLAY_NAME ?? "개발자",
    content: [
      "🧪 **승인 게이트웨이 연결 테스트**",
      "상태: Webhook 보고 채널 연결 성공",
      "다음 단계: `/agent-status`로 상태를 확인하고 `/agent-deploy`로 AWS 변경 세트 승인을 테스트하세요.",
      "실제 AWS 생성·배포·PR 병합은 승인 없이 실행되지 않습니다.",
    ].join("\n"),
    allowed_mentions: { parse: [] },
  }),
  signal: AbortSignal.timeout(15_000),
});

if (!response.ok) throw new Error(`Discord webhook failed: ${response.status} ${await response.text()}`);
console.log("Discord test report sent");
