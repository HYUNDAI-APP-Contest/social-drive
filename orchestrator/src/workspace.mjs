import fs from "node:fs/promises";
import path from "node:path";
import { run } from "./process.mjs";

export class WorkspaceManager {
  constructor(config) { this.config = config; }
  pathFor(issue) { return path.join(this.config.workspaceRoot, `issue-${issue.number}`); }
  branchFor(issue) { return `agent/issue-${issue.number}`; }

  async withGitHubAuth(workspace, operation) {
    const askpass = path.join(workspace, ".git", "orchestrator-askpass.sh");
    await fs.writeFile(askpass, "#!/bin/sh\ncase \"$1\" in *Username*) printf '%s' 'x-access-token' ;; *) printf '%s' \"$ORCHESTRATOR_GITHUB_TOKEN\" ;; esac\n", { mode: 0o700 });
    try {
      return await operation({ ...process.env, GIT_ASKPASS: askpass, GIT_TERMINAL_PROMPT: "0", ORCHESTRATOR_GITHUB_TOKEN: this.config.githubToken });
    } finally {
      await fs.rm(askpass, { force: true });
    }
  }

  async prepare(issue) {
    const workspace = this.pathFor(issue); const branch = this.branchFor(issue);
    await fs.mkdir(this.config.workspaceRoot, { recursive: true });
    try { await fs.access(path.join(workspace, ".git")); return { workspace, branch }; } catch {}
    await run("git", ["clone", "--no-hardlinks", this.config.sourceRepository, workspace]);
    await run("git", ["remote", "set-url", "origin", `https://github.com/${this.config.githubOwner}/${this.config.githubRepo}.git`], { cwd: workspace });
    await this.withGitHubAuth(workspace, (env) => run("git", ["fetch", "origin", this.config.baseBranch], { cwd: workspace, env }));
    await run("git", ["checkout", "-B", branch, `origin/${this.config.baseBranch}`], { cwd: workspace });
    return { workspace, branch };
  }

  async ensureCommit(workspace, issue) {
    const status = (await run("git", ["status", "--porcelain"], { cwd: workspace })).stdout.trim();
    if (status) {
      await run("git", ["add", "-A"], { cwd: workspace });
      await run("git", ["commit", "-m", `feat: resolve issue #${issue.number}`], { cwd: workspace });
    }
    return (await run("git", ["rev-parse", "HEAD"], { cwd: workspace })).stdout.trim();
  }

  async push(workspace, branch) {
    await this.withGitHubAuth(workspace, (env) => run("git", ["push", "--force-with-lease", "origin", `${branch}:${branch}`], { cwd: workspace, env }));
  }
}

