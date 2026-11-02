#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repository="HYUNDAI-APP-Contest/social-drive"
remote_url="https://github.com/${repository}.git"
pulls_endpoint="repos/${repository}/pulls?state=all&per_page=100"

reviewed_work=(
  "orchestrator/workspaces/issue-conflict-review|agent/issue-conflict-review|dd3d61dabda9d69526227a84a40fce20c7bc3833|feat: add Social Drive Android privacy and conflict-safe publishing"
  "orchestrator/workspaces/issue-backend-mvp|agent/issue-backend-mvp|ddbd61fde381d09bc4605c758126ebf6ba0a5ea5|feat: add Social Drive serverless backend MVP"
  "orchestrator/workspaces/issue-contest-submission|agent/issue-contest-submission|cc185dda4218ae8239083339068bc0a93645aa32|docs: prepare 2026 contest submission materials"
)

plan_workspaces=()
plan_branches=()
plan_expected=()
plan_titles=()
plan_urls=()
plan_actions=()

cd "$repo_root"

printf '검토 완료 작업 3개의 로컬 무결성을 확인합니다...\n'
for entry in "${reviewed_work[@]}"; do
  IFS='|' read -r relative branch expected title <<<"$entry"
  workspace="$repo_root/$relative"
  [[ -d "$workspace/.git" ]] || { printf '작업공간이 없습니다: %s\n' "$relative" >&2; exit 1; }
  [[ "$(git -C "$workspace" branch --show-current)" == "$branch" ]] || {
    printf '검토한 브랜치와 다릅니다: %s\n' "$relative" >&2
    exit 1
  }
  git -C "$workspace" cat-file -e "${expected}^{commit}"
  [[ "$(git -C "$workspace" rev-parse HEAD)" == "$expected" ]] || {
    printf '검토한 전체 커밋 SHA와 다릅니다: %s\n' "$relative" >&2
    exit 1
  }
  [[ -z "$(git -C "$workspace" status --porcelain)" ]] || {
    printf '검토 후 변경이 남아 있습니다: %s\n' "$relative" >&2
    exit 1
  }
done

printf 'GitHub 쓰기 권한과 모든 PR 게시 계획을 먼저 사전 검증합니다...\n'
[[ "$(gh api "repos/$repository" --jq '.full_name')" == "$repository" ]] || {
  printf '대상 GitHub 저장소를 확인하지 못했습니다.\n' >&2
  exit 1
}
[[ "$(gh api "repos/$repository" --jq '.permissions.push // false')" == "true" ]] || {
  printf '이 저장소에 브랜치를 올릴 GitHub 권한이 없습니다.\n' >&2
  exit 1
}

snapshot_dir="$(mktemp -d)"
cleanup_snapshot() { rm -rf -- "$snapshot_dir"; }
trap cleanup_snapshot EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
gh api --paginate --slurp "$pulls_endpoint" >"$snapshot_dir/pulls.json"
git ls-remote --heads "$remote_url" >"$snapshot_dir/refs.txt"

for entry in "${reviewed_work[@]}"; do
  IFS='|' read -r relative branch expected title <<<"$entry"
  workspace="$repo_root/$relative"
  git -C "$workspace" fetch --quiet "$remote_url" main
  in_main=false
  if git -C "$workspace" merge-base --is-ancestor "$expected" FETCH_HEAD; then in_main=true; fi
  plan_line="$(node scripts/reviewed-pr-plan.mjs \
    --pulls "$snapshot_dir/pulls.json" \
    --refs "$snapshot_dir/refs.txt" \
    --repository "$repository" \
    --branch "$branch" \
    --expected "$expected" \
    --in-main "$in_main")"
  IFS=$'\t' read -r action publish_branch open_url <<<"$plan_line"

  plan_workspaces+=("$workspace")
  plan_branches+=("$publish_branch")
  plan_expected+=("$expected")
  plan_titles+=("$title")
  plan_urls+=("$open_url")
  plan_actions+=("$action")
done

printf '사전 검증이 끝났습니다. 정확한 검토 SHA만 게시합니다...\n'
for index in "${!plan_workspaces[@]}"; do
  workspace="${plan_workspaces[$index]}"
  publish_branch="${plan_branches[$index]}"
  expected="${plan_expected[$index]}"
  title="${plan_titles[$index]}"
  pr_url="${plan_urls[$index]}"
  action="${plan_actions[$index]}"

  if [[ "$action" == "merged" ]]; then
    printf '이미 main에 반영된 검토 SHA: %s\n' "${pr_url:-$expected}"
    continue
  fi

  if [[ "$action" == "create" ]]; then
    git -C "$workspace" push "$remote_url" "${expected}:refs/heads/${publish_branch}"
    pr_url="$(gh pr create -R "$repository" --base main --head "$publish_branch" --title "$title" --body $'아키 설계, 픽시 구현, 센티넬 독립 검토를 통과한 작업입니다.\n\n- 검토된 정확한 커밋만 게시\n- 테스트 및 제한 사항은 각 PR 변경 문서에 기록\n- 자동 병합 없음\n- 병합은 Discord 승인 후에만 수행')"
  fi

  pr_number="${pr_url##*/}"
  verification="$(gh api "repos/$repository/pulls/$pr_number" --jq '[.state, .base.ref, .head.repo.full_name, .head.sha] | @tsv')"
  expected_verification="$(printf 'open\tmain\t%s\t%s' "$repository" "$expected")"
  [[ "$verification" == "$expected_verification" ]] || {
    printf 'PR이 검토 SHA·main·공식 저장소와 정확히 일치하지 않습니다: %s\n' "$pr_url" >&2
    exit 1
  }
  printf '승인 대기 PR: %s\n' "$pr_url"
done
