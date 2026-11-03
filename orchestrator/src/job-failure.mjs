export const isReviewChangesRequiredError = (error) =>
  /^센티넬 검토를 \d+회 통과하지 못했습니다\.$/.test(String(error?.message ?? ""));

export function classifyJobFailure({ usageLimited, reviewChangesRequired, attempts, maxRetries }) {
  if (usageLimited) return "paused_usage_limit";
  if (reviewChangesRequired) return attempts <= maxRetries ? "review_retry" : "review_blocked";
  return attempts <= maxRetries ? "retry" : "failed";
}

export function failureNotice({ issue, status, attempts, maxRetries, usageLimitCooldownMs }) {
  if (status === "paused_usage_limit") {
    return `⏸️ **#${issue} 사용량 한도로 일시 정지**\n작업 상태는 보존됩니다. ${Math.round(usageLimitCooldownMs / 60_000)}분 후 자동 재시도합니다.`;
  }
  if (status === "review_retry") {
    return `🟡 **#${issue} 검토 수정 주기 계속**\n시스템 장애나 권한 실패가 아닙니다. 센티넬의 수정 요청을 반영해 다음 개발 주기로 이어갑니다. (${attempts}/${maxRetries + 1})`;
  }
  if (status === "review_blocked") {
    return `⏸️ **#${issue} 품질 검토 보류**\n시스템 장애나 권한 실패가 아닙니다. 작업물과 검토 기록은 보존했습니다. 다시 진행하려면 GitHub에서 \`agent:ready\` 라벨을 제거한 뒤 다시 붙이세요.`;
  }
  if (status === "retry") {
    return `⚠️ **#${issue} 자동 복구 중**\n실패 상세는 안전하게 보관했습니다. 다음 폴링에서 재시도합니다. (${attempts}/${maxRetries + 1})`;
  }
  return `❌ **#${issue} 자동 복구 실패**\n재시도 한도를 소진했습니다. 작업 범위와 안전한 운영 로그를 확인해 주세요.`;
}
