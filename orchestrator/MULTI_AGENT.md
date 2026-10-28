# Three-role workflow

One Discord bot represents a three-agent engineering team. Separate bot accounts are intentionally avoided so only one token and one approval identity need protection.

## 아키 · 설계자

- Independent Codex thread
- Read-only workspace and no network
- Produces scope, design decisions, affected files, test plan, risks, and acceptance contract
- Discord color: route blue

## 볼트 · 구현자

- Independent Codex thread
- Workspace-write sandbox
- Implements 아키's contract, runs tests, and commits locally
- Responds to every blocking 센티넬 finding
- Discord color: highlight mint

## 센티넬 · 검토자

- Independent Codex thread
- Read-only workspace and no network
- Inspects the actual diff and observed test evidence
- Reviews correctness, regression risk, security, privacy, and automotive UX
- Must explicitly return `APPROVE` or `CHANGES_REQUIRED`
- Discord color: moment amber

센티넬 rejection sends the work back to 볼트. The loop runs at most `MAX_REVIEW_ROUNDS` times and fails closed. Human approval is requested only after 센티넬 approves and a pull request exists.

Discord persona cards attach local avatar assets to each message, so three distinct profile visuals appear without creating or protecting three separate Discord bot tokens.
