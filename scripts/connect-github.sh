#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_slug="HYUNDAI-APP-Contest/social-drive"

cd "$repo_root"

gh auth status >/dev/null

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git init -b main
fi

if gh repo view "$repo_slug" >/dev/null 2>&1; then
  remote_url="https://github.com/${repo_slug}.git"
  if git remote get-url origin >/dev/null 2>&1; then
    git remote set-url origin "$remote_url"
  else
    git remote add origin "$remote_url"
  fi
else
  gh repo create "$repo_slug" --private --source=. --remote=origin
fi

github_login="$(gh api user --jq .login)"
if ! git config user.name >/dev/null 2>&1; then
  git config user.name "$github_login"
fi
if ! git config user.email >/dev/null 2>&1; then
  git config user.email "${github_login}@users.noreply.github.com"
fi

git add -A
if ! git diff --cached --quiet; then
  git commit -m "chore: bootstrap social drive project"
fi

git push -u origin main

printf '\nConnected: https://github.com/%s\n' "$repo_slug"
git status -sb
