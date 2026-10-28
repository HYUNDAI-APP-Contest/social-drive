# Three-role workflow

One Discord bot represents a three-agent product team. Separate bot accounts are intentionally avoided so only one token and one human approval identity need protection. Each role runs in an independent Codex thread and reports with its own name, color, and car-character profile image. Keeping three roles reduces latency and plan usage while preserving independent review.

## 아키(기획·설계)

- Read-only workspace and no network
- Defines the user problem, journey, priority, scope, non-goals, edge cases, and measurable acceptance criteria
- Converts the product brief into architecture, affected files, interfaces, test strategy, risks, and one implementation contract
- Discord color: route blue

## 픽시(디자인·개발)

- Workspace-write sandbox
- Defines implementation-ready UX from the current design system, then implements it in the same turn
- Covers information hierarchy, component states, responsive behavior, accessibility, motion, copy, visual quality, tests, and local commits
- Responds to every blocking 센티넬 finding
- Discord color: lilac

## 센티넬(검토자)

- Read-only workspace and no network
- Inspects the actual diff and observed test evidence
- Reviews product acceptance criteria, design fidelity, responsive and accessible states, correctness, regressions, security, privacy, and automotive UX
- Must explicitly return `APPROVE` or `CHANGES_REQUIRED`
- Discord color: moment amber

센티넬 rejection sends the work back to 픽시. The loop runs at most `MAX_REVIEW_ROUNDS` times and fails closed. Human approval is requested only after 센티넬 approves and a pull request exists.

Discord persona cards attach local avatar assets to each message, so three distinct team profiles appear without creating or protecting three separate Discord bot tokens.
