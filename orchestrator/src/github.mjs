export class GitHub {
  constructor(config) { this.config = config; this.base = `https://api.github.com/repos/${config.githubOwner}/${config.githubRepo}`; }

  async request(method, path, body) {
    const response = await fetch(`${this.base}${path}`, {
      method,
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${this.config.githubToken}`,
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "social-drive-codex-orchestrator/0.1",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(30_000),
    });
    if (!response.ok) throw new Error(`GitHub ${method} ${path}: ${response.status} ${await response.text()}`);
    return response.status === 204 ? null : response.json();
  }

  listReadyIssues() {
    const label = encodeURIComponent(this.config.readyLabel);
    return this.request("GET", `/issues?state=open&labels=${label}&sort=created&direction=asc&per_page=100`)
      .then((items) => items.filter((item) => !item.pull_request));
  }

  comment(number, body) { return this.request("POST", `/issues/${number}/comments`, { body }); }
  removeReadyLabel(number) { return this.request("DELETE", `/issues/${number}/labels/${encodeURIComponent(this.config.readyLabel)}`); }
  createPullRequest(head, title, body) { return this.request("POST", "/pulls", { head, base: this.config.baseBranch, title, body, draft: false }); }
  mergePullRequest(number) { return this.request("PUT", `/pulls/${number}/merge`, { merge_method: "squash" }); }
}

