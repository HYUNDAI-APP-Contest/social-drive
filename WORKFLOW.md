# Social Drive autonomous development workflow

Work only on the GitHub Issue supplied in the prompt. Treat its title, body, and acceptance criteria as the complete scope.

1. Inspect `AGENTS.md`, repository documentation, and the existing implementation.
2. Produce a short implementation plan in the first progress update.
3. Make the smallest coherent implementation that satisfies the issue.
4. Add or update tests for behavior changed by the issue.
5. Run focused checks, then the broadest safe repository checks available.
6. Review the diff for secrets, unrelated changes, generated artifacts, and unsafe behavior.
7. Commit the finished change locally using a conventional commit message.
8. Finish with a concise report containing the commit, checks run, remaining risks, and whether the issue acceptance criteria are met.

Do not push, merge, deploy, access external secrets, or alter infrastructure. The orchestrator performs the push and opens the pull request after the worker completes.

