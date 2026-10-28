# Repository agent policy

This repository is developed by autonomous Codex workers coordinated from GitHub Issues.

## Allowed without human approval

- Read and edit files inside the assigned issue workspace.
- Install project dependencies inside that workspace.
- Run formatters, linters, unit tests, integration tests, and local builds.
- Create local commits on the assigned `agent/issue-*` branch.

## Never perform from a worker

- Merge a pull request, push directly to the default branch, or bypass branch protection.
- Deploy, publish packages, change DNS/cloud/IAM/billing, or mutate production data.
- Read or print credentials, token stores, `.env` files, keychains, or files outside the workspace.
- Disable tests, security checks, approval gates, or audit logging.
- Run destructive commands outside the issue workspace.

The host orchestrator owns GitHub and Discord credentials. Workers must leave external side effects to it. A pull request is the handoff boundary; merge requires an authorized Discord approval.

## Completion evidence

Before finishing, run the relevant checks and summarize changed files, observed test results, risks, and follow-up work. Do not claim a check passed unless it ran successfully.

