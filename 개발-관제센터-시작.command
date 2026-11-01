#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$repo_root"

bash scripts/apply-and-test.sh

printf '\n모든 설정이 끝났습니다. 이 창은 닫아도 됩니다.\n'
